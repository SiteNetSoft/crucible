#!/usr/bin/env bash
# Measures what the two-pass loop is worth, using the BenchPGO workload.
#
#   source crucible/env.sh && crucible/samples/bench.sh [workload] [reps] [iterations]
#
# workload is the class name, BenchPGO (call-dominated) or BranchBench (layout-dominated).
#
# Builds three images from the same source -- instrumented, profiled, and a control with identical
# flags and no profile -- then runs the profiled and control images alternately and reports the
# median wall time of each. Alternating cancels slow drift in machine load; the median ignores the
# occasional outlier. Neither removes noise, so treat a difference smaller than the spread between
# a run's own fastest and median as not measured.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$here/out"
workload="${1:-BenchPGO}"
reps="${2:-11}"
iterations="${3:-400000000}"
profile_iterations=3000000
mkdir -p "$out"

fail() { echo "bench FAILED: $*" >&2; exit 1; }

javac -d "$out" "$here/$workload.java" || fail "cannot compile $workload"

build() { # build <output-name> <extra flags...>
    local name="$1"; shift
    ( cd "$root/substratevm" && mx native-image -cp "$out" -o "$out/$name" \
            -H:+UnlockExperimentalVMOptions "$@" $workload ) > "$out/bench-$name.log" 2>&1 \
        || fail "building $name failed, see $out/bench-$name.log"
}

echo "== building instrumented =="
build "bench-inst-$workload" -H:+CrucibleInstrument
( cd "$out" && rm -f crucible-profile.json && "./bench-inst-$workload" "$profile_iterations" >/dev/null ) \
    || fail "the instrumented workload did not run"
[ -s "$out/crucible-profile.json" ] || fail "no profile was written"

echo "== building profiled and control =="
build "bench-pgo-$workload" -H:CrucibleProfile="$out/crucible-profile.json"
build "bench-ctl-$workload"
grep -m1 "^Crucible: applied" "$out/bench-bench-pgo-$workload.log" || fail "the profile was not applied"

run_ms() { # run_ms <image>
    local s e
    s=$(date +%s%N); ( cd "$out" && "./$1" "$iterations" >/dev/null ); e=$(date +%s%N)
    echo $(( (e - s) / 1000000 ))
}

echo "== warming up =="
run_ms "bench-ctl-$workload" >/dev/null; run_ms "bench-pgo-$workload" >/dev/null

echo "== $workload: $reps alternating runs of $iterations iterations =="
ctl=(); pgo=()
for ((r = 1; r <= reps; r++)); do
    c=$(run_ms "bench-ctl-$workload"); p=$(run_ms "bench-pgo-$workload")
    ctl+=("$c"); pgo+=("$p")
    printf "  run %2d   control %6s ms   profiled %6s ms\n" "$r" "$c" "$p"
done

stats() { printf '%s\n' "$@" | sort -n | awk '{v[NR]=$1} END {printf "%d %d", v[int((NR+1)/2)], v[1]}'; }
read -r ctl_med ctl_min <<<"$(stats "${ctl[@]}")"
read -r pgo_med pgo_min <<<"$(stats "${pgo[@]}")"

echo
printf "control   median %6s ms   fastest %6s ms\n" "$ctl_med" "$ctl_min"
printf "profiled  median %6s ms   fastest %6s ms\n" "$pgo_med" "$pgo_min"
awk -v c="$ctl_med" -v p="$pgo_med" -v cs="$ctl_med" -v cm="$ctl_min" 'BEGIN {
    delta = c - p
    pct = c > 0 ? 100.0 * delta / c : 0
    noise = cs - cm
    printf "difference %+d ms (%+.1f%%), run-to-run spread of the control %d ms\n", delta, pct, noise
    if (delta < -noise) {
        print "REGRESSION: the profiled image is slower by more than the noise."
    } else if (delta <= noise) {
        print "Not a measurement: the difference is within the noise on this machine."
    }
}'
