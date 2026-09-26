# Every array was eight bytes larger than Oracle's

`2026-09-26`

On scrabble our image allocates 25.1 GB over twenty iterations where Oracle's allocates 19.0,
for the same work. Allocation samples put the difference everywhere at once: a third more in the
stream machinery, a third more in the maps, a third more in the regex matcher. Nothing an
optimizer does spreads that evenly. Object size does. A probe that allocates two million of a
thing and reads the thread's allocated bytes:

| | Oracle GraalVM | this tree | HotSpot |
| --- | --- | --- | --- |
| empty object | 8 | 16 | 16 |
| `long[1]` | 16 | 24 | 24 |
| `Object[2]` | 16 | 24 | 24 |
| `long[8]` | 72 | 80 | 80 |
| a three-character string | 64 | 88 | 96 |
| an object with an int and three references | 24 | 24 | 32 |

Every array carried four bytes, rounded to eight, that Oracle's does not: room for an identity
hash code that almost no array is ever asked for. Oracle GraalVM has an option for it,
`-H:+OptionalIdentityHashCodes`, and its help text says what it does: the hash code's field is
added to an object the first time `identityHashCode` is called on it, which the collector does
when it next moves the object. The community edition turned out to contain the whole of that
runtime -- the hash code snippets, the header bit, the promotion and compaction paths of the
serial collector all handle the optional field -- and no way to turn it on. `HostedConfiguration`
names the other layout in the one place the choice is made.

It is now an option here, on by default with the serial collector, and the probe gives Oracle's
numbers for every row. What it is worth:

| | before | after |
| --- | --- | --- |
| scrabble | 625 ms, 416 MB | 610 ms, 349 MB |
| scrabble with `-XX:SerialGCTimeRatio=6` | 566 ms | 546 ms (Oracle 517) |
| mnemonics | 5654 ms, 149 MB | 5603 ms, 126 MB |
| the five samples | | the same time, identical output |

Sixteen percent less memory and a few percent of time on the program that allocates most, and
memory on the rest. The larger part of the allocation gap is not the size of objects but their
number: the sinks, pipelines and spliterators a stream is made of, which Oracle's escape analysis
removes after inlining further than ours does. That is the next thing.
