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
# Builds a profile-guided native image in one command: an image that records, a run of your
# workload, an image that samples, a second run, and the image to ship, built from the counts and
# the sampled stacks together.
#
#   crucible/pgo.sh --name app --run '$APP --some-flag input.txt' -- -cp app.jar com.example.Main
#
# Everything after `--` is given to native-image as it is, except -o (use --name). In the --run
# command, $APP is the image to run: the workload must make it exit normally, which is when the
# profile is written, so for a service send it SIGTERM, not SIGKILL. The run happens in the current
# directory. Work files go to .crucible-pgo/<name>/; the image to ship is ./<name>.
#
#   --name NAME     name of the image to ship (required)
#   --run CMD       the workload, a shell command using $APP (required)
#   --no-samples    stop after the counted profile: one run and two builds instead of two and three
#   --level LEVEL   optimization level for the images that are built (default 3)
#   --run-timeout S stop the workload with SIGTERM after S seconds, for one that does not end by itself
#
# In a CrucibleVM distribution the script uses the native-image next to it; in the source tree it builds with mx.
# CRUCIBLE_HOME, set to a distribution, chooses that one.
#
set -euo pipefail

usage() { sed -n '/^# Builds/,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; /^set -euo/d'; exit "${1:-1}"; }

NAME=""; RUN=""; SAMPLES=1; LEVEL=3; MIN_SAMPLES=500; RUN_TIMEOUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --run) RUN="$2"; shift 2 ;;
        --no-samples) SAMPLES=0; shift ;;
        --level) LEVEL="$2"; shift 2 ;;
        --run-timeout) RUN_TIMEOUT="$2"; shift 2 ;;
        -h|--help) usage 0 ;;
        --) shift; break ;;
        *) echo "pgo.sh: unknown option $1" >&2; usage ;;
    esac
done
[ -n "$NAME" ] && [ -n "$RUN" ] && [ $# -gt 0 ] || usage
for a in "$@"; do
    case "$a" in -o|-o=*|-H:Name=*) echo "pgo.sh: give the image name with --name, not $a" >&2; exit 1 ;; esac
done

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# In a CrucibleVM distribution this script sits in crucible/ next to bin/native-image; in the source tree, mx builds.
if [ -z "${CRUCIBLE_HOME:-}" ] && [ -x "$REPO/bin/native-image" ]; then
    CRUCIBLE_HOME="$REPO"
fi
if [ -n "${CRUCIBLE_HOME:-}" ]; then
    NATIVE_IMAGE=("$CRUCIBLE_HOME/bin/native-image")
    JFR="$CRUCIBLE_HOME/bin/jfr"
    TOOLS=(python3 setsid)
else
    # shellcheck source=/dev/null
    source "$REPO/crucible/env.sh"
    NATIVE_IMAGE=(mx -p "$REPO/substratevm" native-image)
    JFR="$JAVA_HOME/bin/jfr"
    TOOLS=(mx python3 setsid)
fi
for tool in "${TOOLS[@]}"; do
    command -v "$tool" > /dev/null || { echo "pgo.sh: $tool is not on the path" >&2; exit 1; }
done
if [ "$SAMPLES" = 1 ] && [ ! -x "$JFR" ]; then
    echo "pgo.sh: $JFR is missing; give --no-samples, or a JAVA_HOME_CRUCIBLE with jfr in it" >&2; exit 1
fi
WD="$PWD/.crucible-pgo/$NAME"
mkdir -p "$WD"

step() { echo; echo "== pgo.sh: $*"; }

build() { # output, then extra native-image options
    local out="$1"; shift
    local log="$WD/$(basename "$out").build.log"
    "${NATIVE_IMAGE[@]}" "-O$LEVEL" -H:+UnlockExperimentalVMOptions "$@" "${NI_ARGS[@]}" -o "$out" > "$log" 2>&1 || {
        echo "pgo.sh: the build of $out failed; the end of $log:" >&2; tail -20 "$log" >&2; exit 1; }
    grep -h "^Crucible:" "$log" | head -3 || true
}

workload() { # image, then extra run-time options for it
    local image="$1"; shift
    local wrapper="$WD/run-$(basename "$image")"
    { printf '#!/usr/bin/env bash\nexec %q' "$image"; for o in "$@"; do printf ' %q' "$o"; done; printf ' "$@"\n'; } > "$wrapper"
    chmod +x "$wrapper"
    # A service stopped with SIGTERM exits with 143, so the exit status says nothing: what was written does.
    local status=0
    if [ -n "$RUN_TIMEOUT" ]; then
        # In a process group of its own, so that SIGTERM reaches the image and not only the shell around it.
        APP="$wrapper" setsid bash -c "$RUN" & local pid=$!
        ( sleep "$RUN_TIMEOUT"; kill -TERM -- "-$pid" 2> /dev/null; sleep 60; kill -KILL -- "-$pid" 2> /dev/null ) & local watchdog=$!
        wait "$pid" || status=$?
        kill "$watchdog" 2> /dev/null || true
    else
        APP="$wrapper" bash -c "$RUN" || status=$?
    fi
    [ "$status" = 0 ] || echo "pgo.sh: the workload exited with $status on $(basename "$image")" >&2
}

NI_ARGS=("$@")

step "1/$((SAMPLES ? 6 : 3)) building the image that records"
build "$WD/$NAME-recording" -H:+CrucibleInstrument

step "2/$((SAMPLES ? 6 : 3)) running the workload on it"
rm -f "$WD/profile.json"
workload "$WD/$NAME-recording" "-XX:CrucibleProfileOutput=$WD/profile.json"
[ -s "$WD/profile.json" ] || { echo "pgo.sh: no profile was written; the workload must let the image exit normally" >&2; exit 1; }
PROFILE="$WD/profile.json"

if [ "$SAMPLES" = 1 ]; then
    step "3/6 building an optimized image that samples call stacks"
    build "$WD/$NAME-sampling" "-H:CrucibleProfile=$PROFILE" --enable-monitoring=jfr -H:+SignalHandlerBasedExecutionSampler

    step "4/6 running the workload on it"
    rm -f "$WD/run.jfr"
    workload "$WD/$NAME-sampling" "-XX:StartFlightRecording=filename=$WD/run.jfr"
    [ -s "$WD/run.jfr" ] || { echo "pgo.sh: no recording was written; the workload must let the image exit normally" >&2; exit 1; }

    step "5/6 adding the sampled stacks to the profile"
    # Without --stack-depth, jfr print keeps five frames of each stack, too few to tell callers apart.
    "$JFR" print --json --stack-depth 64 --events jdk.ExecutionSample "$WD/run.jfr" > "$WD/samples.json"
    SAMPLED=$(python3 "$REPO/crucible/samples/jfr-to-samples.py" "$PROFILE" "$WD/samples.json" "$WD/profile-with-stacks.json")
    echo "$SAMPLED"
    # A profile with stacks lets them decide which methods are hot; a few hundred samples cannot.
    if [ "$(echo "$SAMPLED" | grep -oE '^[0-9]+' | head -1)" -ge "$MIN_SAMPLES" ]; then
        PROFILE="$WD/profile-with-stacks.json"
    else
        echo "pgo.sh: fewer than $MIN_SAMPLES samples, so the image is built from the counts alone; a longer workload (a sample is taken every 10 to 20 ms of running) would give the stacks" >&2
    fi
fi

step "$((SAMPLES ? 6 : 3))/$((SAMPLES ? 6 : 3)) building the image to ship"
build "$PWD/$NAME" "-H:CrucibleProfile=$PROFILE"
echo
echo "pgo.sh: ./$NAME, built from $PROFILE"
