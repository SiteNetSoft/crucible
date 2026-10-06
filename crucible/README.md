# CrucibleVM

Open-source profile-guided optimization for GraalVM Community Edition `native-image`.

> **Alpha.** Measured, and checked for identical output, on a handful of benchmarks; not yet on
> the breadth of code a production compiler has to get right. Options and the profile format may
> change. Keep a build without it to compare against, and report anything that miscompiles.

You build your program once so that it records how it runs, run it on a workload that looks like
production, and build it again with what it recorded. The second build knows which branches go
which way, which methods the time is spent in, and where calls actually land, and compiles
accordingly.

## Using it

    source crucible/env.sh
    cd substratevm

    # 1. an image that records
    mx native-image -O3 -cp app.jar -o app-recording \
        -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument com.example.Main

    # 2. run it on a representative workload; it writes crucible-profile.json when it exits
    ./app-recording <the usual arguments>

    # 3. the image you ship
    mx native-image -O3 -cp app.jar -o app \
        -H:+UnlockExperimentalVMOptions -H:CrucibleProfile=crucible-profile.json com.example.Main

The recording image runs about twice as slow as normal on one thread and somewhat more on several, and is around 100 MB.
It writes the profile when the process exits normally; `-XX:CrucibleProfileOutput=<path>` moves it
and `-XX:CrucibleProfileDumpInterval=<seconds>` also writes it periodically, for a service that is
killed rather than stopped.

The second build says what it made of the profile:

    Crucible: the profile saw 348 methods run, 307 of them (88.2%) are in this image, accounting
              for 96.9% of the calls it counted; of the 3 outside the class library and the VM, 3 (100.0%) are.
    Crucible: applied 16666 of 45338 conditional profile lookups (36.8%) ...
    Crucible: loop range split considered 5 hot counted loops, split 4, folded 24 checks.

The first line becomes a warning when the profile does not fit the program: recorded from other
code, or from this code some versions ago. A stale profile still applies wherever names happen to
agree and quietly does nothing elsewhere, so look at that line.

### More than one run

    python3 crucible/samples/merge-profiles.py merged.json run1.json run2.json ...

Counts add. One run reaches a few hundred methods out of several thousand; different workloads
merged reach more, and that matters more than any tuning.

### Sampled call stacks

Counters say how often a thing happened, not under which caller. A time sampler does, and its
stacks can be added to a profile:

    mx native-image ... --enable-monitoring=jfr -H:+SignalHandlerBasedExecutionSampler -o app-jfr ...
    ./app-jfr -XX:StartFlightRecording=filename=run.jfr <the usual arguments>
    jfr print --json --stack-depth 64 --events jdk.ExecutionSample run.jfr > run.json
    python3 crucible/samples/jfr-to-samples.py crucible-profile.json run.json profile-with-stacks.json

Sample an ordinary optimized image, not the recording one. Both flags matter: the default sampler's
stacks stop at the safepoint, and `jfr print` cuts stacks to five frames unless told otherwise. With
stacks in the profile, which methods are hot is measured rather than inferred, the inliner is
told where each call goes in each calling context, and a method that takes a real share of the run
under one caller is compiled again for that caller (`CrucibleContextClones`).

Samples count time, not calls, so a receiver that is called often and returns quickly can be
missing from them, and a method compiled for everyone loses the inlining the counted receivers gave
it: future-genetic ran 33% slower and scala-doku 48%. So when the profile has stacks, the build
takes their receivers in the copies only, which is where they tell one caller's pipeline from
another's (`-H:-CrucibleSampledTargetsOutsideCopies`); gives the hot methods and the copies the
budget Oracle's builder gives a hot unit (`-H:+CrucibleHotInliningBudget`); and answers a call in a
copy that the stacks caught too seldom from the counted receivers along the same path
(`-H:+CrucibleSampledCountedFallback`). All three are the defaults with stacks, and can be turned off.
With the first two, on GraalVM 25.3,
the twelve Renaissance benchmarks run 3.4% faster than from the counted profile alone over six
rounds, akka-uct 6%, scrabble 6%, future-genetic, reactors and par-mnemonics 4 to 5%, and none of
them slower; the geometric mean against Oracle's binary goes from 0.955 to 0.922. Spring PetClinic,
Quarkus, and the five samples run the same or faster, BenchPGO 6%. The copies need the larger budget: without them it makes rx-scrabble 3 to 5% slower.
`-H:CrucibleHotBonusWhileExpanding=50`, which makes the inliner look sooner into the calls the
samples saw time spent under, adds 1.5% on Renaissance (scrabble 5%, scala-kmeans 4%) and costs
PetClinic 3 to 4%.
See `docs/issues/2026-09-30-samples-count-time-not-calls.md`.

### A profile recorded by Oracle GraalVM

    python3 crucible/samples/iprof-to-crucible.py default.iprof crucible-profile.json

Call counts, branches, receiver types and sampled stacks carry over. On the sample workloads a
converted profile drives the build as well as one of our own.

## What it does

| | |
| --- | --- |
| Branch probabilities | counted in every place a method is inlined, including the places where the compiler could decide the branch and removed it, so that what is recorded for a calling context has both sides of it; applied before inlining, so layout, inlining and the loop optimizations all see them |
| Loop range splitting | a hot counted loop whose checks the profile saw go one way nearly always is run in three parts, the middle one without the checks; this is also what lets the vectorizer take it |
| Receiver types | counted at every virtual call in every place its method is inlined, including the places where the compiler could work the receiver out and the call is no longer virtual, so that what is recorded for a call is everything that came through it; the inliner tests for the common receivers first and inlines their methods |
| Call counts | of every method, inlined or not |
| The collector | its branches are counted as the program's are, so a program that spends its time collecting gets a collector laid out for what its heap looks like |
| Cold code | a method the run never reached does not look into its callees, which roughly halves the machine code of an image |
| `System.arraycopy` | not profile work, but found by it: the community edition copied arrays with a scalar loop and a write barrier per reference, three to eight times slower than Oracle's. Copies go to the C library's `memmove` now, references are card-marked once per copy, and a copy between arrays of a known type is checked inline; level with Oracle on primitive copies and ahead on reference copies |
| Code layout | the code section is ordered by how often each method ran |
| Hot methods below `-O3` | are compiled the way `-O3` would compile them: its inliner settings, partial unrolling and loop vectorization, which the default level otherwise holds back for every method alike. On the samples a profiled `-O2` image runs as fast as a profiled `-O3` one and stays the size of an `-O2` one |

## How it compares

An image built with a profile is built at `-O3` unless you ask for another level, as Oracle's `native-image` does when it is given a profile.
Give `-O2` with the profile if you want the smaller image, which is 4 to 8% smaller.
On the samples a profile-guided image at `-O2` runs as fast as one at `-O3`; on most of the Renaissance benchmarks it is 10 to 45% slower.

CrucibleVM is now based on GraalVM 25.4.4.1.1. With its defaults, over fourteen Renaissance benchmarks against Oracle's profile-guided binary, it comes to 0.925 of Oracle's time from a counted profile and 0.907 from one with sampled stacks, behind on two: scala-stm-bench7 by 7% and rx-scrabble by 2%.
Spring PetClinic answers `/` 8.5% faster than Oracle's binary and `/vets` 22.6%, and Quarkus about 5%.
See `docs/issues/2026-10-03-graalvm-25-4.md`. The figures below are from GraalVM 25.3.

Same machine, `-O3`, each binary checked for identical output. Time of the profile-guided binary
from each compiler; lower is better.

| workload | Oracle GraalVM | CrucibleVM |
| --- | --- | --- |
| GameOfLife (Oracle's own example) | 3.19 s | **2.96 s** |
| ArrayBench | 0.37 s | **0.33 s** |
| BranchBench | 0.55 s | **0.46 s** |
| JsonBench | 1.55 s | **1.41 s** |
| BenchPGO | 0.37 s | **0.29 s** |
| Renaissance, twelve benchmarks | ahead on four | ahead on six, level on two |
| Quarkus REST/JSON quickstart, requests a second | 90,364 | **93,401** |
| Spring PetClinic, requests a second on `/vets` (JSON) | 36,936 | **44,188** |

CrucibleVM is ahead on six of the twelve (par-mnemonics by 26%, akka-uct by 22%, scala-doku by 15%, mnemonics by 13%, philosophers by 6%, and reactors by 4%) and level on two (future-genetic and scala-kmeans).
It is behind by 3 to 10% on the other four, fj-kmeans, rx-scrabble, scala-stm-bench7, and scrabble.
Over the twelve the geometric mean of the ratios is 0.943, that is 5.7% ahead, and with the heap of each capped at the memory Oracle's binary uses on it, 0.958.
With each collector choosing its heap, CrucibleVM's binary peaks in less memory than Oracle's on six of the twelve (fj-kmeans 168 MB against 637, mnemonics 161 against 431, par-mnemonics 257 against 499, scala-doku 80 against 132, rx-scrabble 113 against 153, reactors 994 against 1,036), in about the same on three, and in more on three: akka-uct 3,072 MB against 983, scrabble 350 against 182, scala-stm-bench7 458 against 365.
On rx-scrabble what is left is the compiled program: Oracle's builder compiles the loop every pipeline goes through once for each of them. On scrabble and scala-stm-bench7 it is the collector.
These figures are from one machine with six processors, three rounds of each benchmark, and the samples from the same machine, eleven runs each.
Oracle's image depends on how long a run it was recorded on, in either direction, and its figures are the better of a short and a long recording; CrucibleVM's runs within 1% from either.
The two services are built by their frameworks' own native builds, recorded under the load they are timed with, with the service on four processors and the load on four others; PetClinic also answers its page `/` 8% faster, in 172 MB of memory where Oracle's binary has 390.

The gain from the profile is as large as Oracle's or larger on most of these. Where CrucibleVM is
behind, the two compilers already differ by about that much without any profile, and on the
programs that allocate most the difference is allocation and garbage collection rather than the
optimizer. The details, and everything that was tried and did not work, are in `docs/issues/` and
`crucible/ROADMAP.md`.

If your program spends its time collecting, try `-XX:SerialGCTimeRatio=6` at run time (4 to 9 is
the useful range). The serial collector's default policy accepts half of the time going to
collection before it grows the young generation; this asks for a seventh. It took two Renaissance
benchmarks from level with Oracle's binary to 23% ahead of it and two from 32% and 62% behind to 5%
and 27%, the latter in less memory than Oracle's uses. It cost another four times its memory for
little and made one slower, so measure it; the default is unchanged.

An object that survives a young collection is copied to a survivor space and promoted at the next collection it survives.
The community edition's policy copies it seven times first, and more on a program that allocates a lot.
Where most of what survives one collection survives the next as well, and there is a lot of it, even the one copy is wasted, and the collector promotes straight from eden for as long as that lasts.
It checks again at intervals and goes back when the program changes.
Against the community edition's policy, reactors gains 13%, mnemonics 10%, scala-stm-bench7 9%, and par-mnemonics 4%, and reactors goes from behind Oracle's binary to level with it.
To fix the number of collections an object survives before it is promoted, use `-XX:SerialGCTenuringThreshold=<n>`.
At 0, a program whose objects live for a collection or two loses a lot (fj-kmeans 40%), which is why the default looks before it chooses.
`-XX:SerialGCTenuringThreshold=-1` gives the community edition's policy back.
If your program keeps its objects for a couple of collections and then drops them, try `-XX:SerialGCTenuringThreshold=-3`, which chooses among two, one, and zero: it keeps such a program at two, and GameOfLife runs 7% faster with it, because what is promoted early fills the old generation and that is collected five times in a run and not twice.
On the twelve Renaissance benchmarks it is level with the default but for rx-scrabble, which is 1% slower.
`-XX:+VerboseGC` prints the tenuring age in use at each collection, which is the threshold plus one.
See `docs/issues/2026-09-27-the-collector-copied-what-it-was-going-to-promote-anyway.md`.
The old generation is collected by copying, as it is in Oracle's binaries, where the community edition compacts it in place.
A complete collection takes about two thirds of the time: 386 ms on akka-uct against 533.
With the heap capped at the memory Oracle's binary uses, the twelve Renaissance benchmarks are 2 to 4% ahead of it copying and level compacting, and compacting is where scala-stm-bench7 falls off a cliff, 54% behind at 352 MB.
A program that kept 2 GB alive ran 21% faster copying and peaked at 5.5 GB where compacting peaks at 4.2 GB.
If memory is what limits your program, build with `-H:+CompactingOldGen`.
See `docs/issues/2026-09-28-what-the-old-generation-is-collected-with.md`.

`-XX:InitialCollectionPolicy=Adaptive` and `BySpaceAndTime` are the older ways to the
same end and are as mixed. See `docs/issues/2026-09-21-the-collector-was-told-half-the-time-is-fine.md`.

## Options

All are `-H:` options and need `-H:+UnlockExperimentalVMOptions`.

| option | default | |
| --- | --- | --- |
| `CrucibleInstrument` | off | build an image that records a profile |
| `CrucibleProfile=<file>` | | build with a profile |
| `CrucibleRecordStartupOrder` | off | also record the order methods were first entered in, for `CrucibleCodeLayoutByStartup`; costs a call at every method entry |
| `CrucibleMaxContextDepth` | 8 | inlining frames recorded with each counter. A call inside shared code, a stream's machinery say, goes to one place from each caller and to many over all of them, and only the frames above it tell those apart; 1 records the innermost frame only, for a recording image a fifth smaller |
| `CrucibleContextMinimumCount` | 1000 | a recorded context stands in for the call site's pooled record only if it was seen this often |
| `OptionalIdentityHashCodes` | on | give an object room for its identity hash code only once it is asked for, as Oracle GraalVM does; every array is 8 bytes smaller. Not a Crucible option: it is the tree's, and it needs no unlocking |
| `InlineExactArraycopy` | on | an `arraycopy` between arrays of a known type checks its bounds inline and copies short arrays inline, instead of going through one generic call that works the types out at run time. Also the tree's, no profile needed |
| `CrucibleRecordBranchesWithProbes` | on | in a recording image, mark every branch before inlining and count it after, so that a branch the compiler decides where its method was inlined is still counted there; off, branches are counted in the finished graph, and a calling context can have one side of a branch only |
| `CrucibleRecordUninterruptible` | on | count branches in uninterruptible code too, which is where the garbage collector is |
| `CrucibleRecordKeepsCallsVirtual` | on | in a recording image, leave a call with several possible receivers a call; off, the image inlines as an optimized one does |
| `CrucibleContextClones` | on | with sampled stacks, compile a method again for a caller it spends time under |
| `CrucibleMinimumSamplesAtCall` | 32 | fewest samples under a call for the sampled stacks to be believed about where it goes |
| `CrucibleSampledTargetsOutsideCopies` | off | with sampled stacks, also tell the inliner where calls go in methods compiled for everyone, not only in copies made for one caller |
| `CrucibleHotInliningBudget` | on with stacks | give hot methods and copies the inlining budget Oracle's builder gives a hot unit |
| `CrucibleSampledCountedFallback` | on | in a copy, answer a call the stacks caught too seldom from the counted receivers along the same path |
| `CrucibleSinkAfterDuplication` | off | after control flow duplication (`-H:+OptDuplication`), copy arithmetic only rarely run blocks use into each of them |
| `LargeArrayThreshold` | the most an aligned chunk takes | the size from which an array gets a chunk of its own; was 128 KB. Not a Crucible option |
| `OptDuplication` | off with a profile | control flow duplication, new in GraalVM 25.4; with a profile it can put a value computed on a rarely taken path on every path. Not a Crucible option |
| `CrucibleLoopRangeSplit` | on | split hot loops around checks that never fail |
| `CrucibleColdCodeSize` | on | keep cold methods from inlining |
| `CrucibleColdOptimizeForSize` | on | compile cold methods with the settings of `-Os`: the image of a Quarkus service 11% smaller, of Spring PetClinic 12%, of a Renaissance benchmark or a sample 7 to 10%, at the same speed on all nineteen |
| `CrucibleCodeLayout` | on | order the code section by call count |
| `CrucibleHotMethodsAtFullSettings` | on | below `-O3`, compile hot methods as `-O3` would |
| `CrucibleMinimumReceiverShare` | 0.01 | least share of a call site's calls for a receiver type to get its own test and inlined body |
| `CrucibleTypeGuard` | by level | guard a biased virtual call before inlining; on at `-O2` and below |
| `CrucibleProfileDiagnostics` | off | say what was passed over and why |
| `CrucibleProfileTrace=<substring>` | | report every profile lookup whose context contains the substring, and print the inliner's call tree for the methods that match: every call it looked at, inlined or not, and why not |

## Building CrucibleVM

    source crucible/env.sh          # mx and a labs JDK 25
    cd substratevm && mx build      # first build: 20 to 60 minutes
    mx crucible-e2e                 # records, rebuilds and checks that the profile was applied
    mx unittest ProfileKeyTest CrucibleProfileWriterTest CounterSlotAllocatorTest CrucibleProfileParserTest

## Layout

CrucibleVM's code is in `com.oracle.svm.core.crucible` and `com.oracle.svm.hosted.crucible.*`, and
everything that is not Java is under `crucible/`. It reaches the compiler through extension points
upstream already has. Where one was missing, the change to upstream is a few lines in a commit of
its own, so that rebasing onto a new upstream stays simple: a public constructor for the priority
inliner, a guard in the code layout feature, a way to mark a loop simple again after
`insertPrePostLoops`, the inlining provider deciding how rare a receiver may be, and the
`mx crucible-e2e` command.
