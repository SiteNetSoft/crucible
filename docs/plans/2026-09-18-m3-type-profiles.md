# M3: Receiver-type sampling (schema v2)

**Goal:** record which receiver types actually occur at each virtual and interface call site, so
upstream's `JavaTypeProfile` / `JavaMethodProfile` paths light up and the priority inliner can
devirtualise a monomorphic or biased call.

**Done when:** a profiled build of `HelloPGO` applies a type profile to the polymorphic
`Shape.area()` call site, and `crucible/samples/e2e.sh` asserts it.

## What upstream needs

`PGOApplyProfilesPhase.updateInvokeWithNewProfiles` asks the lookup for:

- `getVirtualInvokeProfile(BytecodePosition)` -> `Map<AnalysisType, Long>`, receiver type counts.
- `getVirtualInvokeMethodProfile(BytecodePosition)` -> `Map<AnalysisMethod, Long>`, target counts.

It gates on `profileCategoryRecorded("virtualInvokeProfiles")` or
`"virtualInvokeMethodProfiles"`. Type occurrences are the richer input: upstream derives a method
profile from them via `PGOUtils.updateJavaTypeProfile` + `updateJavaMethodProfile`, so recording
types alone is enough. Method profiles are left for later.

## Design

**Runtime tables.** A counter slot cannot hold a type, so each call site gets a fixed-width row of
`(typeId, count)` pairs, four wide, in two flat arrays. `recordType(site, receiver)` reads the
receiver's type id with `DynamicHubIntrinsics.readHub(o).getTypeID()`, scans its row for a matching
id, claims an empty entry otherwise, and counts an overflow when the row is full. Racy by design,
like the existing counters: a lost update costs precision, not correctness. Empty entries are `-1`
because `0` is a valid type id.

**Type names.** Type ids mean nothing outside the image that produced them, so the image also
carries a table mapping the ids it can actually observe to type names. Built in `afterCompilation`
from the instantiated types of the hosted universe, sorted by id for a binary search at write time.

**Instrumentation.** For every `MethodCallTargetNode` whose `invokeKind` is virtual or interface
and whose position is known, insert a `recordType` foreign call before the invoke, passing the
receiver (argument 0). Same exclusions as the counter pass.

**Schema v2.** `schemaVersion` becomes 2, `categories` gains `virtualInvokeProfiles`, and a method
may carry `virtualInvokes`, each `{ ctx, bci, types: [ { name, count } ] }`. The parser rejects v1
with a message naming both versions; producer and consumer ship together.

**Lookup.** `getVirtualInvokeProfile` matches the context exactly, then falls back to the
context-insensitive point, exactly as conditionals do, and resolves type names to `AnalysisType`
through a name index built once from the hosted universe.

## Tasks

1. `ProfileKey.VirtualInvoke` + encode/decode, with round-trip tests.
2. Runtime type tables, `recordType` foreign call, extended `install`.
3. Instrumentation of virtual and interface call sites.
4. Feature: install the tables and the type-name table.
5. Writer: schema v2 and the `virtualInvokes` member.
6. Parser and model for v2.
7. Lookup: `getVirtualInvokeProfile` and the type-name index.
8. `e2e.sh` asserts a type profile reaches `Shape.area()`.

## Risks

- Reading a hub from a foreign call must stay uninterruptible; `readHub` is an intrinsic, so the
  call has no safepoint.
- A receiver may be null at the call site; `recordType` must tolerate it rather than fault.
- Four entries per site is a guess. Overflow is counted so the profile can say how often the
  width was exceeded.
