# A Chunk for Every Large Array

`2026-10-02`

With the sampled recipe, three of the twelve Renaissance benchmarks were still behind Oracle's profile-guided binary: scrabble by 3 to 6%, scala-stm-bench7 by 6%, and fj-kmeans by 2%.
JDK Flight Recorder put the collector's "Release Spaces" phase at 2.06 s over a recorded run of fj-kmeans in CrucibleVM's binary, and at 61 ms in Oracle's; on scala-stm-bench7, 231 ms against 1.9 ms.
Both binaries use the same chunk sizes: aligned chunks of 512 KB, and a chunk of its own for every array of 128 KB or more (`-H:LargeArrayThreshold=131072`).

## Why

An array at or above the threshold is allocated in an unaligned chunk of its own.
The chunk is committed when the array is allocated and uncommitted when it dies, and the pages are zeroed by the operating system as they are first touched.
`AddressRangeCommittedMemoryProvider.freeInHeapAddressSpace` walks its free lists under a lock and uncommits the chunk at every collection that finds the array dead.
Arrays from 128 KB to the size of an aligned chunk are short-lived on fj-kmeans and scrabble, so each one costs a commit, its page faults, and an uncommit.

The collector's own summary (`-XX:+PrintGCSummary`) shows that most of the cost is outside collections.
On fj-kmeans, twenty iterations:

| | young collections | collection time | run time |
| --- | --- | --- | --- |
| threshold 128 KB, the default | 2,349 | 8.9 s | 63.9 s |
| threshold 500,000 | 2,190 | 7.7 s | **57.4 s** |

The collections take 1.2 s less and the run 6.5 s less.

## The Change

`-H:LargeArrayThreshold` at the most an aligned chunk can take, 520,000 bytes with chunks of 512 KB, puts arrays up to that size into aligned chunks, which the collector keeps for reuse.

## What It Is Worth

The sampled recipe, at `-O3`, three rounds, medians, milliseconds an iteration:

| | Oracle GraalVM | threshold 128 KB | 262,144 | 500,000 | 520,000 |
| --- | --- | --- | --- | --- | --- |
| scrabble | 248.7 | 252.8 | 246.4 | 229.7 | **230.1** |
| fj-kmeans | 3,081 | 3,152 | 2,967 | 2,824 | **2,817** |
| scala-kmeans | 172.3 | 169.6 | 169.0 | 173.1 | 169.5 |
| mnemonics | 1,820 | 1,554 | 1,605 | 1,597 | 1,568 |
| philosophers | 1,684 | 1,532 | 1,513 | 1,542 | 1,537 |

At 500,000 the other seven are level within their rounds, and the geometric mean of the twelve against Oracle's binary goes from 0.917 to 0.904.
At 520,000 the same seven are level as well, akka-uct over nine rounds, and scala-stm-bench7 is still 6% behind Oracle's binary: what it loses is in the copying of its young collections, not in its arrays.
scala-kmeans and mnemonics at 500,000 are 2 to 3% slower in that one build only: at 520,000 they are level.
The five samples at 500,000 are level or faster, ArrayBench 308 ms against 326.
Spring PetClinic's `/` is 1.3 to 2.5% slower at 500,000 and about 1% at 520,000, `/vets` level at both; Quarkus is 1 to 1.5% faster at 500,000 and level at 520,000.

## Tenuring

The same work tried the tenuring threshold at run time.
`-XX:SerialGCTenuringThreshold=0`, which promotes straight from eden, made scrabble 15% faster and fj-kmeans 5 to 6% slower, and the rest level.
On scrabble, each young collection at the default copies the survivors twice, into a survivor space and then into the old generation.
Renaissance forces a full collection before every iteration, which clears at once whatever was promoted too early; on fj-kmeans, promoting from eden took full collections from 20 to 101.
It is not a default.
