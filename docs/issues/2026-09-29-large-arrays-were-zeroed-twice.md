# Large Arrays Were Zeroed Twice

`2026-09-29`

On fj-kmeans the CrucibleVM image is 7% slower than Oracle's.
JDK Flight Recorder execution samples of both images, built with `-O3` from their own recordings, put 7.4% of the CrucibleVM samples under the slow path that allocates arrays, against 2.5% of Oracle's.
Of that, 2.5% is in `ThreadLocalAllocation.allocateLargeArrayLikeObjectInNewTlab`, a frame that Oracle's samples do not contain.
The arrays come from the merge step of the benchmark, where `ArrayList.addAll` grows lists of about 100,000 references.

## Why

An array too large for an aligned chunk is placed in an unaligned chunk of its own.
`allocateLargeArrayLikeObjectInNewTlab` zeroes the array unless `ChunkBasedCommittedMemoryProvider.areUnalignedChunksZeroed()` says the memory is zeroed already.
The base class says it is not, and `AddressRangeCommittedMemoryProvider`, the provider these images use, did not override it.

The memory is zeroed already.
`AddressRangeCommittedMemoryProvider.allocateInHeapAddressSpace` commits every unaligned chunk when it allocates it, and `freeInHeapAddressSpace` uncommits it when it is freed.
On Linux, committing maps anonymous memory with `mmap`, and on Windows it calls `VirtualAlloc` on decommitted pages; the operating system returns zeroed pages in both cases.
So every large array was zeroed twice: once by the kernel on first touch, and once by the runtime.

## The Change

`AddressRangeCommittedMemoryProvider.areUnalignedChunksZeroed()` returns `true`.
The allocation path then formats a large array without filling it, and `HeapChunkProvider` skips zapping such chunks when zapping is on.
An image built with `-H:+VerifyHeap` checks, for every such allocation, that the memory is zero, and fj-kmeans ran with it without a failure.

## What It Is Worth

Twelve Renaissance benchmarks, `-O3`, recordings with branch probes, Oracle's image in the same runs.
fj-kmeans had five rounds; the benchmarks that showed a difference in three rounds had six more, with a second build of each side, because two builds of one image can differ by up to 2%.

| Benchmark | Before (ms) | After (ms) | Oracle (ms) |
| --- | --- | --- | --- |
| fj-kmeans | 3265, 3266, 3286 (three builds) | **3222, 3229** (two builds) | 3083 |
| mnemonics (one build each, three rounds) | 1660 | **1618** | 1815 |
| akka-uct | 11322, 11905, 11450 | 11772, 11337 | 13799 |
| reactors | 10420, 10327, 9812 | 10076, 10287 | 10085 |
| philosophers | 1524, 1589, 1493 | 1512, 1537 | 1707 |

fj-kmeans is 1.4% faster and mnemonics 2.5% faster.
The other seven, scala-stm-bench7, par-mnemonics, scrabble, rx-scrabble, future-genetic, scala-doku, and scala-kmeans, are level.
akka-uct, reactors, and philosophers looked 4 to 8% slower after three rounds; with six rounds and two builds each, both builds with the change fall inside the range of the three builds without it.
