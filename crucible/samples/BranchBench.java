/**
 * Benchmark for what branch probabilities alone are worth.
 *
 * Unlike BenchPGO, nothing here depends on rewriting a call: every call site is monomorphic and
 * statically bound. What the profile can change is layout. Each step runs several branches that are
 * taken roughly once in a thousand iterations, and each of those rare paths has a body far larger
 * than the common one. Without a profile the compiler has no reason to prefer either side, so the
 * bulky rare code can end up on the fall-through path, between the hot code and the next hot block.
 * With a profile it can be moved out of the way.
 *
 * The array indices are deliberately data-dependent so the branches cannot be folded away, and the
 * iteration count is an argument so the profiling run can be short.
 */
public final class BranchBench {

    private static final int SIZE = 4096;
    private static int[] data;

    /** Rare path: deliberately bulky, so where it is placed matters. */
    private static int rare(int seed, int salt) {
        int v = seed ^ salt;
        for (int k = 0; k < 24; k++) {
            v = v * 31 + (seed ^ k);
            v ^= v >>> 7;
            v += (v << 3);
        }
        return v;
    }

    static int step(int i) {
        int v = data[i & (SIZE - 1)];
        int acc = v + i;

        if ((v & 0x3ff) == 0) {          // about 1 in 1024
            acc += rare(v, 1);
        }
        if ((acc & 0x7ff) == 0) {        // about 1 in 2048
            acc += rare(acc, 2);
        }
        if ((v ^ i) % 1021 == 0) {       // about 1 in 1021
            acc += rare(v ^ i, 3);
        }
        return acc;
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300_000_000;
        data = new int[SIZE];
        int x = 12345;
        for (int i = 0; i < SIZE; i++) {
            x = x * 1103515245 + 12345;
            data[i] = x >>> 1;
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += step(i);
        }
        System.out.println("sum=" + sum);
    }
}
