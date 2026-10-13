## What it changes

- What the change does and why.

## Measured

- What it was measured to do, on which workloads, faster and slower, with the median and the spread;
  see [Measuring](https://github.com/SiteNetSoft/crucible/blob/crucible/main/CONTRIBUTING.md#measuring). "Not a performance change" is an answer.

## Tested

- [ ] `mx crucible-e2e`
- [ ] the Crucible unit tests
- [ ] `mx checkstyle`
- [ ] the outputs of the measured workloads compared with a build without the change
- Anything else, or what was not run and why.

## Documentation

- The README's options table, `crucible/ROADMAP.md`, a note in `docs/issues/`, or "none needed".

## Checklist

- [ ] Every commit is signed off (`git commit -s`), under the [Developer Certificate of Origin](https://developercertificate.org/).
- [ ] A change to upstream GraalVM code is in a commit of its own.
- [ ] If I used a coding assistant, I have reviewed everything it wrote and can explain it.
