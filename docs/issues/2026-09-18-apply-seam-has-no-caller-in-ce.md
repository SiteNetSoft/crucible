# Registering `PGOProfilesLookup` is not sufficient to apply profiles in CE

- **Date:** 2026-09-18
- **Milestone:** M2, pass 2
- **Status:** resolved

## What the design assumed

`docs/design/2026-08-29-cruciblevm-pgo-design.md` §7 states that registering a `PGOProfilesLookup`
singleton is the whole of pass 2, and that "CrucibleVM writes no optimisation phase of its own".
The first half holds. The second does not, in the community edition.

## What actually happens

With only the singleton registered, a profiled build of the `HelloPGO` sample performed **298**
conditional lookups and applied 39, and `-H:CrucibleProfileTrace=HelloPGO` showed that **not one
lookup concerned application code**. Every lookup came from VM internals (`ObjectRefFixupVisitor`,
`UnalignedChunkRememberedSet`, `HeapImpl`) being expanded during inlining.

The reason is that the only site in CE that applies profiles is
`SubstratePriorityInliningPhase.applyPGOProfiles`, which reaches a method while expanding it into a
hot caller. A method compiled as its own root -- which is most of an application -- is never asked
about, so its branch probabilities stay at their static estimates.

`PGOApplyProfilesPhase.createContextInsensitive(HostedUniverse, PGOProfilesLookup)` exists for
exactly this job, and has **no caller anywhere in the community edition**; the enterprise build
presumably wires it into the compile queue.

## Fix

`CrucibleApplyProfilesPhase` wraps that factory and `CrucibleProfileFeature.registerGraalPhases`
prepends it to the high tier, before inlining, so a root method sees its own recorded
probabilities. `PGOApplyProfilesPhase` extends `SingleRunSubphase` and cannot be reused across
graphs, so the wrapper creates a fresh instance per graph.

The `HostedUniverse` it needs is reached from the phase-registration `Providers` via
`UniverseMetaAccess.getUniverse()`; `FeatureImpl.CompilationAccessImpl` holds one but does not
expose it.

## Effect

| | lookups | applied | HelloPGO conditionals applied |
|---|---|---|---|
| singleton only | 298 | 39 (13.1%) | 0 of 4 |
| with the phase registered | 11,862 | 2,221 (18.7%) | 4 of 4 |

All four recorded `HelloPGO` conditionals now hit, including the skewed branch
`LHelloPGO;.step(ILHelloPGO$Shape;)I` at bci 4 (9,000,000 / 1,000,000). The profiled image's code
area differs from a control image built with identical flags and no profile (35.62% vs 35.69% of
image size), confirming the profile reaches code generation.

## Note on the hit rate

18-21% is expected rather than alarming. Pass 1 records inlining contexts as they exist in the
*instrumented* image, whose inlining decisions differ from the plain image's, so exact contexts
often do not recur; the context-insensitive fallback in `CrucibleProfilesLookup` is what carries
those cases. Raising the exact-match rate is a tuning problem for later, not a correctness one.

## Reporting

CE tracks profile hit rates behind `-H:+PGOPrintProfileQuality` but only *reports* them in the
enterprise build, so a CE build gives no sign whether a profile was applied or silently ignored.
`CrucibleProfileFeature` therefore prints its own one-line summary after compilation, with
`-H:+CrucibleProfileDiagnostics` for sample unmatched contexts and `-H:CrucibleProfileTrace=<text>`
to follow individual lookups.
