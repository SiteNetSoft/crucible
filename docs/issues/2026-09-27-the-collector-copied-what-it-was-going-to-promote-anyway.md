# The collector copied what it was going to promote anyway

`2026-09-27`

With arraycopy fixed, scala-stm-bench7 still ran 1077 ms an iteration to Oracle's 893, and the
flight recorder put the difference in collection: 8.2 s of pauses over twelve iterations against
5.4 s, in half as many collections. A young collection of ours took 195 ms; one of Oracle's took
50. Verbose collection logs from the two images, same benchmark, same moment of the run:

```
Oracle   Eden: 76.00M->0.00M   Survivor:  0.00M->0.00M     Old: 175.95M->187.95M    8.7 ms
ours     Eden: 229.60M->0.00M  Survivor: 93.04M->136.50M   Old:   0.50M->0.50M    311.0 ms
```

Oracle's collector never puts anything in a survivor space on this program: what survives a
young collection goes straight to the old generation, and complete collections clean up there
(15 of them in the run, 1.3 s). Ours keeps 130 to 165 MB of survivors and copies all of it again
at every young collection, until an object has survived seven times and is promoted. The
objects in question -- transaction records, tree nodes -- live for a few hundred milliseconds:
long enough to be copied several times, not long enough to be worth keeping out of the old
generation. The community edition's policy starts the tenuring threshold at seven and only ever
raises it.

Built with no survivor spaces (`-H:MaxSurvivorSpaces=0`), against the same profile and Oracle's
binary in the same run:

| | Oracle GraalVM | this tree | no survivor spaces |
| --- | --- | --- | --- |
| scala-stm-bench7 | 893 | 1077 | **950** |
| mnemonics | 1815 | 2134 | **1933** |
| par-mnemonics | 3686 | 3856 | **3333** |
| reactors | 17500 | 18270 | **16440** |
| future-genetic | 924 | 1092 | 1031 |
| scrabble | 250 | 274 (some runs 228) | 252 |
| philosophers, scala-doku, scala-kmeans, akka-uct, rx-scrabble | | | within 2% |
| fj-kmeans | 3070 | 3267 | **4415** |

Five benchmarks gain 5 to 14%, par-mnemonics and reactors go from behind Oracle to ahead, and
fj-kmeans loses a third. Its survivors are the opposite case: they die a collection or two after
they are made, so with no survivor stage they are promoted and die in the old generation, where
every young collection then pays to scan them (12.9 ms against 5.3) and 56 complete collections
pay to sweep them. Oracle's collector on fj-kmeans does use a survivor space, a few megabytes.
It adapts; ours does not.

Two attempts at adapting it did not earn their place. Lowering the threshold when the survivor
spaces' contents keep surviving (retention above 60%) got scala-stm-bench7 from seven to two
stages and no faster, because one survivor stage costs as much as seven here: the expense is the
first copy of each object, not the repeats. Lowering it a step at a time and taking the step back
if young collections got dearer per byte of eden needs eight collections a step, and a run of
this benchmark has thirty-five. The knob is a run-time option instead,
`-XX:SerialGCTenuringThreshold=0`, which promotes straight from eden and gets most of the gain
without a rebuild (scala-stm-bench7 990, mnemonics 1940; the built image is a little faster
because its eden is not sized around survivor spaces that are never used). `0` suits programs
whose objects either die at once or live long; a program whose objects live for a collection or
two, like fj-kmeans, should leave it alone. `-XX:+VerboseGC` now prints the tenuring age at each
collection so a run can be checked.

## A threshold of one

With the option in place the threshold could be swept without rebuilding. Each row is one run of
all its columns, three rounds unless it says otherwise, milliseconds an iteration at `-O3`:

| | Oracle GraalVM | policy | 7 | 3 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- |
| mnemonics | 1830 | 2117 | 2023 | 2064 | **1923** | 1941 |
| par-mnemonics | 3695 | 3850 | 3808 | | **3571** | 3385 |
| philosophers | 1696 | 1512 | | | 1475 | |
| future-genetic | 943 | 1080 | 1094 | 1069 | 1059 | 1040 |
| akka-uct | 27408 | 25718 | 25642 | | 25366 | 25903 |
| reactors, six rounds | 10072 | 11921 | | | 11711 | **9698** |
| scala-stm-bench7 | 901 | 1077 | 1080 | 1085 | 1077 | **989** |
| fj-kmeans | 3047 | 3261 | 3272 | 3262 | 3284 | **4579** |
| scala-doku | 1248 | 1067 | | | 1063 | |
| scala-kmeans | 171.5 | 173.1 | | | 173.3 | |
| rx-scrabble | 63.9 | 70.1 | | | 69.6 | |
| scrabble, twelve rounds | 249.8 | 256.9 | | | 263.1 | |

The policy is not the same as a fixed seven. On mnemonics, par-mnemonics, and scrabble it raises
the threshold to fifteen within a few dozen collections, because more of the time there goes to
complete collections than to young ones, and that is the one thing it reacts to.

A threshold of one costs nothing anywhere and gains 9% on mnemonics, 7% on par-mnemonics, and 2%
on philosophers and future-genetic. It keeps the survivor stage that fj-kmeans needs: objects
that die a collection after they are made still die young. It does nothing for scala-stm-bench7
and reactors, which gain only when there is no survivor stage at all, so zero stays an option for
the programs it suits and one is the default. `-XX:SerialGCTenuringThreshold=-1` gives the policy
back. A rebuilt mnemonics image ran 1971 by default and 2135 with `-1`, against 1934 and 2151
for the previous image with `=1` and without.

Reactors needed the six rounds. Its rounds under the policy span 10928 to 12855, and the first
three-round run put a threshold of one 8% behind the policy.

### Scrabble has two speeds, and neither is the collector's

Under the policy, five scrabble rounds in twelve ran at about 229 and seven at about 277. With a
threshold of one all twelve ran between 257 and 270. The collection logs of eight runs under the
policy and three with a threshold of one, last ten iterations of each:

| | young collections an iteration | eden | each young collection | all pauses in the run |
| --- | --- | --- | --- | --- |
| policy, slow runs | 3 | 471 MB | 37 ms | 7.3 to 7.4 s |
| policy, fast runs | 2 | 478 to 506 MB | 33 to 35 ms | 7.5 to 7.7 s |
| threshold of one | 3.2 to 3.9 | 332 to 377 MB | 15 to 20 ms | 7.2 to 7.5 s |

An iteration of scrabble allocates about 1420 MB, which is three edens of the size the policy
settles on, give or take 1.5%. When eden comes out a few megabytes larger, the third eden is not
full when the iteration ends. Renaissance asks for a complete collection between iterations, and
that collection, which is not timed, takes it: 160 ms instead of 110. The run as a whole spends
the same time collecting either way. The fast rounds are the harness's accounting, and a
threshold of one loses nothing by not having them. For anything that touches the collector,
compare the pauses of the whole run and not only the iteration times.

## A Threshold That Chooses

`2026-09-28`

A threshold of zero is worth 9% on scala-stm-bench7 and 19% on reactors, and costs fj-kmeans 40%.
What tells them apart is visible at a threshold of one.
There the survivor space holds what survived the previous young collection and nothing else, and everything a young collection promotes comes out of it.
So the growth of the old generation divided by the survivor space before the collection is the share of survivors that a second collection does not free.
One run of each benchmark at a threshold of one, median of the collections that had at least 1 MB of survivors:

| | share promoted at the next collection | survivors as a share of eden | what a threshold of zero does |
| --- | --- | --- | --- |
| reactors | 0.94 | 14% | 19% faster |
| scala-stm-bench7 | 0.92 | 32% | 9% faster |
| rx-scrabble | 0.89 | 1.9% | level |
| mnemonics | 0.86 | 2.0% | level to 4% faster |
| scrabble | 0.83 | 2.3% | level to 3% slower |
| future-genetic | 0.00 | 2.0% | level |
| fj-kmeans | 0.00 | 2.3% | 40% slower |

The first column says whether the copy to the survivor space is wasted, and the second says whether there is enough of it to matter.
fj-kmeans promoted 19 MB of 2208 MB of survivors in 1194 collections.

`-XX:SerialGCTenuringThreshold=-2` uses both.
It starts at one.
Two young collections in a row with a share of at least 0.75 and survivors of at least 8% of eden take the threshold to zero.
At zero there are no survivors to look at, so after 16 young collections that promoted something the threshold goes back to one until a collection gives a sample, which takes two collections.
A probe that finds a share of at least 0.5 doubles the distance to the next probe, up to 128 collections.
Two probes in a row that find less take the threshold back to one.

Three things had to be got right, each found in the collection log of a run:

- At zero the survivor space shrinks, because nothing is put in it. The first probes overflowed it, and a collection that overflows promotes from eden as well, so its share says nothing. A probe now asks for a survivor space of one and a half times what recent collections promoted.
- Reactors has phases: some fifteen young collections of half a megabyte of survivors and half a millisecond, then eight to ten with 70 MB of survivors that take 200 to 500 ms. Collections that promote less than 1 MB do not count toward the next probe, and a probe that starts in a quiet phase waits there, where a threshold of one costs nothing.
- One probe landed on the last collection of a busy phase, found 3.5 MB of survivors and a share of 0.43, and sent the threshold back to one for the next busy phase. That is why it takes two.

All twelve, each row one run of all four columns, three rounds (reactors six), milliseconds an iteration at `-O3`:

| | Oracle GraalVM | choosing (`-2`) | 0 | 1 |
| --- | --- | --- | --- | --- |
| scala-doku | 1245 | 1066 | 1083 | 1066 |
| akka-uct | 14374 | 11634 | 13308 | 11051 |
| philosophers | 1703 | 1548 | 1565 | 1564 |
| par-mnemonics | 1834 | 1667 | 1632 | 1702 |
| reactors | 10154 | **10077** | 9377 | 11501 |
| scala-kmeans | 171.5 | 174.6 | 174.5 | 173.4 |
| scrabble | 251.7 | 263.3 | 267.7 | 260.2 |
| fj-kmeans | 3073 | 3284 | 4598 | 3269 |
| rx-scrabble | 63.9 | 70.0 | 69.9 | 70.1 |
| scala-stm-bench7 | 897 | **984** | 985 | 1081 |
| mnemonics | 1832 | 2030 | 1920 | 2001 |
| future-genetic | 938 | 1061 | 1053 | 1054 |

The akka-uct and par-mnemonics rows are from a machine with six processors where the earlier tables had them from one with four, which is why they are twice as fast here.

Pauses and wall time of whole runs, with the collection log on:

| | | choosing | 0 | 1 |
| --- | --- | --- | --- | --- |
| scala-stm-bench7, 10 iterations | all pauses | 5.7 s | 5.5 s | 6.7 s |
| | wall | 11.4 s | 11.1 s | 12.3 s |
| | peak memory | 480 MB | 425 MB | 626 MB |
| reactors, 10 iterations | all pauses | 36.0 s | 34.2 s | 45.9 s |
| | wall | 106.8 s | 94.8 s | 112.7 s |
| fj-kmeans, 10 iterations | all pauses | 5.9 s | 19.6 s | 6.0 s |
| | wall | 33.9 s | 47.7 s | 34.0 s |
| | peak memory | 164 MB | 378 MB | 164 MB |

scala-stm-bench7 goes to zero after six collections and runs as it does at a fixed zero.
fj-kmeans, mnemonics, and akka-uct never leave one: fj-kmeans ran 1210 collections at one, and akka-uct stayed there in four runs of four.
Reactors reaches zero in the first busy phase and takes four fifths of what a fixed zero takes off its pauses.
Its iteration times gain less than its pauses do, and they spread as they always have: six rounds of it choosing ran between 9162 and 10455.

rx-scrabble goes to zero in its first three collections, while it loads its data: the second and third have shares of 0.89 and 0.80 and survivors of 10 and 12% of eden.
After that its young collections promote next to nothing, so no probe comes due and it stays at zero, where it runs as it does at one.
future-genetic, scrabble, philosophers, scala-doku, scala-kmeans, and par-mnemonics stay at one in two runs each.

On akka-uct one round in three of the choosing threshold was slow, 12772 against 10902 and 11229.
It was not the threshold, which never moved.
Single iterations of 13 to 15 seconds turn up in akka-uct at every setting.

mnemonics is 4% faster at zero in this image and was level in the one before, and the rule leaves it at one either way, because 2% of eden surviving is too little for the rule to act on.
At a threshold of one it has two levels of its own, 1945 and 2030, in the same image.

It is the default since.
In images built with the default changed, against the community edition's policy (`-1`) and a fixed one, three rounds and reactors six:

| | Oracle GraalVM | policy (`-1`) | 1 | default |
| --- | --- | --- | --- | --- |
| scala-stm-bench7 | 894 | 1085 | 1080 | **991** |
| reactors | 9822 | 11307 | 11733 | **9798** |
| mnemonics | 1813 | 2147 | 1918 | 1924 |
| par-mnemonics | 1878 | 1744 | 1732 | 1682 |
| fj-kmeans | 3077 | 3261 | 3283 | 3255 |

Against the policy that is 13% on reactors, 10% on mnemonics, 9% on scala-stm-bench7, and 4% on par-mnemonics.
Images of scala-stm-bench7 and reactors built with `-H:+VerifyHeap` verify the heap before and after every collection and run through the change of threshold.

## A Threshold of Two

`2026-09-29`

The threshold that chooses between one and zero costs GameOfLife 7%.
What GameOfLife allocates survives two young collections and is dead by the third: at a threshold of one all of it is copied once and then promoted, to be collected in the old generation, which is collected five times in a run where it is collected twice at a threshold of two.

GameOfLife at fixed thresholds, seven runs, the median:

| threshold | ms | promoted |
| --- | --- | --- |
| the default | 2916 | 1021 MB |
| 0 | 2854 | 1079 MB |
| 1 | 3050 | 854 MB |
| 2 | 2720 | 216 MB |
| 3 | 2739 | 182 MB |
| 5 | 2758 | 154 MB |
| 7 | 2766 | 174 MB |
| 15 | 2818 | 198 MB |
| the policy's own | 2772 | 172 MB |

A threshold of two does not suit everything: mnemonics runs 1665 at one and 1720 at two, because what survives one collection there goes on surviving, and each further age is a copy for nothing.
What tells the two apart is the second age.
At two, what a collection promotes comes out of the space of the second age, so the two give the share of it that survives a third collection: 0.00 to 0.11 on GameOfLife, collection after collection, and half or more on mnemonics and scala-stm-bench7.

`-XX:SerialGCTenuringThreshold=-3` chooses among two, one, and zero.
It starts at two, comes down to one when half or more of the second age survives, twice in a row, and goes on to zero under the same test as the default.
At one it looks at intervals at two, for as long as it takes to see the second age, and at zero it looks at one, as the default does.
Two looks against zero are needed before it is left.

Three earlier versions were measured and dropped.
The first came down from two at three quarters and looked at zero from two: scala-stm-bench7 ran 8% slower than with the default, because its second-age shares while it grows are 0.55 and 0.63 and reset the count, and a look at two from zero takes three collections at twice the copying.
The second and third needed fewer samples and looked at zero from one, and still left scala-stm-bench7 5% behind, reaching zero after 15 to 26 collections where the default does after six.

Against the default, in one image, three rounds, reactors and philosophers six, the median:

| | the default | `-3` | |
| --- | --- | --- | --- |
| GameOfLife | 2911 | 2715 | 6.7% faster |
| scala-stm-bench7 | 995 | 992 | level |
| reactors | 10263 | 10353 | level; rounds of either span 10% |
| mnemonics | 1713 | 1722 | level |
| par-mnemonics | 1396 | 1398 | level |
| fj-kmeans | 3275 | 3269 | level |
| future-genetic | 933 | 941 | level |
| scala-doku | 1052 | 1052 | level |
| scala-kmeans | 174.2 | 174.4 | level |
| akka-uct | 11779 | 12369 | see below |
| rx-scrabble | 68.7 | 69.6 | 1.3% slower |
| philosophers | 1527 | 1561 | 2.2% slower |
| BenchPGO | 295 | 295 | level |
| BranchBench | 461 | 462 | level |
| ArrayBench | 321 | 321 | level |
| JsonBench | 1404 | 1408 | level |

rx-scrabble goes to zero while it loads under the default and stays there; under `-3` it rests at one.
philosophers collects for 75 ms in four iterations and promotes nothing, so its difference is not the collector's work; it is the noisiest of the twelve.
akka-uct was slower under `-3` in three rounds of four, and collects for the same time under both, 26857 ms and 26982 in four iterations: `-3` rests at one for 58 of its 66 young collections, as the default does, and the other eight are at two and cheaper.
akka-uct has iterations of 13 to 15 seconds at every setting, and its difference is not the collector's work either.
scrabble has its two speeds under both.
BenchPGO, BranchBench, and ArrayBench do not collect at all.

Images of GameOfLife built with `-H:+VerifyHeap` verify the heap at every collection under `-3`, the default, and a fixed two, and print what the control prints.
The default is unchanged.

