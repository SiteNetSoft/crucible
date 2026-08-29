# Verified Build Environment (M0)

| Item | Value |
|---|---|
| Upstream baseline | `oracle/graal` tag `vm-25.3.4.1` |
| Branch | `crucible/main` |
| mx | 7.85.1 at `~/tools/mx` |
| JDK | labsjdk-ce-latest `ce-25.0.4.1+1-jvmci-25.3-b22` at `~/.mx/jdks/labsjdk-ce-latest-jvmci-25.3-b22_amd64` |
| Host | Linux x86_64, gcc, make, zlib1g-dev |
| Entry point | `source crucible/env.sh && cd substratevm && mx build` |
| Smoke test | `crucible/samples/build.sh && crucible/samples/out/hellopgo` → `sum=45000000 hot=9000000 cold=1000000` |

Rebase procedure: `git fetch upstream && git rebase upstream/master` on `crucible/main`; all
CrucibleVM files are additive so conflicts should be limited to `mx.substratevm/suite.py` once
the test project is added in M2.
