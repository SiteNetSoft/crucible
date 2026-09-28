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
