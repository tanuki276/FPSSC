package com.tanuki276.fpssc;

import java.util.List;

public final class PracticeTacticalPlannerTest {
    private static AgentCore.Track track(int id, AgentCore.Kind kind, float x, float y,
                                         float vx, float vy, float confidence) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(0, 0), .025f, confidence, 8, true);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PracticeTacticalPlanner planner = new PracticeTacticalPlanner();
        AgentCore.Track player = track(1, AgentCore.Kind.PLAYER, .50f, .50f, 0, 0, .99f);
        PracticeOpponentModel.Estimate predictable = new PracticeOpponentModel.Estimate(.02f, .03f, 0f, .08f, 12);

        // Long range should not blindly rush when already near preferred distance.
        BrawlerProfile longRange = BrawlerProfile.byId("long_range");
        PracticeTacticalPlanner.TargetState target = new PracticeTacticalPlanner.TargetState(
                2, new AgentCore.Vec2(.96f, .50f), new AgentCore.Vec2(0, 0), new AgentCore.Vec2(0, 0), .98f, true);
        PracticeTacticalPlanner.Plan hold = planner.choose(player, target, List.of(), longRange, 1f,
                3f, new AgentCore.Vec2(0, 0), predictable, 1000L);
        check(hold.mode() != PracticeTacticalPlanner.Mode.PRESSURE || hold.move().x() < .95f,
                "long-range planner should not blindly full-rush a comfortable target");
        check(hold.danger() < .05f, "no-projectile scene should have negligible danger");

        // Melee should pressure a distant target.
        BrawlerProfile primo = BrawlerProfile.byId("el_primo");
        PracticeTacticalPlanner.TargetState far = new PracticeTacticalPlanner.TargetState(
                3, new AgentCore.Vec2(.86f, .50f), new AgentCore.Vec2(0, 0), new AgentCore.Vec2(0, 0), .98f, true);
        PracticeTacticalPlanner.Plan chase = planner.choose(player, far, List.of(), primo, 1f,
                3f, new AgentCore.Vec2(0, 0), predictable, 1300L);
        check(chase.move().x() > .35f, "melee planner should close distance on a distant target");

        // Crossfire must dominate offensive positioning.
        AgentCore.Track a = track(10, AgentCore.Kind.PROJECTILE, .76f, .50f, -1.6f, 0, .98f);
        AgentCore.Track b = track(11, AgentCore.Kind.PROJECTILE, .50f, .24f, 0, 1.5f, .98f);
        AgentCore.Track c = track(12, AgentCore.Kind.PROJECTILE, .26f, .60f, 1.35f, -.18f, .95f);
        PracticeTacticalPlanner.Plan evade = planner.choose(player, target, List.of(a, b, c), longRange, 1f,
                2.4f, new AgentCore.Vec2(0, 0), predictable, 1600L);
        check(evade.mode() == PracticeTacticalPlanner.Mode.EVADE || evade.danger() > .55f,
                "crossfire should force evasive planning");
        check(evade.move().len() > .8f, "evasive plan should choose a decisive escape vector");

        // Search should keep moving and prefer safety.
        PracticeTacticalPlanner.Plan search = planner.search(player, List.of(a), 1f,
                new AgentCore.Vec2(0, 0), 1900L);
        check(search.move().len() > .8f, "search planner should produce decisive movement");

        System.out.println("PracticeTacticalPlannerTest OK");
    }
}
