# M4: Calling-context samples, so the inliner can devirtualise

**Goal:** implement `PGOProfilesLookup.getSampleCounts` so upstream's `PrefixTree` is populated and
`SubstratePriorityInliningPhase` can rewrite a biased indirect call into a guarded direct call.

**Done when:** the hot call site in `BenchPGO` is no longer an indirect `call *%rax` in the
profiled image, and `crucible/samples/bench.sh` shows a difference larger than the control's own
run-to-run spread.

## Why this is the missing piece

Branch probabilities reach the compiler through `getConditionalProfile`, and they work. A call is
rewritten by a different path entirely:

    getSampleCounts()  ->  PrefixTree.populatePrefixTree
                       ->  Cursor.profileFor(universe, position)  -> JavaMethodProfile
                       ->  SubstratePriorityInliningPhase.devirtualizeIndirectCallTargetInvokes

`getSampleCounts` has a default returning empty, which CrucibleVM never overrode, so the tree is
empty and `profileFor` returns null at every site. See
`docs/issues/2026-09-19-profiles-apply-but-nothing-devirtualises.md`.

## What the tree expects

From `PrefixTree.populatePrefixTree`:

- The map is keyed by a `NodeSourcePosition` that is **iterated as a chain**, outermost frame
  first, and each frame's `getMethod()` is cast to `AnalysisMethod`. Supplying positions built
  from `HostedMethod` will fail.
- The value is a count; subtree counts are summed to give a callee its weight.
- `Cursor.profileFor(position)` finds candidate **child** nodes at a call site, so a callee must
  appear as a child of the context that called it. A flat set of method entry counts is useless
  here; the parent-child edge is the whole point.

## The gap in the current schema

Pass 1 records, per call site, which receiver types occurred and how often. It does not record
which method those receivers dispatch to, so the callee cannot be named when the tree is built.

## Design

1. **Schema v3.** A virtual-invoke record gains the invoked method's id, taken from
   `MethodCallTargetNode.targetMethod()` at sampling time. The receiver type is still what is
   counted; the target tells pass 2 what to resolve against.
2. **Resolution.** For each observed receiver type, resolve the concrete implementation with
   `AnalysisType.resolveConcreteMethod(target, callerType)`, giving the `AnalysisMethod` the call
   actually reached.
3. **Tree construction.** For each `(context, receiver type, count)`, build the chain from the
   recorded context, append a frame for the resolved callee, and emit that as one sample entry.
   Contexts are already stored innermost-first; the tree wants outermost-first, so reverse.
4. **Positions.** Build `NodeSourcePosition` from `AnalysisMethod` plus bci, resolving method ids
   through a name index like the one `indexTypes` already builds for types.

## Tasks

1. Record the target method id at each sampled call site; schema v3; parser and tests.
2. A name index from method id to `AnalysisMethod`, alongside the existing type index.
3. `getSampleCounts`: resolve, build chains, return the map. Report how many entries were built
   and how many could not be resolved, in the same summary line as the rest.
4. Re-run `bench.sh`; inspect the disassembly of `BenchPGO_work` for a guarded direct call.

## Risks

- Resolution can legitimately fail (a type no longer in the image, an abstract target). Those are
  counted and dropped, never guessed.
- A synthesised chain is not a real stack sample: it says "this call site reached this callee this
  often", which is what `profileFor` consumes, but it carries no information about anything deeper
  in the call tree. If the inliner wants transitive subtree weights, the counts will be
  conservative rather than wrong.
- Devirtualisation may still not fire for reasons beyond the profile, so task 4 checks the
  generated code and not only the clock.
