package com.tanuki276.fpssc;

import java.util.List;

public final class PracticeOpponentModelTest {
    private static AgentCore.Track enemy(int id, float x, float y, float vx, float vy) {
        return new AgentCore.Track(id, AgentCore.Kind.ENEMY,
                new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(0, 0), .025f, .96f, 8, true);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 900_000L;
        PracticeOpponentModel model = new PracticeOpponentModel();

        // Straight mover: low reversal/stop tendency, prediction should lead forward.
        for (int i = 0; i < 8; i++) {
            model.observe(List.of(enemy(1, .40f + i * .015f, .50f, .30f, 0f)), 1f, now + i * 100L);
        }
        PracticeOpponentModel.Estimate straight = model.estimate(1);
        PracticeOpponentModel.Prediction straightPrediction = model.predict(
                1, new AgentCore.Vec2(.25f, .50f), new AgentCore.Vec2(.50f, .50f),
                new AgentCore.Vec2(.30f, 0f), new AgentCore.Vec2(0, 0), .35f, .05f);
        check(straight.reversalRate() < .20f, "straight mover should not look reversal-heavy");
        check(straightPrediction.point().x() > .54f, "straight mover should be led forward");

        // Reversal-heavy mover: learned uncertainty and reversal rate must rise.
        for (int i = 0; i < 10; i++) {
            float vx = (i & 1) == 0 ? .34f : -.34f;
            model.observe(List.of(enemy(2, .55f + ((i & 1) == 0 ? .01f : -.01f), .48f, vx, 0f)),
                    1f, now + 1200L + i * 100L);
        }
        PracticeOpponentModel.Estimate reversal = model.estimate(2);
        check(reversal.reversalRate() > straight.reversalRate() + .10f,
                "zig-zag mover should accumulate reversal tendency");
        check(reversal.uncertainty() > straight.uncertainty(),
                "zig-zag mover should be considered less predictable");

        // Stop-start mover: stop rate should become material.
        for (int i = 0; i < 10; i++) {
            float vx = (i % 3) == 2 ? 0f : .28f;
            model.observe(List.of(enemy(3, .42f + i * .008f, .54f, vx, 0f)),
                    1f, now + 2600L + i * 100L);
        }
        PracticeOpponentModel.Estimate stopStart = model.estimate(3);
        check(stopStart.stopRate() > .03f, "stop-start mover should accumulate stop tendency");

        System.out.println("PracticeOpponentModelTest OK");
    }
}
