# Receiver-type sampling found no call sites at all

- **Date:** 2026-09-18
- **Milestone:** M3, receiver-type sampling
- **Status:** resolved

## Symptom

The first working build of the type-sampling pass produced a profile with **zero** receiver-type
sites — not merely none for the sample's polymorphic `Shape.area()` call, but none anywhere in an
image of 8,145 compiled graphs. `verify-profile.py` reported:

    profile FAILED: no receiver types recorded for Shape.area();
                    missing ['LHelloPGO$Circle;', 'LHelloPGO$Square;'], saw []

## First hypothesis, which was wrong

Sampling iterated call sites with `graph.getNodes(MethodCallTargetNode.TYPE)`. That form matches
only nodes whose node class is *exactly* the given type, and hosted compilation builds
`SubstrateMethodCallTargetNode`, a subclass with its own `NodeClass` — upstream's own apply phase
casts call targets to it. Iterating by class instead of by node type looked like the fix.

It was not. The rebuilt image still reported zero sites. Switching to
`.filter(MethodCallTargetNode.class)` is correct and was kept, but it was not the cause.

## Actual cause

Counters added to each filter step settled it:

    Crucible: visited 8145 graphs, saw 0 call targets, 0 indirect, 0 of those positioned.

Zero call targets *seen*, before any filtering. Sampling was appended to the end of the high tier,
next to the counter pass, and by that point invokes have been lowered: no `MethodCallTargetNode`
survives to the end of the tier. The phase was not selecting the wrong nodes, it was running after
the nodes ceased to exist.

## Fix

Receiver-type sampling moved into its own phase, `CrucibleTypeSamplingPhase`, **prepended** to the
high tier. Counter instrumentation stays appended at the end, where it needs the post-inlining
shape of the graph.

Prepending has a second benefit that makes it the right place rather than merely a working one:
`CrucibleApplyProfilesPhase` also runs at the head of the high tier, so pass 1 records a calling
context at exactly the point pass 2 asks about it.

    Crucible: saw 25852 call targets, 2813 indirect, 2813 sampled.

## Result

    receiver types LHelloPGO$Circle;=5000000, LHelloPGO$Square;=5000000
    113 of 905 receiver-type lookups applied (12.5%)

The counts match the sample exactly: it alternates the two implementations over ten million
iterations, five million each.

## Lesson

A phase that finds nothing is reporting something, and "wrong node selector" and "no nodes left"
look identical from the outside. Counting what a pass *sees* before it filters costs a few lines
and distinguishes them immediately; two rebuilds were spent on a plausible wrong answer that a
single count would have ruled out. The instrumented build now prints what each stage found.
