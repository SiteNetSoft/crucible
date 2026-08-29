#!/usr/bin/env bash
# Usage: crucible/samples/build.sh [native-image flags...]
# Builds crucible/samples/out/hellopgo. Requires `source crucible/env.sh` first.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$here/out"
mkdir -p "$out"
javac -d "$out" "$here/HelloPGO.java"
cd "$root/substratevm"
mx native-image -cp "$out" -o "$out/hellopgo" "$@" HelloPGO
