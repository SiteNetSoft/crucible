#!/usr/bin/env python3
"""Checks that a CrucibleVM profile produced by the HelloPGO sample has the expected shape.

Exits 0 if the profile is well-formed and carries the profile HelloPGO is designed to produce,
non-zero otherwise. Reused as the end-to-end gate for the two-pass loop in M2.
"""
import json
import sys

HOT, COLD = 9_000_000, 1_000_000
MAIN_ID = "LHelloPGO;.main([Ljava/lang/String;)V"
STEP_PREFIX = "LHelloPGO;.step("
SHAPES = {"LHelloPGO$Square;", "LHelloPGO$Circle;"}
SCHEMA = 2


def fail(msg):
    print("profile FAILED:", msg, file=sys.stderr)
    sys.exit(1)


def main(path):
    try:
        with open(path) as f:
            profile = json.load(f)
    except OSError as e:
        fail(f"cannot read {path}: {e}")
    except json.JSONDecodeError as e:
        fail(f"{path} is not valid JSON: {e}")

    if profile.get("schemaVersion") != SCHEMA:
        fail(f"expected schemaVersion {SCHEMA}, got {profile.get('schemaVersion')!r}")

    producer = profile.get("producer", {})
    if producer.get("tool") != "CrucibleVM":
        fail(f"unexpected producer: {producer!r}")
    if not producer.get("imageBuildId"):
        fail("producer.imageBuildId is empty; the profile cannot be paired with its image")

    categories = profile.get("categories", [])
    for required in ("methodCounts", "conditionalProfiles", "virtualInvokeProfiles"):
        if required not in categories:
            fail(f"missing category {required!r} in {categories!r}")

    methods = profile.get("methods", [])
    if not methods:
        fail("profile contains no methods")

    entries = [m for m in methods if m["id"] == MAIN_ID]
    if len(entries) != 1:
        fail(f"expected exactly one entry for main, got {len(entries)}")
    if entries[0].get("calls") != 1:
        fail(f"expected main to be called once, got {entries[0].get('calls')!r}")

    skewed = [
        (m["id"], c["bci"])
        for m in methods
        for c in m.get("conditionals", [])
        if sorted((s["count"] for s in c["successors"]), reverse=True) == [HOT, COLD]
    ]
    if not skewed:
        fail(f"no conditional with the expected {HOT}/{COLD} split")

    # The sample calls Shape.area() on an even split of two implementations; both must be recorded.
    observed = {}
    for m in methods:
        if not m["id"].startswith(STEP_PREFIX):
            continue
        for invoke in m.get("virtualInvokes", []):
            for t in invoke["types"]:
                observed[t["name"]] = observed.get(t["name"], 0) + t["count"]
    missing = SHAPES - observed.keys()
    if missing:
        fail(f"no receiver types recorded for Shape.area(); missing {sorted(missing)}, saw {sorted(observed)}")
    for shape in SHAPES:
        if observed[shape] < HOT // 2:
            fail(f"receiver {shape} counted only {observed[shape]} times, expected roughly 5000000")

    print(f"profile OK: {len(methods)} methods; skewed branch at {skewed[0][0]} bci {skewed[0][1]}; "
          f"receiver types {', '.join(f'{k}={v}' for k, v in sorted(observed.items()))}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "crucible-profile.json")
