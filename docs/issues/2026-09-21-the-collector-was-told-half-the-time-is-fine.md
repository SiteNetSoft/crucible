# The collector was told that half the time is fine

`2026-09-21`

mnemonics and par-mnemonics are the two Renaissance benchmarks we lose worst, by 60% and 31%,
and the whole of it is collection: the optimizer's share of the loss is nothing.

Over eight iterations of mnemonics:

| | collections | paused | heap when a collection starts |
| --- | --- | --- | --- |
| Oracle GraalVM 25.0.4 | 193 | 3.6 s | 150 MB |
| this tree, default policy | 1375 | 12.4 s | 54 MB |
| this tree, `-XX:InitialCollectionPolicy=Adaptive` | 340 | 4.8 s | 173 MB |

The serial collector's default policy in this tree, `Adaptive2`, is newer than the one Oracle's
25.0.4 release uses, and it was tuned for footprint. It grows the young generation until
collection takes no more than half of the time (`GC_TIME_RATIO = 1`; HotSpot's default is 99,
one percent) and until collections are at least 20 ms apart. Four threads allocating fill
48 MB in a little over 20 ms, both conditions hold, and the young generation never grows. The
slow iterations that looked like a slow mode of the whole run are the iterations with a major
collection in them, and there are twice as many of those too.

The ratio is a constant. Made a run-time option, `-XX:SerialGCTimeRatio`, with the default left
as it was (iteration ms / wall s of twelve iterations / peak resident MB):

| ratio | par-mnemonics | mnemonics | scrabble | scala-stm-bench7 |
| --- | --- | --- | --- | --- |
| Oracle | 3507 / 47.7 / 398 | 3573 / 48.4 / 529 | 525 / 8.2 / 159 | 1602 / 22.5 / 317 |
| 1, the default | 4555 / 60.1 / 186 | 5710 / 72.4 / 143 | 679 / 12.2 / 412 | 1889 / 28.3 / 709 |
| 2 | 4305 / 57.1 / 206 | 5398 / 69.8 / 161 | 586 / 11.3 / 992 | 2070 / 28.8 / 903 |
| 4 | 3829 / 51.1 / 263 | 4898 / 63.2 / 206 | 552 / 11.5 / 1554 | 2013 / 28.2 / 895 |
| 6 | 3733 / 50.0 / 323 | 4421 / 58.4 / 348 | 569 / 11.7 / 1526 | 2004 / 27.8 / 893 |
| 9 | 3528 / 48.4 / 548 | 4317 / 58.1 / 428 | 596 / 11.1 / 1669 | 2034 / 28.1 / 894 |
| 19 | 3468 / 48.9 / 1348 | 4204 / 57.7 / 1072 | 583 / 11.8 / 1669 | 2022 / 28.3 / 893 |

At 6 par-mnemonics runs as fast as Oracle's binary in less memory than it, and mnemonics goes
from 60% behind to 24%. Wall time moves with the iteration time, so this is not collection
pushed out of the timed region, which is what a fixed young generation turned out to be.
scrabble takes four times the memory for a second of wall time, and scala-stm-bench7 gets
slower at every setting. So it is not a default; it is the first thing to try on a program
that spends its time collecting, and 4 to 9 is the range.

What is left on mnemonics after that is allocation: the same program allocates more under
the community edition's compiler than under Oracle's.
