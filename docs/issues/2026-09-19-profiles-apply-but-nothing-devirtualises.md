# Profiles are applied but the hot call site is not devirtualised

- **Date:** 2026-09-19
- **Milestone:** after M3, first speedup measurement
- **Status:** open

## What was measured

`crucible/samples/bench.sh` builds three images from one source — instrumented, profiled, and a
control with identical flags and no profile — and alternates runs of the profiled and control
images. On the `BenchPGO` workload, 400,000,000 iterations, 11 alternating pairs:

    control   median   2221 ms   fastest   2207 ms
    profiled  median   2204 ms   fastest   2197 ms
    difference +17 ms (+0.8%), run-to-run spread of the control 14 ms

0.8% with a 14 ms spread is not a speedup worth claiming. It is barely outside the noise.

## The profile reaches the right place

This is not a case of the profile missing the hot code. Pass 1 records the call site exactly:

    LBenchPGO;.work(ILBenchPGO$Op;)I:2  types [Dbl=93750, Inc=2906250]   (96.9% dominant)
    LBenchPGO;.work(ILBenchPGO$Op;)I:12 conditional [2953125, 46875]

and pass 2 applies both, confirmed with `-H:CrucibleProfileTrace=BenchPGO`:

    TYPE LBenchPGO;.work(ILBenchPGO$Op;)I:2
    HIT  LBenchPGO;.work(ILBenchPGO$Op;)I:12

## But the generated code is unchanged

Built with `-H:-DeleteLocalSymbols` and disassembled, the profiled image's hot method still
dispatches through the vtable:

    30754:  mov   0x78(%r14,%rax,8),%rax     ; vtable load
    30767:  call  *%rax                      ; indirect call

A receiver that is 96.9% one type was not turned into a guarded direct call, so the call overhead
the profile was supposed to remove is still there. That fully accounts for the missing speedup.

## Where it stops

`PGOApplyProfilesPhase` sets a `JavaTypeProfile` on the call target and upstream derives a method
profile from it. That is not what drives devirtualisation.

The priority inliner devirtualises in `devirtualizeIndirectCallTargetInvokes`, which asks
`SubstrateInliningProvider.samplingMethodProfiles(root, invoke)` for a `JavaMethodProfile`. That
method reads from a `PrefixTree.Cursor`, and the tree is built in `PrefixTree.populatePrefixTree`:

```java
Map<NodeSourcePosition, Long> samples = pgoProfiles.getSampleCounts().orElseGet(HashMap::new);
```

`getSampleCounts` is a `PGOProfilesLookup` method with a default returning `Optional.empty()`, and
CrucibleVM never overrode it. The prefix tree is therefore empty, `profileFor` returns `null` at
every call site, no `JavaMethodProfile` is produced, and no devirtualisation is attempted. The
`JavaTypeProfile` CrucibleVM does supply influences other decisions, but not this one.

So the seam has a second half nobody noticed: registering a lookup and answering the conditional
and receiver-type queries is enough to change branch probabilities, and not enough to change a
call.

### A correction to an earlier reading

An earlier revision of this note claimed `SubstratePriorityInliningPhase` was absent from the high
tier. That was wrong, and the fault was in the diagnostic rather than in the build. Features are
offered **two** hosted suites; the first is the real AOT suite and the second is a reduced one. The
diagnostic kept only the last suite it was given and printed that:

    suite #0: CrucibleApplyProfilesPhase, CanonicalizerPhase, BoxNodeIdentityPhase,
              SubstratePriorityInliningPhase, DeadStoreRemovalPhase, RemoveUnwindPhase,
              ... FinalPartialEscapePhase, ReadEliminationPhase, BoxNodeOptimizationPhase
    suite #1: CrucibleApplyProfilesPhase, CanonicalizerPhase, BoxNodeIdentityPhase,
              DeadStoreRemovalPhase, RemoveUnwindPhase, ...

The inliner is present, it runs after `CrucibleApplyProfilesPhase`, and the ordering was never the
problem.

## What this does and does not say about M1-M3

The acquisition half — the part CrucibleVM set out to build — works: counters, receiver types, a
schema, a parser, and a lookup upstream consumes at the right call sites. Branch probabilities do
reach the compiler. What is missing is the one input the inliner needs to rewrite a call, and it is
a different input from the one the design anticipated.

Note that upstream's own documentation states plainly that "PGO is not available in GraalVM
Community Edition" (`docs/reference-manual/native-image/PGO.md`). The machinery is present and
reachable; it is the data supply that CE leaves unimplemented, which is exactly the seam this
project set out to fill. `getSampleCounts` is simply a part of that seam that was not visible until
a benchmark asked why nothing got faster.

## Next: M4

Feeding `getSampleCounts` needs a calling-context tree: each key is a `NodeSourcePosition` chain
whose methods are `AnalysisMethod`s, each value the number of times that context was executed, and
the callee must appear as a child of its call site for `profileFor` to find candidates.

CrucibleVM already records, per call site, which receiver types occurred and how often. What it
does not record is which method each of those receivers would dispatch to, so the callee cannot be
named. Adding the invoked method to the virtual-invoke record, and resolving the concrete
implementation per observed receiver type at apply time, produces exactly the (context, callee,
count) triples the tree wants. That is the M4 plan.
