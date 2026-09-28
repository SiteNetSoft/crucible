# What the Old Generation Is Collected With

`2026-09-28`

The collection log of Oracle's binary begins with `GC policy: adaptive`, and its complete collections have no planning and fix-up phases.
It collects the old generation by copying, under the older of the two adaptive policies.
This tree's default, which is the community edition's, is the compacting old generation under `adaptive2`.
Both can be had in a CrucibleVM image: `-H:-CompactingOldGen` at build time, and `-XX:InitialCollectionPolicy=Adaptive` at run time.

## On the Twelve

Each row is one run of all its columns, three rounds and reactors six, milliseconds an iteration at `-O3`, on one machine with six processors.

| | Oracle GraalVM | default | copying | difference |
| --- | --- | --- | --- | --- |
| reactors | 10295 | 10183 | **9328** | 8% faster |
| scala-stm-bench7 | 890 | 997 | **954** | 4% faster |
| fj-kmeans | 3059 | 3269 | 3204 | 2% faster |
| rx-scrabble | 64.0 | 70.1 | 68.7 | 2% faster |
| scala-kmeans | 172.2 | 174.6 | 171.9 | 2% faster |
| future-genetic | 935 | 1065 | 1050 | 1% faster |
| par-mnemonics | 1817 | 1685 | 1690 | level |
| philosophers | 1698 | 1558 | 1568 | level |
| scala-doku | 1244 | 1065 | 1072 | level |
| mnemonics | 1833 | 1922 | 1960 | 2% slower |
| scrabble | 250 | 260 | 273 | 5% slower |
| akka-uct | 14237 | 11249 to 11390 | 12290 to 12452 | 8% slower |

The akka-uct row gives the rounds and not their mean, because one round of the default took 13902.

Peak memory is the same to within a tenth on all twelve, except mnemonics, which goes from 163 MB to 203 MB.
These programs keep little alive, so there is little for a complete collection to copy.

## The Policy

The policy `adaptive` by itself, on the default image:

| | default | with `adaptive` |
| --- | --- | --- |
| akka-uct | 11506 | 15284 |
| scrabble | 260 | 291 |
| fj-kmeans | 3264 | 3533 |
| philosophers | 1537 | 1615 |
| reactors | 10323 | 10728 |
| rx-scrabble | 70.3 | 67.7 |
| par-mnemonics | 1715 | 1661 |

The other five are level.
It is not a default.

With the copying old generation and `adaptive` together, which is Oracle's configuration, fj-kmeans runs 2992 where Oracle's binary runs 3089.
It does that at a peak of 611 MB where the default has 165 MB and Oracle's binary 430 MB.
What fj-kmeans loses by default is how often it collects, and not how it is compiled.

## A Program That Keeps a Lot Alive

The twelve do not show what copying costs, so a program was written that does: it keeps a given amount of small objects alive in a table and replaces a tenth of them in each of twenty rounds.
All three binaries print the same checksum.

| kept alive | | compacting, the default | copying | Oracle GraalVM |
| --- | --- | --- | --- | --- |
| 500 MB | wall | 10.9 s | 9.1 s | 9.2 s |
| | peak memory | 1079 MB | 1336 MB | 1261 MB |
| | complete collections | 4.9 s | 2.8 s | 4.5 s |
| 2000 MB | wall | 57.0 s | 45.1 s | 57.0 s |
| | peak memory | 4163 MB | 5501 MB | 4514 MB |
| | complete collections | 29.6 s | 16.9 s | 31.9 s |
| 4000 MB | wall | 126.0 s | 99.3 s | 124.3 s |
| | peak memory | 7326 MB | 10630 MB | 9115 MB |
| | complete collections | 62.6 s | 36.2 s | 67.9 s |

A complete collection by copying takes a little over half the time of one by compacting, and the program runs 17 to 21% faster for it.
It takes 24 to 45% more memory at its peak, and the more the more is kept alive.
That is the trade the compacting old generation was made the default for, and it stays the default.

## What Did Not Lead Anywhere

Every chunk the collector frees is uncommitted at once, and releasing spaces was 17% of the time of a young collection on fj-kmeans, 546 ms over six iterations where Oracle's binary has 19 ms.
System time and page faults are the same in both binaries all the same (5.6 s and 3.8 million against 6.7 s and 4.1 million on fj-kmeans), and Oracle's young collections have 431 ms outside the phases they list where this tree's have 62 ms.
Oracle's collector pays for it in another place.
