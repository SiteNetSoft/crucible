# Devirtualisation and code layout: both implemented, neither pays

- **Date:** 2026-09-19
- **Status:** measured; devirtualisation off by default, layout on but unproven

Two more of Oracle's PGO techniques were implemented so that CrucibleVM covers everything the
community edition can consume. Both work. Neither makes anything faster, and one makes things
slower. The measurements are here so the next person does not repeat them hopefully.

## Devirtualisation: correct, and a regression

`CrucibleDevirtualizationPhase` rewrites an indirect call whose receiver is strongly biased into a
type-guarded direct call, using the public `DevirtualizationUtil` and
`StandaloneAddressBasedDevirtualization`. It runs appended to the high tier, after lowering, which
is where `IndirectCallTargetNode` exists — upstream's own attempt looks for that node from inside
the priority inliner, near the head of the tier, where it cannot exist yet.

It demonstrably works. `BenchPGO.work` compiles to a guard on the dominant receiver's address:

    mov  0x78(%r14,%rax,8),%rax          ; vtable load
    lea  -0x528(%rip),%rcx               ; address of BenchPGO$Inc.apply
    sub  %r13,%rcx
    cmp  %rax,%rcx                       ; is the receiver the dominant type?
    jne  <fallback>

And it is **2.8% slower** than not doing it:

    control   median 2217 ms
    profiled  median 2279 ms      difference -62 ms (-2.8%), control spread 10 ms

The reason is structural. Devirtualising is worth something because it lets the callee be *inlined*
afterwards. Our phase necessarily runs after lowering, where nothing will inline anything, so the
transformation trades a single well-predicted indirect call for a comparison, a branch and a direct
call — and a modern branch predictor handles a 97%-biased indirect call perfectly well. Upstream
devirtualises from inside the inliner for exactly this reason.

`-H:CrucibleDevirtualize` therefore defaults to **false**. The phase is kept because it is the
groundwork for doing this properly, which means a pre-inliner transformation on
`MethodCallTargetNode` — building the hub guard directly, so the resulting direct call is still in
front of the inliner and can be inlined. That is a compiler change of real size and is not done.

## Code layout: correct, and worth nothing measurable here

The community edition lays the code section out alphabetically by method name
(`SortByMethodNameCodeSectionLayouter`), which scatters the methods a run actually executes.
`CrucibleCodeSectionLayouter` orders by recorded call count instead, hottest first, with never
observed methods after them: *"463 observed methods first, 4201 never observed after them"*.

Throughput, `BranchBench`, medians over 9 runs:

    control                  1718 ms
    profiled, layout on      1459 ms
    profiled, layout off     1458 ms

The two profiled numbers are identical: **the whole gain is branch probabilities, and layout adds
nothing.** That is not surprising for a workload whose hot loop is three methods that fit in cache
however they are ordered.

Startup, median of 25 runs of a near-empty workload, where layout should matter instead:

    control          4858 us
    layout on        4798 us
    layout off       4906 us

Layout is about 2% ahead of no-layout, in the direction theory predicts, on a 4.8 ms measurement
whose run-to-run spread was not captured. That is **suggestive and not a result**. Anyone quoting
it should first measure the spread and use a workload with a real class-loading and initialisation
phase rather than this one.

`-H:CrucibleCodeLayout` defaults to true: it costs nothing measurable, it is theoretically right,
and it is the technique Oracle uses. It is not claimed as a gain.

## What this leaves

Everything CrucibleVM is measurably worth still comes from one technique: conditional branch
profiles, which are worth ~15% on layout-sensitive code and nothing on dispatch-sensitive code.
Adding two more techniques did not move that, and the honest reading is that the remaining
distance to Oracle's figures is mostly inlining driven by devirtualisation, which needs the
pre-inliner transformation described above.
