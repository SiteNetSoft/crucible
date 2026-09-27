# A stream looks polymorphic from nowhere in particular

`2026-09-26`

Half of scrabble's extra allocation next to Oracle's binary is the machinery of a stream: the
sinks, the pipeline stages, the spliterator, the collector's lambdas. Oracle's compiler removes
most of them; ours leaves them. The usual explanation would be that our inliner stops short of
the code that makes them, so they escape. A trace of the inliner's own call tree for the hottest
lambda (`-H:CrucibleProfileTrace=<method>` prints it now) says otherwise. `wrapSink` and
`copyInto`, which build the chain and run the loop, are inlined into the lambda. Inside them,
this is what the inliner had for the call that wraps each stage:

    InlineCache AbstractPipeline.opWrapSink(int, Sink)   freq=1.2
      Subgraph IntPipeline$1.opWrapSink                  freq=0.3
      Subgraph SliceOps$2.opWrapSink                     freq=0.2
      Subgraph ReferencePipeline$4.opWrapSink            freq=0.2
      Generic  AbstractPipeline.opWrapSink               freq=0.5   Indirect

Three receivers and, for half the calls, "something else". The same at every call below it:
`Sink.begin`, `forEachRemaining`, `Sink.end`, each with `HashMap$EntrySpliterator` and
`ArraySpliterator` among the candidates, in a lambda that only ever streams the characters of a
word. The inliner did what it should with what it was told: a type switch with a virtual call as
the last case. Through a virtual call everything escapes, and escape analysis leaves every sink
and stage on the heap.

What it was told is the truth about the call site pooled over the whole program. `wrapSink` is
one method, inlined into every stream in the image, and the profile recorded one frame of
context: the method the call is in, and nothing above it. Every stream's receivers landed in one
row, the row holds four, and half the calls fell off the end into "other". Seen from the lambda,
each of those calls goes to exactly one place.

The recording depth was one frame because carrying the whole context made the recording image
large, back when a key was a string of method names; keys have been pooled since. Recorded eight
frames deep, the receiver lookups answered from a context go from none to fourteen thousand on
scrabble, and, same run, our default recording against eight frames:

| | default | eight frames |
| --- | --- | --- |
| scrabble | 830 ms | 745 ms |
| future-genetic | 2260 | 2050 |
| par-mnemonics | 5110 | 4580 |
| mnemonics | 6010 | 5580 |
| scala-doku | 2140 | 2080 |
| akka-uct, scala-stm-bench7, rx-scrabble, scala-kmeans, philosophers, fj-kmeans | within their spread | |
| reactors | bimodal between whole runs, 17 or 21 s, whichever binary | |

Seven to ten percent on everything built out of streams and nothing lost elsewhere. The
recording image is a fifth larger and the profile twice the size. Eight frames is the default
now.

One guard came with it. A context's record replaces the pooled one only if the context was seen
a thousand times and holds a thousandth of the site's observations
(`CrucibleContextMinimumCount`, `CrucibleContextMinimumShare`): two receivers out of five in a
context entered three hundred times is chance, and a build that believes it guards for the
wrong one.

Oracle records up to fifteen frames, which was in their profile format from the first day and
looked like an accident of their design until now.
