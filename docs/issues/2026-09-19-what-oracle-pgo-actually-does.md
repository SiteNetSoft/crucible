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
