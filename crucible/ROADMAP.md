# Performance roadmap

What has been tried, what it was worth, and what is left. Numbers are `-O3`, same machine, both
compilers, each against its own control unless it says otherwise, and every comparison checks that
the binaries produce identical output. The reasoning behind each entry is in `docs/issues/`.

## Standing

Absolute time of the profile-guided binary from each compiler.

| Workload | Oracle GraalVM PGO | CrucibleVM PGO | |
| --- | --- | --- | --- |
| GameOfLife | 5759 ms | **4936 ms** | ahead |
| ArrayBench | 783 ms | **681 ms** | ahead |
| BranchBench | 1001 ms | **859 ms** | ahead |
| JsonBench | 2526 ms | 2512 ms | level |
| BenchPGO | **541 ms** | 578 ms | behind by 7% |
| Renaissance philosophers | 2148 ms | **1874 ms** | ahead |
| Renaissance akka-uct | 29496 ms | **26704 ms** | ahead |
| Renaissance scala-doku | 2075 ms | **2030 ms** | ahead |
| Renaissance scala-kmeans | 308 ms | **300 ms** | ahead |
| Renaissance rx-scrabble | **119 ms** | 134 ms | behind by 13% |
| Renaissance fj-kmeans | **6666 ms** | 7680 ms | behind by 15% |
| Renaissance scala-stm-bench7 | **1601 ms** | 1940 ms | behind by 21% |
| Renaissance future-genetic | **1730 ms** | 2026 ms | behind by 17% |
| Renaissance scrabble | **514 ms** | 653 ms | behind by 27% |
| Renaissance reactors | **15848 ms** | 18458 ms | behind by 16% |
| Renaissance par-mnemonics | **3670 ms** | 4798 ms | behind by 31% |
| Renaissance mnemonics | **3598 ms** | 5813 ms | behind by 62% |

Recorded again on 2026-09-21 with counting marked before inlining, which moved future-genetic
(2386 to 2026), reactors (20722 to 18458) and scrabble (688 to 653) and left the rest where they
were. Across Renaissance the profile-guided gain is as large as Oracle's or larger on most
benchmarks. What separates the binaries is what is underneath: where the two controls are
level we are ahead, and where Oracle's starts ahead we stay behind by about that much. On the
benchmarks that allocate most, the difference is a third more allocation and a slower
collector, not the optimizer. See `docs/issues/2026-09-20-renaissance-across-the-suite.md`.

## Open

| | What | State | Why it might matter |
| --- | --- | --- | --- |
| C | The community edition is behind Oracle's before any profile is involved: 30% on scrabble | measured, and mostly not the optimizer: a third more allocation, and a collector that takes twice as long over it | decides what "ahead of Oracle" can mean on allocation-heavy code |
| G | The last 7% on BenchPGO, which is a loop Oracle unrolls twice | blocked: upstream's early-exit merging cannot take an exception exit | small, and risky to force |
| H | More of Renaissance | twelve measured; dotty fails the harness's validation under both compilers; the Spark, Neo4j and Finagle ones not attempted | breadth; the Spark ones may not build closed-world at all |
| I | Usability | done: `iprof-to-crucible.py` reads Oracle's `.iprof` (a converted profile drives the build as well as our own recording), the build says how much of a profile fits the program and warns when it does not, `mx crucible-e2e` is the gate. Left: documentation for users | needed before this stops being alpha |
| J | Allocation: a third more than Oracle on scrabble, three quarters more on mnemonics, where collection is the entire 61% loss | measured; raising the limits of escape analysis changes nothing, so it is not being cut short. Both images allocate the same things, the machinery of a stream, and Oracle's removes a larger share | the largest single thing between us and Oracle on Renaissance, and not something a profile feeds |
| K | Compile what the profile calls cold for size (cold methods also skip the loop optimizations that copy code now, another 3%). Oracle's scrabble image has 5.9 MB of code, ours had 17.4 MB | first step done: cold methods no longer look into their callees, which brought scrabble to 8.5 MB of code and the image from 38.5 MB to 29.0 MB at the same speed. Left: turning off loop optimizations and escape analysis in cold code, for which `HostedConfiguration.setInstanceIfEmpty` and `CompileQueue.getCustomizedOptions(method)` are the way in | image size |
| L | At the default `-O2` the compile queue narrows the inliner, turns off its escape analysis and takes partial unrolling and vectorization out of the suites, for every method alike | done: hot methods get the `-O3` options and suites. At `-O2` a profile was worth a quarter to a third of what it is at `-O3`; now BenchPGO 1593 to 580 ms, BranchBench 1474 to 859, ArrayBench 1571 to 660, JsonBench 3818 to 2521, each the `-O3` figure or close, in an image of `-O2` size | most builds are at the default level |
| M | Which collection policy suits a profiled image | settled: none as a default. `BySpaceAndTime` is worth 20% on mnemonics and par-mnemonics and 10% on scrabble, and costs 25% on akka-uct; a fixed young generation is neutral to far worse (akka-uct 2.6 times slower at 128 MB) | the README says to measure it and nothing more |
| M2 | The slow iterations of a Renaissance run are the ones with a major collection in them, and this tree's default policy, `Adaptive2`, has them twice as often as `Adaptive`, the default of Oracle's 25.0.4 | measured across the suite with `-XX:InitialCollectionPolicy=Adaptive`: mnemonics 5813 to 4715, par-mnemonics 4798 to 4015, fj-kmeans 7680 to 7340, and scrabble 653 to 761, scala-stm-bench7 1940 to 2168, akka-uct 26704 to 33974. Not a default either | what is left of the losses on the mnemonics pair is mostly this |

| P | One run in four of our future-genetic binaries is 13% slower than the rest, all iterations of it; Oracle's never is | seen in every build of ours, not looked into. Heap layout or the collection policy are the first things to rule out | a quarter of all runs |
| Q | Our compiler is 4% faster on Oracle's recording of future-genetic than on ours | not the calling context: recorded four and eight frames deep, with the recording image inlining as an optimized one does, 12 500 lookups are answered from a context and the image runs at 2011 ms against 2026, within the spread, for a recording image 18% larger. What is left is that their recording counts branches in the collector and other uninterruptible code and ours cannot | the rest of the gap in what a profile is worth |

## Done since the list was started

| | What | Outcome |
| --- | --- | --- |
| O | What a recording leaves out. future-genetic is the one Renaissance benchmark where Oracle's profile is worth more than ours (24% against 10%), and Oracle's own switches show that none of it is their sampling machinery: it is receiver types and branch counts | receivers were counted after inlining, so a call the compiler had made direct in one caller was not counted there, and the profile of the call, added up, held only the receivers the compiler could not work out: `DoubleChromosome` 99% where the truth is `ArrayISeq` 57%. Probes now go in as each method leaves parsing and are turned into counters after inlining. Receiver counts agree with Oracle's to a part in ten thousand, 4382 methods have a call count where 1626 had, and future-genetic goes from 2208 ms to 2019 (our compiler on Oracle's profile: 1936). A row of receivers also keeps the frequent ones now and not the first four. See `docs/issues/2026-09-21-the-profile-left-out-what-the-compiler-worked-out.md` |
| D | A separate copy of a hot method for each hot calling context, Oracle's `%%H` variants | built, without an upstream change: `HostedMethod.getOrCreateMethodVariant` and the original's encoded graph are enough. With sampled stacks in the profile it makes the copies Oracle makes on future-genetic, with the same things inlined into them, and the time does not move; Oracle's does not either with theirs turned off. Kept, on when there are samples |
| N | Cost of recording, which had never been measured against Oracle's | it was two to four times theirs and fifteen times on a parallel benchmark, because every thread wrote the same counters. Inline counters in a data-section block, eight stripes picked by the thread register, receiver tables striped likewise: philosophers 14.3 s to 3.0 s an iteration (Oracle 3.3), fj-kmeans 239 s to 31 s (Oracle 15), scrabble 8.5 s to 4.8 s (Oracle 2.2) |

## Done earlier

| | What | Outcome |
| --- | --- | --- |
| A | Fallback profile summed over every caller a method was inlined into | about 2% on scrabble, within the spread; kept because it is the right answer to give |
| B | Lower inliner threshold in the methods the run spent its time in, judged by branches and calls executed | 10 to 89 more inlines on scrabble at 4 to 64 times, no speed; kept as an option, off |
| E | More inliner exploration in hot methods | tried globally first, `-H:TuneInlinerExploration=1`: 7 MB larger, no speed; not built |
| F | Time sampling as a profile source | done by way of JFR (it names lambda classes `Outer$$Lambda/0x…` where the image has `Outer$$Lambda.0x…`, and until the converter allowed for that every sampled chain broke at a lambda): `jfr-to-samples.py` puts sampled stacks in the profile, and with them self time and hotness are measured rather than inferred |

## Tried and dropped

| What | Outcome |
| --- | --- |
| `MethodDuplicationPhase`, which splits a method into a hot and a cold part | 1.2 MB more code, no speed |
| Marking hot callers (`-H:+CrucibleMarkHotCallers`) | no gain at `-O3`; no longer crashes the compiler |
| Unrolling small hot loops that have branches in them | upstream's `mergeEarlyLoopExits` fails on an exception exit, and an inline cache always leaves one |
| Keeping no fallback call where static analysis lists every receiver | builds and runs correctly, no speed |
| A second unswitching pass after lowering | no effect |
| Oracle's settings for a hot compilation unit, as `--expert-options-all` lists them (three times the inliner's budget, its size penalties at zero, eight to ten times the duplication budgets), given to our hot methods through `-H:CrucibleHotMethodOptions` | the images differ and run the same: future-genetic 2035 ms with and without, scrabble 630. The inliner's hotness bonus at 10 and at 100 likewise |
| Probes for what `instanceof` tests, as for receivers | fuller counts, the same speed, and recording slower on future-genetic, 13.7 s an iteration where runs without them took 8.5 to 11.2; kept as `-H:+CrucibleRecordTestsWithProbes`, off |
| Profiling the garbage collector's own code | 94 collector methods profiled, collection no faster; the rest of the package cannot be counted without crashing at start-up |
| A 2 GB young generation on Renaissance | reported time drops below Oracle's, wall time gets worse: it moves collection outside the timed region |
