# CrucibleVM: Open Profile-Guided Optimization for GraalVM CE Native Image

Status: approved design, 2026-08-29
Upstream baseline: `oracle/graal` tag `vm-25.3.4.1`

## 1. Goal

Provide a fully open-source PGO pipeline for GraalVM CE / Mandrel `native-image`:

1. **Instrument pass** (`-H:+CrucibleInstrument`): build an image whose code counts method
   invocations and control-split successor executions, and writes them to a JSON profile at exit.
2. **Optimize pass** (`-H:CrucibleProfile=<file>`): build an image whose compilation is guided by
   that profile (branch probabilities, hot-callee inlining, and — transitively — loop unrolling).

## 2. Key finding that shapes the design

Upstream CE at `vm-25.3.4.1` already contains the *application* half of PGO:

| Upstream component | Role |
|---|---|
| `com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup` | Interface the compiler queries for profiles. Registered as an `ImageSingleton`; every consumer uses `singletonOrNull()`, so absence means "no PGO". |
| `com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase` | Rewrites `ControlSplitNode` successor probabilities, virtual-invoke method/type profiles and `instanceof` profiles from the lookup. |
| `SubstratePriorityInliningPhase` / `SubstrateInliningProvider` | Consume the lookup to inline hot callees when `-H:+AOTPriorityInline`. |
| `InternalFeature.registerGraalPhases(providers, suites, hosted, fallback)` | Extension point to add phases without editing upstream suites. |
| `RuntimeSupport.addTearDownHook` (used by `CounterSupport.teardownHook()`) | Isolate tear-down hook for dumping data at exit. |
| `PGOApplyProfilesPhase.distributeConditionalProbabilities(long[])` | Defines the conditional record layout: triplets `[bci, successorKey, count]` (`CONDITIONAL_RECORD_SIZE = 3`). |

Only profile **acquisition** is proprietary. CrucibleVM therefore implements exactly that gap
("fill the seam") and reuses upstream's apply pipeline unchanged. This minimises the diff against
upstream and keeps rebases trivial.

## 3. Repository layout and drift policy

* The repository is a full fork of `oracle/graal`. Branch `crucible/main` is created from tag
  `vm-25.3.4.1`; `upstream` remote tracks `oracle/graal` for rebasing.
* All new code lives in three new packages. No upstream Java file is modified.
  * `substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/crucible/`
    – `CrucibleProfileRuntime`, `CrucibleProfileWriter`, `CrucibleRuntimeOptions`, `CrucibleCounterTable`
  * `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible/instrument/`
    – `CrucibleInstrumentFeature`, `CrucibleInstrumentationPhase`, `CounterSlotAllocator`,
      `CrucibleCounterIncrementNode` (+ its lowering)
  * `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/crucible/profiles/`
    – `CrucibleProfileFeature`, `CrucibleProfileParser`, `CrucibleProfilesLookup`, `ProfileKey`
* Build integration: features are discovered through the existing `@AutomaticallyRegisteredFeature`
  mechanism, so `mx.substratevm/suite.py` needs no change unless a new test project is added
  (`com.oracle.svm.crucible.test`, one additional project entry).
* Options (all `HostedOptionKey`, defined in `CrucibleRuntimeOptions` so they are visible to both
  core and hosted):
  * `-H:+CrucibleInstrument` — enable pass 1.
  * `-H:CrucibleProfileOutput=<path>` — output file; default `crucible-profile.json` in the working
    directory of the running image. Overridable at run time by `-XX:CrucibleProfileOutput=` (a
    `RuntimeOptionKey`) so one instrumented binary can produce several profiles.
  * `-H:CrucibleProfile=<path>` — enable pass 2 with this profile.
  * Setting both `CrucibleInstrument` and `CrucibleProfile` is a build error.

## 4. Pass 1: instrumentation

### 4.1 Phase placement
`CrucibleInstrumentFeature.registerGraalPhases` appends `CrucibleInstrumentationPhase` to the
**end of the high tier** when `hosted && !fallback`. Rationale: at that point inlining is complete,
so the graph shape and every node's `NodeSourcePosition` (which encodes the inlining chain) match
what `PGOApplyProfilesPhase` will see in pass 2 — which is the key the profile must be stored under.

### 4.2 What is counted
* **Method entry**: one counter after the `StartNode` of every compiled root method.
* **Control splits**: for each `ControlSplitNode` accepted by upstream's own
  `ProfilingUtilities.isNotForImplicitException` filter, one counter per successor. This covers
  `if`, `switch` (`IntegerSwitchNode`, `TypeSwitchNode`) and loop conditions (loop exits and
  back-edges are ordinary control splits at this level, which is exactly the granularity upstream's
  apply phase consumes). Successor key = `successorIndex` of the split.
* Counters are keyed by `ProfileKey(rootMethod, NodeSourcePosition, successorIndex)`.

### 4.3 Injection mechanism
* `CrucibleCounterIncrementNode` is a fixed-with-next node carrying a slot index. It is lowered
  (in `CrucibleInstrumentFeature.registerLowerings`) to `load counters[slot]; add 1; store` on a
  `long[]` that lives in the image heap. Increments are unsynchronised: lost updates under
  contention are an accepted PGO trade-off (same as HotSpot's profile counters).
* `CounterSlotAllocator` is a concurrent, append-only registry (compilation runs in parallel in
  `CompileQueue`). It is an `ImageSingleton`; after compilation completes
  (`Feature.afterCompilation`) it is frozen, the backing `long[]` is sized, and the
  slot→`ProfileKey` table is materialised as `CrucibleCounterTable` in the image heap.
* Deopt-target and fallback compilations are not instrumented.

## 5. Runtime and serializer (`com.oracle.svm.core.crucible`)

* `CrucibleProfileRuntime` holds the `long[] counters` and the `CrucibleCounterTable`.
* A `RuntimeSupport.Hook` registered via `addTearDownHook` in `CrucibleInstrumentFeature` runs on
  normal exit, `System.exit`, and signal-initiated shutdown. It does not run on `SIGKILL` or a VM
  crash; this is documented behaviour.
* `CrucibleProfileWriter` emits the JSON described in §6 with a hand-written streaming emitter
  (no third-party JSON dependency is permitted in `svm.core`). Zero-count entries are omitted to
  keep files small; `methods[].calls` is always present for executed methods.
* If the output file cannot be written, a single warning is logged and shutdown proceeds.

## 6. Profile schema (`crucible-profile.json`, `schemaVersion` 1)

```json
{
  "schemaVersion": 1,
  "producer": { "tool": "CrucibleVM", "graalBase": "vm-25.3.4.1", "imageBuildId": "<ImageBuildID>" },
  "categories": ["methodCounts", "conditionalProfiles"],
  "methods": [
    {
      "id": "Lcom/acme/Foo;.bar(ILjava/lang/String;)V",
      "calls": 12345,
      "conditionals": [
        {
          "ctx": ["Lcom/acme/Foo;.bar(ILjava/lang/String;)V:17"],
          "bci": 17,
          "successors": [ { "key": 0, "count": 10000 }, { "key": 1, "count": 2345 } ]
        }
      ]
    }
  ]
}
```

* `methods[].id` is the JVM method descriptor `L<class>;.<name><signature>`.
* `ctx` is the `NodeSourcePosition` chain, callee-first, each element `<methodId>:<bci>`. The last
  element is always in the root method. This is what lets pass 2 resolve profiles for nodes that
  were inlined along the same path, and is passed to `PGOProfilesLookup.getConditionalProfile
  (BytecodePosition)` semantics.
* `successors[].key` is the successor index; the parser flattens each conditional into upstream's
  `[bci, key, count]` triplets.
* `categories` maps 1:1 onto `PGOProfilesLookup.profileCategoryRecorded(String)`; upstream skips
  categories that are absent. Version 1 records `methodCounts` and `conditionalProfiles` only.
  `virtualInvokeProfiles`, `virtualInvokeMethodProfiles` and `instanceOfProfiles` are additive
  extensions reserved for schema version 2 (milestone M3).
* Unknown keys are ignored by the parser; a `schemaVersion` greater than supported is a build error.

## 7. Pass 2: application

* `CrucibleProfileFeature.afterRegistration`: when `-H:CrucibleProfile` is set, parse the file
  with `CrucibleProfileParser` (hand-written, tolerant, streaming), build `CrucibleProfilesLookup`
  and `ImageSingletons.add(PGOProfilesLookup.class, lookup)`.
* That single registration activates upstream's `PGOApplyProfilesPhase` (branch probabilities) and
  the PGO paths inside `SubstratePriorityInliningPhase` (hot-callee inlining). CrucibleVM writes no
  optimisation phase of its own.
* Loop unrolling: Graal's `LoopPartialUnrollPhase` and loop-frequency computations are driven by
  the corrected loop-exit probabilities, so unrolling decisions improve without extra code. The
  phase is only present at `-O3`/`-march=native` (upstream `applyRegularSuiteTuning`); the design
  does not change that.
* `CrucibleProfilesLookup` mapping:
  * `getCallCountProfile`/`getCallCountOrZero`/`isExecuted` ← `methods[].calls`.
  * `getConditionalProfile(BytecodePosition)` ← exact `ctx` match; if none, fall back to the
    context-insensitive entry (`ctx` of length 1) for the same `(method, bci)`.
  * `getTotalConditionalProfileValue` ← sum of successor counts for the method.
  * `ProfileSource` reported as `PROFILED`.
  * Virtual-invoke / instanceof / monitor queries return `Optional.empty()` in v1.
  * `profileCategoryRecorded` ← `categories`.
* Profiles whose `producer.graalBase` differs from the building toolchain produce a warning, not an
  error. `-H:+PGOPrintProfileQuality` (upstream) reports match/miss statistics for free.

## 8. Testing

* **Unit** (JUnit under `com.oracle.svm.crucible.test`, run with `mx unittest crucible`):
  parser round-trip with the writer; malformed/over-versioned input errors; `CounterSlotAllocator`
  under concurrent allocation; `ProfileKey` context encoding; flattening output validated with
  upstream's `PGOApplyProfilesPhase.distributeConditionalProbabilities`.
* **End-to-end** (`mx crucible-e2e`): a `HelloPGO` program with a 90/10 skewed branch, a hot
  virtual call, and a counted loop.
  1. Build with `-H:+CrucibleInstrument`, run, assert the JSON contains the expected methods and
     that the branch counts are skewed as expected.
  2. Build with `-H:CrucibleProfile=… -H:+PGOPrintProfileQuality`, assert successes > 0 and that
     the branch's `ControlSplitNode` probability in a dumped graph is ≥ 0.85 for the hot path.
* Both are wired into `mx gate --tags crucible`.

## 9. Milestones

| ID | Deliverable | Done when |
|---|---|---|
| M0 | Environment: fork, `upstream` remote, `mx` installed, labs-JDK 25 via `mx fetch-jdk`, `mx build` in `substratevm`, hello-world `native-image` works | Unmodified baseline builds and runs |
| M1 | Pass 1: feature, phase, node + lowering, allocator, runtime, writer | Instrumented HelloPGO writes a valid v1 profile |
| M2 | Pass 2: parser, lookup, feature; e2e gate | `mx crucible-e2e` green |
| M3 | Schema v2: receiver-type and instanceof sampling | Upstream virtual-invoke profiles light up |

Each milestone gets its own implementation plan.

## 10. Non-goals (v1)

Sampling-based profiling, profile merging tooling, Truffle/runtime-compilation support, Windows
signal semantics, and any change to upstream's optimisation heuristics.
