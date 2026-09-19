/**
 * Benchmark workload for the two-pass loop. Unlike HelloPGO, which exists to make the profile easy
 * to assert on, this is shaped so a profile can actually change code generation:
 *
 * - {@code Op.apply} has three implementations, so the closed-world analysis cannot devirtualise
 *   it, but one receiver dominates at run time. A receiver-type profile lets the inliner guard on
 *   the dominant type and inline it.
 * - The branch in {@code work} is taken about 98% of the time, so branch probabilities decide
 *   which path gets the fall-through.
 *
 * The iteration count is an argument so the profiling run can be short while the measured run is
 * long enough to time.
 */
public final class BenchPGO {

    interface Op {
        int apply(int x);
    }

    static final class Inc implements Op {
        public int apply(int x) {
            return x + 1;
        }
    }

    static final class Dbl implements Op {
        public int apply(int x) {
            return x * 2;
        }
    }

    static final class Neg implements Op {
        public int apply(int x) {
            return -x;
        }
    }

    static int work(int i, Op op) {
        int v = op.apply(i);
        if ((i & 0x3f) != 0) {
            v += 1;
        } else {
            v -= 1;
        }
        return v;
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 400_000_000;
        Op[] ops = {new Inc(), new Dbl(), new Neg()};
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Op op = (i & 31) == 0 ? ops[1 + (i & 1)] : ops[0];
            sum += work(i, op);
        }
        System.out.println("sum=" + sum);
    }
}
