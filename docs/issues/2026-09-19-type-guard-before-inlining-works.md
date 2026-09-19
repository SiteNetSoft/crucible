# Guarding a biased call before inlining pays; guarding it after lowering does not

- **Date:** 2026-09-19
- **Status:** implemented, on by default, measured

## Result

    BenchPGO      control 2224 ms -> profiled 2122 ms    +4.6%   (control spread 21 ms)
    BranchBench   control 1720 ms -> profiled 1458 ms   +15.2%   (control spread 6 ms)

`BenchPGO` is the dispatch-dominated workload that had never gained anything: +0.8% (noise) when
the profile was merely applied, and **-2.8%** when devirtualisation ran after lowering. Guarding
the same call before inlining instead makes it **+4.6%**, with the layout-dominated workload
unchanged. Outputs are identical across both images at three different iteration counts.

## Why the position in the pipeline decides the sign

`CrucibleDevirtualizationPhase` ran after lowering, where `IndirectCallTargetNode` exists, and used
`AddressBasedDevirtualization`: it compares the dispatch address against a method address. That is
the only form available there, and it buys nothing that a correctly predicted indirect branch does
not already give, so the guard was pure overhead.

`CrucibleTypeGuardPhase` runs before inlining and uses `ReceiverBasedDevirtualization`, whose
condition is an `InstanceOfNode` plus a `PiNode`. The `PiNode` is the point: it pins the receiver to
an exact type on the guarded path, so everything downstream compiles against a known type rather
than an interface. The direct call is a consequence, not the whole benefit.

Notably the callee is **not** inlined even so -- `work` still contains a `call` to
`BenchPGO$Inc.apply`. The expectation that a direct call created by a phase would be inlined like
one produced by parsing did not hold, and the gain arrived without it. Whatever makes the inliner
skip a phase-created call is still unexplained and is now the remaining upside rather than the
mechanism.

## Three constraints this phase had to satisfy

Each was found by a failed build rather than by reading, and each is specific to a closed-world AOT
compiler:

1. **Uninterruptible reasons are typed.** `mayBeInlined = true` is only legal with the reason
   `CALLED_FROM_UNINTERRUPTIBLE_CODE`; a prose reason aborts the build.
2. **Hosted and analysis methods are different objects.** `callTarget.targetMethod()` yields a
   `HostedMethod`, while `AnalysisType.resolveConcreteMethod` demands the `AnalysisMethod` it
   wraps.
3. **A guard may only name a reachable implementation.** A profile is collected from an
   *instrumented* image, whose reachability graph differs from the optimised one, so it can name a
   receiver whose callee the analysis never saw invoked. Emitting a call to it fails with
   "reachable during compilation, but was not seen during Bytecode parsing". The phase now
   intersects candidates with `hostedTarget.getImplementations()` and requires
   `isImplementationInvoked()`, counting what it drops.

Constraint 3 has no analogue in JIT PGO, where any observed receiver is by definition loaded and
callable. It is the sharpest difference between profiling a closed-world image and profiling a JVM.

## Settings

`-H:+CrucibleTypeGuard` is on by default, with `-H:CrucibleDevirtualizeMinimumBias` (0.7) and
`-H:CrucibleDevirtualizeMaxTargets` (2). The after-lowering phase stays available behind
`-H:+CrucibleDevirtualize` and off, and `-H:+CrucibleMarkHotCallers` stays off; both were measured
as regressions.

## Addendum: the optimization level dominates everything measured here

Every number above, and every number reported earlier in this project, was taken at the default
`-O2`. Repeating the measurements at `-O3` changes the picture substantially, and in one case
changes the sign:

    BranchBench     -O2  +15.2%     -O3  +50.1%      (1722 ms -> 859 ms)
    BenchPGO        -O2   +4.6%     -O3   -2.3%      (1019 ms -> 1042 ms)
    GameOfLife  1g  -O2   -2.2%     -O3  +21.7%
    GameOfLife 20g  -O2   +8.6%     -O3  +13.5%

`-O3` runs the frequency-driven phases -- partial loop unrolling above all -- that consume branch
probabilities, so the same profile is worth far more there. The conditional application rate rises
too, from 21.9% to 35.4% on GameOfLife, because more of the graph survives in a form the profile
can be matched against.

The type guard reverses: worth +4.6% at `-O2` and -2.3% at `-O3`, where the compiler already
handles that dispatch and the guard becomes overhead. It should not be an unconditional default,
and is left on only because `-O2` is the default level; this needs tuning per level rather than a
single answer.

## Against Oracle's own benchmark

`crucible/samples/GameOfLife.java` is Oracle's PGO example, copied verbatim from
`docs/reference-manual/native-image/PGO-Basic-Usage.md`, so CrucibleVM can be measured on the
program Oracle publishes numbers for rather than only on its own. Oracle documents roughly 42% for
one generation and 45% for a hundred. At `-O3` CrucibleVM reaches **+21.7%** and **+13.5%**: about
a third of the published figure, on the same program, with identical output.
