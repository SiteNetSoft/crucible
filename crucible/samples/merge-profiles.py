#!/usr/bin/env python3
"""Merges several CrucibleVM profiles into one.

    merge-profiles.py out.json in1.json in2.json [...]

One run of one workload covers a fraction of an image -- a few hundred methods out of several
thousand -- so a profile gathered from a single run describes far less of an application than the
application actually does. Merging the profiles of several runs, or of several workloads, widens
that coverage, which matters more for how much profile reaches the compiler than any tuning of the
pipeline does.

Counts add. Conditional successors and observed types are matched on their identity: a conditional
by calling context and bytecode index, a successor within it by its own bytecode index, a type by
name. Sites seen in only one input survive untouched.
"""
import json
import sys
from collections import OrderedDict

SCHEMA = 3


def load(path):
    with open(path) as f:
        profile = json.load(f)
    if profile.get("schemaVersion") != SCHEMA:
        sys.exit(f"{path}: expected schemaVersion {SCHEMA}, got {profile.get('schemaVersion')!r}")
    if profile.get("producer", {}).get("tool") != "CrucibleVM":
        sys.exit(f"{path}: not a CrucibleVM profile")
    return profile


def merge_types(into, types):
    for t in types:
        into[t["name"]] = into.get(t["name"], 0) + t["count"]


def merge_type_sites(into, sites, key_name):
    for site in sites:
        key = (tuple(site["ctx"]), site["bci"])
        entry = into.setdefault(key, {"ctx": site["ctx"], "bci": site["bci"], "overflow": 0,
                                      "types": OrderedDict(), key_name: site.get(key_name)})
        entry["overflow"] += site.get("overflow", 0)
        merge_types(entry["types"], site["types"])


def main(out_path, in_paths):
    methods = OrderedDict()
    categories = []
    producer = None
    for path in in_paths:
        profile = load(path)
        producer = producer or profile["producer"]
        for category in profile.get("categories", []):
            if category not in categories:
                categories.append(category)
        for method in profile["methods"]:
            entry = methods.setdefault(method["id"], {"calls": 0, "conditionals": {},
                                                      "virtualInvokes": {}, "instanceOfs": {}})
            entry["calls"] += method.get("calls", 0)
            for cond in method.get("conditionals", []):
                key = (tuple(cond["ctx"]), cond["bci"])
                merged = entry["conditionals"].setdefault(key, {"ctx": cond["ctx"], "bci": cond["bci"],
                                                                "successors": OrderedDict()})
                for succ in cond["successors"]:
                    prev = merged["successors"].get(succ["bci"])
                    merged["successors"][succ["bci"]] = {
                        "key": succ["key"], "bci": succ["bci"],
                        "count": succ["count"] + (prev["count"] if prev else 0)}
            merge_type_sites(entry["virtualInvokes"], method.get("virtualInvokes", []), "target")
            merge_type_sites(entry["instanceOfs"], method.get("instanceOfs", []), None)

    out_methods = []
    for method_id, entry in sorted(methods.items()):
        out = {"id": method_id, "calls": entry["calls"]}
        if entry["conditionals"]:
            out["conditionals"] = [{"ctx": c["ctx"], "bci": c["bci"],
                                    "successors": list(c["successors"].values())}
                                   for c in entry["conditionals"].values()]
        for name, key_name in (("virtualInvokes", "target"), ("instanceOfs", None)):
            if entry[name]:
                sites = []
                for site in entry[name].values():
                    item = {"ctx": site["ctx"], "bci": site["bci"], "overflow": site["overflow"],
                            "types": [{"name": n, "count": c} for n, c in sorted(site["types"].items())]}
                    if key_name:
                        item[key_name] = site[key_name]
                    sites.append(item)
                out[name] = sites
        out_methods.append(out)

    merged = {"schemaVersion": SCHEMA,
              "producer": {"tool": "CrucibleVM", "graalBase": producer["graalBase"],
                           "imageBuildId": producer["imageBuildId"]},
              "categories": categories,
              "methods": out_methods}
    with open(out_path, "w") as f:
        json.dump(merged, f, indent=1)
    print(f"merged {len(in_paths)} profiles into {out_path}: {len(out_methods)} methods")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
