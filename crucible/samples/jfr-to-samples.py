#!/usr/bin/env python3
"""Adds sampled call stacks from a JFR recording to a CrucibleVM profile.

    jfr print --json --stack-depth 64 --events jdk.ExecutionSample recording.jfr > samples.json
    jfr-to-samples.py profile.json samples.json profile-with-samples.json

Without --stack-depth, jfr print keeps five frames of each stack, too few to tell callers apart.
The recording can come from any native image of the same program built with
    --enable-monitoring=jfr -H:+SignalHandlerBasedExecutionSampler
and run with -XX:StartFlightRecording=filename=recording.jfr. An ordinary optimized build is the
better source: a recording image runs several times slower, and not evenly so, which skews where a
sampler finds it. The safepoint-based sampler is no use here, its stacks end at the safepoint.
"""
import collections
import json
import sys


def type_id(name):
    # JFR writes a hidden class as Outer$$Lambda/0x1234, the image names it Outer$$Lambda.0x1234,
    # and lambdas are the frames a calling context is most wanted for.
    head, slash, tail = name.rpartition("/0x")
    if slash and "$$" in head:
        return head.replace(".", "/") + ".0x" + tail
    return name.replace(".", "/")


def frame_id(frame):
    method = frame["method"]
    return "L%s;.%s%s:%d" % (type_id(method["type"]["name"]), method["name"], method["descriptor"], frame.get("bytecodeIndex", -1))


def events_of(path, chunk=1 << 20):
    # One event at a time: `jfr print --json` writes close to 80 KB for an event with a deep stack, and a
    # minute of a many-threaded program comes to gigabytes, which json.load would hold all at once.
    decoder = json.JSONDecoder()
    with open(path) as f:
        buf = ""
        while '"events"' not in buf:
            more = f.read(chunk)
            if not more:
                return
            buf += more
        buf = buf[buf.index('"events"'):]
        buf = buf[buf.index("[") + 1:]
        while True:
            buf = buf.lstrip(" \t\r\n,")
            if buf.startswith("]"):
                return
            try:
                event, end = decoder.raw_decode(buf)
            except json.JSONDecodeError:
                more = f.read(chunk)
                if not more:
                    raise
                buf += more
                continue
            yield event
            buf = buf[end:]


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    profile = json.load(open(sys.argv[1]))
    stacks = collections.Counter()
    for event in events_of(sys.argv[2]):
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
