#!/usr/bin/env bash
#
# Copyright (c) 2026, CrucibleVM contributors. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# Packs the GraalVM that `mx build` made into a CrucibleVM distribution, a tar.gz that needs neither mx nor the
# source tree: unpack it and run cruciblevm/crucible/pgo.sh, which uses the native-image next to it.
#
#   crucible/package.sh [output directory]
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$PWD}"
DIST=$(ls -d "$REPO"/sdk/mxbuild/linux-amd64/GRAALVM_*/graalvm-*/ 2> /dev/null | head -1)
[ -n "$DIST" ] && [ -x "$DIST/bin/native-image" ] || { echo "package.sh: no GraalVM with native-image under sdk/mxbuild; run mx build in substratevm first" >&2; exit 1; }

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -a "${DIST%/}" "$STAGE/cruciblevm"
mkdir -p "$STAGE/cruciblevm/crucible/samples"
cp "$REPO/crucible/pgo.sh" "$REPO/crucible/README.md" "$STAGE/cruciblevm/crucible/"
cp "$REPO/crucible/samples/jfr-to-samples.py" "$REPO/crucible/samples/merge-profiles.py" "$REPO/crucible/samples/iprof-to-crucible.py" "$STAGE/cruciblevm/crucible/samples/"

ARCHIVE="$OUT/cruciblevm-$(git -C "$REPO" rev-parse --short HEAD)-linux-$(uname -m).tar.gz"
tar -C "$STAGE" -czf "$ARCHIVE" cruciblevm
echo "package.sh: $ARCHIVE"
