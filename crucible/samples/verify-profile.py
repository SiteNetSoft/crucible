#!/usr/bin/env python3
"""Checks that a CrucibleVM profile produced by the HelloPGO sample has the expected shape.

Exits 0 if the profile is well-formed and carries the profile HelloPGO is designed to produce,
non-zero otherwise. Reused as the end-to-end gate for the two-pass loop in M2.
"""
import json
import sys

HOT, COLD = 9_000_000, 1_000_000
MAIN_ID = "LHelloPGO;.main([Ljava/lang/String;)V"


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

    if profile.get("schemaVersion") != 1:
        fail(f"expected schemaVersion 1, got {profile.get('schemaVersion')!r}")

    producer = profile.get("producer", {})
    if producer.get("tool") != "CrucibleVM":
        fail(f"unexpected producer: {producer!r}")
    if not producer.get("imageBuildId"):
        fail("producer.imageBuildId is empty; the profile cannot be paired with its image")

    categories = profile.get("categories", [])
    for required in ("methodCounts", "conditionalProfiles"):
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

    print(f"profile OK: {len(methods)} methods; skewed branch at {skewed[0][0]} bci {skewed[0][1]}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "crucible-profile.json")
