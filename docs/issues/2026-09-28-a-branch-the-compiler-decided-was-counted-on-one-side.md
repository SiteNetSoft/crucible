# A Branch the Compiler Decided Was Counted on One Side

`2026-09-28`

mnemonics was the largest loss to Oracle GraalVM with a known cause: twice the allocation.
Allocation samples of both images, ten iterations at `-O3`, put all of it in four kinds of object.

| | Oracle GraalVM | CrucibleVM |
| --- | --- | --- |
| all allocation sampled | 35.0 GB | 53.0 GB |
| `IntPipeline$1`, the `mapToObj` stage | 0.00 | 7.16 |
| `IntPipeline$Head` | 0.04 | 6.59 |
| the lambda of `wordCode` | 0.11 | 2.46 |
| `IntPipeline$1$1`, the sink around the stage | 0.00 | 1.53 |

That is 17.6 of the 18.0 GB.
They are the stream in `MnemonicsCoderWithStream.wordCode`, which the benchmark runs once for every word of its dictionary at every call of `encode`.
Oracle's compiler removes the four objects and this one allocated them.

## What Held Them on the Heap

With the symbols kept, the machine code of `encode` still had calls to `IntPipeline$1.opWrapSink`, `StringLatin1$CharsSpliterator.forEachRemaining`, and `AbstractPipeline.copyIntoWithCancel`.
Oracle's image has none of them in the unit that holds the stream.
A stage passed to a call escapes, and the head and the sink with it, so all of the calls have to go before any of the objects can.
That is why inlining one of them by force, tried the day before, changed nothing.

The inliner had not refused `opWrapSink` for its size.
It had never looked into it, because the call had a frequency of 0.4 where the stream around it had 36.
The call is the body of the loop in `AbstractPipeline.wrapSink`, which goes over the stages of a stream, and the profile said this about the test of that loop:

| recorded in the calling context of | out of the loop | into its body |
| --- | --- | --- |
| `wrapAndCopyInto` | 765,139 | 765,140 |
| `wordCode` | 50,945,040 | not recorded |

The body ran as often as the loop was left.
Oracle's recording of the same program has both sides.

## Why One Side Was Missing

Branches were counted in the graph a method is finally compiled from.
By then the compiler has used the calling context the counts are wanted for.
In `wordCode` the stream is made and used in one place, so the recording compiler knew the first stage and its depth, decided the first test of the loop, and removed it.
The second test, which leaves the loop, was what remained to count.
The profile of that context held what the compiler could not work out and nothing of what it could, and a build reading it took the loop for one that is never entered.

Receivers and method entries had the same fault and have been counted through probes since 2026-09-21; see `2026-09-21-the-profile-left-out-what-the-compiler-worked-out.md`.
Branches had been left as they were.

Before anything was changed, the missing count was written into the recording by hand.
mnemonics ran 1617, 1623, and 1616 for it, where the image in use ran 1930 and Oracle's 1786.

## The Fix

A probe goes on each way out of each branch as the graph of a method comes out of parsing.
It travels with the method wherever the method is inlined and becomes the counter after inlining, under the calling context it ended up in.
Where the compiler decides the branch, the side not taken goes and its probe with it, and the probe on the side taken is left to say that it was.

The branches probed are the ones a profile is later applied to, chosen by the same function of the compiler, `ProfilingUtilities.relevantConditionalNodesFromGraph`.
The phase that counts in the finished graph leaves every split of a probed method alone.
It still counts in code that has no probes, which is the garbage collector's.
`-H:-CrucibleRecordBranchesWithProbes` records as before.

Recorded again from one build, the loop has 50,945,040 on both sides with the probes and on one side without.

## What It Is Worth

One machine, six processors, `-O3`, three rounds, milliseconds for an iteration, the median of the three medians.
"In use" is the image the tables had until now.
"As before" and "probes" are recorded and built from one build, without the probes and with them.

| | Oracle GraalVM | in use | as before | probes | |
| --- | --- | --- | --- | --- | --- |
| par-mnemonics | 1821 | 1644 | 1632 | **1365** | 17% faster |
| mnemonics | 1786 | 1930 | 2030 | **1659** | 14% faster |
| future-genetic | 941 | 1058 | 1068 | **938** | 11% faster |
| scrabble | 249 | 260 | 246 | **234** | 10% faster |
| scala-doku | 1245 | 1062 | 1060 | **1047** | 1% faster, in every round |
| rx-scrabble | 63.8 | 68.9 | 69.6 | 69.8 | level |
| fj-kmeans | 3057 | 3266 | 3249 | 3276 | level |
| scala-stm-bench7 | 891 | 993 | 979 | 998 | level with the image in use |
| reactors | 9914 | 10097 | 10355 | 10197 | level; Oracle's binary ran 9257, 11002, and 9914 |
| akka-uct | 13942 | 11298 | 11492 | 11559 | level; the image in use ran 12043, 11298, and 11076 |
| philosophers | 1702 | 1520 | 1575 | 1463 | not to be told; the image in use ran 1435, 1520, and 1606 |
| scala-kmeans | 171.9 | 173.6 | 174.1 | 175.8 | level; the same two recordings built again the next day gave 175.2 without the probes and 173.1 with them |

The percentages are against the image in use.
The gain is where the program is made of streams: one method inlined in many places, and its branches decided differently in each.
The image recorded as before ran mnemonics at 2030, 2035, and 1935, so whole runs of it differ by 5%.

future-genetic is level with Oracle's binary by the median and 6% behind by the mean, 999 against 940, because it has slow iterations that the median does not show.
The image in use has a mean of 1067.

Over the twelve, the geometric mean of the ratios to Oracle's binary goes from 0.998 to 0.950.

## The Samples

Seven runs, the median, milliseconds.
Every image prints what the control prints.

| | Oracle GraalVM | in use | as before | probes |
| --- | --- | --- | --- | --- |
| BranchBench | 546 | 462 | 461 | 461 |
| ArrayBench | 379 | 323 | 321 | 324 |
| JsonBench | 1577 | 1446 | 1420 | 1442 |
| GameOfLife | 3198 | 2769 | 2906 | 2918 |
| BenchPGO | 371 | 278 | 278 | **295** |

BenchPGO is 6% slower with the probes, and its seven runs are within 3 ms of each other.
It is one count in one record.
The program tests `(i & 31) == 0` in its loop, which is true once in 32 times.
Over the 3,000,000 iterations of a recording the old way counted 93,749 and the probes count 93,750, which is what the program does.
The old-way recording with that one count changed gives the slower image, 294 ms, and the recording made with probes gives the faster one, 275 ms, when that one count is taken from the old way and all 1,433 other differences are left in.

The count sits on a tie.
With the record's other side left at 2,906,250:

| count | share | |
| --- | --- | --- |
| 60,000 | 0.0202 | 277 ms |
| 90,000 | 0.0300 | 277 ms |
| 93,749 | 0.0312497 | 276 ms |
| 93,750 | 0.03125 | 292 ms |
| 93,751 | 0.0312503 | 292 ms |
| 97,000 | 0.0323 | 292 ms |
| 150,000 | 0.0491 | 293 ms |

At one in 32 and above the image is the slower one, and `main` is two bytes larger, 1,557 against 1,555.
It is not loop range splitting and not the type guard: with either turned off the two counts give the same two times.

One in 32 is not a constant of the compiler.
The receiver `BenchPGO$Dbl` at the call in `work` also comes once in 32 calls, and moving its count moves the edge with it:

| share of `Dbl` at the call | share of the branch | |
| --- | --- | --- |
| 0.03125, as recorded | 0.0312497 | 275 ms |
| | 0.03125 | 294 ms |
| 0.0397 | 0.0313 | 275 ms |
| | 0.0333 | 277 ms |
| | 0.0406 | 294 ms |
| | 0.0428 | 294 ms |
| 0.0202 | 0.0169 | 277 ms |
| | 0.0235 | 292 ms |
| | 0.0313 | 294 ms |

The image is the slower one whenever the branch is at least as frequent as the receiver.
Where the two are equal the compiler orders the two rare blocks of the loop the other way, and the slower order puts an alignment `nop` inside the loop.
That ordering is upstream's (`DefaultCodeEmissionOrder`, `BasicBlockOrderUtils`), and a tie there goes to the successor looked at second.

So this is not what the probes cost.
The program has two rare events of exactly the same frequency, the old way had one of them wrong by one count, and that broke the tie the fast way.

An earlier version of this note gave another reason, and it was wrong.
With the probes a recording has counts of one for code that ran once, and the build inlines `Integer.parseInt` and `PrintStream.writeln` into `main` for them, which is then 5,594 bytes and not 1,555.
That is so, and it is not why the loop is slower: with those calls kept out of `main`, which is then 1,482 bytes, the image runs the same 294 ms.
The rule that kept them out was dropped.
It refused about 1,570 callees in an image, at calls that ran at most three times and did next to none of the recorded work, and ArrayBench ran 338 ms with it and 321 without.

The reason was found by taking the differences between the two recordings in halves, fifteen builds, after two reasons given from reading the code had been wrong.

GameOfLife recorded and built today is 5% slower than the image in use, with the probes and without.
The image in use was built on 2026-09-26.
It is not the recording: today's compiler gives 2905 from the recording of the 26th as well, and 2913 from one made today with one frame of calling context.
It is the tenuring threshold the collector has chosen by default since 2026-09-28.
Run with `-XX:SerialGCTenuringThreshold=-1`, which is the community edition's policy, today's image takes 2752, and the image of the 26th takes 2759.

## What Recording Costs

The recording image is 12 to 14% smaller: 102 MB against 118 MB on mnemonics.
The old way counted at every split left in the finished graph, the copies the compiler had made of a branch among them.

Recording takes no longer.
Each benchmark was recorded once each way, four iterations, and one recording says little: mnemonics took 9.4 s an iteration with the probes and 10.0 s without, and the same image recorded a second time took 10.1 s.
On two benchmarks the difference is larger than that, and on both the probes are the faster: scrabble 0.92 s against 1.23 s, and scala-doku 7.0 s against 8.1 s.
The largest difference the other way is scala-stm-bench7, 3.4 s against 3.1 s.

## What a Recording No Longer Has

scala-kmeans was 1.5 to 2.5% slower with the probes in eight rounds, and it was not the probes.
Built a second time from the same recordings, the image recorded as before ran 175.2 ms and the one with the probes 173.1, over six rounds, and all four images were the same size to the byte.
An image built from the recording as before with every difference of the other applied to it ran 173.2.
Two builds of one program from one recording differ here by up to 2%, and a difference of that size between two images says nothing until each has been built twice.

On scala-kmeans the two recordings agree on the 4,121 branches both have, 0.82 billion counts in each.
The old way counted at 2,361 more, with 1.16 billion counts, the largest of them at the bytecode of a call.
They are not among the branches a profile is applied to.
They do enter the measure of how much of a run happened in each method, which says which methods are cold.

## Tried and Dropped

Keeping those counts: the phase that counts in the finished graph skipped a successor only when its counter was one a probe counts into, and counted at every other split as before.
JsonBench ran 1583 for it, 11% slower, where it runs 1416 to 1446 otherwise.
It counted branches twice.
A probe's key has the successor's place among the successors as it is in the graph out of parsing, and what is left of the branch in the finished graph often has its successors the other way round.
The key differs, the counter is not one a probe counts into, and the branch is counted again: `String.charAt` had the same count on both sides of a test that goes one way every time.
190 records had two successors at one bytecode, where the old way has 99 and the probes 25.
A record is matched to a successor by its bytecode, so a build reads such a record as a branch that goes both ways.
