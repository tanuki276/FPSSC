package com.tanuki276.fpssc;

import java.util.ArrayList;
import java.util.List;

/** Deterministic multi-frame stress test for the detached practice AI. */
public final class PracticeCombatStressTest {
    private static AgentCore.Track track(int id, AgentCore.Kind kind, float x, float y,
                                         float vx, float vy, float ax, float ay,
                                         float confidence, boolean observed) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(ax, ay), .025f, confidence, 12, observed);
    }

    private static AgentCore.WorldState world(AgentCore.Track player, List<AgentCore.Track> enemies,
                                               List<AgentCore.Track> shots, long now) {
        return new AgentCore.WorldState(player, enemies, shots, now, 0f,
                new AgentCore.Vec2(0, 0), .96f);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PracticeCombatPolicy policy = new PracticeCombatPolicy();
        policy.setProfile(BrawlerProfile.byId("colt"));

        float px = .50f, py = .50f;
        float ex = .86f, ey = .46f;
        float evx = 0f, evy = .24f;
        int fired = 0;
        int emergencyDodges = 0;
        int plannerMoves = 0;
        int directionFlips = 0;
        AgentCore.Vec2 previousMove = new AgentCore.Vec2(0, 0);
        long now = 900_000L;

        for (int frame = 0; frame < 240; frame++) {
            float dt = .05f;
            // Deterministic opponent: straight segments, reversals, and short stops.
            if (frame % 52 == 0 && frame > 0) evy = -evy;
            if (frame % 71 >= 62) evy = 0f;
            else if (Math.abs(evy) < .01f) evy = (frame / 71) % 2 == 0 ? .24f : -.24f;
            ex += evx * dt;
            ey += evy * dt;
            if (ey < .24f) { ey = .24f; evy = Math.abs(evy); }
            if (ey > .76f) { ey = .76f; evy = -Math.abs(evy); }

            List<AgentCore.Track> shots = new ArrayList<>();
            // Add occasional intersecting projectiles from changing approach angles.
            if (frame % 29 >= 20 && frame % 29 <= 24) {
                float phase = frame % 29 - 20;
                shots.add(track(100 + frame, AgentCore.Kind.PROJECTILE,
                        .76f - phase * .055f, py, -1.10f, 0f, 0f, 0f, .92f, true));
            }
            if (frame % 47 >= 38 && frame % 47 <= 42) {
                float phase = frame % 47 - 38;
                shots.add(track(300 + frame, AgentCore.Kind.PROJECTILE,
                        px, .25f + phase * .06f, 0f, 1.0f, 0f, 0f, .90f, true));
            }

            boolean visible = frame % 83 < 74;
            AgentCore.Track player = track(1, AgentCore.Kind.PLAYER, px, py, 0f, 0f, 0f, 0f, .99f, true);
            AgentCore.Track enemy = track(2, AgentCore.Kind.ENEMY, ex, ey, evx, evy, 0f, 0f, .96f, visible);
            List<AgentCore.Track> enemies = visible ? List.of(enemy) : List.of();

            PracticeCombatPolicy.Action action = policy.decide(world(player, enemies, shots, now), 1f, now);
            if (action.fire()) fired++;
            if (action.reason().contains("emergency predictive dodge")) emergencyDodges++;
            if (action.reason().contains("planner")) plannerMoves++;
            if (previousMove.len() > .2f && action.move().len() > .2f
                    && previousMove.norm().dot(action.move().norm()) < -.72f) directionFlips++;

            // Integrate our simulated movement conservatively.
            px += action.move().x() * .018f;
            py += action.move().y() * .018f;
            px = Math.max(.10f, Math.min(.90f, px));
            py = Math.max(.12f, Math.min(.88f, py));
            previousMove = action.move();
            now += 50L;
        }

        System.out.println("PracticeCombatStress fired=" + fired
                + " emergencyDodges=" + emergencyDodges
                + " plannerMoves=" + plannerMoves
                + " directionFlips=" + directionFlips
                + " finalAmmo=" + policy.estimatedAmmo());

        require(fired >= 8, "stress policy became too passive to engage");
        require(fired <= 38, "stress policy wastes too much ammo");
        require(emergencyDodges >= 2, "stress policy failed to react to repeated projectile threats");
        require(plannerMoves >= 160, "short-horizon planner should drive most non-emergency frames");
        require(directionFlips <= 45, "movement oscillates too aggressively");
        require(policy.estimatedAmmo() >= 0f && policy.estimatedAmmo() <= 3.01f,
                "ammo estimator escaped valid bounds");
        System.out.println("PracticeCombatStressTest OK");
    }
}
