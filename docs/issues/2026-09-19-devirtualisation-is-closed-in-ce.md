# Profile-driven devirtualisation is not reachable in the community edition

- **Date:** 2026-09-19
- **Status:** established; a project-level decision, not a defect

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
