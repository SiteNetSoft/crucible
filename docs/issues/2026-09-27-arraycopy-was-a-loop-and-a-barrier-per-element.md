# arraycopy was a loop, and a barrier per element

`2026-09-27`

Execution samples of scala-stm-bench7 put 48 ms of every iteration under
`UnmanagedMemoryUtil.copyLongsForward`, where Oracle's image has no such frame at all. That is
`System.arraycopy`. A benchmark that copies arrays of one size over and over, both images built
at `-O3` on the same machine:

| copy | Oracle GraalVM | this tree, before |
| --- | --- | --- |
| `long[]`, 64 bytes | 23 GB/s | 9.5 GB/s |
| `long[]`, 4 KB | 83 GB/s | 22 GB/s |
| `long[]`, 4 MB | 31 GB/s | 15 GB/s |
| `Object[]`, 64 references | 6.2 G/s | 1.6 G/s |
| `Object[]`, 4096 references | 15.6 G/s | 1.9 G/s |

Three to eight times slower, for the most common bulk operation in the library. Three things
added up to it.

**Every copy was one generic call.** The community edition lowers every `System.arraycopy` to
a foreign call whose target reads both hubs, tells primitive from reference arrays, checks for
overlap, checks the bounds, and then copies. Graal carries the machinery for doing better --
`ArrayCopySnippets`, which the compiler uses under HotSpot: when the array types are known at the
call site, the type check disappears, the bounds checks become two compares inline, and short
copies become an inline loop. The community edition even instantiates those snippets, in the
AMD64 lowering provider, but only reaches them behind the enterprise vectorizer's option, so in
practice never. The fixed cost per call showed as a 16-byte copy running at a tenth of Oracle's
speed.

**The copy loop moved 32 bytes per iteration with scalar loads and stores.** Oracle's expanded
copies vectorize. The C library's `memmove` also does, with the widest vectors the machine has
and non-temporal stores for the largest sizes, and it handles overlap. From a few dozen bytes up
it is several times faster than any loop written against the word API; below that its call costs
about what the loop costs. Primitive copies now go to it.

**Every reference was stored through its own write barrier.** The serial collector's card table
has one card per object, so after copying references into an array, one mark of the destination
suffices; `Heap.dirtyAllReferencesOf` does exactly that and existed for stored continuations. The
reference copy is now a raw word copy followed by that one mark, inside an uninterruptible
region so the destination cannot move in between. A test that copies young objects into old
arrays and then forces collections, run with heap verification on, passes; the same test with
the mark removed fails verification on the first collection, so the test sees what it is meant
to see.

Copies whose array types the compiler knows -- most of them, since `Arrays.copyOf`, string
building and collection growth all copy between arrays of the same declared type -- now check
bounds inline, copy up to eight primitive elements inline, and otherwise call a routine for that
element kind that goes straight to `memmove`, or to the raw copy and the card mark for
references. Everything else still takes the generic call, which itself now copies the same way.

| copy | Oracle GraalVM | before | after |
| --- | --- | --- | --- |
| `long[]`, 16 bytes | 24 GB/s | 2.5 GB/s | 18 GB/s |
| `long[]`, 64 bytes | 23 GB/s | 9.5 GB/s | 23 GB/s |
| `long[]`, 256 bytes | 52 GB/s | 17 GB/s | 52 GB/s |
| `long[]`, 4 KB | 83 GB/s | 22 GB/s | 132 GB/s |
| `long[]`, 64 KB | 58 GB/s | 17 GB/s | 54 GB/s |
| `long[]`, 4 MB | 31 GB/s | 15 GB/s | 38 GB/s |
| `Object[]`, 16 references | 1.7 G/s | | 3.0 G/s |
| `Object[]`, 64 references | 6.2 G/s | 1.6 G/s | 10.5 G/s |
| `Object[]`, 4096 references | 15.6 G/s | 1.9 G/s | 18.2 G/s |

Level with Oracle on primitive copies from 32 bytes up, ahead on reference copies at every size.
The inline lowering is `-H:±InlineExactArraycopy`, on by default; the copy routines are used
either way. What it is worth on Renaissance is in the roadmap table.
