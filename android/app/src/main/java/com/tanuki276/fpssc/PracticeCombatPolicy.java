package com.tanuki276.fpssc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Practice/simulation combat controller. This policy remains detached from the live input backend.
 * It focuses on deterministic training-quality decisions: target memory, range control, predictive
 * aiming, ammo pacing, multi-projectile evasion, and conservative ability recommendations.
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

    private record Target(
            int id,
            AgentCore.Vec2 pos,
            AgentCore.Vec2 velocity,
            AgentCore.Vec2 acceleration,
            float confidence,
            boolean observed,
            long lastSeenMs
    ) {}

    private record DodgeCandidate(AgentCore.Vec2 direction, float score) {}

    private final Map<Integer, Memory> enemyMemory = new HashMap<>();
    private BrawlerProfile profile = BrawlerProfile.byId(BrawlerProfile.DEFAULT_ID);
    private int targetId = -1;
    private AgentCore.Vec2 previousAim = new AgentCore.Vec2(0, 0);
    private AgentCore.Vec2 previousMove = new AgentCore.Vec2(0, 0);
    private int stableAimFrames;
    private long lastShotMs;
    private float estimatedAmmo;
    private long lastTickMs;
    private long lastSuperMs;
    private long lastHyperMs;
    private int searchPhase;
    private AgentCore.Vec2 lastKnownEnemy = new AgentCore.Vec2(0, 0);
    private long lastKnownEnemyMs;

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
        previousMove = new AgentCore.Vec2(0, 0);
        stableAimFrames = 0;
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

        AgentCore.Vec2 dodge = bestDodge(player, world.projectiles(), aspect);
        if (dodge.len() > .1f) {
            previousMove = dodge;
            return new Action(dodge, previousAim, false, false, false, "practice: predictive multi-projectile dodge");
        }

        Target target = selectTarget(playerNow, world.enemies(), aspect, nowMs);
        if (target == null) {
            stableAimFrames = Math.max(0, stableAimFrames - 1);
            AgentCore.Vec2 search = searchVector(playerNow, aspect, nowMs);
            previousMove = search;
            return new Action(search, previousAim, false, false, false, "practice: search / patrol");
        }

        if (target.observed()) {
            lastKnownEnemy = target.pos();
            lastKnownEnemyMs = nowMs;
        }

        AgentCore.Vec2 toTarget = target.pos().sub(playerNow);
        float distance = toTarget.len();
        AgentCore.Vec2 rawAim = computeAim(playerNow, target, distance);
        float aimBlend = target.observed() ? .78f : .56f;
        AgentCore.Vec2 aim = previousAim.len() > .05f
                ? previousAim.mul(1f - aimBlend).add(rawAim.mul(aimBlend)).norm()
                : rawAim;
        float agreement = previousAim.len() > .05f ? previousAim.dot(aim) : 1f;
        stableAimFrames = agreement > .975f ? stableAimFrames + 1 : Math.max(0, stableAimFrames - 1);
        previousAim = aim;

        float hitChance = estimateHitChance(target, distance, stableAimFrames);
        float effectiveRange = profile.attackRange() * (target.observed() ? 1f : .90f);
        boolean inRange = distance <= effectiveRange;
        boolean hasAmmo = estimatedAmmo >= .92f;
        boolean cadenceReady = nowMs - lastShotMs >= minimumShotIntervalMs();
        boolean fire = inRange && hasAmmo && cadenceReady && hitChance >= profile.fireThreshold();

        if (!target.observed()) {
            long hiddenMs = nowMs - target.lastSeenMs();
            float uncertainty = hiddenUncertainty(hiddenMs);
            fire = fire
                    && hiddenMs <= (long)(profile.bushMemorySeconds() * 1000f)
                    && distance <= profile.attackRange() * (.90f - uncertainty * .15f)
                    && target.confidence() >= .38f
                    && stableAimFrames >= 1;
        }

        AgentCore.Vec2 move = movementForRange(playerNow, toTarget, distance, aspect, target, nowMs);
        previousMove = move;
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
                + " stable=" + stableAimFrames
                + (target.observed() ? " visible" : " memory")
                + (fire ? " fire" : " hold");
        return new Action(move, aim, fire, useSuper, useHyper, reason);
    }

    private long minimumShotIntervalMs() {
        return switch (profile.attackMode()) {
            case MELEE, DASH -> 230L;
            case SPREAD -> 260L;
            case THROWER -> 360L;
            case PROJECTILE -> 290L;
        };
    }

    private void updateAmmo(long nowMs) {
        if (lastTickMs == 0) { lastTickMs = nowMs; return; }
        float dt = Math.max(0f, Math.min(.5f, (nowMs - lastTickMs) / 1000f));
        lastTickMs = nowMs;
        float perSecond = 1f / Math.max(.2f, profile.reloadSeconds());
        estimatedAmmo = Math.min(profile.ammoCapacity(), estimatedAmmo + dt * perSecond);
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

        Target best = candidates.stream().max(Comparator.comparingDouble(t -> targetScore(player, t))).orElse(null);
        if (best != null) {
            if (targetId != best.id()) stableAimFrames = 0;
            targetId = best.id();
        }
        return best;
    }

    private float targetScore(AgentCore.Vec2 player, Target t) {
        float d = t.pos().sub(player).len();
        float preferred = (profile.preferredMinRange() + profile.preferredMaxRange()) * .5f;
        float rangeFit = 1f - Math.min(1f, Math.abs(d - preferred) / Math.max(.08f, profile.attackRange()));
        float velocityThreat = clamp(t.velocity().len() / .9f, 0f, 1f);
        float sticky = t.id() == targetId ? .28f : 0f;
        float visible = t.observed() ? .20f : -.08f;
        float closeThreat = 1f - clamp(d / Math.max(.16f, profile.attackRange()), 0f, 1f);
        return t.confidence() * 1.35f + rangeFit * .34f + sticky + visible
                + closeThreat * .22f + velocityThreat * .08f - d * .20f;
    }

    private AgentCore.Vec2 computeAim(AgentCore.Vec2 shooter, Target target, float distance) {
        if (profile.meleeLike()) return target.pos().sub(shooter).norm();

        float speed = Math.max(.25f, profile.projectileSpeed());
        float maxT = profile.thrower() ? .90f : .70f;
        float t = clamp(distance / speed, 0f, maxT);
        for (int i = 0; i < 5; i++) {
            AgentCore.Vec2 future = target.pos()
                    .add(target.velocity().mul(t))
                    .add(target.acceleration().mul(.5f * t * t));
            float flight = future.sub(shooter).len() / speed;
            if (profile.thrower()) flight = flight * 1.20f + .06f;
            t = clamp(t * .30f + flight * .70f, 0f, maxT);
        }
        AgentCore.Vec2 future = target.pos()
                .add(target.velocity().mul(t))
                .add(target.acceleration().mul(.5f * t * t));
        return future.sub(shooter).norm();
    }

    private float estimateHitChance(Target target, float distance, int stableFrames) {
        float range = Math.max(.05f, profile.attackRange());
        float distanceRatio = clamp(distance / range, 0f, 1.25f);
        float distanceScore = 1f - clamp(distanceRatio * .50f, 0f, .62f);
        float speed = Math.max(.5f, profile.meleeLike() ? 3f : profile.projectileSpeed());
        float lateralMotion = clamp(target.velocity().len() / speed, 0f, .50f);
        float accelPenalty = clamp(target.acceleration().len() / 4f, 0f, .22f);
        if (profile.meleeLike()) {
            lateralMotion *= .20f;
            accelPenalty *= .20f;
        }
        float widthBonus = clamp(profile.attackWidth() / .12f, 0f, 1f) * .20f;
        float visibility = target.observed() ? .12f : -.20f;
        float aimStability = clamp(stableFrames / 4f, 0f, 1f) * .10f;
        return clamp(target.confidence() * .40f + distanceScore * .43f + widthBonus + visibility
                + aimStability - lateralMotion - accelPenalty, 0f, 1f);
    }

    private float hiddenUncertainty(long hiddenMs) {
        float horizon = Math.max(250f, profile.bushMemorySeconds() * 1000f);
        return clamp(hiddenMs / horizon, 0f, 1f);
    }

    private AgentCore.Vec2 movementForRange(
            AgentCore.Vec2 player,
            AgentCore.Vec2 to,
            float distance,
            float aspect,
            Target target,
            long nowMs
    ) {
        AgentCore.Vec2 forward = to.norm();
        float min = profile.preferredMinRange();
        float max = profile.preferredMaxRange();

        if (profile.meleeLike()) {
            if (distance > profile.attackRange() * .92f) return edgeSafe(player, forward, aspect);
            AgentCore.Vec2 tangent = new AgentCore.Vec2(-forward.y(), forward.x());
            return chooseStrafe(player, tangent, aspect, nowMs).mul(.48f);
        }

        if (distance > max) return edgeSafe(player, forward, aspect);
        if (distance < min) return edgeSafe(player, forward.mul(-1f), aspect);

        AgentCore.Vec2 tangent = new AgentCore.Vec2(-forward.y(), forward.x());
        AgentCore.Vec2 strafe = chooseStrafe(player, tangent, aspect, nowMs);
        float rangeCenter = (min + max) * .5f;
        float radialError = clamp((distance - rangeCenter) / Math.max(.08f, max - min), -1f, 1f);
        AgentCore.Vec2 radial = forward.mul(radialError * .22f);
        float confidenceScale = target.observed() ? 1f : .72f;
        return edgeSafe(player, strafe.mul(.38f).add(radial).norm(), aspect).mul(.36f * confidenceScale);
    }

    private AgentCore.Vec2 chooseStrafe(AgentCore.Vec2 player, AgentCore.Vec2 tangent, float aspect, long nowMs) {
        AgentCore.Vec2 a = edgeSafe(player, tangent, aspect);
        AgentCore.Vec2 b = edgeSafe(player, tangent.mul(-1f), aspect);
        float sa = edgeScore(player.add(a.mul(.17f)), aspect) + a.dot(previousMove.norm()) * .08f;
        float sb = edgeScore(player.add(b.mul(.17f)), aspect) + b.dot(previousMove.norm()) * .08f;
        // A slow deterministic phase prevents endless one-direction circles in simulations.
        if (((nowMs / 850L) & 1L) == 1L) { sa += .025f; sb -= .025f; }
        return sa >= sb ? a : b;
    }

    private AgentCore.Vec2 bestDodge(AgentCore.Track playerRaw, List<AgentCore.Track> projectileRaw, float aspect) {
        if (projectileRaw.isEmpty()) return new AgentCore.Vec2(0, 0);
        AgentCore.Track player = metric(playerRaw, aspect);
        List<AgentCore.Track> projectiles = new ArrayList<>();
        for (AgentCore.Track raw : projectileRaw) {
            AgentCore.Track shot = metric(raw, aspect);
            if (shot.confidence() >= .18f && shot.velocity().len() >= .08f) projectiles.add(shot);
        }
        if (projectiles.isEmpty()) return new AgentCore.Vec2(0, 0);

        float stayRisk = pathRisk(player.pos(), new AgentCore.Vec2(0, 0), player, projectiles);
        if (stayRisk < .24f) return new AgentCore.Vec2(0, 0);

        List<DodgeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double angle = i * Math.PI * 2.0 / 20.0;
            AgentCore.Vec2 dir = new AgentCore.Vec2((float)Math.cos(angle), (float)Math.sin(angle));
            float risk = pathRisk(player.pos(), dir, player, projectiles);
            float edge = edgeScore(player.pos().add(dir.mul(.18f)), aspect);
            float continuity = previousMove.len() > .1f ? dir.dot(previousMove.norm()) * .08f : 0f;
            float score = -risk + clamp(edge / .20f, -1f, 1f) * .18f + continuity;
            candidates.add(new DodgeCandidate(dir, score));
        }
        DodgeCandidate best = candidates.stream().max(Comparator.comparingDouble(DodgeCandidate::score)).orElse(null);
        if (best == null) return new AgentCore.Vec2(0, 0);
        return best.direction();
    }

    private float pathRisk(
            AgentCore.Vec2 start,
            AgentCore.Vec2 move,
            AgentCore.Track player,
            List<AgentCore.Track> projectiles
    ) {
        float risk = 0f;
        for (int step = 1; step <= 6; step++) {
            float t = step * .08f;
            AgentCore.Vec2 playerFuture = start.add(move.mul(.42f * t));
            for (AgentCore.Track shot : projectiles) {
                AgentCore.Vec2 shotFuture = shot.pos()
                        .add(shot.velocity().mul(t))
                        .add(shot.acceleration().mul(.5f * t * t));
                float radius = Math.max(.042f, player.radius() + shot.radius() + .025f);
                float d = shotFuture.sub(playerFuture).len();
                if (d < radius * 2.1f) {
                    float proximity = 1f - clamp(d / (radius * 2.1f), 0f, 1f);
                    float early = 1f - (step - 1) / 6f;
                    risk += proximity * (.55f + .45f * early) * Math.max(.25f, shot.confidence());
                }
            }
        }
        return risk;
    }

    private boolean shouldUseSuper(Target target, float distance, float hitChance, long nowMs) {
        if (nowMs - lastSuperMs < 4500) return false;
        if (distance < profile.superMinRange() || distance > profile.superRange()) return false;
        if (!target.observed() && target.confidence() < .58f) return false;
        return hitChance >= .62f && stableAimFrames >= 1;
    }

    private boolean shouldUseHypercharge(Target target, float distance, float hitChance, long nowMs) {
        if (nowMs - lastHyperMs < 9000) return false;
        if (!target.observed()) return false;
        return distance <= profile.attackRange() * 1.08f && hitChance >= .72f && stableAimFrames >= 2;
    }

    private AgentCore.Vec2 searchVector(AgentCore.Vec2 player, float aspect, long nowMs) {
        if (lastKnownEnemyMs != 0 && nowMs - lastKnownEnemyMs < 2400 && lastKnownEnemy.len() > .01f) {
            AgentCore.Vec2 towardLast = lastKnownEnemy.sub(player);
            if (towardLast.len() > .08f) return edgeSafe(player, towardLast.norm(), aspect).mul(.46f);
        }
        searchPhase = (searchPhase + 1) % 480;
        float angle = (float)(searchPhase / 480.0 * Math.PI * 2.0);
        AgentCore.Vec2 orbit = new AgentCore.Vec2((float)Math.cos(angle), (float)Math.sin(angle));
        AgentCore.Vec2 center = new AgentCore.Vec2(aspect * .5f, .50f).sub(player).norm();
        return edgeSafe(player, orbit.mul(.52f).add(center.mul(.48f)).norm(), aspect).mul(.42f);
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
