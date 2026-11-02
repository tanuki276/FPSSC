package com.tanuki276.fpssc;

import java.util.List;

public final class PracticeCombatPolicyTest {
    private static AgentCore.Track track(int id, AgentCore.Kind kind, float x, float y, float vx, float vy,
                                         float confidence, boolean observed) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(0, 0), .025f, confidence, 8, observed);
    }

    private static AgentCore.Track acceleratingTrack(int id, AgentCore.Kind kind, float x, float y,
                                                      float vx, float vy, float ax, float ay,
                                                      float confidence, boolean observed) {
        return new AgentCore.Track(id, kind, new AgentCore.Vec2(x, y), new AgentCore.Vec2(vx, vy),
                new AgentCore.Vec2(ax, ay), .025f, confidence, 8, observed);
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

        // Melee profiles must close distance and never punch at unreachable targets.
        PracticeCombatPolicy primo = new PracticeCombatPolicy();
        primo.setProfile(BrawlerProfile.byId("el_primo"));
        AgentCore.Track farEnemy = track(2, AgentCore.Kind.ENEMY, .78f, .50f, 0, 0, .92f, true);
        PracticeCombatPolicy.Action far = primo.decide(world(player, List.of(farEnemy), List.of(), now), 1f, now);
        check(!far.fire(), "El Primo must not attack outside melee range");
        check(far.move().x() > .25f, "El Primo should close distance instead of punching air");

        AgentCore.Track closeEnemy = track(2, AgentCore.Kind.ENEMY, .64f, .50f, 0, 0, .96f, true);
        PracticeCombatPolicy.Action close = primo.decide(world(player, List.of(closeEnemy), List.of(), now + 300), 1f, now + 300);
        check(close.fire(), "El Primo should attack a reliable target in melee range");
        check(close.aim().x() > .8f, "El Primo aim should point toward the nearby target");

        // Attack cadence must stop command-rate spam from spending multiple ammo instantly.
        PracticeCombatPolicy.Action tooSoon = primo.decide(world(player, List.of(closeEnemy), List.of(), now + 360), 1f, now + 360);
        check(!tooSoon.fire(), "Shot cadence should reject immediate repeated attacks");

        // A target that disappears briefly remains tracked rather than making the agent freeze.
        PracticeCombatPolicy.Action hidden = primo.decide(world(player, List.of(), List.of(), now + 600), 1f, now + 600);
        check(hidden.aim().len() > .5f, "Recently hidden enemy should retain a predicted aim direction");
        check(!hidden.reason().contains("search / patrol"), "Recently hidden enemy should remain in short-term memory");

        PracticeCombatPolicy search = new PracticeCombatPolicy();
        PracticeCombatPolicy.Action patrol = search.decide(world(player, List.of(), List.of(), now), 1f, now);
        check(patrol.move().len() > .1f, "No-target state should patrol instead of idling");

        // Iterative interception should lead both velocity and acceleration instead of aiming at current position.
        PracticeCombatPolicy sniper = new PracticeCombatPolicy();
        sniper.setProfile(BrawlerProfile.byId("long_range"));
        AgentCore.Track movingEnemy = acceleratingTrack(3, AgentCore.Kind.ENEMY, .90f, .50f,
                0, .28f, 0, .18f, .95f, true);
        PracticeCombatPolicy.Action lead = sniper.decide(world(player, List.of(movingEnemy), List.of(), now), 1f, now);
        check(lead.aim().y() > .02f, "Projectile profile should lead an accelerating target");

        // A stable target should become easier to shoot after several consistent observations.
        PracticeCombatPolicy steady = new PracticeCombatPolicy();
        steady.setProfile(BrawlerProfile.byId("colt"));
        AgentCore.Track steadyEnemy = track(7, AgentCore.Kind.ENEMY, .88f, .50f, 0, 0, .97f, true);
        PracticeCombatPolicy.Action s1 = steady.decide(world(player, List.of(steadyEnemy), List.of(), now), 1f, now);
        PracticeCombatPolicy.Action s2 = steady.decide(world(player, List.of(steadyEnemy), List.of(), now + 320), 1f, now + 320);
        check(s2.reason().contains("stable="), "Telemetry should expose aim stability");
        check(s2.aim().dot(s1.aim()) > .98f, "Stable target should preserve a consistent aim vector");

        // Multi-projectile avoidance should choose a decisive route instead of averaging into danger.
        AgentCore.Track shotA = track(4, AgentCore.Kind.PROJECTILE, .72f, .50f, -1.4f, 0, .9f, true);
        AgentCore.Track shotB = track(5, AgentCore.Kind.PROJECTILE, .50f, .25f, 0, 1.2f, .9f, true);
        AgentCore.Track shotC = track(6, AgentCore.Kind.PROJECTILE, .30f, .61f, 1.1f, -.18f, .86f, true);
        PracticeCombatPolicy.Action dodge = search.decide(world(player, List.of(), List.of(shotA, shotB, shotC), now + 100), 1f, now + 100);
        check(dodge.reason().contains("dodge"), "Intersecting projectile paths should trigger dodge");
        check(dodge.move().len() > .8f, "Dodge should produce a decisive normalized movement vector");

        check(BrawlerProfile.byId("el_primo").meleeLike(), "El Primo profile must be melee");
        check(BrawlerProfile.byId("colt").projectileSpeed() > BrawlerProfile.byId("dynamike").projectileSpeed(),
                "Fast projectile and thrower profiles must remain distinguishable");

        System.out.println("PracticeCombatPolicyTest OK");
    }
}
