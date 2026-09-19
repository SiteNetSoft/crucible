# Profile-driven devirtualisation is not reachable in the community edition

- **Date:** 2026-09-19
- **Status:** four gates filled, a fifth found; devirtualisation still does not fire

## Claim

A `PGOProfilesLookup` registered from a `Feature` can change branch probabilities. It cannot cause
an indirect call to be rewritten, and no amount of additional profile data changes that, because
the paths that would consume the data are disabled in the community edition.

## Evidence

1. `grep -rn "new PrefixTree(" --include=*.java` over the whole repository returns **nothing**. The
   calling-context tree that `getSampleCounts` exists to populate is never constructed, so that
   method is never called.
2. The only public constructor of `SubstratePriorityInliningPhase` hard-wires the context provider:

       new SubstrateInliningProvider(universe, _ -> null)

   `samplingMethodProfiles` begins `if (compilationRootContext == null) return null;`, so it exits
   before consulting any profile. The constructor taking a provider is `private`.
3. `SubstrateInliningProvider.shouldApplyProfilesWhileExpanding` returns `false`.
4. `PrefixTree` exposes no public way to obtain a `Cursor` for a compilation root, so a tree built
   independently could not be supplied either.
5. In the inliner, `pgoProfiles` is referenced only by `applyPGOProfiles`, which applies branch and
   type profiles to graphs being expanded. Nothing in `phases/` or `code/` reads
   `getCallCountOrZero`, `isExecuted` or `getTotalConditionalProfileValueOrZero`, so profiled call
   counts do not steer inlining either.

## Consequence for the design

`docs/design/2026-08-29-cruciblevm-pgo-design.md` §2 holds that the application half of PGO is
already present in CE and only acquisition is missing. That is true for branch probabilities and
false for call rewriting. The seam is partly welded shut.

This also explains the benchmark: a profiled image is 0.8% faster than its control because branch
probabilities changed block layout and nothing else moved.

## What would change it

One to three lines of upstream: make the eight-argument `SubstratePriorityInliningPhase`
constructor public, or have `HostedGraalConfiguration` pass a context provider instead of
`_ -> null`. Either breaks the project's rule that upstream files are never modified, which exists
to keep `git rebase upstream/master` trivial. That is a trade to decide deliberately, not to make
by accident.


## Update, after filling four of the gates

With the one-word upstream patch (public inliner constructor) and CrucibleVM supplying its own
configuration, provider and calling-context tree, four of the five obstacles are gone. Each was
verified rather than assumed:

| Gate | State |
|---|---|
| No `PrefixTree` is ever built | Replaced: `CrucibleCallTree` serves `PrefixTree.Cursor` directly. Verified: *"call tree has 64 roots and 120 resolved call edges (3438163 observations)"*. |
| Context provider hard-wired to `_ -> null` | Replaced: `CrucibleGraalConfiguration` installs `CrucibleInliningProvider`. Verified: the tree is only built when a cursor is requested, and it is. |
| `shouldApplyProfilesWhileExpanding` false | Overridden to true in `CrucibleInliningProvider`. |
| Every graph's `hotCaller()` is false | Filled: nothing in the community edition calls the public `StructuredGraph.setGlobalProfileProvider`, so `CrucibleApplyProfilesPhase` now installs one from recorded call counts. Verified inside the inliner: `devirtualizeHotCallees root=BenchPGO.work hotCaller=true option=true`. |

### The fifth gate

`devirtualizeIndirectCallTargetInvokes` iterates

```java
readonlySubgraph.getNodes().filter(IndirectCallTargetNode.class::isInstance)
```

`IndirectCallTargetNode` is a **lowered** call target. The priority inliner runs near the head of
the high tier, where call targets are still `MethodCallTargetNode`; lowering happens in
`HighTierLoweringPhase`, the last phase of the tier. The filter therefore matches nothing and the
loop body never executes — confirmed by instrumenting the method: the enclosing
`devirtualizeHotCallees` is reached with `hotCaller=true`, and no iteration of the inner loop ever
runs for the sample.

The other branch, `devirtualizeInlineCacheNodes`, works on `InlineCacheNode` children of the
inliner's own call tree. Those are produced while the tree is built, from the four-argument
`samplingMethodProfiles(nodeContextMap, root, caller, callee)`, whose cursors come from a
`nodeContextMap` maintained inside the inlining algorithm rather than from the provider we supply.
Reaching it needs upstream plumbing well beyond one constructor.

### Recommendation

Stop here rather than widen the patch. The agreed constraint was the smallest possible upstream
diff, and the remaining gate is not a one-liner: it is inside the inlining algorithm's tree
construction. Reopening it is a decision to take deliberately, with a much larger patch and a much
larger rebase cost, and it should be weighed against simply using Oracle GraalVM where PGO is
supported.

What the work did establish is worth keeping: the profile pipeline is complete and correct, branch
probabilities reach the compiler, and four separate CE shut-offs can be reopened from a feature
with a single upstream word. The fifth is where the community edition genuinely stops.
