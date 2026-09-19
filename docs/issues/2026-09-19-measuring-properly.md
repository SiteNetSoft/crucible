# What survived measuring properly

- **Date:** 2026-09-19
- **Status:** current

Several results in this project were produced by comparing two separate runs, or by trusting a
script that had not rebuilt. Re-measuring them as controlled A/B tests -- one build, one option
differing, alternating runs, medians, the control's own spread printed, outputs compared, and a
counter proving the feature ran -- changed some of the conclusions.

## The type guard's sign follows the optimization level

    -O2:  control 2219 ms | guard-off 2209 ms (+0.5%) | guard-on 2134 ms (+3.8%)   spread 12 ms
    -O3:  control 1019 ms | guard-off 1024 ms (-0.5%) | guard-on 1044 ms (-2.5%)   spread  2 ms

Both are real: the distributions do not overlap and the control spreads are 12 ms and 2 ms. At
`-O3` the compiler resolves the dispatch well enough on its own that the guard is only a comparison
and a branch in the way. `-H:CrucibleTypeGuard` now follows the level unless set explicitly.

Worth noting in both rows: **guard-off is indistinguishable from the control.** On this workload
the profile's entire contribution is the guard; the branch probabilities do nothing, because there
is only one interesting branch and the hardware predicts it perfectly.

## Profile-driven hot inlining never fires

`CruciblePolicyFactory` allows a callee through the inlining budget when the profile shows it
taking a meaningful share of the run. The counter reads **0 in every build measured**, at a
threshold of 0.5% and with methods far above it. The community edition's static budget already
inlines those callees, so there is no refused decision left to rescue.

The measurement that appeared to show it hurting GameOfLife (+13.5% falling to +9.6%) compared two
separate runs of the *same* code and was noise, as the counter shows.

## The noise floor is workload-specific, and larger than it looks

Two binaries that differ only in an embedded build id -- identical size, identical behaviour --
measured **8740 ms against 8483 ms on GameOfLife, a 3.0% spread**, while BenchPGO at `-O3` varies
by 2 ms across nine runs. A single tolerance applied to both workloads would be wrong in both
directions. GameOfLife comparisons below about 3% carry no information.

This does not touch the large results: +56.7% on BranchBench at `-O3` had no overlap between the
two distributions at all, and GameOfLife's +13.5% is four times its own floor.

## Method

`crucible/samples/bench.sh` and the ad-hoc scripts now all follow the same shape, and it is worth
keeping:

1. Build first, in the script, every time.
2. Print a counter from every feature. A missing counter line means the feature is not in the
   binary and the number is void -- this caught two invalid runs.
3. One build, one option differing. Comparing separate runs cannot separate an effect from drift.
4. Alternate the runs, report medians, and print the control's own spread next to the difference.
5. Compare program output at several input sizes. A speedup that changes results is not a speedup.
6. Run nothing else on the machine. Two containers sharing a `:Z` mount also corrupt each other's
   SELinux labels; the mounts are `:z` now.
