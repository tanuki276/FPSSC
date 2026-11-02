package com.tanuki276.fpssc;

import java.util.List;

/**
 * Deterministic training benchmark. This is not a live-match evaluator; it scores whether the
 * practice policy preserves core combat qualities across representative synthetic situations.
 */
public final class PracticeCombatBenchmarkTest {
    private static AgentCore.Track track(int id, AgentCore.Kind kind, float x, float y,
                                         float vx, float vy, float confidence, boolean observed) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(0, 0), .025f, confidence, 8, observed);
    }

    private static AgentCore.WorldState world(AgentCore.Track player, List<AgentCore.Track> enemies,
                                               List<AgentCore.Track> projectiles, long now) {
        return new AgentCore.WorldState(player, enemies, projectiles, now, 0f,
                new AgentCore.Vec2(0, 0), .95f);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 500_000L;
        AgentCore.Track player = track(1, AgentCore.Kind.PLAYER, .50f, .50f, 0, 0, .98f, true);
        int score = 0;
        int possible = 0;

        // 1) Melee approach discipline.
        PracticeCombatPolicy melee = new PracticeCombatPolicy();
        melee.setProfile(BrawlerProfile.byId("el_primo"));
        AgentCore.Track far = track(10, AgentCore.Kind.ENEMY, .82f, .50f, 0, 0, .95f, true);
        PracticeCombatPolicy.Action m = melee.decide(world(player, List.of(far), List.of(), now), 1f, now);
        possible += 2;
        if (!m.fire()) score++;
        if (m.move().x() > .4f) score++;

        // 2) Stable ranged shot discipline.
        PracticeCombatPolicy ranged = new PracticeCombatPolicy();
        ranged.setProfile(BrawlerProfile.byId("colt"));
        AgentCore.Track target = track(11, AgentCore.Kind.ENEMY, .84f, .50f, 0, 0, .98f, true);
        PracticeCombatPolicy.Action r1 = ranged.decide(world(player, List.of(target), List.of(), now), 1f, now);
        PracticeCombatPolicy.Action r2 = ranged.decide(world(player, List.of(target), List.of(), now + 320), 1f, now + 320);
        possible += 3;
        if (r2.aim().x() > .95f) score++;
        if (r2.reason().contains("stable=")) score++;
        if (r2.fire()) score++;

        // 3) Moving-target lead.
        AgentCore.Track moving = track(12, AgentCore.Kind.ENEMY, .87f, .45f, 0, .32f, .97f, true);
        PracticeCombatPolicy.Action lead = ranged.decide(world(player, List.of(moving), List.of(), now + 700), 1f, now + 700);
        possible += 2;
        if (lead.aim().y() > .02f) score++;
        if (lead.aim().x() > .75f) score++;

        // 4) Temporary visibility loss should keep pursuit knowledge.
        PracticeCombatPolicy.Action memory = ranged.decide(world(player, List.of(), List.of(), now + 900), 1f, now + 900);
        possible += 2;
        if (!memory.reason().contains("search / patrol")) score++;
        if (memory.aim().len() > .5f) score++;

        // 5) No target must still move.
        PracticeCombatPolicy scout = new PracticeCombatPolicy();
        PracticeCombatPolicy.Action patrol = scout.decide(world(player, List.of(), List.of(), now), 1f, now);
        possible += 1;
        if (patrol.move().len() > .2f) score++;

        // 6) Crossfire should trigger a full-strength escape vector.
        AgentCore.Track a = track(20, AgentCore.Kind.PROJECTILE, .74f, .50f, -1.45f, 0, .95f, true);
        AgentCore.Track b = track(21, AgentCore.Kind.PROJECTILE, .50f, .27f, 0, 1.30f, .94f, true);
        AgentCore.Track c = track(22, AgentCore.Kind.PROJECTILE, .30f, .62f, 1.20f, -.22f, .90f, true);
        PracticeCombatPolicy.Action dodge = scout.decide(world(player, List.of(), List.of(a, b, c), now + 100), 1f, now + 100);
        possible += 2;
        if (dodge.reason().contains("dodge")) score++;
        if (dodge.move().len() > .9f) score++;

        // 7) Cadence: a second command immediately after a shot should not spend another ammo.
        PracticeCombatPolicy cadence = new PracticeCombatPolicy();
        cadence.setProfile(BrawlerProfile.byId("el_primo"));
        AgentCore.Track close = track(30, AgentCore.Kind.ENEMY, .63f, .50f, 0, 0, .99f, true);
        PracticeCombatPolicy.Action shot = cadence.decide(world(player, List.of(close), List.of(), now), 1f, now);
        PracticeCombatPolicy.Action repeat = cadence.decide(world(player, List.of(close), List.of(), now + 60), 1f, now + 60);
        possible += 2;
        if (shot.fire()) score++;
        if (!repeat.fire()) score++;

        float ratio = score / (float) possible;
        System.out.println("PracticeCombatBenchmark score=" + score + "/" + possible + " (" + ratio + ")");
        require(ratio >= .90f, "practice combat benchmark regressed below 90%");
    }
}
