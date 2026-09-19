# What instrumentation costs, and getting most of it back

- **Date:** 2026-09-19
- **Status:** measured; context depth bounded by default

## The cost

Nobody had measured what an instrumented image costs, which decides whether profiling a real
workload is practical at all. It is worse than expected in one dimension:

    run time      BranchBench 7.2x slower, GameOfLife 5.5x slower
    image size    8,260,544 -> 103,746,496 bytes, 12.6x larger

A five- to sevenfold slowdown is the price of a counter on every branch and is in line with what
Oracle warns about. A hundred-megabyte image is a different matter: it makes profiling an
application awkward in a way that discourages exactly the long, varied runs that produce good
coverage -- and coverage is already the binding constraint on what a profile is worth.

## Where the size went

    197,330 counters
    counter keys      66,817 KiB     <- 65 MiB of the 103 MiB image
    type-site keys     1,165 KiB
    type names           100 KiB

Every counter carried its full inlining context as a string, and those contexts are long and
repetitive: `C|LGameOfLife;.applyRules(...)|LA;.x()V:3#LB;.y()V:17#...|25|0|28`.

## The profile's own logs said that context was not earning its keep

The pass 2 summary reports how profiles are matched, and it had been saying this all along:

    applied 16369 of 45369 conditional profile lookups (36.1%), 14789 via the context-insensitive fallback

Nine in ten matches came from the **fallback**, which needs only the innermost frame. The full
context was being carried to win the other tenth.

## Bounding it

`-H:CrucibleMaxContextDepth` limits how many frames a site records, defaulting to 1.

    depth 1     keys 29,229 KiB   image 63,441,856   profile   713,194   applied 16,288 (36.0%)
    full        keys 66,832 KiB   image 103,746,496  profile 1,357,326   applied 16,369 (36.1%)

Essentially the same number of profiles applied. Fifteen alternating runs of GameOfLife at `-O3`
settle the performance question:

    control 9824 ms (spread 396) | depth-1 8722 ms (+11.2%, spread 330) | full 8676 ms (+11.7%, spread 302)
    depth-1 against full: -0.5%

Half a percent against spreads of three to four hundred milliseconds is nothing. An earlier
seven-run comparison had suggested a 2.8 point gap; it was noise, which the measured floor for this
workload (3.0%) had already predicted.

So the default costs nothing measurable and gives back **39% of the image size** and **47% of the
profile size**. Both remain available: `-H:CrucibleMaxContextDepth=0` records everything.

## Still open

Even at depth 1 the keys are 29 MiB, because each still spells out method names in full and the
same names repeat across thousands of keys. Interning them into a table and storing keys as
integer records should remove most of what remains; it is a larger change to the allocator, the
runtime and the writer, and is not done.
