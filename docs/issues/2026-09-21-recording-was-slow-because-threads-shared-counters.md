# Recording was slow because every thread wrote the same counters

`2026-09-21`

Nobody had measured what recording costs next to Oracle's. On eleven Renaissance
benchmarks our recording image ran two to four times slower than theirs, and fifteen times
on fj-kmeans, 239 s an iteration against 15 s. Someone recording under a realistic load
would feel that before anything else about this project.

## The obvious cause, which was a small part

Every counter was a call into the runtime where Oracle emits an add. Branch counters became
inline first: a node that stays itself until the low tier and only there turns into a read,
an add and a write, because a write is a state split and the places a counter goes have no
frame state to give it. The counters moved out of an array in the image heap into a block in
the data section, which code can address directly and whose size can be left open until
compilation has finished. That was worth 13% on scrabble. Method entries, 250 million an
iteration there, went through the call only so that the order of first entry could be noted
for a layout mode that is off by default; making them inline as well was worth another 6%.

## The cause

fj-kmeans, an iteration, in milliseconds:

| | the program | our recording | Oracle's recording |
| --- | --- | --- | --- |
| one CPU | 24 420 | 45 953 | 36 279 |
| four CPUs | 9 093 | 164 604 | 28 625 |

On one CPU recording cost 1.9 times, close to Oracle's 1.5. On four it ran slower than on
one. Every thread was writing the same words, and each write takes the cache line away
from the other cores.

There are now eight copies of the counters, and a thread picks one by bits of the address of
its own thread structure, which SubstrateVM keeps in a register. A bump is a few register
operations and one add to memory. Nothing is set up per thread, which matters: a per-thread
block would leave a moment at which a thread runs counted code with nowhere to count, and
that is the kind of thing that crashed the image when the collector's code was counted. The
receiver-type tables, written at every virtual call, are striped the same way, the stripes
one after another rather than interleaved so that two never share a line. The writer adds
the stripes up.

| an iteration while recording, ms | Oracle | before | now |
| --- | --- | --- | --- |
| philosophers | 3 332 | 14 338 | 2 956 |
| scrabble | 2 200 | 8 516 | 4 820 |
| fj-kmeans | 15 280 | 238 565 | 31 274 |

The profiles are the same: the end-to-end check asserts exact counts and passes, and totals
scale with the number of iterations recorded.

## What it costs

The block is zero-filled and is written into the image file all the same: eight stripes of
one 64-bit word per counter, 17 MB for a program with 268 000 counters, so a recording image
is 85 to 120 MB where Oracle's is 55 to 60.

At first the size of a stripe was a constant compiled into every bump, which meant choosing
it before compilation had handed the counters out, as a limit the build then enforced. The
first larger program, Renaissance's db-shootout with 844 458 counters, ran into it. The
block now says how large a stripe is in its own first word, filled in when the image is
written, and a bump reads it from there: one load that is always in cache, no limit, and a
block exactly as large as the program needs.
