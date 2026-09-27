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
two, like fj-kmeans, should leave it alone. The default is unchanged, and `-XX:+VerboseGC` now
prints the tenuring age at each collection so a run can be checked.
