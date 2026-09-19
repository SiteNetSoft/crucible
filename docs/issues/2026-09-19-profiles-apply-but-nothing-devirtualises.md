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

## Where it stops, and the part that is unexplained

`PGOApplyProfilesPhase` sets a `JavaTypeProfile` on the call target, and upstream derives a method
profile from it. Acting on that profile — guarding on the dominant type and inlining it — is the
inliner's job, and **`SubstratePriorityInliningPhase` does not appear in the high tier at all**:

    Crucible: Optimize=2 AOTPriorityInline=true
    Crucible: high tier phase order:
      CrucibleApplyProfilesPhase
      CanonicalizerPhase
      BoxNodeIdentityPhase
      DeadStoreRemovalPhase
      RemoveUnwindPhase
      ...

`HostedGraalConfiguration.createHostedInliners` inserts the priority inliner between
`BoxNodeIdentityPhase` and the `DeadStoreRemovalPhase`/`RemoveUnwindPhase` pair that
`NativeImageGenerator.createSuites` adds straight afterwards. Those two neighbours are present and
the inliner between them is not, so the insertion ran and the phase still is not in the suite this
build compiled with.

Two explanations were checked and ruled out:

- **Not the optimization level.** `AOTPriorityInline` gates on `-O2`/`-O3` and reports `true` at
  both; `-O3` produces the same phase list.
- **Not a stale capture.** The phase list is read after compilation from the `Suites` handed to
  `registerGraalPhases`, and that same object did receive `DeadStoreRemovalPhase` and
  `RemoveUnwindPhase`, which are added after the inliner.

The remaining candidate is that hosted compilation uses a different `Suites` instance from the one
features register phases on — plausible, since `CrucibleApplyProfilesPhase` demonstrably runs, but
not established. Until it is, the diagnosis stops here rather than guessing further.

## What this does and does not say about M1-M3

The acquisition half — the part CrucibleVM set out to build — works: counters, receiver types, a
schema, a parser, and a lookup that upstream consumes at the right call sites. What is not yet
shown is that the community edition's optimiser *acts* on the profile once it has it. The
two-pass loop is complete and verified; the payoff is not.

## Next

1. Establish whether the compiling suite is the one features see, by identity rather than inference.
2. If the inliner is genuinely absent, find what installs it in a normal build and why it is missing
   here; if it is present, find why a 96.9% biased receiver is not guarded.
3. Only then repeat the benchmark. A speedup number is meaningless while the optimiser is not
   consuming the profile.
