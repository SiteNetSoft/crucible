# Renaissance scrabble: where a better optimizer shows, and what sampling is for

`2026-09-20`

Renaissance 0.16.1 can be built closed-world one benchmark at a time: the bundle holds a
standalone jar per benchmark whose manifest points at the jars it needs, and the harness
has a `--standalone` mode that does without its module loader. A run under the tracing
agent supplies the reflection configuration. `scrabble`, a parallel Java streams
benchmark, built with both compilers at the first attempt.

## The result

Milliseconds per iteration in steady state, `-O3`, same machine.

| | control | PGO | gain |
| --- | --- | --- | --- |
| Oracle GraalVM | 650 | 520 | +20% |
| CrucibleVM | 849 | 677 | +20% |

The two profile-guided gains are the same. The 23% between the binaries is the compiler
underneath: Oracle's optimizer is 30% ahead on this code before any profile is involved.
That had not shown on the earlier workloads because there the two controls were within a
few percent of each other.

## Finding where the time goes without a profiler

The build host has no `perf` and nothing is to be installed on it. Both compilers can
build an image with JFR, though, and JFR samples stacks. Three things needed finding out.

- The community edition's default sampler runs at safepoints and its stacks stop at the
  safepoint stub, five frames of the sampler itself. `-H:+SignalHandlerBasedExecutionSampler`
  gives whole stacks.
- `jfr print` cuts stacks to five frames unless told `--stack-depth 64`.
- SubstrateVM reports every frame as compiled, inlined or not, so the stacks say where
  time goes but not where the call boundaries are. Symbols say that: build with
  `-H:-DeleteLocalSymbols` and see which methods still exist on their own and how large.

Self time per iteration, the largest differences:

| method | CrucibleVM | Oracle |
| --- | --- | --- |
| `Pattern$StartS.match` | 112 ms | 34 ms |
| `Collectors.lambda$groupingBy$0` | 71 ms | 3 ms |
| lambdas of `JavaScrabble`, three of them | 95 ms | 0 ms |
| `AbstractPipeline.copyInto`, `wrapSink`, `evaluate` | 94 ms | 32 ms |
| `UnmanagedMemoryUtil.copyLongsForward`, which is the collector copying | 18 ms | 0 ms |
| `AbstractPipeline.wrapAndCopyInto` | 1 ms | 151 ms |
| `Matcher.search` | 6 ms | 92 ms |
| `HashMap.computeIfAbsent` | 0 ms | 46 ms |

Oracle's time sits in a few large outer methods with everything beneath them inlined.
Ours is spread over the lambdas and helpers themselves. The symbols agree:
`Matcher.search` is 7694 bytes in their image and 523 in ours, and they carry several
copies of `wrapAndCopyInto` at 13 KB each where we have one of 1.5 KB.

The symbols also show something that has nothing to do with hot code. Their image has
5.9 MB of machine code and ours 17.4 MB, method for method: `String.charAt` is 159 bytes
against 709, `AbstractPipeline.copyInto` 1965 against 7798. Oracle compiles whatever the
profile calls cold for size. We only stop inlining into it.

## Sampled stacks as a profile

`crucible/samples/jfr-to-samples.py` adds the stacks of a JFR recording to a profile as a
`samples` section, and `CrucibleCallTree` files each stack under every method on it, so
that whichever of them is being compiled can be asked what happened beneath it, context
by context. An ordinary optimized image is the better thing to sample: a recording image
runs several times slower, and not evenly. With stacks in the profile, the time a method
took and whether it is hot are measured rather than inferred from counts, and upstream's
`samplingMethodProfiles` hook, which the inliner consults before anything else when it
builds an inline cache, is answered from the tree.

On scrabble the tree resolved 366 calls in context, 283 of them to a single target, and the
binary was no faster. Knowing where a call goes was not what held the inliner back here.

## Most of the gap is the garbage collector

More inliner exploration (`-H:TuneInlinerExploration=1`) made the image 7 MB larger and no
faster, which with the rest rules the inliner out: not its thresholds, not its depth, not
what it knows about call targets. JFR's allocation statistics pointed somewhere else.

| over 60 iterations | allocated | collections | paused |
| --- | --- | --- | --- |
| Oracle | 58.3 GB | 1903 | 17.0 s |
| CrucibleVM | 77.6 GB | 405 | 31.6 s |

We allocate a third more, which is escape analysis, and spend almost twice as long
collecting it. A collection costs us 59 ms and them 9 ms. The harness also forces a
collection between iterations, outside the time it reports: counted that way, Oracle takes
667 ms of wall time per iteration and we take 970, so the reported 520 against 665 flatters
us. Collection is around half of this benchmark in both images.

Two things about the collector were tried.

- The collection policy is a run-time option. This tree defaults to `Adaptive2`; Oracle's
  25.0.4 release has `Adaptive` and no such option. `Adaptive` is worse for us, 764 ms.
  `-XX:InitialCollectionPolicy=BySpaceAndTime` is better, 582 ms against 650, and 25.1 s
  against 29.1 s of wall time for 30 iterations. One benchmark is not grounds for changing
  a default, but it is worth a user's while to try.
- A young generation of 2 GB brings the reported time to 396 ms, well under Oracle's. It is
  not a result: the iteration then never collects, and the harness's forced collection
  afterwards, which is not timed, does all of it at 540 ms a time. Wall time gets worse,
  30.5 s. Any figure from this harness has to be checked against wall time.

The collector is Java code compiled into the image, nearly all of it uninterruptible, and
the recording image does not count uninterruptible code. Counting the classes that only
run during a collection put 94 collector methods in the profile, 82 with branches. The
rest of the package cannot be counted: it also runs while an isolate starts, and the image
then crashes before `main`. With the collector profiled, collection was no faster, 0.324 ms
per megabyte reclaimed against 0.322, and Oracle's is 0.236 with or without a profile. What
makes theirs faster is not something a profile reaches. The change was dropped.

## What was tried on it

| | ms per iteration |
| --- | --- |
| as committed | 665 |
| fallback profile summed over contexts | about 2% better, within the spread |
| inliner threshold lowered 4, 16 and 64 times in hot methods | no change; 10 to 89 more inlines |
| call targets from sampled stacks | no change |
| more inliner exploration | no change, 7 MB larger |
| `BySpaceAndTime` collection policy, at run time | 582, and 14% less wall time |
| collector code profiled | no change |
