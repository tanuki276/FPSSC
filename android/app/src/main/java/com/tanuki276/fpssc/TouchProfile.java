package com.tanuki276.fpssc;

/** Coordinates are fractions of the full landscape display; radius is a fraction of its height. */
record TouchProfile(float moveX, float moveY, float attackX, float attackY, float radius) {
    TouchProfile {
        if (!unit(moveX) || !unit(moveY) || !unit(attackX) || !unit(attackY)
                || !Float.isFinite(radius) || radius < .01f || radius > .15f)
            throw new IllegalArgumentException("Coordinates must be 0–1; radius must be 0.01–0.15");
        if (Math.abs(moveX - attackX) < .25f)
            throw new IllegalArgumentException("Move and attack controls must be separated");
    }
    static AgentCore.Vec2 screenVector(AgentCore.Vec2 direction, int width, int height) {
        float strength = Math.min(1, direction.len());
        return new AgentCore.Vec2(direction.x() * width / (float)height, direction.y()).norm().mul(strength);
    }
    private static boolean unit(float value) { return Float.isFinite(value) && value > 0 && value < 1; }
}
