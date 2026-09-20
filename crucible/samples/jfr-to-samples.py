#!/usr/bin/env python3
"""Adds sampled call stacks from a JFR recording to a CrucibleVM profile.

    jfr print --json --events jdk.ExecutionSample recording.jfr > samples.json
    jfr-to-samples.py profile.json samples.json profile-with-samples.json

The recording can come from any native image of the same program built with
    --enable-monitoring=jfr -H:+SignalHandlerBasedExecutionSampler
and run with -XX:StartFlightRecording=filename=recording.jfr. An ordinary optimized build is the
better source: a recording image runs several times slower, and not evenly so, which skews where a
sampler finds it. The safepoint-based sampler is no use here, its stacks end at the safepoint.
"""
import collections
import json
import sys


def frame_id(frame):
    method = frame["method"]
    return "L%s;.%s%s:%d" % (method["type"]["name"].replace(".", "/"), method["name"], method["descriptor"], frame.get("bytecodeIndex", -1))


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    profile = json.load(open(sys.argv[1]))
    events = json.load(open(sys.argv[2]))["recording"]["events"]
    stacks = collections.Counter()
    for event in events:
        frames = (event["values"].get("stackTrace") or {}).get("frames") or []
        if frames:
            # JFR lists the innermost frame first; the profile wants the outermost first.
            stacks[tuple(frame_id(f) for f in reversed(frames))] += 1
    profile["samples"] = [{"stack": list(stack), "count": count} for stack, count in stacks.most_common()]
    if "sampledStacks" not in profile.get("categories", []):
        profile.setdefault("categories", []).append("sampledStacks")
    json.dump(profile, open(sys.argv[3], "w"))
    print("%d samples in %d distinct stacks" % (sum(stacks.values()), len(stacks)))


if __name__ == "__main__":
    main()
