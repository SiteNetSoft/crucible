# Contributing to CrucibleVM

CrucibleVM is a fork of GraalVM Community Edition that adds profile-guided optimization to
`native-image`. It is alpha: measured, and checked for identical output, on a handful of
benchmarks. What it needs most is more programs, so the most useful thing you can do is try it on
yours and say what happened, whether that is a wrong answer, a crash, a slower binary or a faster
one.

This guide is for CrucibleVM, at [SiteNetSoft/crucible](https://github.com/SiteNetSoft/crucible).
GraalVM itself, with its own guide and contributor agreement, is at
[oracle/graal](https://github.com/oracle/graal).

## Try it on your program

Start at [`crucible/README.md`](crucible/README.md); `crucible/pgo.sh` records, samples and builds
in one command. Keep a build of the same program without a profile, and if you can, one from the
GraalVM Community Edition release CrucibleVM is based on: comparing against them is what tells a
CrucibleVM problem from your program's or from upstream's.

Then open an issue with the form that fits:

- **Wrong output or a crash.** The program does something different built with a profile, or
  with CrucibleVM, than without. This is the report that matters most.
- **Performance.** Faster or slower than without a profile, or than Oracle GraalVM's
  profile-guided binary. Slower is as welcome as faster: the results in the README include where
  CrucibleVM is behind, and yours should too.
- **Build failure.** `native-image`, `pgo.sh` or the profile tools fail, or the build says it
  applied little or none of the profile.

For a question, or a result that is not a problem, such as "it works on my service, and here is how
fast", use [Discussions](https://github.com/SiteNetSoft/crucible/discussions): Q&A for questions,
Show and tell for results, Ideas for what CrucibleVM should do next.

### Narrowing down wrong output

1. Build without `-H:CrucibleProfile`. If it still goes wrong, build with the GraalVM Community
   Edition release CrucibleVM is based on. If that goes wrong too, the bug is upstream; report it to
   [oracle/graal](https://github.com/oracle/graal/issues).
2. If only the profile-guided build goes wrong, turn the options in the README's
   [Options](crucible/README.md#options) table off one at a time, with `-H:-<option>`, until it goes
   right. The one that fixes it is the best clue you can give.
3. `-H:CrucibleProfileTrace=<substring>` prints every profile lookup and the inliner's decisions for
   the methods that match, once you know which method computes the wrong value.

A program that can be shared, however small, is worth more than any description. If yours cannot
be, say so in the issue, and the profile alone (`crucible-profile.json`) may still help.

### Measuring

The noise between two runs of the same binary is several percent on some workloads, so a
comparison of two separate runs says little. What the project's own figures are held to, and what
makes yours comparable:

- one machine, nothing else heavy running on it;
- the two binaries built from the same source, differing only in what is being compared;
- runs alternated between them, several of each, with the median and the spread reported;
- the outputs compared, so that a faster binary is known to compute the same thing;
- the build's `Crucible:` lines, which show how much of the profile it applied.

[`docs/issues/2026-09-19-measuring-properly.md`](docs/issues/2026-09-19-measuring-properly.md) is
what happened before these rules.

## Changing the code

### Building

    source crucible/env.sh          # mx and a labs JDK 25
    cd substratevm && mx build      # first build: 20 to 60 minutes
    mx crucible-e2e                 # records, rebuilds and checks that the profile was applied
    mx unittest ProfileKeyTest CrucibleProfileWriterTest CounterSlotAllocatorTest CrucibleProfileParserTest
    mx checkstyle

Run one `mx build` at a time in a checkout. A native-image build at full parallelism is a heavy
load; on a machine that struggles with it, pass `--parallelism=<n>`.

### Where things are

CrucibleVM's code is in `com.oracle.svm.core.crucible` and `com.oracle.svm.hosted.crucible.*`, and
everything that is not Java is under `crucible/`. It reaches the compiler through extension points
upstream already has. Where one is missing, put the change to upstream code in a commit of its own
and keep it to a few lines, so that rebasing onto a new GraalVM release stays simple.

A new option is an `-H:` option behind `-H:+UnlockExperimentalVMOptions`, and gets a row in the
README's options table with its default and what it does.

### What a change needs

- `mx crucible-e2e`, the unit tests above and `mx checkstyle` pass.
- A change to what the compiler does comes with what it was measured to do, held to the rules under
  [Measuring](#measuring), including where it made things slower. Most of what has been tried and
  did not work is written down in [`docs/issues/`](docs/issues) and
  [`crucible/ROADMAP.md`](crucible/ROADMAP.md); look there before you start, and add a note of the
  same kind when you find something out, whether or not it worked.
- Commit messages say what the change does, as a sentence without a full stop: "Leave the monitor
  slot out of the types a run used and never locked, by default". The body, if there is one, says
  why and gives the numbers.
- One change to a pull request. The pull request template asks what was tested.

### Sign your work: the Developer Certificate of Origin

CrucibleVM uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO), as
the Linux kernel does, and not a contributor agreement. By adding a `Signed-off-by:` line to a
commit you certify that you wrote it, or otherwise have the right to submit it under the project's
licenses:

    Signed-off-by: Your Name <you@example.com>

`git commit -s` adds the line for you, with the name and email of your git configuration. Use your
real name. Every commit in a pull request needs one; to add it to commits you have already made,
`git rebase --signoff crucible/main` and push again.

Contributions are under the licenses of the files they change, see [LICENSE](LICENSE); a new file
carries the same header as the files next to it.

### Coding assistants

You may use them. You remain responsible for every line you submit, and you sign it off as your own
under the DCO: review it, understand it, and be ready to explain and maintain it. Saying in the pull
request that a tool helped is welcome, and not required. See
[CODING_ASSISTANTS.md](CODING_ASSISTANTS.md).

## Security

Do not report a vulnerability in a public issue; see [SECURITY.md](SECURITY.md).
