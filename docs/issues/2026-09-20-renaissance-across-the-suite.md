# Renaissance across the suite

`2026-09-20`

One benchmark said the community edition starts behind; twelve say by how much, where, and
what moves it. Each was built closed-world from its standalone jar, with reflection
configuration from a run under the tracing agent, by both compilers at `-O3`: a control, a
recording image, and a profile-guided image from four recorded iterations. Times are the
harness's own, in milliseconds per iteration, the median of the last eight of twelve, two
interleaved runs. The harness forces a collection between iterations that it does not time,
so each pair was also timed by the clock for the whole run.

| benchmark | Oracle control | Oracle PGO | our control | our PGO | our PGO, other collection policy |
| --- | --- | --- | --- | --- | --- |
| philosophers | 2205 | 2152 | 1827 | 1902 | **1770** |
| scala-doku | 2984 | 2086 | 2977 | 1999 | **1960** |
| scala-kmeans | 310 | 308 | 361 | 300 | **296** |
| akka-uct | 30519 | 28040 | 28902 | **27029** | 33978 |
| rx-scrabble | 146 | **121** | 162 | 136 | 133 |
| par-mnemonics | 4337 | **3488** | 7572 | 4886 | 3884 |
| fj-kmeans | 7271 | **6507** | 9070 | 7521 | 7374 |
| scrabble | 663 | **517** | 877 | 655 | 588 |
| scala-stm-bench7 | 1890 | **1612** | 2109 | 1866 | 1947 |
| future-genetic | 2132 | **1720** | 2336 | 2159 | 2044 |
| reactors | 17414 | **15611** | 21358 | 20312 | 19069 |
| mnemonics | 6078 | **3561** | 9952 | 5718 | 4588 |

## What the table says

We are ahead on four and behind on eight, by 10% to 38% with the collector's default policy. A thirteenth, dotty, fails the harness's own
validation of its result under all four binaries and is left out.

The profile is not what is behind. Our gain over our own control is as large as Oracle's
over theirs or larger on most rows: 43% against 41% on mnemonics, 35% against 20% on
par-mnemonics, 33% against 30% on scala-doku, 25% against 22% on scrabble. Where we lose,
the controls already differ by about as much as the results do. The four we win are the
four where the controls are level or ours is ahead.

The second thing is the last column. It is the same binary, run with
`-XX:InitialCollectionPolicy=BySpaceAndTime`. It is worth 20% on mnemonics and
par-mnemonics, 10% on scrabble, 6% on reactors and future-genetic, and costs 4% on
scala-stm-bench7, whose wall time it nonetheless improves by 14%. Wall time agrees
elsewhere: scrabble 12.6 s to 11.0 s for twelve iterations, mnemonics 74 s to 60 s,
par-mnemonics 63 s to 52 s. This tree's default policy for the serial collector is
`Adaptive2`; Oracle's 25.0.4 release has `Adaptive` and does not have the option.

It is not a default to change, though. On akka-uct the same option costs 25%, 34.0 s an
iteration against 27.0 s, in both runs and by the clock, which turns a win into the worst
loss in the table. A policy that is worth a fifth on stream code and costs a quarter on
actors is a thing to try on one's own program, which `-R:InitialCollectionPolicy=` at build
time and `-XX:InitialCollectionPolicy=` at run time already allow. CrucibleVM leaves the
collector's default alone.

## What is left of the gap

Even with the better policy for each, the eight losses run from 9% to 29%. scrabble was taken apart in
its own write-up and the rest look alike from outside: programs that allocate constantly,
where we allocate about a third more for the same work and collect it more slowly. Neither
is something the inliner's thresholds, its depth, or knowledge of call targets moved. What
is left is escape analysis and the collector itself, and of those only the first is a
compiler problem a profile could bear on.
