# Call sites in inlined methods were never sampled

`2026-09-19`

BenchPGO is the call-dominated sample: a loop calling `work(i, op)`, which calls
`op.apply(i)` on a receiver that is `Inc` 97% of the time. Oracle's PGO gains 49% on it.
Ours lost 2%.

The machine code showed a three-way type test in the order `Dbl`, `Inc`, `Neg`, falling
through to `Neg`, the rarest, so that the common case cost two compares, a taken branch
and a jump back. Upstream sorts a type profile by probability before the inliner builds
that chain from it, so the chain was not being built from a profile. The profile file
had no receiver types for `BenchPGO` at all.

Receiver types were sampled by a phase at the front of the high tier, on each method's own
graph. But `work` is small and called from a hot loop, so it is always inlined, and the
inliner inlines a fresh copy of the callee's graph, not the one that phase had been over.
The standalone `work` that did carry the sampling was never called. A call site in a
method that is always inlined was therefore never observed, and small methods called from
hot loops are where receiver types matter most.

Two changes:

- A second instance of the sampling phase runs directly after the inliner and takes only
  sites with an inlining context, the root method's own having been done already.
- An image that records a profile keeps polymorphic calls as calls. Left alone, the
  inliner turns a call with a few possible receivers into type tests with the bodies
  inlined, leaving no call at which to sample. The recording build now installs the same
  hosted configuration as a profiled build, whose inlining provider allows no polymorphic
  dispatches while recording.

Also fixed on the way: `-H:+CrucibleMarkHotCallers` crashed the compiler at `-O3`, because
the inherited `createPGOApplyProfilesPhase` looks a callee up in a tree of sampled calling
contexts that a counter-based profile does not have. The phase itself accepts a missing
tree. Marking hot callers still does not pay and stays off.

`-O3`, 7 interleaved rounds (11 for GameOfLife), each compiler against its own control,
identical output throughout, absolute times in brackets:

| workload | Oracle PGO | CrucibleVM PGO |
| --- | --- | --- |
| GameOfLife | +43.0% (5630 ms) | **+51.4% (4804 ms)** |
| ArrayBench | +64.7% (780 ms) | **+68.7% (692 ms)** |
| BranchBench | +41.8% (1002 ms) | **+50.2% (858 ms)** |
| BenchPGO | **+29.2% (541 ms)** | +21.5% (600 ms) |

The Oracle percentages in the mixed rows are against our control, since both ran in the
same interleaved set; against their own control they are +40%, +70%, +16% and +49%.
