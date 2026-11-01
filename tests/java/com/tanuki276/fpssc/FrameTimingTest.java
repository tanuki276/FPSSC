package com.tanuki276.fpssc;
public final class FrameTimingTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        FrameTiming t = new FrameTiming();
        check(t.observe(900_000_000_000L, 20_000_000L) == 0);
        check(t.observe(900_033_000_000L, 20_000_033L) == 0);
        check(t.observe(900_066_000_000L, 20_000_266L) == 200);
        check(t.observe(900_299_000_000L, 20_000_299L) == 0);
        check(t.observe(900_299_000_000L, 20_000_332L) == -1);
        check(t.observe(0, 20_000_365L) == -1);
        System.out.println("FrameTiming: 6 checks passed");
    }
}
