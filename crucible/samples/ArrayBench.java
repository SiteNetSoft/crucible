/*
 * Array kernels of the kind most Java numeric code is made of, where the loop limit is not the
 * array's own length and every access therefore carries a bounds check the compiler cannot drop.
 *
 *   ArrayBench <rounds>
 *
 * Prints a checksum, so that differently compiled images can be compared for equal results.
 */
public class ArrayBench {

    static long dot(int[] a, int[] b, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (long) a[i] * b[i];
        }
        return sum;
    }

    static void prefix(int[] a, int[] b, int n) {
        for (int i = 1; i < n; i++) {
            a[i] = a[i - 1] + b[i];
        }
    }

    static void blur(int[] in, int[] out, int n) {
        for (int i = 0; i < n; i++) {
            int left = i - 1 >= 0 ? in[i - 1] : 0;
            int right = i + 1 < n ? in[i + 1] : 0;
            out[i] = (left + in[i] + right) / 3;
        }
    }

    static void multiply(int[][] x, int[][] y, int[][] z, int n) {
        for (int i = 0; i < n; i++) {
            int[] zi = z[i];
            for (int k = 0; k < n; k++) {
                int xik = x[i][k];
                int[] yk = y[k];
                for (int j = 0; j < n; j++) {
                    zi[j] += xik * yk[j];
                }
            }
        }
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int n = 1 << 16;
        int[] a = new int[n + 7];
        int[] b = new int[n + 3];
        int[] c = new int[n + 5];
        for (int i = 0; i < n; i++) {
            a[i] = (i * 31) ^ (i >>> 3);
            b[i] = (i * 17) ^ (i >>> 5);
        }
        int m = 96;
        int[][] x = new int[m][m + 1];
        int[][] y = new int[m][m + 2];
        int[][] z = new int[m][m + 3];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                x[i][j] = i + j;
                y[i][j] = i - j;
            }
        }
        long checksum = 0;
        for (int r = 0; r < rounds; r++) {
            checksum += dot(a, b, n);
            prefix(a, b, n);
            blur(a, c, n);
            checksum ^= c[r % n] + (long) c[n - 1 - r % n];
            multiply(x, y, z, m);
            checksum += z[r % m][(r * 7) % m];
        }
        System.out.println(checksum);
    }
}
