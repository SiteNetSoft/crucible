# CrucibleVM

Open-source profile-guided optimization for GraalVM Community Edition `native-image`.

> **Alpha.** Measured, and checked for identical output, on a handful of benchmarks; not yet on
> the breadth of code a production compiler has to get right. Options and the profile format may
> change. Keep a build without it to compare against, and [report anything that
> miscompiles](https://github.com/SiteNetSoft/crucible/issues/new?template=1_wrong_output.yml); see
> [CONTRIBUTING.md](../CONTRIBUTING.md).

You build your program once so that it records how it runs, run it on a workload that looks like
production, and build it again with what it recorded. The second build knows which branches go
which way, which methods the time is spent in, and where calls actually land, and compiles
accordingly.

## Quick start

    crucible/pgo.sh --name app --run '$APP <your workload arguments>' -- -cp app.jar com.example.Main

builds an image that records, runs your workload on it, builds an optimized image that samples
call stacks, runs the workload again, adds the stacks to the profile and builds `./app`, the image
to ship. The arguments after `--` go to `native-image` as they are (`-jar app.jar` works too); in
`--run`, `$APP` is the image to run, and the workload has to let it exit normally, which is when it
writes what it recorded, so stop a service with SIGTERM. `--no-samples` stops after the counted
profile, one run and two builds. Work files are in `.crucible-pgo/app/`.

For a service, the workload is a small script that starts it, puts load on it and stops it:

    #!/usr/bin/env bash
    "$APP" --server.port=8080 & PID=$!
    until curl -sf -o /dev/null http://127.0.0.1:8080/; do sleep 0.1; done
    oha -z 20s http://127.0.0.1:8080/        # or wrk, hey, a test suite: whatever looks like production
    oha -z 20s http://127.0.0.1:8080/vets
    kill -TERM $PID; wait $PID

    crucible/pgo.sh --name petclinic --run ./workload.sh -- <Spring's native-image arguments> --install-exit-handlers

`--install-exit-handlers` lets SIGTERM end the image normally. Built this way, Spring PetClinic
serves its `/` 11% and `/vets` 26% more requests a second than Oracle's profile-guided binary; the
whole command takes about a quarter of an hour.

The steps one at a time:

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
| The class library's branches, without a profile | are given the probabilities recorded for them in other programs, from a profile of the library that is built into the builder (4,900 to 6,100 branches an image; the branches that leave a loop are left alone). On by default since 2026-10-13; `-H:-CrucibleLibraryProfile` turns it off. A build with a profile of its own, or one that records, does not use it |

## How it compares

An image built with a profile is built at `-O3` unless you ask for another level, as Oracle's `native-image` does when it is given a profile.
Give `-O2` with the profile if you want the smaller image, which is 4 to 8% smaller.
On the samples a profile-guided image at `-O2` runs as fast as one at `-O3`; on most of the Renaissance benchmarks it is 10 to 45% slower.

CrucibleVM is now based on GraalVM 25.4.4.1.1. With its defaults, over fourteen Renaissance benchmarks against Oracle's profile-guided binary, it comes to 0.913 of Oracle's time from a counted profile and 0.890 from one with sampled stacks (one session of five rounds, 2026-10-08), behind on two: rx-scrabble by 4 to 8% and scala-stm-bench7 by 2%. Control flow duplication stays on with a profile, with the pass that moves what it puts on every path back onto the paths that use it, and the collector prefetches what the objects it copies point to once a collection copies more than fits in the caches (scrabble 20% faster). Spring PetClinic's `/` and `/vets` serve about 10% and 25% more requests a second than Oracle's binary, Quarkus about 7%.
Since 2026-10-11 a recording also lists the types the run locked, and the types it used without locking them are built without a monitor slot: over the fourteen, 0.871 of Oracle's time against 0.881 in the same session, scala-stm-bench7 now 3.4% ahead, and rx-scrabble, by 2%, the one benchmark behind.
See `docs/issues/2026-10-03-graalvm-25-4.md`.

Without a profile, at the default level, the fourteen ran at 1.159 of Oracle's default binary until 2026-10-12, scala-kmeans at twice its time and scala-doku at 1.8: an upstream change from August 2026 sets the inliner's exploration to its minimum at `-O2`, and the hosted policy scales every budget the inliner has by that value, so the typical graph size and the invoke bonuses go to zero and the default level inlines next to nothing. The exploration is left at its middle now and the default level gets the `-O3` typical graph size; the rest of what `-O2` holds back stays held back. Over the fourteen, 0.939 of Oracle's default binary's time (five rounds, same session), ahead on twelve and behind by 1% on mnemonics and rx-scrabble; the images are 17% larger than Oracle's and 48% larger than before, and a build takes a third longer than before and a tenth less than Oracle's. The full `-O3` inliner budget would give 0.921 for images 14% larger again and builds 11% longer again, and is slower on five of the fourteen.
With the library's profile on top, which is the default since 2026-10-13, the fourteen are at 0.917 of Oracle's default binary, ahead on thirteen and level on rx-scrabble; it gives the services 3 to 5% (finagle-chirper 0.99 to 0.94, finagle-http 0.98 to 0.95) and philosophers 6%, and costs under 1% on future-genetic and scala-doku.

Same machine, `-O3`, each binary checked for identical output, 2026-10-09 and 10. Time of the profile-guided binary
from each compiler; lower is better. The samples are built from counted recordings, medians of eleven runs; the
Renaissance benchmarks from sampled stacks; the services with `crucible/pgo.sh`.

| workload | Oracle GraalVM | CrucibleVM |
| --- | --- | --- |
| GameOfLife (Oracle's own example) | 3.39 s | **2.91 s** |
| ArrayBench | 1.19 s | **0.31 s** |
| BranchBench | 0.55 s | **0.54 s** |
| JsonBench | 1.56 s | **1.39 s** |
| BenchPGO | 0.37 s | **0.32 s** |
| Renaissance, fourteen benchmarks, geometric mean of the time | 1 | **0.88 to 0.89** |
| Quarkus REST/JSON quickstart, requests a second on `/fruits` | 100,060 | **105,200** |
| Spring PetClinic, requests a second on `/vets` (JSON) | 36,800 | **46,500** |

Over three sessions of five rounds, CrucibleVM is ahead on twelve of the fourteen Renaissance benchmarks: par-mnemonics by 29%, scrabble and scala-doku by 23%, philosophers, fj-kmeans, and akka-uct by 12 to 16%, future-genetic and finagle-chirper by 8 to 10%, reactors by 7 to 13%, mnemonics by 6 to 10%, finagle-http by 5%, and scala-kmeans by 2%.
It is behind on rx-scrabble by 3 to 4% and on scala-stm-bench7 by up to 2%.
BranchBench and BenchPGO lose 17% and 9% to control flow duplication, which the fourteen gain 0.7% from (see the page above).

With each collector choosing its heap, CrucibleVM's binary peaks in less memory than Oracle's on seven of the fourteen (fj-kmeans 179 MB against 530, mnemonics 202 against 353, scala-doku 82 against 132, finagle-chirper 125 against 258, rx-scrabble 114 against 153, reactors 841 against 1,093, finagle-http 91 against 115), in about the same on five, and in more on two: akka-uct 2,460 MB against 931, and scrabble 335 against 174 (three rounds, 2026-10-10).
akka-uct's memory buys it a little: on 2026-10-10, with `-Xmx700m`, it ran as fast, 12.1 s an iteration against 12.0, in 723 MB; on 2026-10-25, three rounds, the uncapped binary runs 10.1 to 10.7 s in 1.9 to 2.1 GB, `-Xmx1g` 10.9 to 11.2 s in 1.05 GB and `-Xmx700m` 11.4 to 11.7 s in 722 MB, against Oracle's 13.3 to 14.2 s in 0.95 to 1.03 GB. The program spends half its time in young collections at any young size, the share the policy grows the young generation to reach, and promotes 8 to 10% of what it allocates at every size, so the growth saves the per-collection cost and nothing else; below 700 MB it slows down, 14.1 s with `-Xmx500m`, and `-XX:SerialGCTimeRatio=6` goes the other way, 9.9 s in 4 GB.
akka-uct can hang, one dispatcher thread spinning and the others parked, about once in two thousand runs. That is akka's own bug ([akka/akka-core#33019](https://github.com/akka/akka-core/issues/33019): a mailbox is submitted to the fork-join pool again while another worker is still taking it), and the benchmark hangs the same way on HotSpot.
To hold a service's memory down, give it an `-Xmx` of about three times what it keeps alive.

The rest of this section is from GraalVM 25.3, over twelve benchmarks.
With the heap of each capped at the memory Oracle's binary used on it, the geometric mean was 0.958.
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

If your program, like a benchmark harness, drops everything it allocated at intervals, try
`-XX:SerialGCMinDistance=50` at run time. The adaptive policy grows the young generation only while
young collections come closer than 20 ms apart (HotSpot uses 100), and this raises that to 50 ms, so
the young generation can hold what the program allocates between those intervals. It brought
rx-scrabble level with Oracle's binary and scala-stm-bench7 1% further ahead, but rx-scrabble then
peaked at 153 MB and not 102, and scrabble at 722 MB and not 310, so measure it; the default is unchanged.
Measured on 2026-10-25 over the fourteen, three rounds, against 20 ms: 30 ms runs 1% faster for 11% more
memory in all, 50 ms 3.3% faster for a third more, 75 ms 3.9% for two thirds more, and 100 ms 4.3% for
86% more. The gain is fewer young collections an iteration and flattens past 50 ms, where par-mnemonics
runs 11% faster, reactors 6%, mnemonics and fj-kmeans 4%, and philosophers, future-genetic and scrabble
peak at two to three times their memory, above Oracle's binary where they were below it. On the Quarkus
service under load, 30 ms answers 3% more requests a second in 90 MB and not 86, 50 ms 8% more in 106 MB,
75 ms 10% in 125 and 100 ms 11% in 145, where Oracle's binary answers 3% more than 20 ms in 140 to 170 MB;
Spring PetClinic is unchanged at every value.

The policy shrinks the young generation for memory only while next to nothing is promoted, so it keeps
the size it starts at: a quarter of `-Xms` when that is given, and a small one otherwise. On Spring
PetClinic under load with `-Xmx512m -Xms512m` it stays at 128 MB and the process holds 240 MB, where
Oracle's binary holds 200; without `-Xms` it settles at 20 MB, 129 MB in all, and answers 8% fewer
requests. `-XX:+SerialGCShrinkWhilePromoting` lets it shrink while objects are promoted too, as far as the
time ratio and the minimum distance allow, so that with and without `-Xms` it ends at the same size.
For a service with a capped heap, use it with `-XX:SerialGCTimeRatio=19`: measured on 2026-10-29, two
rounds, `-Xmx512m`, Spring PetClinic answers 5,490 to 5,520 requests a second in 142 to 149 MB, with or
without `-Xms` (Oracle's binary 4,460 in 198 to 204 MB, the default 5,690 to 5,800 in 237 to 242), and
the Quarkus quickstart 15,760 to 15,940 in 113 to 118 MB (Oracle's 15,000 in 150 to 167, the default
14,450 to 14,640 in 86 to 88). Without a cap, ratio 19 grows the young generation far: over the fourteen,
on eight processors, it runs 10% faster than the default and peaks at 2.2 times its memory, akka-uct at
6.6 GB and scrabble at 1.3, with or without the shrinking, which changes nothing there. Both stay off by default.

The adaptive policy collected the old generation only when it reached its capacity, and `-Xms` sets
that capacity to nearly the whole heap from the start, so a service under load kept what it promoted
until the heap was full: Spring PetClinic under load held 311 MB where Oracle's binary holds 200, with
no complete collection in 90 s. Since 2026-10-25 the collector collects completely once the old
generation holds twice what the last complete collection kept, 16 MB at the least, and PetClinic holds
240 to 280 MB and answers 1% more requests; Quarkus is unchanged. On the fourteen Renaissance
benchmarks it costs 1% over all against the policy as it was: par-mnemonics runs 3 to 6% slower,
mnemonics 1 to 3%, and reactors within 3% either way between sessions, and the two mnemonics peak at
about 165 and 203 MB and not 195 and 253. mnemonics runs in one of two modes, about 1,500 and 1,700 ms,
in either setting; the mode goes with the eden size the policy chooses, and a comparison of few runs can
show a 10% difference that is only which mode each run landed in.
`-XX:SerialGCOldGrowthFactor=<n>` sets the multiple at run time, and 0 waits for the capacity as before.

The collector scans the young generation on several threads (build with `-H:-ParallelYoungCollection` to keep it on one), and `-XX:ParallelYoungCollectionThreads=<n>` sets how many at run time, the thread that runs the collection included (the default is the processors the program may use, which in a container is its limit, up to 9; with one processor, collections stay serial).
Programs that spend much of their time in young collections on several threads of their own gain the most: on eight processors, with a profile, akka-uct runs 15% faster than on one thread and peaks at 1.2 GB and not 1.9, scala-stm-bench7 and reactors 12% faster, and scrabble 7%.
The fourteen Renaissance benchmarks run 3.6% faster on eight processors (geometric mean; scala-kmeans, the one that loses most, 1.6% slower) and level on two, and the Quarkus and Spring services answer as many requests in the same memory.

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
| `CrucibleSinkAfterDuplication` | on with a profile | after control flow duplication (`-H:+OptDuplication`), copy arithmetic only rarely run blocks use into each of them |
| `CrucibleProfiledMonitorSlots` | on | with a profile that lists the types the run locked (one recorded by this version does), leave the monitor slot, eight bytes an object, out of the types the run used and never locked; they are still locked correctly, through a table, slowly, so record a workload that locks what production locks |
| `CrucibleAnalysisMonitorSlots` | on | give a monitor slot to every type the analysis finds possibly locked, which is close to every type; off, only the types that must have one get it |
| `GreyScanPrefetchAhead` | 8 | while a collection visits the references of a copied object, prefetch what the reference this many slots on points to. Not a Crucible option |
| `GreyScanPrefetchNextObject` | on | and prefetch the referents of the next copied object. Not a Crucible option |
| `GreyScanPrefetchMinCopiedKB` | 1024 | prefetch only in a collection after a young collection that copied this much, or once it has itself; 0 always. Not a Crucible option |
| `GreyScanPrefetchQueue` | 0 | instead, visit each reference this many later with its referent prefetched; faster where collections copy a lot, slower elsewhere. Not a Crucible option |
| `LargeArrayThreshold` | the most an aligned chunk takes | the size from which an array gets a chunk of its own; was 128 KB. Not a Crucible option |
| `OptDuplication` | on | control flow duplication, new in GraalVM 25.4; with a profile it can put a value computed on a rarely taken path on every path, which `CrucibleSinkAfterDuplication` undoes. Not a Crucible option |
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
