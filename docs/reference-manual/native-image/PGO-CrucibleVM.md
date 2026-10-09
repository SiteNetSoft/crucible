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

## Building CrucibleVM

CrucibleVM is built from source.
To build it, run:

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

## Related Documentation

- [Profile-Guided Optimization](PGO.md)
- [Optimizations and Performance](OptimizationsAndPerformance.md)
