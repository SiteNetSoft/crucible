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
| A | Sum the context-insensitive fallback profile over every caller a method was inlined into, instead of keeping whichever was seen first | written, unmeasured | nearly all applied branch data goes through the fallback |
| B | Inline more inside the methods the run spent its time in, judged by branches and calls executed rather than by call count | written, off by default, unmeasured | the lever for stream and lambda code; scrabble had 9 profile-justified inlines in the whole image |
| C | The community edition's optimizer is behind Oracle's before any profile is involved | found on scrabble | the profile has to pay for that as well; B and E are the first attempts |
| D | A separate copy of a hot method for each hot calling context, which is what Oracle's `%%H1` variants are | not built; the community edition has the consumers and no producer | matters when a method behaves differently depending on who calls it |
| E | Let the inliner explore deeper in hot methods, not only accept more of what it explored | not started | pairs with B |
| F | Time sampling in the recording image | not built; work share stands in for it | a better idea of what is hot, for B, D and E |
| G | The last 7% on BenchPGO, which is a loop Oracle unrolls twice | blocked: upstream's early-exit merging cannot take an exception exit | small, and risky to force |
| H | More of Renaissance: the Scala and actor benchmarks, then the Spark ones | running | breadth; the Spark ones may not build closed-world at all |
| I | Reading Oracle's `.iprof`, reporting profile quality, warning about stale profiles, an `mx` gate for the end-to-end check | not started | needed before this stops being alpha |

## Tried and dropped

| What | Outcome |
| --- | --- |
| `MethodDuplicationPhase`, which splits a method into a hot and a cold part | 1.2 MB more code, no speed |
| Marking hot callers (`-H:+CrucibleMarkHotCallers`) | no gain at `-O3`; no longer crashes the compiler |
| Unrolling small hot loops that have branches in them | upstream's `mergeEarlyLoopExits` fails on an exception exit, and an inline cache always leaves one |
| Keeping no fallback call where static analysis lists every receiver | builds and runs correctly, no speed |
| A second unswitching pass after lowering | no effect |
