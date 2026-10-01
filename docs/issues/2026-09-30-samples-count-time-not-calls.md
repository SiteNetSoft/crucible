# Samples Count Time, Not Calls

`2026-09-30`

rx-scrabble ran 6% behind Oracle's profile-guided binary with the collector level between the two: JDK Flight Recorder execution samples put 2.9% of CrucibleVM's time and 3.1% of Oracle's in collections inside the timed iterations.
The difference was in the loop that drives every RxJava pipeline, `ObservableFromIterable$FromIterableDisposable.run`.
In Oracle's binary 16.7% of the samples had it as the top frame, in CrucibleVM's 3.5%, with the rest of that time in a dozen frames below it.

## Why

Oracle's builder compiles `run` five times, once for each pipeline that goes through it, and inlines 33 and 41 calls into the two largest copies.
CrucibleVM compiles it once, and its `onNext` call, whose receivers are pooled over every pipeline, inlines nothing.
CrucibleVM can make such copies too (`CrucibleContextClones`), but only from a profile with sampled stacks, and rx-scrabble's had counts only.

With stacks added, the builder copied `run` ten times, and each copy stopped inlining after 6 to 12 calls: at the filter's own downstream `onNext` the inliner's budget was spent.
Neither longer sampling (27,776 samples against 4,272) nor believing the stacks from 8 samples under a call rather than 32 changed that.
Oracle's builder gives a method the samples show hot a larger budget, and its values passed to CrucibleVM's hot methods (`-H:CrucibleHotMethodOptions`) took rx-scrabble from 68 ms to 66, against Oracle's 64, with the copies.
Without the copies the same values made it 3 to 5% slower, the larger budget spent in code shared by every pipeline.

Across the twelve Renaissance benchmarks that combination made scrabble 12% faster and philosophers 10%, and future-genetic 33% slower and scala-doku 48%.
Built from the sampled profile with neither copies nor the hot values, future-genetic was already 25 to 27% slower and scala-doku 61 to 62%.
Where a call has 32 samples under it, the inliner is told where it goes from the samples alone, and samples count time.
A receiver the call reaches often and returns from quickly is seldom caught, so it drops out of the profile, and the call is not inlined to it.

## The Change

With `-H:-CrucibleSampledTargetsOutsideCopies` the inliner is told the stacks' receivers in the copies made for one caller only.
A method compiled for everyone gets the counted receivers, as it does without stacks.
The default leaves the stacks' receivers everywhere, as before.

Taking the counted receivers instead wherever they are one or two types over every calling context, and the stacks' elsewhere, fixed neither future-genetic nor scala-doku: their losses are at calls the counts do not settle either.

## What It Is Worth

The twelve Renaissance benchmarks, each from a profile with about a minute of sampled stacks, with Oracle's hot values and `-H:CrucibleHotBonusWhileExpanding=50`, three to six rounds, beside the same tree built from the counted profile and Oracle's binary, milliseconds an iteration, medians:

| | Oracle GraalVM | counted profile | with stacks, in copies only | |
| --- | --- | --- | --- | --- |
| akka-uct | 14,568 | 12,738 | **11,214** | 12% faster |
| scrabble | 252.1 | 275.2 | **247.3** | 10% faster |
| philosophers | 1,693 | 1,600 | **1,509** | 6% faster |
| scala-kmeans | 171.5 | 171.5 | **161.8** | 6% faster |
| future-genetic | 944.8 | 926.9 | **895.8** | 3% faster |
| scala-doku | 1,246 | 1,057 | **1,028** | 3% faster |
| rx-scrabble | 63.6 | 67.7 | 66.0 | 2.5% faster |
| reactors | 10,421 | 9,673 | **9,494** | 2% faster |
| scala-stm-bench7 | 893 | 960 | 952 | 1% faster |
| fj-kmeans | 3,091 | 3,147 | 3,163 | level |
| par-mnemonics | 1,843 | 1,355 | 1,378 | 2% slower |
| mnemonics | 1,826 | 1,590 | 1,659 | 4% slower |

The geometric mean of the ratios is 0.967, and against Oracle's binary it goes from 0.947 to 0.916.
A second build of the fixed image of scrabble and of future-genetic ran within the rounds of the first.
The stacks are not part of a profile by default: they need an image built with JFR, a run sampled, and the stacks added with `crucible/samples/jfr-to-samples.py`, as the README says.
