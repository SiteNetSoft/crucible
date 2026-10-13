#!/usr/bin/env python3
"""Makes a profile of the class library out of the recordings of several programs: for every branch
of a method of java.*, jdk.* or sun.*, how often each side was taken, added up over the programs
and over the callers it was recorded under. A build without a recording of its own can take the
probabilities of the library's branches from it (-H:CrucibleAdoptedProfile).

  adopt-profile.py out.json profile.json ...
"""
import json
import sys
from collections import OrderedDict

LIBRARY = ("Ljava/", "Ljdk/", "Lsun/")


def main(out_path, in_paths):
    points = OrderedDict()
    producer = None
    for path in in_paths:
        with open(path) as f:
            profile = json.load(f)
        producer = producer or profile["producer"]
        for method in profile["methods"]:
            for cond in method.get("conditionals", []):
                # The first frame is the branch itself; the others are the callers it was inlined into.
                point = cond["ctx"][0]
                if not point.startswith(LIBRARY):
                    continue
                entry = points.setdefault((point, cond["bci"]), OrderedDict())
                for succ in cond["successors"]:
                    prev = entry.get(succ["bci"])
                    entry[succ["bci"]] = {"key": succ["key"], "bci": succ["bci"],
                                          "count": succ["count"] + (prev["count"] if prev else 0)}
    methods = OrderedDict()
    for (point, bci), successors in points.items():
        if sum(s["count"] for s in successors.values()) == 0:
            continue
        method_id = point.rsplit(":", 1)[0]
        methods.setdefault(method_id, []).append({"ctx": [point], "bci": bci, "successors": list(successors.values())})
    adopted = {"schemaVersion": 3,
               "producer": {"tool": "CrucibleVM", "graalBase": producer["graalBase"], "imageBuildId": "adopted"},
               "categories": ["conditionalProfiles"],
               "methods": [{"id": method_id, "calls": 0, "conditionals": conds} for method_id, conds in sorted(methods.items())]}
    with open(out_path, "w") as f:
        json.dump(adopted, f)
    print("%d branches of %d library methods from %d recordings" % (sum(len(c) for c in methods.values()), len(methods), len(in_paths)))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
