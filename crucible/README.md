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

`verify-profile.py` exits 0 only if the profile is schema v1, carries a non-empty
`producer.imageBuildId`, records exactly one call to `main`, and contains the 9M/1M conditional
that the sample is built to produce.

Run-time override of the output path: `./hellopgo -XX:CrucibleProfileOutput=/tmp/run1.json`.
The profile is written from an isolate tear-down hook, so it is not produced on `SIGKILL` or a
crash.

Instrumentation is never applied to `CrucibleProfileRuntime` itself or to any `@Uninterruptible`
method; see `docs/issues/2026-09-18-instrumented-image-stack-overflow.md` for why.
