---
layout: docs
toc_group: pgo
link_title: Profile-Guided Optimization in CrucibleVM
permalink: /reference-manual/native-image/optimizations-and-performance/PGO/crucible/
---

# Profile-Guided Optimization in CrucibleVM

CrucibleVM adds Profile-Guided Optimization (PGO) to GraalVM Community Native Image.
You build your application once so that it records how it runs, run it on a workload that looks like production, and build it again with what it recorded.
The second build knows which branches go which way, which methods take the time, and where virtual calls land, and compiles accordingly.

The options are not the ones of Oracle GraalVM Native Image (`--pgo-instrument` and `--pgo`).
A profile recorded by Oracle GraalVM can be converted, see [Converting an Oracle GraalVM Profile](#converting-an-oracle-graalvm-profile).

> Note: CrucibleVM is in an early stage. Keep a build without a profile to compare against.

## Installing CrucibleVM

A CrucibleVM distribution is a _.tar.gz_ archive that contains a GraalVM JDK with the `native-image` tool and the CrucibleVM scripts.
Unpack it anywhere; it does not need `mx` or the source tree:

```shell
tar xzf cruciblevm-<version>-linux-x86_64.tar.gz
cruciblevm/crucible/pgo.sh --help
```

`native-image` also needs a C toolchain, for example `gcc`, `glibc-devel`, and `zlib-devel` on Linux.

## Building CrucibleVM

To build CrucibleVM from source, run:

```shell
source crucible/env.sh
cd substratevm
mx build
```

The first build takes 20 to 60 minutes.
To check that profiles are recorded and applied, run:

```shell
mx crucible-e2e
```

To pack what you built into a distribution archive, run:

```shell
crucible/package.sh
```

## Building an Optimized Executable in One Command

The script _crucible/pgo.sh_ runs the whole workflow: it builds an executable that records, runs your workload on it, builds an optimized executable that samples call stacks, runs the workload again, adds the stacks to the profile, and builds the executable to ship.

To build _app_ from _app.jar_ with a workload that runs it on some input, run:

```shell
crucible/pgo.sh --name app --run '$APP input.txt' -- -cp app.jar com.example.Main
```

The arguments after `--` go to `native-image` as they are; `-jar app.jar` works as well.
In the `--run` command, `$APP` is the executable to run.
The workload must let it exit normally, because that is when it writes what it recorded.
The executable to ship is _./app_, and the work files are in _.crucible-pgo/app/_.

The script takes these options:

- `--name NAME`: the name of the executable to ship.
- `--run COMMAND`: the workload, a shell command that uses `$APP`.
- `--no-samples`: stop after the counted profile, with one run and two builds instead of two runs and three builds.
- `--run-timeout SECONDS`: stop the workload with `SIGTERM` after that many seconds.
- `--level LEVEL`: the optimization level of the builds, 3 by default.

If the sampling run takes fewer than 500 samples, the script builds from the counted profile alone and says so.
A sample is taken every 10 to 20 milliseconds of running, so give it a workload of at least several seconds.

### Building a Service

For a service, the workload is a short script that starts it, puts load on it, and stops it.
For example, for Spring PetClinic:

```shell
#!/usr/bin/env bash
"$APP" --server.port=8080 & PID=$!
until curl -sf -o /dev/null http://127.0.0.1:8080/; do sleep 0.1; done
oha -z 20s http://127.0.0.1:8080/
oha -z 20s http://127.0.0.1:8080/vets
kill -TERM $PID; wait $PID
```

Give `--install-exit-handlers` to `native-image` so that `SIGTERM` ends the executable normally:

```shell
crucible/pgo.sh --name petclinic --run ./workload.sh -- <your native-image arguments> --install-exit-handlers
```

Use any load generator or test suite that resembles production traffic.
A workload that exercises only part of the application gives a profile for only that part.
The recording also notes which types the workload locks, and the optimized executable keeps a monitor slot only in those and in types the workload never used.
An object of a type the workload used without locking it is still locked correctly, but slowly, so the workload should lock what production locks; `-H:-CrucibleProfiledMonitorSlots` turns this off.

## The Steps One at a Time

To build an executable that records, run:

```shell
mx native-image -O3 -cp app.jar -o app-recording -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument com.example.Main
```

Run it on your workload.
It writes _crucible-profile.json_ when it exits; `-XX:CrucibleProfileOutput=<path>` writes it elsewhere, and `-XX:CrucibleProfileDumpInterval=<seconds>` also writes it periodically.

To build the executable to ship, run:

```shell
mx native-image -O3 -cp app.jar -o app -H:+UnlockExperimentalVMOptions -H:CrucibleProfile=crucible-profile.json com.example.Main
```

The build reports how well the profile fits the application.
If the profile was recorded from other code, or from an older version of the same code, the first line becomes a warning.

### Adding Sampled Call Stacks

A sampled call stack tells the build under which caller the time was spent, which a counter cannot.
To add stacks to a profile, build an optimized executable with the sampler, run it, and convert its recording:

```shell
mx native-image ... --enable-monitoring=jfr -H:+SignalHandlerBasedExecutionSampler -H:CrucibleProfile=crucible-profile.json -o app-jfr ...
./app-jfr -XX:StartFlightRecording=filename=run.jfr <the usual arguments>
jfr print --json --stack-depth 64 --events jdk.ExecutionSample run.jfr > run.json
python3 crucible/samples/jfr-to-samples.py crucible-profile.json run.json profile-with-stacks.json
```

Both options of the first command matter: without `-H:+SignalHandlerBasedExecutionSampler`, stacks end at a safepoint.
Without `--stack-depth`, `jfr print` keeps five frames of each stack, which is too few to tell callers apart.

### Merging Profiles of Several Runs

To merge the profiles of several workloads, run:

```shell
python3 crucible/samples/merge-profiles.py merged.json run1.json run2.json
```

Counts add up.
A profile that covers more of the application's code helps more than any option.

### Converting an Oracle GraalVM Profile

To convert a profile recorded with Oracle GraalVM's `--pgo-instrument`, run:

```shell
python3 crucible/samples/iprof-to-crucible.py default.iprof crucible-profile.json
```

## Building Without a Profile

Without a profile, the build gives the branches of the Java class library the probabilities recorded for them in other programs.
The recorded probabilities come with the builder, so you do not need to provide anything.
To leave the class library's branches to the compiler's own estimates, pass `-H:-CrucibleLibraryProfile` to `native-image`.

## Tuning Memory and Garbage Collection

CrucibleVM uses the serial garbage collector of GraalVM Community Native Image, with changes that make it collect the young generation on several threads and size the heap differently.
The defaults suit most programs.
The options in this section trade memory for speed, or speed for memory, and each one is worth measuring with your own workload before you keep it.

### Collecting the Young Generation on Several Threads

The young generation is collected on several threads by default.
The number of threads is the number of processors the executable may use, up to 9; in a container, that is the container's processor limit.
With one processor, collections run on one thread.

To set the number of threads at run time, including the thread that runs the collection, pass:

```shell
./app -XX:ParallelYoungCollectionThreads=4
```

To build an executable that always collects on one thread, pass `-H:-ParallelYoungCollection` to `native-image`.

### Capping the Memory of a Service

By default, the young generation shrinks only while next to nothing is promoted to the old generation.
A service started with `-Xms` therefore keeps the young generation it starts with, a quarter of `-Xms`, even when it needs less.

For a service with a capped heap, the recommended options are:

```shell
./app -Xmx512m -XX:+SerialGCShrinkWhilePromoting -XX:SerialGCTimeRatio=19
```

`-XX:+SerialGCShrinkWhilePromoting` lets the young generation shrink while objects are promoted, so that it ends at the same size with or without `-Xms`.
`-XX:SerialGCTimeRatio=19` grows the young generation until collection takes at most a twentieth of the time.
Measured on Spring PetClinic under load with `-Xmx512m`, the two together hold 142 to 149 MB instead of 237 to 242 MB, for 3% fewer requests a second.

Use these options only together with `-Xmx`.
Without a cap, a time ratio of 19 lets the young generation grow far, and benchmarks then peak at about twice their memory.

### Other Run-Time Options

All of the following options apply at run time:

- `-XX:SerialGCTimeRatio=<n>`: the ratio of time spent running to time spent collecting that the young generation is grown to reach. A higher ratio trades memory for speed. 0, the default, uses the policy's own value, which accepts half the time in collection.
- `-XX:SerialGCMinDistance=<ms>`: the time between young collections that the young generation is grown to reach. 0, the default, uses 20 ms. A larger value makes collections rarer for more memory; for example, 50 ms made a set of fourteen Renaissance benchmarks 3% faster for a third more memory.
- `-XX:SerialGCOldGrowthFactor=<n>`: collect the whole heap once the old generation holds this many times what the last complete collection kept, 16 MB at the least. The default is 2; 0 waits until the old generation reaches its capacity, which with `-Xms` is nearly the whole heap.
- `-XX:SerialGCTenuringThreshold=<n>`: how many young collections an object survives before it is promoted. The default, -2, starts at 1 and goes to 0 while most of what survives one collection also survives the next. -3 starts at 2 instead, which suits programs whose objects survive two collections and not a third.

## Related Documentation

- [Profile-Guided Optimization](PGO.md)
- [Optimizations and Performance](OptimizationsAndPerformance.md)
