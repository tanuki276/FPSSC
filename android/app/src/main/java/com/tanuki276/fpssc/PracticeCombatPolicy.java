package com.tanuki276.fpssc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Practice/simulation combat controller. It consumes the existing perception tracks and keeps
 * short-term memory for temporarily hidden actors, range-aware movement, predictive aiming,
 * ammo estimation, projectile avoidance, and ability recommendations.
 *
 * This class is deliberately input-backend agnostic: callers decide whether/how to execute the
 * returned action in a training harness.
 */
final class PracticeCombatPolicy {
    record Action(
            AgentCore.Vec2 move,
            AgentCore.Vec2 aim,
            boolean fire,
            boolean useSuper,
            boolean useHypercharge,
            String reason
    ) {
        static Action idle(String reason) {
            return new Action(new AgentCore.Vec2(0, 0), new AgentCore.Vec2(0, 0), false, false, false, reason);
        }
    }

    private record Memory(
            int id,
            AgentCore.Vec2 pos,
            AgentCore.Vec2 velocity,
            AgentCore.Vec2 acceleration,
            float confidence,
            long lastSeenMs
    ) {
        AgentCore.Vec2 project(long nowMs) {
            float t = Math.max(0f, Math.min(2.5f, (nowMs - lastSeenMs) / 1000f));
            return pos.add(velocity.mul(t)).add(acceleration.mul(.5f * t * t));
        }
    }

    private final Map<Integer, Memory> enemyMemory = new HashMap<>();
    private BrawlerProfile profile = BrawlerProfile.byId(BrawlerProfile.DEFAULT_ID);
    private int targetId = -1;
    private AgentCore.Vec2 previousAim = new AgentCore.Vec2(0, 0);
    private long lastShotMs;
    private float estimatedAmmo;
    private long lastTickMs;
    private long lastSuperMs;
    private long lastHyperMs;
    private int searchPhase;

    PracticeCombatPolicy() {
        estimatedAmmo = profile.ammoCapacity();
    }

    void setProfile(BrawlerProfile next) {
        if (next == null) return;
        profile = next;
        estimatedAmmo = Math.min(estimatedAmmo, profile.ammoCapacity());
        if (estimatedAmmo < .1f) estimatedAmmo = profile.ammoCapacity();
        targetId = -1;
        previousAim = new AgentCore.Vec2(0, 0);
    }

    BrawlerProfile profile() { return profile; }
    float estimatedAmmo() { return estimatedAmmo; }

    Action decide(AgentCore.WorldState world, float aspect, long nowMs) {
        if (world == null || world.player() == null || world.player().confidence() < .30f)
            return Action.idle("practice: player uncertain");

        updateAmmo(nowMs);
        remember(world.enemies(), nowMs);
        AgentCore.Track player = metric(world.player(), aspect);
        AgentCore.Vec2 playerNow = player.pos();

        AgentCore.Vec2 dodge = combinedDodge(player, world.projectiles(), aspect);
        if (dodge.len() > .1f)
            return new Action(dodge, previousAim, false, false, false, "practice: multi-projectile dodge");

        Target target = selectTarget(playerNow, world.enemies(), aspect, nowMs);
        if (target == null) {
            AgentCore.Vec2 search = searchVector(playerNow, aspect);
            return new Action(search, previousAim, false, false, false, "practice: search / patrol");
        }

        AgentCore.Vec2 toTarget = target.pos().sub(playerNow);
        float distance = toTarget.len();
        AgentCore.Vec2 aim = computeAim(playerNow, target, distance);
        if (previousAim.len() > .05f) aim = previousAim.mul(.28f).add(aim.mul(.72f)).norm();
        previousAim = aim;

        boolean inRange = distance <= profile.attackRange();
        float hitChance = estimateHitChance(target, distance);
        boolean hasAmmo = estimatedAmmo >= .92f;
        boolean fire = inRange && hasAmmo && hitChance >= profile.fireThreshold();

        // Hidden-target shots are intentionally conservative: only continue briefly after losing
        // sight and only if the predicted position remains comfortably inside weapon range.
        if (!target.observed()) {
            long hiddenMs = nowMs - target.lastSeenMs();
            fire = fire
                    && hiddenMs <= (long)(profile.bushMemorySeconds() * 1000f)
                    && distance <= profile.attackRange() * .88f
                    && target.confidence() >= .38f;
        }

        AgentCore.Vec2 move = movementForRange(playerNow, toTarget, distance, aspect);
        boolean useSuper = shouldUseSuper(target, distance, hitChance, nowMs);
        boolean useHyper = shouldUseHypercharge(target, distance, hitChance, nowMs);

        if (fire) {
            estimatedAmmo = Math.max(0f, estimatedAmmo - 1f);
            lastShotMs = nowMs;
        }
        if (useSuper) lastSuperMs = nowMs;
        if (useHyper) lastHyperMs = nowMs;

        String reason = "practice: " + profile.label()
                + " d=" + fmt(distance)
                + " hit=" + fmt(hitChance)
                + " ammo=" + fmt(estimatedAmmo)
                + (target.observed() ? " visible" : " memory")
                + (fire ? " fire" : " hold");
        return new Action(move, aim, fire, useSuper, useHyper, reason);
    }

    private void updateAmmo(long nowMs) {
        if (lastTickMs == 0) { lastTickMs = nowMs; return; }
        float dt = Math.max(0f, Math.min(.5f, (nowMs - lastTickMs) / 1000f));
        lastTickMs = nowMs;
        float perSecond = 1f / Math.max(.2f, profile.reloadSeconds());
        estimatedAmmo = Math.min(profile.ammoCapacity(), estimatedAmmo + dt * perSecond);
        // Give a small grace period after a shot so one held gesture cannot drain the estimate
        // faster than the command cadence can observe.
        if (nowMs - lastShotMs < 140) estimatedAmmo = Math.max(0f, estimatedAmmo);
    }

    private void remember(List<AgentCore.Track> enemies, long nowMs) {
        for (AgentCore.Track enemy : enemies) {
            if (!enemy.observed()) continue;
            enemyMemory.put(enemy.id(), new Memory(enemy.id(), enemy.pos(), enemy.velocity(), enemy.acceleration(),
                    enemy.confidence(), nowMs));
        }
        long ttl = (long)(Math.max(1.0f, profile.bushMemorySeconds()) * 1000f);
        enemyMemory.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs() > ttl);
    }

    private record Target(int id, AgentCore.Vec2 pos, AgentCore.Vec2 velocity, AgentCore.Vec2 acceleration,
                          float confidence, boolean observed, long lastSeenMs) {}

    private Target selectTarget(AgentCore.Vec2 player, List<AgentCore.Track> visible, float aspect, long nowMs) {
        List<Target> candidates = new ArrayList<>();
        for (AgentCore.Track raw : visible) {
            AgentCore.Track enemy = metric(raw, aspect);
            if (enemy.confidence() < .22f) continue;
            Memory m = enemyMemory.get(enemy.id());
            candidates.add(new Target(enemy.id(), enemy.pos(), enemy.velocity(), enemy.acceleration(),
                    enemy.confidence(), enemy.observed(), m == null ? nowMs : m.lastSeenMs()));
        }
        for (Memory m : enemyMemory.values()) {
            boolean already = candidates.stream().anyMatch(t -> t.id() == m.id());
            if (already) continue;
            long age = nowMs - m.lastSeenMs();
            float fade = 1f - Math.min(1f, age / (profile.bushMemorySeconds() * 1000f));
            if (fade <= 0) continue;
            candidates.add(new Target(m.id(), metric(m.project(nowMs), aspect), metric(m.velocity(), aspect),
                    metric(m.acceleration(), aspect), m.confidence() * (.42f + .48f * fade), false, m.lastSeenMs()));
        }
        return candidates.stream().max(Comparator.comparingDouble(t -> {
            float d = t.pos().sub(player).len();
            float rangeFit = 1f - Math.min(1f, Math.abs(d - profile.preferredMaxRange()) / Math.max(.08f, profile.attackRange()));
            float sticky = t.id() == targetId ? .18f : 0f;
            return t.confidence() * 1.25f + rangeFit * .28f + sticky - d * .22f + (t.observed() ? .18f : 0f);
        })).map(t -> {
            targetId = t.id();
            return t;
        }).orElse(null);
    }

    private AgentCore.Vec2 computeAim(AgentCore.Vec2 shooter, Target target, float distance) {
        if (profile.meleeLike()) return target.pos().sub(shooter).norm();
        float speed = Math.max(.25f, profile.projectileSpeed());
        float t = Math.min(.65f, distance / speed);
        if (profile.thrower()) t = Math.min(.85f, t * 1.35f + .08f);
        AgentCore.Vec2 future = target.pos()
                .add(target.velocity().mul(t))
                .add(target.acceleration().mul(.5f * t * t));
        return future.sub(shooter).norm();
    }

    private float estimateHitChance(Target target, float distance) {
        float range = Math.max(.05f, profile.attackRange());
        float distanceScore = 1f - clamp(distance / range, 0f, 1f) * .48f;
        float motionPenalty = clamp(target.velocity().len() / Math.max(.5f, profile.projectileSpeed()), 0f, .45f);
        if (profile.meleeLike()) motionPenalty *= .3f;
        float widthBonus = clamp(profile.attackWidth() / .12f, 0f, 1f) * .18f;
        float visibility = target.observed() ? .12f : -.18f;
        return clamp(target.confidence() * .42f + distanceScore * .46f + widthBonus + visibility - motionPenalty, 0f, 1f);
    }

    private AgentCore.Vec2 movementForRange(AgentCore.Vec2 player, AgentCore.Vec2 to, float distance, float aspect) {
        AgentCore.Vec2 forward = to.norm();
        if (distance > profile.preferredMaxRange()) return edgeSafe(player, forward, aspect);
        if (distance < profile.preferredMinRange()) return edgeSafe(player, forward.mul(-1f), aspect);
        AgentCore.Vec2 tangent = new AgentCore.Vec2(-forward.y(), forward.x());
        AgentCore.Vec2 a = edgeSafe(player, tangent, aspect);
        AgentCore.Vec2 b = edgeSafe(player, tangent.mul(-1f), aspect);
        float sa = edgeScore(player.add(a.mul(.15f)), aspect);
        float sb = edgeScore(player.add(b.mul(.15f)), aspect);
        return (sa >= sb ? a : b).mul(profile.meleeLike() ? .45f : .34f);
    }

    private AgentCore.Vec2 combinedDodge(AgentCore.Track playerRaw, List<AgentCore.Track> projectiles, float aspect) {
        AgentCore.Track player = metric(playerRaw, aspect);
        AgentCore.Vec2 sum = new AgentCore.Vec2(0, 0);
        float weight = 0f;
        for (AgentCore.Track raw : projectiles) {
            AgentCore.Track shot = metric(raw, aspect);
            if (shot.confidence() < .18f || shot.velocity().len() < .08f) continue;
            AgentCore.Vec2 r = shot.pos().sub(player.pos());
            AgentCore.Vec2 v = shot.velocity().sub(player.velocity());
            float vv = Math.max(v.len2(), 1e-5f);
            float t = clamp(-r.dot(v) / vv, 0f, .60f);
            if (r.dot(v) >= 0) continue;
            AgentCore.Vec2 miss = r.add(v.mul(t));
            float radius = Math.max(.045f, player.radius() + shot.radius() + .03f);
            if (miss.len() > radius * 1.35f) continue;
            AgentCore.Vec2 dir = v.norm();
            AgentCore.Vec2 perp = new AgentCore.Vec2(-dir.y(), dir.x());
            // Choose the side that increases separation from the projectile's closest point.
            if (perp.dot(miss) < 0) perp = perp.mul(-1f);
            float urgency = (1f - t / .60f) * (1f - clamp(miss.len() / (radius * 1.35f), 0f, 1f));
            sum = sum.add(perp.mul(Math.max(.08f, urgency)));
            weight += urgency;
        }
        if (weight < .06f || sum.len() < .03f) return new AgentCore.Vec2(0, 0);
        return sum.norm();
    }

    private boolean shouldUseSuper(Target target, float distance, float hitChance, long nowMs) {
        if (nowMs - lastSuperMs < 4500) return false;
        if (distance < profile.superMinRange() || distance > profile.superRange()) return false;
        if (!target.observed() && target.confidence() < .55f) return false;
        return hitChance >= .58f;
    }

    private boolean shouldUseHypercharge(Target target, float distance, float hitChance, long nowMs) {
        if (nowMs - lastHyperMs < 9000) return false;
        if (!target.observed()) return false;
        return distance <= profile.attackRange() * 1.15f && hitChance >= .68f;
    }

    private AgentCore.Vec2 searchVector(AgentCore.Vec2 player, float aspect) {
        searchPhase = (searchPhase + 1) % 480;
        float angle = (float)(searchPhase / 480.0 * Math.PI * 2.0);
        AgentCore.Vec2 orbit = new AgentCore.Vec2((float)Math.cos(angle), (float)Math.sin(angle));
        AgentCore.Vec2 center = new AgentCore.Vec2(aspect * .5f, .50f).sub(player).norm();
        return edgeSafe(player, orbit.mul(.55f).add(center.mul(.45f)).norm(), aspect).mul(.42f);
    }

    private AgentCore.Vec2 edgeSafe(AgentCore.Vec2 player, AgentCore.Vec2 desired, float aspect) {
        AgentCore.Vec2 future = player.add(desired.mul(.16f));
        if (edgeScore(future, aspect) >= .18f) return desired;
        AgentCore.Vec2 center = new AgentCore.Vec2(aspect * .5f, .50f).sub(player).norm();
        return desired.mul(.35f).add(center.mul(.65f)).norm();
    }

    private float edgeScore(AgentCore.Vec2 p, float aspect) {
        return Math.min(Math.min(p.x(), aspect - p.x()), Math.min(p.y(), 1f - p.y()));
    }

    private static AgentCore.Track metric(AgentCore.Track t, float aspect) {
        return new AgentCore.Track(t.id(), t.kind(), metric(t.pos(), aspect), metric(t.velocity(), aspect),
                metric(t.acceleration(), aspect), t.radius(), t.confidence(), t.ageFrames(), t.observed());
    }

    private static AgentCore.Vec2 metric(AgentCore.Vec2 v, float aspect) {
        return new AgentCore.Vec2(v.x() * aspect, v.y());
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.2f", v); }
}
