#!/usr/bin/env bash
# Measures what the two-pass loop is worth, using the BenchPGO workload.
#
#   source crucible/env.sh && crucible/samples/bench.sh [reps] [iterations]
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
reps="${1:-11}"
iterations="${2:-400000000}"
profile_iterations=3000000
mkdir -p "$out"

fail() { echo "bench FAILED: $*" >&2; exit 1; }

javac -d "$out" "$here/BenchPGO.java" || fail "cannot compile the workload"

build() { # build <output-name> <extra flags...>
    local name="$1"; shift
    ( cd "$root/substratevm" && mx native-image -cp "$out" -o "$out/$name" \
            -H:+UnlockExperimentalVMOptions "$@" BenchPGO ) > "$out/bench-$name.log" 2>&1 \
        || fail "building $name failed, see $out/bench-$name.log"
}

echo "== building instrumented =="
build bench-inst -H:+CrucibleInstrument
( cd "$out" && rm -f crucible-profile.json && ./bench-inst "$profile_iterations" >/dev/null ) \
    || fail "the instrumented workload did not run"
[ -s "$out/crucible-profile.json" ] || fail "no profile was written"

echo "== building profiled and control =="
build bench-pgo -H:CrucibleProfile="$out/crucible-profile.json"
build bench-ctl
grep -m1 "^Crucible: applied" "$out/bench-bench-pgo.log" || fail "the profile was not applied"

run_ms() { # run_ms <image>
    local s e
    s=$(date +%s%N); ( cd "$out" && "./$1" "$iterations" >/dev/null ); e=$(date +%s%N)
    echo $(( (e - s) / 1000000 ))
}

echo "== warming up =="
run_ms bench-ctl >/dev/null; run_ms bench-pgo >/dev/null

echo "== $reps alternating runs of $iterations iterations =="
ctl=(); pgo=()
for ((r = 1; r <= reps; r++)); do
    c=$(run_ms bench-ctl); p=$(run_ms bench-pgo)
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
    if (delta <= noise) {
        print "Not a measurement: the difference is within the noise on this machine."
    }
}'
