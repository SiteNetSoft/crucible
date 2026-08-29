/** Deterministic workload with a skewed branch, a polymorphic call, and a counted loop. */
public final class HelloPGO {

    interface Shape { int area(); }
    static final class Square implements Shape { public int area() { return 4; } }
    static final class Circle implements Shape { public int area() { return 5; } }

    private static int hot;
    private static int cold;

    static int step(int i, Shape s) {
        if (i % 10 != 0) {   // taken ~90% of the time
            hot++;
        } else {             // taken ~10% of the time
            cold++;
        }
        return s.area();
    }

    public static void main(String[] args) {
        Shape square = new Square();
        Shape circle = new Circle();
        long sum = 0;
        for (int i = 0; i < 10_000_000; i++) {
            sum += step(i, (i & 1) == 0 ? square : circle);
        }
        System.out.println("sum=" + sum + " hot=" + hot + " cold=" + cold);
    }
}
