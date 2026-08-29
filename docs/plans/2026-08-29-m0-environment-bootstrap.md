# M0: Environment Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the empty `crucible` repository into a buildable fork of `oracle/graal` at tag `vm-25.3.4.1`, with `mx`, the labs JDK, a working `native-image`, and a tracked sample program that later milestones instrument and optimise.

**Architecture:** The repo becomes a full clone of upstream (remote `upstream`), branch `crucible/main` cut from the tag, with the existing design-doc commit cherry-picked on top. Toolchain lives outside the repo (`~/tools/mx`, `~/.mx/jdks`) and is wired in by a tracked `crucible/env.sh`. All CrucibleVM-owned non-Java files live under a new top-level `crucible/` directory so upstream rebases never conflict.

**Tech Stack:** git, `mx` 7.85.1 (Python 3), labs-JDK `ce-25.0.4.1+1-jvmci-25.3-b22`, gcc/make/zlib1g-dev (already installed), GraalVM `substratevm` build.

**Spec:** `docs/design/2026-08-29-cruciblevm-pgo-design.md` (§3 layout, §9 milestone M0)

## Global Constraints

- Upstream baseline is exactly tag `vm-25.3.4.1`; never commit modifications to upstream-owned files in M0.
- mx version must be `7.85.1` (the value of `mx_version` in upstream `common.json`).
- JDK must be `labsjdk-ce-latest` = `ce-25.0.4.1+1-jvmci-25.3-b22`; the system JDK 17 at `/usr/bin/java` must NOT be used.
- Commit messages follow the repository's existing style, with no added attribution trailers.
- New files only under `crucible/` and `docs/`.
- Long builds: run `mx build` with `timeout` ≥ 600000 ms or in the background; expect 20–60 minutes on first build.

---

### Task 1: Wire the fork to upstream and cut `crucible/main`

**Files:**
- Modify: git remotes/branches only (no working-tree files)

**Interfaces:**
- Produces: branch `crucible/main` whose tree is `vm-25.3.4.1` plus commit "Add CrucibleVM PGO design document"; remote `upstream` → `https://github.com/oracle/graal.git`.

- [ ] **Step 1: Record the design-doc commit hash and add the upstream remote**

```bash
cd "$REPO"
DOC_COMMIT=$(git rev-parse master)   # currently 81370df
git remote add upstream https://github.com/oracle/graal.git
git remote -v
```
Expected: two lines showing `upstream` fetch/push URLs.

- [ ] **Step 2: Fetch the baseline tag with full history (needed for future rebases; ~1.5 GB)**

```bash
git fetch upstream tag vm-25.3.4.1 --no-tags
git rev-parse vm-25.3.4.1^{commit}
```
Expected: a 40-char commit hash printed; no error.

- [ ] **Step 3: Create `crucible/main` from the tag and carry the design doc over**

```bash
git checkout -b crucible/main vm-25.3.4.1
git cherry-pick "$DOC_COMMIT"
git log --oneline -3
```
Expected: top commit "Add CrucibleVM PGO design document", second commit is the tag's commit.

- [ ] **Step 4: Verify the upstream PGO seams the design relies on are present**

```bash
ls substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/pgo/profiles/PGOProfilesLookup.java \
   substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/pgo/phases/PGOApplyProfilesPhase.java \
   substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/feature/InternalFeature.java
grep -n '"mx_version"' common.json
```
Expected: all three paths listed; `"mx_version": "7.85.1"`.

- [ ] **Step 5: Retire the orphan `master` branch (its only commit now lives on `crucible/main`)**

```bash
git branch -D master
git branch --show-current
```
Expected: `crucible/main`.

---

### Task 2: Install `mx` and the labs JDK, expose them via `crucible/env.sh`

**Files:**
- Create: `crucible/env.sh`
- Create: `crucible/README.md`

**Interfaces:**
- Produces: `source crucible/env.sh` puts `mx` on `PATH` and sets `JAVA_HOME` to the labs JDK. Later tasks and milestones assume this has been sourced.

- [ ] **Step 1: Clone mx at the pinned version**

```bash
mkdir -p ~/tools
git clone https://github.com/graalvm/mx.git ~/tools/mx
git -C ~/tools/mx checkout 7.85.1
~/tools/mx/mx version
```
Expected: `7.85.1`.

- [ ] **Step 2: Fetch the labs JDK (downloads ~350 MB into `~/.mx/jdks`)**

```bash
cd "$REPO"/substratevm
~/tools/mx/mx fetch-jdk --jdk-id labsjdk-ce-latest --to ~/.mx/jdks --alias labsjdk-ce-latest
ls ~/.mx/jdks
```
Expected: a directory named like `labsjdk-ce-25.0.4.1-jvmci-25.3-b22` and an alias `labsjdk-ce-latest`. The command prints the exact `JAVA_HOME` path at the end; note it.

- [ ] **Step 3: Write `crucible/env.sh`**

```bash
cat > "$REPO"/crucible/env.sh <<'SH'
# Source this file before building CrucibleVM:  source crucible/env.sh
export MX_HOME="${MX_HOME:-$HOME/tools/mx}"
export JAVA_HOME="${JAVA_HOME_CRUCIBLE:-$HOME/.mx/jdks/labsjdk-ce-latest}"
export PATH="$MX_HOME:$JAVA_HOME/bin:$PATH"
# Keep mx from picking up unrelated JDKs on this machine.
export MX_PYTHON="${MX_PYTHON:-python3}"
SH
```

- [ ] **Step 4: Verify the environment resolves to JDK 25 with JVMCI**

```bash
source crucible/env.sh
mx version
java -version 2>&1 | head -1
java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -version 2>&1 | grep -c jvmci
```
Expected: `7.85.1`; a line containing `25.0.4.1`; and `1` (JVMCI build detected).

- [ ] **Step 5: Write `crucible/README.md`**

```bash
cat > "$REPO"/crucible/README.md <<'MD'
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
MD
```

- [ ] **Step 6: Commit**

```bash
cd "$REPO"
git add crucible/env.sh crucible/README.md
git commit -m "Add build environment script and project README"
```

---

### Task 3: Build substratevm and verify `native-image`

**Files:**
- None tracked (build output in `substratevm/mxbuild`, `sdk/mxbuild`, `compiler/mxbuild`, all already git-ignored upstream)

**Interfaces:**
- Produces: a working `mx native-image` launcher in `substratevm/`.

- [ ] **Step 1: Confirm build prerequisites**

```bash
gcc --version | head -1; make --version | head -1; dpkg -s zlib1g-dev | grep Status
```
Expected: gcc and make versions printed; `Status: install ok installed`.

- [ ] **Step 2: Build (long-running; run in background with a log)**

```bash
cd "$REPO" && source crucible/env.sh
cd substratevm && mx build > "$TMPDIR/mx-build.log" 2>&1; echo EXIT=$?
```
Expected: `EXIT=0`. If it fails, the last 50 lines of the log identify the failing project; the most common causes are a wrong `JAVA_HOME` (must be the labs JDK) or a missing `zlib1g-dev`.

- [ ] **Step 3: Verify native-image is runnable**

```bash
cd "$REPO"/substratevm && source ../crucible/env.sh
mx native-image --version
```
Expected: a line containing `GraalVM` and `25.0.4.1` (or the CE snapshot version string), exit 0.

- [ ] **Step 4: Verify git tree is still clean (build must not touch tracked files)**

```bash
cd "$REPO" && git status --porcelain | head
```
Expected: no output.

---

### Task 4: Add the `HelloPGO` sample and prove an image builds and runs

**Files:**
- Create: `crucible/samples/HelloPGO.java`
- Create: `crucible/samples/build.sh`

**Interfaces:**
- Produces: `crucible/samples/build.sh [extra native-image flags...]` compiles `HelloPGO.java` and builds `crucible/samples/out/hellopgo`. M1/M2 reuse this script with `-H:+CrucibleInstrument` / `-H:CrucibleProfile=`.
- `HelloPGO` has a 90/10 skewed branch, a hot virtual call over two receiver types, and a counted loop, and prints deterministic output `sum=45000000 hot=9000000 cold=1000000` (spec §8).

- [ ] **Step 1: Write the sample program**

```bash
mkdir -p "$REPO"/crucible/samples
cat > "$REPO"/crucible/samples/HelloPGO.java <<'JAVA'
/** Deterministic workload with a skewed branch, a polymorphic call, and a counted loop. */
public final class HelloPGO {

    interface Shape { int area(); }
    static final class Square implements Shape { public int area() { return 4; } }
    static final class Circle implements Shape { public int area() { return 5; } }

    private static int hot;
    private static int cold;

    static int step(int i, Shape s) {
        if (i % 10 != 0) {   // taken ~90% of the time
            hot++;
        } else {             // taken ~10% of the time
            cold++;
        }
        return s.area();
    }

    public static void main(String[] args) {
        Shape square = new Square();
        Shape circle = new Circle();
        long sum = 0;
        for (int i = 0; i < 10_000_000; i++) {
            sum += step(i, (i & 1) == 0 ? square : circle);
        }
        System.out.println("sum=" + sum + " hot=" + hot + " cold=" + cold);
    }
}
JAVA
```

- [ ] **Step 2: Write the build script**

```bash
cat > "$REPO"/crucible/samples/build.sh <<'SH'
#!/usr/bin/env bash
# Usage: crucible/samples/build.sh [native-image flags...]
# Builds crucible/samples/out/hellopgo. Requires `source crucible/env.sh` first.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$here/out"
mkdir -p "$out"
javac -d "$out" "$here/HelloPGO.java"
cd "$root/substratevm"
mx native-image -cp "$out" -o "$out/hellopgo" "$@" HelloPGO
SH
chmod +x "$REPO"/crucible/samples/build.sh
```

- [ ] **Step 3: Run the JVM version first to fix the expected output**

```bash
cd "$REPO" && source crucible/env.sh
mkdir -p crucible/samples/out && javac -d crucible/samples/out crucible/samples/HelloPGO.java && java -cp crucible/samples/out HelloPGO
```
Expected: `sum=45000000 hot=9000000 cold=1000000`.

- [ ] **Step 4: Build the native image and run it**

```bash
cd "$REPO" && source crucible/env.sh
crucible/samples/build.sh
./crucible/samples/out/hellopgo
```
Expected: image build finishes (1–3 min), then the exact line `sum=45000000 hot=9000000 cold=1000000`.

- [ ] **Step 5: Confirm the upstream PGO option surface is present (sanity for M2)**

```bash
cd "$REPO"/substratevm && source ../crucible/env.sh
mx native-image --expert-options-all 2>/dev/null | grep -E 'AOTPriorityInline|PGOPrintProfileQuality'
```
Expected: both option names listed.

- [ ] **Step 6: Ignore build output and commit**

```bash
cd "$REPO"
printf 'out/\n' > crucible/samples/.gitignore
git add crucible/samples/HelloPGO.java crucible/samples/build.sh crucible/samples/.gitignore
git commit -m "Add HelloPGO sample and native-image build script"
git status --porcelain
```
Expected: clean tree after the commit.

---

### Task 5: Record the verified environment in the design docs

**Files:**
- Create: `docs/design/environment.md`

- [ ] **Step 1: Write the environment record with the actual values observed in Tasks 2–4**

```bash
cat > "$REPO"/docs/design/environment.md <<'MD'
# Verified Build Environment (M0)

| Item | Value |
|---|---|
| Upstream baseline | `oracle/graal` tag `vm-25.3.4.1` |
| Branch | `crucible/main` |
| mx | 7.85.1 at `~/tools/mx` |
| JDK | labsjdk-ce-latest `ce-25.0.4.1+1-jvmci-25.3-b22` at `~/.mx/jdks/labsjdk-ce-latest` |
| Host | Linux x86_64, gcc, make, zlib1g-dev |
| Entry point | `source crucible/env.sh && cd substratevm && mx build` |
| Smoke test | `crucible/samples/build.sh && crucible/samples/out/hellopgo` → `sum=45000000 hot=9000000 cold=1000000` |

Rebase procedure: `git fetch upstream && git rebase upstream/master` on `crucible/main`; all
CrucibleVM files are additive so conflicts should be limited to `mx.substratevm/suite.py` once
the test project is added in M2.
MD
```

- [ ] **Step 2: Commit**

```bash
cd "$REPO"
git add docs/design/environment.md
git commit -m "Document verified build environment"
```

---

## Self-review

- Spec §3 (fork, branch, `upstream` remote): Task 1. Spec §9 M0 (mx, labs JDK, `mx build`, hello-world image): Tasks 2–4. Spec §8 sample program shape (skewed branch, hot virtual call, counted loop): Task 4. Environment record: Task 5.
- No placeholders; every step has runnable commands and expected output.
- Names reused across tasks: `crucible/env.sh` (Tasks 2→3→4→5), `crucible/samples/build.sh` (Task 4→5), expected output string identical in Tasks 4 and 5.
