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
