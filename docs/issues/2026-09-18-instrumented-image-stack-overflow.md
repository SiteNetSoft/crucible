# Instrumented image crashes at startup and records an empty profile

- **Date:** 2026-09-18
- **Milestone:** M1, Task 5 (`CrucibleInstrumentationPhase` / `CrucibleInstrumentFeature`)
- **Status:** root cause identified, fixes applied, pending end-to-end verification

## Symptom

`crucible/samples/build.sh -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument` succeeds
(`Finished generating 'hellopgo' in 1m 27s`, 12.94 MiB artifact), but running the resulting image
dies immediately:

    Segmentation fault (core dumped)      # exit 139

No output is produced, so no tear-down hook runs and no `crucible-profile.json` is written. The
crash is fully deterministic. A non-instrumented build of the same sample in the same environment
runs correctly (`sum=45000000 hot=9000000 cold=1000000`), which isolates the fault to the
instrumentation.

## Evidence

`gdb` on the crashing image shows the faulting instruction and the stack mapping:

    => 0x...ee9:  call   0x...ee0
       $rsp        = 0x7fff91ec3000
       [stack] map = 0x00007fff91ec3000-0x00007fff926c3000

`$rsp` sits exactly at the base of the stack mapping: the process ran off the bottom of its stack
into the guard page. This is a stack overflow, not a null dereference.

Disassembling the faulting function shows its whole body:

    0x...ee0:  sub    $0x8,%rsp
    0x...ee4:  mov    $0x15e5,%edi     ; slot = 5605
    0x...ee9:  call   0x...ee0         ; calls itself
    0x...eee:  add    $0x8,%rsp
    0x...ef2:  ret

That is `CrucibleProfileRuntime.increment(int)`: it loads a counter slot and calls itself, with no
base case and none of its original body.

A second, independent observation: `strings` on the instrumented image finds ordinary Java string
constants (`sum=`, `HelloPGO`, `CrucibleVM`, `crucible-profile.json`) but **zero** encoded profile
keys (`M|...` / `C|...`), and the `HelloPGO` string count is identical in the instrumented and the
plain image. The key table in the image heap is empty.

## Root causes

Two separate defects, which together produce the symptom.

### 1. The instrumentation phase instruments its own foreign-call target

`CrucibleInstrumentationPhase.run` skipped only `graph.method() == null` and deopt targets.
`CrucibleProfileRuntime.increment` is a compiled root method like any other, so it received a
method-entry counter of its own — an injected unconditional call to itself. Every call recurses,
and the stack is exhausted on the first increment executed.

### 2. `counters` is constant-folded before `install()` runs

`increment` reads `singleton().counters`. `singleton()` folds to a constant image-heap object, so
the field read folds to whatever the field holds *at compile time*. `install()` only assigns the
real tables from the feature's `afterCompilation` hook, which runs after compilation, so the
compiler sees the initial `new long[0]`, proves `slot >= 0 && slot < c.length` is always false, and
deletes the counter update.

This is why the recursive call is the *only* thing left in the compiled method, and why the key
table is empty. Fixing defect 1 alone would stop the crash but still yield empty profiles.

## Fixes

1. `CrucibleInstrumentationPhase` now skips any method declared by `CrucibleProfileRuntime`, and
   any method for which `UninterruptibleAnnotationUtils.isUninterruptible` holds.
2. `CrucibleProfileRuntime.counters` and `.keys` are annotated
   `@UnknownObjectField(availability = AfterCompilation.class)`, matching the idiom used by
   `RuntimeMetadataEncoding`, so the analysis does not treat the pre-install values as constants.

## Notes

The instrumented build succeeds, so the defect only shows at run time; a green build proves
nothing here. Toolchain for the reproduction: mx 7.85.1 and labsjdk
`ce-25.0.4.1+1-jvmci-25.3-b22`, matching `docs/design/environment.md`.
