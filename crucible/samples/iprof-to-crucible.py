#!/usr/bin/env python3
"""Converts a profile recorded by Oracle GraalVM (an .iprof file) into a CrucibleVM profile.

    iprof-to-crucible.py default.iprof crucible-profile.json

Call counts, branch counts, receiver types and sampled stacks carry over. Monitor profiles have no
counterpart and are dropped. An .iprof records every inlining context in full, and all of it is
kept, so a converted profile is if anything more context-sensitive than one recorded with the
default -H:CrucibleMaxContextDepth.
"""
import collections
import json
import sys

PRIMITIVES = {"boolean": "Z", "byte": "B", "short": "S", "char": "C", "int": "I", "float": "F", "long": "J", "double": "D", "void": "V"}


def descriptor(name):
    """JVM descriptor of a type named the way java.lang.Class.getName() names it."""
    if name in PRIMITIVES:
        return PRIMITIVES[name]
    if name.startswith("["):
        return name.replace(".", "/")
    # A hidden class is Outer$$Lambda/0x1234 here and Outer$$Lambda.0x1234 in the image.
    head, slash, tail = name.rpartition("/0x")
    if slash and "$$" in head:
        return "L" + head.replace(".", "/") + ".0x" + tail + ";"
    return "L" + name.replace(".", "/") + ";"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    iprof = json.load(open(sys.argv[1]))
    types = {t["id"]: t["name"] for t in iprof["types"]}
    method_ids = {}
    for m in iprof["methods"]:
        holder, returns, *parameters = m["signature"]
        method_ids[m["id"]] = "%s.%s(%s)%s" % (descriptor(types[holder]), m["name"], "".join(descriptor(types[p]) for p in parameters), descriptor(types[returns]))

    def frames(ctx):
        """Innermost first, as both formats have it, each frame <methodId>:<bci>."""
        result = []
        for frame in ctx.split("<"):
            method, bci = frame.split(":")
            result.append((method_ids[int(method)], int(bci)))
        return result

    methods = collections.OrderedDict()

    def method(method_id):
        return methods.setdefault(method_id, {"id": method_id, "calls": 0, "conditionals": [], "virtualInvokes": []})

    for record in iprof.get("callCountProfiles", []):
        method(frames(record["ctx"])[0][0])["calls"] += record["records"][0]

    for record in iprof.get("conditionalProfiles", []):
        context = frames(record["ctx"])
        values = record["records"]
        successors = [{"key": values[i + 1], "bci": values[i], "count": values[i + 2]} for i in range(0, len(values) - 2, 3)]
        if any(s["count"] for s in successors):
            # A CrucibleVM profile files a record under the method being compiled, the outermost frame.
            method(context[-1][0])["conditionals"].append({"ctx": ["%s:%d" % f for f in context], "bci": context[0][1], "successors": successors})

    for record in iprof.get("virtualInvokeProfiles", []):
        context = frames(record["ctx"])
        values = record["records"]
        observed = [{"name": descriptor(types[values[i]]), "count": values[i + 1]} for i in range(0, len(values) - 1, 2) if values[i + 1]]
        if observed:
            # An .iprof does not say which method the call site names, only which receivers turned up.
            method(context[-1][0])["virtualInvokes"].append({"ctx": ["%s:%d" % f for f in context], "bci": context[0][1], "target": "", "overflow": 0, "types": observed})

    samples = []
    for record in iprof.get("samplingProfiles", []):
        samples.append({"stack": ["%s:%d" % f for f in reversed(frames(record["ctx"]))], "count": record["records"][0]})

    categories = ["methodCounts", "conditionalProfiles", "virtualInvokeProfiles"] + (["sampledStacks"] if samples else [])
    profile = {"schemaVersion": 3, "producer": {"tool": "iprof-to-crucible", "graalBase": iprof.get("version", ""), "imageBuildId": ""},
               "categories": categories, "methods": list(methods.values()), "samples": samples}
    json.dump(profile, open(sys.argv[2], "w"))
    print("%d methods, %d branch records, %d call sites with receivers, %d sampled stacks" % (
        len(methods), sum(len(m["conditionals"]) for m in methods.values()), sum(len(m["virtualInvokes"]) for m in methods.values()), len(samples)))


if __name__ == "__main__":
    main()
