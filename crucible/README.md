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

### A profile recorded by Oracle GraalVM

    python3 crucible/samples/iprof-to-crucible.py default.iprof crucible-profile.json

Call counts, branches, receiver types and sampled stacks carry over. On the sample workloads a
converted profile drives the build as well as one of our own.

## What it does

| | |
| --- | --- |
| Branch probabilities | applied before inlining, so layout, inlining and the loop optimizations all see them |
| Loop range splitting | a hot counted loop whose checks the profile saw go one way nearly always is run in three parts, the middle one without the checks; this is also what lets the vectorizer take it |
| Receiver types | counted at every virtual call in every place its method is inlined, including the places where the compiler could work the receiver out and the call is no longer virtual, so that what is recorded for a call is everything that came through it; the inliner tests for the common receivers first and inlines their methods |
| Call counts | of every method, inlined or not |
| Cold code | a method the run never reached does not look into its callees, which roughly halves the machine code of an image |
| Code layout | the code section is ordered by how often each method ran |
| Hot methods below `-O3` | are compiled the way `-O3` would compile them: its inliner settings, partial unrolling and loop vectorization, which the default level otherwise holds back for every method alike. On the samples a profiled `-O2` image runs as fast as a profiled `-O3` one and stays the size of an `-O2` one |

## How it compares

Same machine, `-O3`, each binary checked for identical output. Time of the profile-guided binary
from each compiler; lower is better.

| workload | Oracle GraalVM | CrucibleVM |
| --- | --- | --- |
| GameOfLife (Oracle's own example) | 5.6 s | **4.8 s** |
| ArrayBench | 0.78 s | **0.68 s** |
| BranchBench | 1.00 s | **0.86 s** |
| JsonBench | 2.49 s | **2.41 s** |
| BenchPGO | **0.54 s** | 0.58 s |
| Renaissance, twelve benchmarks | ahead on eight | ahead on four |

The gain from the profile is as large as Oracle's or larger on most of these. Where CrucibleVM is
behind, the two compilers already differ by about that much without any profile, and on the
programs that allocate most the difference is allocation and garbage collection rather than the
optimizer. The details, and everything that was tried and did not work, are in `docs/issues/` and
`crucible/ROADMAP.md`.

If your program allocates heavily, try `-XX:InitialCollectionPolicy=BySpaceAndTime` at run time. It
was worth 20% on two Renaissance benchmarks and cost 25% on another, so measure it; it is not a
default.

## Options

All are `-H:` options and need `-H:+UnlockExperimentalVMOptions`.

| option | default | |
| --- | --- | --- |
| `CrucibleInstrument` | off | build an image that records a profile |
| `CrucibleProfile=<file>` | | build with a profile |
| `CrucibleRecordStartupOrder` | off | also record the order methods were first entered in, for `CrucibleCodeLayoutByStartup`; costs a call at every method entry |
| `CrucibleMaxContextDepth` | 1 | inlining frames recorded per counter; more is more precise and a larger recording image |
| `CrucibleRecordKeepsCallsVirtual` | on | in a recording image, leave a call with several possible receivers a call; off, the image inlines as an optimized one does |
| `CrucibleContextClones` | on | with sampled stacks, compile a method again for a caller it spends time under |
| `CrucibleMinimumSamplesAtCall` | 32 | fewest samples under a call for the sampled stacks to be believed about where it goes |
| `CrucibleLoopRangeSplit` | on | split hot loops around checks that never fail |
| `CrucibleColdCodeSize` | on | keep cold methods from inlining |
| `CrucibleCodeLayout` | on | order the code section by call count |
| `CrucibleHotMethodsAtFullSettings` | on | below `-O3`, compile hot methods as `-O3` would |
| `CrucibleMinimumReceiverShare` | 0.01 | least share of a call site's calls for a receiver type to get its own test and inlined body |
| `CrucibleTypeGuard` | by level | guard a biased virtual call before inlining; on at `-O2` and below |
| `CrucibleProfileDiagnostics` | off | say what was passed over and why |
| `CrucibleProfileTrace=<substring>` | | report every profile lookup whose context contains the substring |

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
