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
| Renaissance philosophers | 2152 ms | **1902 ms** | ahead |
| Renaissance akka-uct | 28040 ms | **27029 ms** | ahead |
| Renaissance scala-doku | 2086 ms | **1999 ms** | ahead |
| Renaissance scala-kmeans | 308 ms | **300 ms** | ahead |
| Renaissance rx-scrabble | **121 ms** | 136 ms | behind by 12% |
| Renaissance fj-kmeans | **6507 ms** | 7521 ms | behind by 16% |
| Renaissance scala-stm-bench7 | **1612 ms** | 1866 ms | behind by 16% |
| Renaissance future-genetic | **1720 ms** | 2159 ms | behind by 26% |
| Renaissance scrabble | **517 ms** | 655 ms | behind by 27% |
| Renaissance reactors | **15611 ms** | 20312 ms | behind by 30% |
| Renaissance par-mnemonics | **3488 ms** | 4886 ms | behind by 40% |
| Renaissance mnemonics | **3561 ms** | 5718 ms | behind by 61% |

Across Renaissance the profile-guided gain is as large as Oracle's or larger on most
benchmarks. What separates the binaries is what is underneath: where the two controls are
level we are ahead, and where Oracle's starts ahead we stay behind by about that much. On the
benchmarks that allocate most, the difference is a third more allocation and a slower
collector, not the optimizer. See `docs/issues/2026-09-20-renaissance-across-the-suite.md`.

## Open

| | What | State | Why it might matter |
| --- | --- | --- | --- |
| C | The community edition is behind Oracle's before any profile is involved: 30% on scrabble | measured, and mostly not the optimizer: a third more allocation, and a collector that takes twice as long over it | decides what "ahead of Oracle" can mean on allocation-heavy code |
| D | A separate copy of a hot method for each hot calling context, which is what Oracle's `%%H1` variants are | half done: sampled stacks now give the inliner call targets per inlining context, without copies | matters when a method behaves differently depending on who calls it; did not on scrabble |
| G | The last 7% on BenchPGO, which is a loop Oracle unrolls twice | blocked: upstream's early-exit merging cannot take an exception exit | small, and risky to force |
| H | More of Renaissance | twelve measured; dotty fails the harness's validation under both compilers; the Spark, Neo4j and Finagle ones not attempted | breadth; the Spark ones may not build closed-world at all |
| I | Usability | done: `iprof-to-crucible.py` reads Oracle's `.iprof` (a converted profile drives the build as well as our own recording), the build says how much of a profile fits the program and warns when it does not, `mx crucible-e2e` is the gate. Left: documentation for users | needed before this stops being alpha |
| J | Allocation: a third more than Oracle on scrabble, three quarters more on mnemonics, where collection is the entire 61% loss | measured; raising the limits of escape analysis changes nothing, so it is not being cut short. Both images allocate the same things, the machinery of a stream, and Oracle's removes a larger share | the largest single thing between us and Oracle on Renaissance, and not something a profile feeds |
| K | Compile what the profile calls cold for size. Oracle's scrabble image has 5.9 MB of code, ours had 17.4 MB | first step done: cold methods no longer look into their callees, which brought scrabble to 8.5 MB of code and the image from 38.5 MB to 29.0 MB at the same speed. Left: turning off loop optimizations and escape analysis in cold code, for which `HostedConfiguration.setInstanceIfEmpty` and `CompileQueue.getCustomizedOptions(method)` are the way in | image size |
| L | At the default `-O2` the compile queue narrows the inliner, turns off its escape analysis and takes partial unrolling and vectorization out of the suites, for every method alike | done: hot methods get the `-O3` options and suites. At `-O2` a profile was worth a quarter to a third of what it is at `-O3`; now BenchPGO 1593 to 580 ms, BranchBench 1474 to 859, ArrayBench 1571 to 660, JsonBench 3818 to 2521, each the `-O3` figure or close, in an image of `-O2` size | most builds are at the default level |
| M | Which collection policy suits a profiled image | settled: none as a default. `BySpaceAndTime` is worth 20% on mnemonics and par-mnemonics and 10% on scrabble, and costs 25% on akka-uct; a fixed young generation is neutral to far worse (akka-uct 2.6 times slower at 128 MB) | the README says to measure it and nothing more |

## Done since the list was started

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
| Profiling the garbage collector's own code | 94 collector methods profiled, collection no faster; the rest of the package cannot be counted without crashing at start-up |
| A 2 GB young generation on Renaissance | reported time drops below Oracle's, wall time gets worse: it moves collection outside the timed region |
