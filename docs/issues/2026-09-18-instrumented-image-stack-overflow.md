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

The failure was first hit on the development workstation, where the build left no diagnostics. It
was reproduced in a resource-capped container (4 CPUs, 10 GiB) on the test host, which is where the
evidence above was collected. Build environment: mx 7.85.1, labsjdk
`ce-25.0.4.1+1-jvmci-25.3-b22`, Rocky Linux 10 container, gcc 14.3.1.

## Verification after the fixes

Instrumented image builds (33 MiB, up from 12.94 MiB now that the counter table is real), runs
clean, and writes the expected profile:

    sum=45000000 hot=9000000 cold=1000000        # exit 0
    crucible-profile.json                        # 501,195 bytes
    schemaVersion 1, producer CrucibleVM, categories [methodCounts, conditionalProfiles]
    582 methods, LHelloPGO;.main([Ljava/lang/String;)V calls=1
    skewed branch: LHelloPGO;.step(ILHelloPGO$Shape;)I bci 4 -> [9000000, 1000000]

A non-instrumented build of the same sample writes no profile, and all ten Crucible unit tests
(`ProfileKeyTest`, `CrucibleProfileWriterTest`, `CounterSlotAllocatorTest`) pass.

## Third instance of the same defect: empty `producer.imageBuildId`

The first round of fixes left `producer.imageBuildId` emitted as the empty string. The driver does
supply the value (`NativeImage.java` always passes `-H:ImageBuildID=` -- a bundle id, or a UUID
derived from the build arguments), so the option is set; the empty value came from the *same*
constant-folding defect as above, on a third field that the first fix missed. `imageBuildId` is
read through the constant `singleton()`, so it folded to the value the field held while compiling,
which is the initial `""`.

Annotating it `@UnknownObjectField(availability = AfterCompilation.class)` alongside `counters` and
`keys` fixes it; the profile now carries e.g.
`"imageBuildId": "173db938-3220-25b6-f005-a8498a5d6669"`.

`crucible/samples/verify-profile.py` asserts the field is non-empty, so this specific regression
cannot return unnoticed.

## Lesson

Any field of an image-heap singleton that hosted code assigns after compilation must be
`@UnknownObjectField`, or a read through the folded singleton silently yields the compile-time
value. The failure mode ranges from a crash (the counter table) to an undetectable wrong value
(the build id).
