# What Oracle's PGO actually does, measured against ours

`2026-09-19`

Every claim in this project about how CrucibleVM compares to Oracle's PGO rested on
prose in Oracle's documentation, measured on their hardware. Oracle GraalVM is free
under the GraalVM Free Terms and Conditions, so it can simply be run next to ours.
This is what that showed.

## The gap, measured

GameOfLife 4000x4000, 20 generations, `-O3`, 4-core container, 11 interleaved rounds,
each compiler scored against its own control. All binaries produce identical output.

| | control | PGO | gain |
| --- | --- | --- | --- |
| Oracle GraalVM | 9370 ms | 5607 ms | **+40.2%** |
| CrucibleVM | 9771 ms | 8740 ms | **+10.6%** |

Their published figure of roughly 45% is not an artifact of their machine. It
reproduces here at 40.2%, and we reach about a quarter of it.

## Which part of the profile carries the speedup

Oracle's `.iprof` is JSON with five profile categories. Emptying categories one at a
time and rebuilding with their own compiler decomposes their speedup exactly.

| profile fed to `--pgo` | time | vs control |
| --- | --- | --- |
| control, no `--pgo` | 9383 ms | -- |
| full profile | 5605 ms | **+40.3%** |
| everything except sampling | 9735 ms | -3.8% |
| conditional + call counts | 9750 ms | -3.9% |
| call counts only | 11223 ms | -19.6% |
| conditional only | 14355 ms | -53.0% |
| empty profile | 16688 ms | -77.9% |

The whole speedup comes from `samplingProfiles`, and that category has **22 entries**.
Branch probabilities and call counts -- the two categories CrucibleVM records in
detail, at 5178 and 7015 entries -- are together a net regression.

They are a regression because `--pgo` is not additive. It switches the compiler into a
mode where anything the profile does not vouch for is compiled cold. Without sampling
data nothing is ever hot, so the mode costs everything and returns nothing.

## What the sampling profile unlocks

Symbols, with `-H:-DeleteLocalSymbols`:

| variant | `run` | standalone `getAliveNeighbours`? |
| --- | --- | --- |
| control `-O3` | `run` 1605 B | no, already inlined |
| profile without sampling | `run` 261 B | yes, nothing inlined |
| full profile | `run%%H1` 18351 B | no |

Three things to read here. Ordinary `-O3` already inlines this program's hot methods,
so inlining is not what we are missing. Without sampling, `--pgo` inlines nothing at
all. And with it, the emitted method is not `run` but `run%%H1`, eleven times the size.

`%%` is `StableMethodNameFormatter.METHOD_VARIANT_KEY_SEPARATOR` and `H1` is a
`MethodVariant.MethodVariantKey`. `saveGrid` and `loadGrid` come out as `%%H2` and
`%%H3`: a distinct key each, not a shared "hot" flag.

So Oracle's PGO creates a **specialised clone of each method per hot calling context**.
The clone is analysed and compiled separately with that context's profiles applied, and
the original stays as the cold version.

## The seam this leaves

The community edition contains the consumers and none of the producer.

- `cai/PrefixTree` turns `PGOProfilesLookup.getSampleCounts()` into a tree of hot
  calling contexts. Nothing in the tree ever calls `new PrefixTree`.
- `StructuredGraph.GlobalProfileProvider.hotCaller()` gates
  `SubstratePriorityInliningPhase.devirtualizeHotCallees`, the hot path of
  `applyPGOProfiles`, and receiver-type profiles on invokes. It is false by default and
  nothing upstream sets it.
- `PGOApplyProfilesPhase.createForExpandingHotCutoffs` and
  `createForBeforeHotCompilationPhase` take a `PrefixTree.Cursor`. Neither is called.
- `MethodVariant.getOrCreateMethodVariant` is general enough to make the `%%H` clones.
  Only `ORIGINAL_METHOD` and the deoptimization variants use it.

## What this rules out

`MethodDuplicationPhase` looked like the answer: it splits a method into a hot and a
cold part, peels loops until the cold re-entry is outside them, keys off branch
frequencies we already supply, and is gated off by default. Turning it on with our
profile added 1.2 MB of code to a 7.9 MB image and changed nothing measurable, 11.5% to
10.8% across seven interleaved rounds. It is not the mechanism.

## What this means for CrucibleVM

Reaching parity is not a matter of recording more, or of applying what we record
earlier. We already record the same categories Oracle does, in more detail, and we
apply them before inlining. The missing piece is a compilation tier: a sampling profile
of hot calling contexts, and per-context method specialisation driven from it.

## Follow-up: what the hot variant actually contains, and closing the gap

None of Oracle's own switches removes their speedup: with `-H:-CAIAggressivelyOptimizeHot`
it is still +29%, with `-H:HotCodeMinSelfTime=2.0` +31%, and `-H:-Vectorization` changes
nothing. The machine code says why. The inner loop of `run%%H1` does one range check per
cell and then loads the neighbours unguarded; the 36 border comparisons per cell are gone
and the original loop survives as a fallback. It is loop versioning on the checks the
profile says never fail.

Doing the same split by hand in the source, with no profile at all, confirms that this is
the whole effect: Oracle's plain `-O3` goes from 9073 ms to 5973 ms, against 5802 ms for
their PGO, and ours goes from 9724 ms to 6957 ms.

Our build already unswitches the checks on the row, which do not change inside the inner
loop; the loop phases log `f=4001.00` from a trusted source, so the profile reaches them.
The checks on the column depend on the induction variable and unswitching cannot move them.

`CrucibleLoopRangeSplitPhase` handles those. For a hot counted loop it collects the checks
of the form `iv + c < K` that the profile saw go one way at least 99% of the time, works
out the range of `iv` over which they all go that way, and runs the loop three times: up
to that range, across it with the checks folded, and over the rest. The three-loop
structure is `LoopTransformations.insertPrePostLoops`, which the compiler already has for
partial unrolling; the phase only picks the two limits and folds the checks.

GameOfLife, `-O3`, 20 generations, 11 interleaved rounds, identical output at 1, 2, 7 and
20 generations:

| | control | PGO | gain |
| --- | --- | --- | --- |
| Oracle GraalVM | 9340 ms | 5609 ms | +39.9% |
| CrucibleVM, range split off | 9812 ms | 8692 ms | +11.4% |
| CrucibleVM | 9812 ms | **5003 ms** | **+49.0%** |

Four loops split and 24 checks folded. BranchBench (+50.0% either way) and BenchPGO (-0.5%
either way) have no loop that qualifies and are unchanged.

What this does not show is parity in general. It is one workload, and the one Oracle chose
to demonstrate their PGO on. Their per-context method variants are still something we do
not have, and a program whose time goes into virtual dispatch rather than a stencil would
not be helped by this phase at all.

## Follow-up: a second workload, and what was left of the gap

GameOfLife is Oracle's own example, so `crucible/samples/ArrayBench.java` was written as a
check on it: a dot product, a prefix sum, a guarded blur and a matrix multiply, each over
arrays longer than the range looped over, so that no access is trivially in bounds.

At first the phase gave +29% where Oracle's PGO gives +70%. Building Oracle's image with
`-H:-Vectorization` placed the difference: 1347 ms without against 780 ms with, while ours
ran 1550 ms either way. On scalar code we were within 13%. The rest was the vectorizer,
which this tree also contains and also runs, and which was declining every one of our
middle loops with `don't vectorize partially unrolled loop`.

`insertPrePostLoops` marks its loops pre, main and post because partial unrolling is what
it was written for, and `LoopVectorizationAnalysis` reads the mark as a loop whose strides
have been changed. Ours have not. `LoopBeginNode.setSimpleLoop` takes the mark off the
middle loop again; it is the third and smallest upstream patch.

The phase also learned to take bounds that are fixed in the loop without being constants,
an array length above all. A length read inside the loop is hoisted in front of it,
looking through the null check the array came by, and if the array may be null the length
is read behind a null test and counts as zero, so that the middle loop does not run and
the loops either side of it throw where the original would have. Bounds are worked out in
64 bits and the middle range is kept to where `iv + c` cannot wrap. Loops that can also be
left by an exception are taken. A second instance runs after lowering.

`-O3`, interleaved rounds, each compiler against its own control, identical output:

| workload | Oracle PGO | CrucibleVM PGO | Oracle, absolute | CrucibleVM, absolute |
| --- | --- | --- | --- | --- |
| GameOfLife, 20 generations | +40.2% | **+51.3%** | 5600 ms | **4782 ms** |
| ArrayBench, 1500 rounds | +70.4% | **+68.6%** | 781 ms | **694 ms** |

BranchBench stays at +50.1% and BenchPGO at -2.2%; neither has a loop the phase takes.
BenchPGO is the call-dominated one, and it is where the method-variant machinery described
above would have to earn its keep. That is the part still missing.
