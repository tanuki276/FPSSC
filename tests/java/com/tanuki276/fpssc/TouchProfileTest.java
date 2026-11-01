package com.tanuki276.fpssc;
public final class TouchProfileTest {
    private static void invalid(float x, float y, float ax, float ay, float radius) {
        try { new TouchProfile(x, y, ax, ay, radius); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid profile accepted");
    }
    public static void main(String[] args) {
        new TouchProfile(.15f, .8f, .88f, .75f, .06f);
        invalid(Float.NaN, .8f, .88f, .75f, .06f);
        invalid(.15f, Float.POSITIVE_INFINITY, .88f, .75f, .06f);
        invalid(-.1f, .8f, .88f, .75f, .06f);
        invalid(.15f, .8f, 1, .75f, .06f);
        invalid(.15f, .8f, .88f, .75f, 0);
        invalid(.15f, .8f, .88f, .75f, .2f);
        invalid(.15f, .8f, .2f, .75f, .06f);
        System.out.println("TouchProfile: 8 regression checks passed");
    }
}
