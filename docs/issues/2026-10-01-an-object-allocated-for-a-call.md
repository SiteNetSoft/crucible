# An Object Allocated for a Call

`2026-10-01`

On scrabble, `Collectors.groupingBy`'s accumulator allocates a lambda for every element and hands it to `HashMap.computeIfAbsent`.
CrucibleVM's binary allocated 2.9 GB of these lambdas, and Oracle's profile-guided binary allocated 0.17 GB.
CrucibleVM's priority inliner declines `computeIfAbsent` on its cost, and Oracle's inlines it.
Inlining the call is what lets escape analysis remove the lambda, which is allocated for the call and used by it alone.

## The Option

The priority inliner already notes when an argument of a call is an object allocated for it (`BenefitKind.NewAllocation`), and then does nothing with it.
`-H:CrucibleFreshArgumentBenefit=N` multiplies the benefit of such a call by N.
`-H:+CrucibleFreshArgumentLambdasOnly` limits that to arguments that are lambdas.
The factor is read from each compilation's options, so `-H:CrucibleHotMethodOptions` can give it to the methods the run spent its time in, or take it back from them.
Both options are off by default.

## What It Is Worth

Against the same tree built from the counted profile, at `-O3`, three rounds, medians:

| | factor 2 | factor 3 | factor 4 | factor 8 | factor 4, lambdas only |
| --- | --- | --- | --- | --- | --- |
| scrabble | 5% faster | 14% faster | 10 to 13% faster | 4% faster | 2.5% faster |
| scala-kmeans | 3% slower | 4% faster | 4.5% faster | 6% faster | 4.5% faster |
| philosophers | | level | 4 to 6% faster | | 2% faster |
| mnemonics | 3% slower | 29% slower | level to 3% slower | 40% slower | |
| par-mnemonics | | 22% slower | level to 7% faster | | 25% slower |
| rx-scrabble | | level | 2 to 3% slower | | 1.5% slower |
| Spring PetClinic | | level | 2 to 3% slower | | level |
| Quarkus | | level | 1 to 2.5% slower | | level |

At factor 4 the other six Renaissance benchmarks are level, and akka-uct is level over eleven runs.
Over the twelve, the geometric mean against Oracle's binary goes from 0.955 to 0.944.

## Mnemonics

mnemonics and par-mnemonics have a slow state, at about the speed of Oracle's binary.
In it, `IntPipeline$Head` and `IntPipeline$1`, the `chars()` stream and the `mapToObj` stage of `wordCode`, are 10 to 14% of what the benchmark allocates, where without the option they do not appear at all.
Per callee, the inliner's decisions show `wordCode` inlined at 6 of its 7 call sites without the option, and at 2 of 9 with factor 3 and 2 of 8 with factor 4.
The boost spends the callers' budget on other calls, and `wordCode` is compiled on its own.
Whether its stream is then removed depends on how far that compilation inlines down to `CharsSpliterator.forEachRemaining`, and factor 4 happens to go far enough.
The 12 to 25% that CrucibleVM's binary is ahead of Oracle's on these two benchmarks comes from `wordCode` being inlined into its caller, and even the lambda-only boost, with 1,026 calls counted for more, takes it away.

The inlining trace (`-H:+TraceInlining`) does not settle which call it is: even with one compiler thread (`--parallelism=1`) it lists decisions under compilations they do not belong to.
Each decision line names its callee, so counting decisions per callee is reliable, and that is what the numbers above use.

## On Top of the Sampled Recipe

The recipe in the README, stacks in copies only and the hot inlining budget, already runs the twelve at 0.915 of Oracle's binary.
Against the recipe alone, three rounds, medians:

| | factor 4 everywhere | factor 4 in the hot methods only | factor 4 everywhere but the hot methods |
| --- | --- | --- | --- |
| scrabble | 10% faster | 10% slower | **11% faster** |
| scala-kmeans | 4.6% faster | level | **4% faster** |
| philosophers | 4% faster | 3.5% faster | level |
| mnemonics | 1.7% slower | 3.5% faster | level |
| par-mnemonics | 1.5% slower | 2.5% slower | level |
| reactors | 9% slower | 16% slower | 1% slower |
| akka-uct | level | level | 7% slower |
| Spring PetClinic | | `/` 3 to 4.5% slower | 1.5 to 2.5% slower |
| Quarkus | | 1 to 2% faster | level to 1% slower |

reactors runs at one of two speeds in a round, about 8.3 s or 9.6 s, for every image, and akka-uct at about 10.5 s or 12.5 s.
Their rows come from eight rounds, except reactors with factor 4 everywhere but the hot methods, from five.

Factor 4 everywhere leaves the twelve at a geometric mean of 0.998 against the recipe alone, 0.913 against Oracle's binary.
Every placement of the factor costs Spring PetClinic something, so none of them is a default.
