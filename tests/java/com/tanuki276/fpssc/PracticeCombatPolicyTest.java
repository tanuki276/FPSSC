package com.tanuki276.fpssc;

import java.util.List;

public final class PracticeCombatPolicyTest {
    private static AgentCore.Track track(int id, AgentCore.Kind kind, float x, float y, float vx, float vy,
                                         float confidence, boolean observed) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(0, 0), .025f, confidence, 8, observed);
    }

    private static AgentCore.WorldState world(AgentCore.Track player, List<AgentCore.Track> enemies,
                                               List<AgentCore.Track> projectiles, long now) {
        return new AgentCore.WorldState(player, enemies, projectiles, now, 0f,
                new AgentCore.Vec2(0, 0), .9f);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 100_000L;
        AgentCore.Track player = track(1, AgentCore.Kind.PLAYER, .50f, .50f, 0, 0, .95f, true);

        PracticeCombatPolicy primo = new PracticeCombatPolicy();
        primo.setProfile(BrawlerProfile.byId("el_primo"));
        AgentCore.Track farEnemy = track(2, AgentCore.Kind.ENEMY, .78f, .50f, 0, 0, .92f, true);
        PracticeCombatPolicy.Action far = primo.decide(world(player, List.of(farEnemy), List.of(), now), 1f, now);
        check(!far.fire(), "El Primo must not attack outside melee range");
        check(far.move().x() > .25f, "El Primo should close distance instead of punching air");

        AgentCore.Track closeEnemy = track(2, AgentCore.Kind.ENEMY, .64f, .50f, 0, 0, .96f, true);
        PracticeCombatPolicy.Action close = primo.decide(world(player, List.of(closeEnemy), List.of(), now + 200), 1f, now + 200);
        check(close.fire(), "El Primo should attack a reliable target in melee range");
        check(close.aim().x() > .8f, "El Primo aim should point toward the nearby target");

        PracticeCombatPolicy.Action hidden = primo.decide(world(player, List.of(), List.of(), now + 450), 1f, now + 450);
        check(hidden.aim().len() > .5f, "Recently hidden enemy should retain a predicted aim direction");
        check(!hidden.reason().contains("search / patrol"), "Recently hidden enemy should remain in short-term memory");

        PracticeCombatPolicy search = new PracticeCombatPolicy();
        PracticeCombatPolicy.Action patrol = search.decide(world(player, List.of(), List.of(), now), 1f, now);
        check(patrol.move().len() > .1f, "No-target state should patrol instead of idling");

        PracticeCombatPolicy sniper = new PracticeCombatPolicy();
        sniper.setProfile(BrawlerProfile.byId("long_range"));
        AgentCore.Track movingEnemy = track(3, AgentCore.Kind.ENEMY, .90f, .50f, 0, .35f, .95f, true);
        PracticeCombatPolicy.Action lead = sniper.decide(world(player, List.of(movingEnemy), List.of(), now), 1f, now);
        check(lead.aim().y() > .01f, "Projectile profile should lead a moving target");

        AgentCore.Track shotA = track(4, AgentCore.Kind.PROJECTILE, .72f, .50f, -1.4f, 0, .9f, true);
        AgentCore.Track shotB = track(5, AgentCore.Kind.PROJECTILE, .50f, .25f, 0, 1.2f, .9f, true);
        PracticeCombatPolicy.Action dodge = search.decide(world(player, List.of(), List.of(shotA, shotB), now + 100), 1f, now + 100);
        check(dodge.reason().contains("dodge"), "Intersecting projectile paths should trigger dodge");
        check(dodge.move().len() > .5f, "Dodge should produce a decisive movement vector");

        check(BrawlerProfile.byId("el_primo").meleeLike(), "El Primo profile must be melee");
        check(BrawlerProfile.byId("colt").projectileSpeed() > BrawlerProfile.byId("dynamike").projectileSpeed(),
                "Fast projectile and thrower profiles must remain distinguishable");

        System.out.println("PracticeCombatPolicyTest OK");
    }
}
