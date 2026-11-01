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
        var vector = TouchProfile.screenVector(new AgentCore.Vec2(.1f, .1f), 1600, 900);
        if (Math.abs(vector.x()/vector.y()-1600f/900f)>.001f) throw new AssertionError("Screen aspect lost");
        if (Math.abs(vector.len()-(float)Math.sqrt(.02))>.001f) throw new AssertionError("Move strength lost");
        System.out.println("TouchProfile: 10 regression checks passed");
    }
}
