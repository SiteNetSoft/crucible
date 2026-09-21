# The profile left out what the compiler had worked out

`2026-09-21`

future-genetic is the one Renaissance benchmark where the loss to Oracle is not the baseline.
Without a profile the two compilers are 2145 ms and 2390 ms an iteration. With one, Oracle's
gets to 1727 and ours to 2170: their profile is worth 24% and ours 10%.

## What it was not

The obvious suspect was the mechanism behind Oracle's GameOfLife number, a copy of a hot
method for each calling context. Their image has twenty such copies here, all down the path
a stream takes: `evaluate`, `wrapAndCopyInto`, `copyInto`, two of
`BaseSeqSpliterator.forEachRemaining`. So that was built (see below), it produced the same
copies with the same things inlined into them, and the time did not move.

Oracle's own switches then said why. Same profile, one mechanism off at a time:

| build | ms |
| --- | --- |
| no profile | 2145 |
| full profile | 1727 |
| no hot compilation units (`-H:HotCodeMinSelfTime=2.0`) | 1731 |
| no aggressive optimization of hot units | 1667 |
| sampled stacks taken out of the profile | 1750 |
| receiver types taken out | 2030 |
| branch counts taken out | 2085 |

On this benchmark none of the sampling-driven machinery is worth anything. All of it is
receiver types and branch counts, which is what we record too.

## What it was

Our compiler given *their* profile, converted by `iprof-to-crucible.py`, ran at 1950. The
same compiler with our own recording of the same program ran at 2170. So the recording was
the poorer one, and putting the two side by side, call site by call site, showed where:

| call | Oracle's recording | ours |
| --- | --- | --- |
| `BaseSeqIterator.next`, the `seq.get` | ArrayISeq 57%, DoubleChromosome 42%, of 226.9 M | DoubleChromosome 99%, of 95.5 M |
| `BaseSeqSpliterator.forEachRemaining`, the `seq.get` | ArrayISeq 52%, DoubleChromosome 47%, of 144.0 M | ArrayISeq 99%, of 75.8 M |
| `ThreadLocal.get` | 37.0 M | 0.16 M |

Receivers were counted in the graph a method is finally compiled from, and by then the
compiler has used the very context the counts are wanted for. `BaseSeqIterator.next` is
inlined into several callers. In one of them the compiler can see that the sequence is an
`ArrayISeq`, makes the call direct, and there is no virtual call left to count at. The
copies inlined elsewhere still have one and count what comes through them. Added up over the
copies, the profile of that call holds exactly the receivers the compiler could *not* work
out, and a build reading it takes the rarer receiver for the only one: it guards for
`DoubleChromosome` and sends the other 57% down the slow path.

The same thing had hidden every method that is always inlined. Entries were counted at the
top of each compiled method, so `ObjectStore.get`, entered 505 million times, was not in the
profile at all. 1626 methods had a call count where Oracle's profile has 5483.

## The fix

Counting is now marked before inlining instead of added after it. As each method's graph
leaves parsing (`CompileQueue.Policy.beforeEncode`, a hook the community edition has and
does not use) a probe node goes in ahead of every virtual call and at the method's entry. A
probe does nothing and says nothing about memory. It travels with the method wherever it is
inlined, picks up the calling context in its source position as every node does, and after
inlining is turned into the counter it stands for, under the context it ended up in. A call
the compiler has made direct, or turned into a chain of type tests with the bodies inlined,
still has its probe in front of it.

The same comparison afterwards: `BaseSeqIterator.next` 57% / 42% of 226.8 M against Oracle's
226.9 M, and so on down the list, mostly to within one part in ten thousand. 4382 methods
have a call count.

Because a probe survives a call being turned into type tests, a recording image no longer
has to keep such calls as calls to be able to count at them
(`-H:-CrucibleRecordKeepsCallsVirtual`), so it can inline as the optimized image will.

## Built on the way, and kept

* **A copy of a method for a caller it spends time under** (`CrucibleContextClonePhase`,
  needs sampled stacks). Where a call is left standing after inlining and the samples show
  real time under it on this path, the call is pointed at a copy of the callee whose place
  in the tree of sampled stacks is the path and not the method on its own. The community
  edition turned out to have what this needs: `HostedMethod.getOrCreateMethodVariant` makes
  the copy, and giving it the original's encoded graph is enough for the compile queue to
  compile it when a call to it turns up. No upstream change. A copy is only made if some
  call within reach of inlining goes to fewer places on that path than it does in general.
* **The longest recorded context wins.** A lookup used to be answered from the exact inlining
  context or from the bare point. It now takes the longest inner part of the context that
  was recorded.
* **Sampled call targets need enough samples** (`CrucibleMinimumSamplesAtCall`). With a few
  thousand samples in all, most calls have a handful, which says that time was spent there
  and little about where else the call goes.
* `CrucibleHotBonusWhileExpanding` / `...WhileInlining`: the inliner keeps a hotness for
  every call it has yet to look into and asks the inlining provider what it is worth. In the
  community edition the answer is zero.
