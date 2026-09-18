#!/usr/bin/env bash
# End-to-end check of the CrucibleVM two-pass loop against the HelloPGO sample.
#
#   source crucible/env.sh && crucible/samples/e2e.sh
#
# Pass 1 instruments, runs and verifies the profile; pass 2 rebuilds consuming it and asserts that
# the profile actually reached the application's own code. Exits non-zero on the first failure.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$here/out"
mkdir -p "$out"

fail() { echo "e2e FAILED: $*" >&2; exit 1; }

javac -d "$out" "$here/HelloPGO.java" || fail "cannot compile the sample"

echo "== pass 1: instrumented build =="
( cd "$root/substratevm" && mx native-image -cp "$out" -o "$out/hellopgo-inst" \
        -H:+UnlockExperimentalVMOptions -H:+CrucibleInstrument HelloPGO ) > "$out/e2e-pass1.log" 2>&1 \
    || fail "instrumented build failed, see $out/e2e-pass1.log"

( cd "$out" && rm -f crucible-profile.json && ./hellopgo-inst ) || fail "instrumented image did not run"
python3 "$here/verify-profile.py" "$out/crucible-profile.json" || fail "profile did not have the expected shape"

echo "== pass 2: build consuming the profile =="
( cd "$root/substratevm" && mx native-image -cp "$out" -o "$out/hellopgo-pgo" \
        -H:+UnlockExperimentalVMOptions -H:CrucibleProfile="$out/crucible-profile.json" \
        -H:CrucibleProfileTrace=HelloPGO HelloPGO ) > "$out/e2e-pass2.log" 2>&1 \
    || fail "profiled build failed, see $out/e2e-pass2.log"

( cd "$out" && ./hellopgo-pgo ) || fail "profiled image did not run"

summary=$(grep -m1 "^Crucible: applied" "$out/e2e-pass2.log") || fail "no application summary in the build log"
echo "$summary"
applied=$(sed -E 's/^Crucible: applied ([0-9]+) of.*/\1/' <<<"$summary")
[ "${applied:-0}" -gt 0 ] || fail "the profile was registered but never applied"

# The point of the exercise: the sample's own hot branch must receive its recorded profile.
grep -q "HIT  LHelloPGO;.step(ILHelloPGO\$Shape;)I:4" "$out/e2e-pass2.log" \
    || fail "the sample's skewed branch did not get its profile applied"

# And the polymorphic Shape.area() call must receive a receiver-type profile.
types=$(sed -E 's/.*receiver-type lookups.*//;t;d' <<<"$summary")
applied_types=$(sed -E 's/.*fallback; ([0-9]+) of [0-9]+ receiver-type.*/\1/' <<<"$summary")
[ "${applied_types:-0}" -gt 0 ] || fail "no receiver-type profile was applied"
grep -q "TYPE LHelloPGO;.step(ILHelloPGO\$Shape;)I:" "$out/e2e-pass2.log" \
    || fail "the sample's polymorphic call did not get a receiver-type profile"

echo "e2e OK: $applied conditional profiles and $applied_types receiver-type profiles applied,"
echo "        including the sample's skewed branch and its polymorphic Shape.area() call"
