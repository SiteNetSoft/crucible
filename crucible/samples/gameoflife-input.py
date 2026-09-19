#!/usr/bin/env python3
"""Generates the 4000x4000 starting grid the Game of Life sample reads.

Oracle's documentation does not ship an input file, so one is generated deterministically here:
the measurement only needs both images to run the identical workload, not a specific pattern.
"""
import random
import sys

M = N = 4000
out = sys.argv[1] if len(sys.argv) > 1 else "input.txt"
rng = random.Random(20260919)
with open(out, "w") as f:
    for _ in range(M):
        f.write("".join("*" if rng.random() < 0.3 else "." for _ in range(N)))
        f.write("\n")
print(f"wrote {out}: {M}x{N}")
