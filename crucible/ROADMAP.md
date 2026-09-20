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
| Renaissance scrabble | **520 ms** | 677 ms | behind by 23% |

On scrabble the two profile-guided gains are the same, +20%. What separates the binaries is the
compiler underneath: without any profile Oracle's build runs an iteration in 650 ms and the
community edition's in 849 ms. Where the two controls are close we are ahead, because our profile
is put to more use; where theirs starts ahead, matching their gain is not enough.

## Open

| | What | State | Why it might matter |
| --- | --- | --- | --- |
| C | The community edition is behind Oracle's before any profile is involved: 30% on scrabble | measured, and mostly not the optimizer: a third more allocation, and a collector that takes twice as long over it | decides what "ahead of Oracle" can mean on allocation-heavy code |
| D | A separate copy of a hot method for each hot calling context, which is what Oracle's `%%H1` variants are | half done: sampled stacks now give the inliner call targets per inlining context, without copies | matters when a method behaves differently depending on who calls it; did not on scrabble |
| G | The last 7% on BenchPGO, which is a loop Oracle unrolls twice | blocked: upstream's early-exit merging cannot take an exception exit | small, and risky to force |
| H | More of Renaissance: the Scala and actor benchmarks, then the Spark ones | scrabble done, mnemonics measured once (Oracle 3.8 s, us 7.0 s an iteration), the rest queued | breadth; the Spark ones may not build closed-world at all |
| I | Reading Oracle's `.iprof`, reporting profile quality, warning about stale profiles, an `mx` gate for the end-to-end check | not started | needed before this stops being alpha |
| J | Allocation: we allocate a third more than Oracle on scrabble | found | escape analysis is where a profile could plausibly help, by saying which allocation sites are hot |
| K | Compile what the profile calls cold for size. Oracle's scrabble image has 5.9 MB of code, ours 17.4 MB | found; `HostedConfiguration.setInstanceIfEmpty` and `CompileQueue.getCustomizedOptions(method)` are the way in | image size, and possibly instruction-cache behaviour |
| L | At the default `-O2` the compile queue turns off escape analysis in the inliner and narrows its search, for every method alike | found; same way in as K | a profile could give the hot methods the `-O3` settings and leave the rest cheap |
| M | Which collection policy suits a profiled image | `BySpaceAndTime` was 12% better than the default on scrabble | one benchmark; needs the rest of Renaissance before anything is recommended |

## Done since the list was started

| | What | Outcome |
| --- | --- | --- |
| A | Fallback profile summed over every caller a method was inlined into | about 2% on scrabble, within the spread; kept because it is the right answer to give |
| B | Lower inliner threshold in the methods the run spent its time in, judged by branches and calls executed | 10 to 89 more inlines on scrabble at 4 to 64 times, no speed; kept as an option, off |
| E | More inliner exploration in hot methods | tried globally first, `-H:TuneInlinerExploration=1`: 7 MB larger, no speed; not built |
| F | Time sampling as a profile source | done by way of JFR: `jfr-to-samples.py` puts sampled stacks in the profile, and with them self time and hotness are measured rather than inferred |

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
