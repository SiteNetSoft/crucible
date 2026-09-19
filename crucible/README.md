# CrucibleVM

Open-source profile-guided optimization for GraalVM CE / Mandrel `native-image`.
Design: `docs/design/2026-08-29-cruciblevm-pgo-design.md`.

## Building

    source crucible/env.sh          # mx 7.85.1 + labs JDK 25 (see docs/plans/2026-08-29-m0-environment-bootstrap.md)
    cd substratevm && mx build      # first build: 20-60 min
    mx native-image --version

## Layout

All CrucibleVM code lives in `com.oracle.svm.core.crucible` and `com.oracle.svm.hosted.crucible.*`;
tracked non-Java assets live under `crucible/`. Upstream files are never modified so that
`git rebase upstream/master` stays trivial.

## Instrumented build (pass 1)

    source crucible/env.sh
    crucible/samples/build.sh -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument
    (cd crucible/samples/out && ./hellopgo)          # writes crucible-profile.json
    python3 crucible/samples/verify-profile.py crucible/samples/out/crucible-profile.json

`verify-profile.py` exits 0 only if the profile is schema v2, carries a non-empty
`producer.imageBuildId`, records exactly one call to `main`, and contains the 9M/1M conditional
that the sample is built to produce.

Run-time override of the output path: `./hellopgo -XX:CrucibleProfileOutput=/tmp/run1.json`.
The profile is written from an isolate tear-down hook, so it is not produced on `SIGKILL` or a
crash.

Instrumentation is never applied to `CrucibleProfileRuntime` itself or to any `@Uninterruptible`
method; see `docs/issues/2026-09-18-instrumented-image-stack-overflow.md` for why.

An instrumented image records four things: how often each method body ran, how often each
successor of each branch was taken, and which receiver types occurred at each indirect call. A
call site remembers four distinct receiver types; beyond that it counts overflows, which the
profile reports per site so a site with a wider type set is visible rather than silently truncated.
The fourth is which types reach each `instanceof`, recorded the same way and used by the compiler
to order and shortcut the type check.

### Merging profiles

    python3 crucible/samples/merge-profiles.py merged.json run1.json run2.json [...]

One run covers a fraction of an image — a few hundred methods out of several thousand — so a single
profile describes far less than an application does. Merging several runs, or several workloads,
widens that coverage, which affects how much profile reaches the compiler more than any tuning of
the pipeline. Counts add; conditionals match on calling context and bytecode index, successors on
their own bytecode index, and types by name.

### A note on devirtualisation

`-H:+CrucibleMarkHotCallers` tells the compiler which methods ran often, which is the only thing
that lets upstream apply receiver-type profiles and devirtualise. It is **off by default**: the
resulting direct call is not inlined afterwards, and a guard costs more than a well-predicted
indirect call saves — measured as a 1.8% regression on a dispatch-heavy workload. Branch
probabilities, which are what this project is measurably worth, are applied regardless.

## Profiled build (pass 2)

    source crucible/env.sh
    crucible/samples/build.sh -H:+UnlockExperimentalVMOptions \
        -H:CrucibleProfile=crucible/samples/out/crucible-profile.json

`-H:+CrucibleInstrument` and `-H:CrucibleProfile` are mutually exclusive: an image either records a
profile or consumes one.

Registering the profile activates upstream's `PGOApplyProfilesPhase` and the PGO paths of
`SubstratePriorityInliningPhase`. Note that the community edition applies profiles *only* while
inlining into a hot caller, so CrucibleVM also registers upstream's context-insensitive apply phase
for methods compiled as their own root; see
`docs/issues/2026-09-18-apply-seam-has-no-caller-in-ce.md`.

Because CE never reports profile hit rates, the build prints its own summary:

    Crucible: applied 2460 of 11862 conditional profile lookups (20.7%), 1351 via the context-insensitive fallback.

Diagnostics: `-H:+CrucibleProfileDiagnostics` prints sample contexts that matched nothing, and
`-H:CrucibleProfileTrace=<substring>` reports every lookup whose context contains the substring,
marked `HIT`, `MISS` or `TYPE`.

Receiver-type profiles feed upstream's `JavaTypeProfile`, from which it derives a method profile,
so a call site that is monomorphic or strongly biased at run time can be devirtualised in pass 2.

## Benchmark

    source crucible/env.sh
    crucible/samples/bench.sh [workload] [reps] [iterations]

`workload` is `BranchBench` (layout-dominated) or `BenchPGO` (call-dominated). The script builds an
instrumented, a profiled and a control image from that workload, then alternates runs
of the profiled and control images and reports the median of each. Alternating cancels slow drift
in machine load and the median ignores outliers; neither removes noise, so the script prints the
control's own run-to-run spread next to the difference and says so when the difference is smaller.

Results as of 2026-09-19:

    BranchBench   control 1730 ms   profiled 1458 ms   +15.7%   (control spread 15 ms)
    BenchPGO      control 2221 ms   profiled 2204 ms    +0.8%   (control spread 14 ms)

The two differ in what they ask of the profile. `BranchBench` is decided by layout: its rare paths
are bulky and a profile lets the compiler move them off the hot path. `BenchPGO` is decided by a
biased virtual call, and the community edition never rewrites a call however good the profile is,
so it stays at the noise floor. See
`docs/issues/2026-09-19-devirtualisation-is-closed-in-ce.md`.

## End-to-end check

    source crucible/env.sh
    crucible/samples/e2e.sh

Runs both passes and asserts that the profile was applied, including to the sample's own skewed
branch. Exits non-zero on the first failure.
