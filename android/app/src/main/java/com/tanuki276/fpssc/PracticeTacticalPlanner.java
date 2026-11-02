package com.tanuki276.fpssc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Practice/simulation-only short-horizon planner.
 *
 * The planner evaluates candidate movement vectors over a short horizon instead of reacting to the
 * current frame only. It does not dispatch input and is intentionally detached from TouchService.
 */
final class PracticeTacticalPlanner {
    enum Mode { PRESSURE, HOLD, RETREAT, EVADE, SEARCH }

    record TargetState(
            int id,
            AgentCore.Vec2 pos,
            AgentCore.Vec2 velocity,
            AgentCore.Vec2 acceleration,
            float confidence,
            boolean observed
    ) {}

    record Plan(
            AgentCore.Vec2 move,
            Mode mode,
            float score,
            float danger,
            float rangeError,
            float fireBias,
            String detail
    ) {}

    private record Candidate(AgentCore.Vec2 move, Mode mode, float score,
                             float danger, float rangeError, float fireBias) {}

    private static final int HORIZON_STEPS = 10;
    private static final float DT = .07f;
    private static final float MOVE_SPEED = .48f;

    Plan choose(
            AgentCore.Track playerRaw,
            TargetState target,
            List<AgentCore.Track> projectileRaw,
            BrawlerProfile profile,
            float aspect,
            float ammo,
            AgentCore.Vec2 previousMove,
            PracticeOpponentModel.Estimate opponent,
            long nowMs
    ) {
        AgentCore.Track player = metric(playerRaw, aspect);
        List<AgentCore.Track> projectiles = new ArrayList<>();
        for (AgentCore.Track raw : projectileRaw) {
            AgentCore.Track shot = metric(raw, aspect);
            if (shot.confidence() >= .16f && shot.velocity().len() >= .07f) projectiles.add(shot);
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.add(evaluate(new AgentCore.Vec2(0, 0), Mode.HOLD, player, target, projectiles,
                profile, aspect, ammo, previousMove, opponent, nowMs));

        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0 / 24.0;
            AgentCore.Vec2 dir = new AgentCore.Vec2((float)Math.cos(angle), (float)Math.sin(angle));
            candidates.add(evaluate(dir, Mode.HOLD, player, target, projectiles,
                    profile, aspect, ammo, previousMove, opponent, nowMs));
        }

        AgentCore.Vec2 to = target.pos().sub(player.pos());
        if (to.len() > .01f) {
            AgentCore.Vec2 toward = to.norm();
            AgentCore.Vec2 away = toward.mul(-1f);
            AgentCore.Vec2 left = new AgentCore.Vec2(-toward.y(), toward.x());
            AgentCore.Vec2 right = left.mul(-1f);
            candidates.add(evaluate(toward, Mode.PRESSURE, player, target, projectiles,
                    profile, aspect, ammo, previousMove, opponent, nowMs));
            candidates.add(evaluate(away, Mode.RETREAT, player, target, projectiles,
                    profile, aspect, ammo, previousMove, opponent, nowMs));
            candidates.add(evaluate(left, Mode.HOLD, player, target, projectiles,
                    profile, aspect, ammo, previousMove, opponent, nowMs));
            candidates.add(evaluate(right, Mode.HOLD, player, target, projectiles,
                    profile, aspect, ammo, previousMove, opponent, nowMs));
        }

        Candidate best = candidates.stream().max(Comparator.comparingDouble(Candidate::score)).orElseThrow();
        Mode mode = best.danger() > .72f ? Mode.EVADE : best.mode();
        return new Plan(best.move(), mode, best.score(), best.danger(), best.rangeError(), best.fireBias(),
                "planner " + mode + " score=" + fmt(best.score()) + " danger=" + fmt(best.danger())
                        + " range=" + fmt(best.rangeError()) + " fireBias=" + fmt(best.fireBias()));
    }

    Plan search(AgentCore.Track playerRaw, List<AgentCore.Track> projectileRaw, float aspect,
                AgentCore.Vec2 previousMove, long nowMs) {
        AgentCore.Track player = metric(playerRaw, aspect);
        List<AgentCore.Track> projectiles = new ArrayList<>();
        for (AgentCore.Track raw : projectileRaw) {
            AgentCore.Track shot = metric(raw, aspect);
            if (shot.confidence() >= .16f && shot.velocity().len() >= .07f) projectiles.add(shot);
        }
        Candidate best = null;
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0 / 24.0;
            AgentCore.Vec2 dir = new AgentCore.Vec2((float)Math.cos(angle), (float)Math.sin(angle));
            float danger = projectileRisk(player.pos(), dir, player, projectiles);
            float edge = pathEdgeSafety(player.pos(), dir, aspect);
            float continuity = previousMove.len() > .05f ? dir.dot(previousMove.norm()) * .12f : 0f;
            float centerBias = centerBias(player.pos(), dir, aspect) * .12f;
            float phase = (float)Math.sin((nowMs / 900.0) + i * .41) * .015f;
            Candidate c = new Candidate(dir, danger > .55f ? Mode.EVADE : Mode.SEARCH,
                    -danger * 1.8f + edge * .46f + continuity + centerBias + phase,
                    danger, 0f, -.08f);
            if (best == null || c.score() > best.score()) best = c;
        }
        if (best == null) best = new Candidate(new AgentCore.Vec2(0, 0), Mode.SEARCH, 0, 0, 0, -.1f);
        return new Plan(best.move(), best.mode(), best.score(), best.danger(), 0f, best.fireBias(),
                "planner search score=" + fmt(best.score()) + " danger=" + fmt(best.danger()));
    }

    private Candidate evaluate(
            AgentCore.Vec2 move,
            Mode requestedMode,
            AgentCore.Track player,
            TargetState target,
            List<AgentCore.Track> projectiles,
            BrawlerProfile profile,
            float aspect,
            float ammo,
            AgentCore.Vec2 previousMove,
            PracticeOpponentModel.Estimate opponent,
            long nowMs
    ) {
        float danger = projectileRisk(player.pos(), move, player, projectiles);
        float edgeSafety = pathEdgeSafety(player.pos(), move, aspect);
        float rangeError = rangeError(player.pos(), move, target, profile);
        float engagement = engagementValue(player.pos(), move, target, profile, opponent);
        float continuity = previousMove.len() > .05f && move.len() > .05f
                ? move.norm().dot(previousMove.norm()) : 0f;
        float ammoPressure = ammoPressure(ammo, profile);
        float futureLine = futureLineQuality(player.pos(), move, target, profile, opponent);
        float unpredictability = movementUnpredictability(move, previousMove, nowMs);

        float score = engagement * 1.28f
                + futureLine * .62f
                + edgeSafety * .38f
                + continuity * .10f
                + unpredictability * .08f
                - danger * 2.20f
                - Math.abs(rangeError) * .72f
                - ammoPressure * Math.max(0f, -.15f + engagement * -.05f);

        Mode mode = requestedMode;
        if (danger > .58f) mode = Mode.EVADE;
        else if (rangeError > .18f) mode = Mode.PRESSURE;
        else if (rangeError < -.18f) mode = Mode.RETREAT;
        else if (move.len() < .05f) mode = Mode.HOLD;

        float fireBias = clamp(engagement * .18f + futureLine * .16f
                - danger * .12f - ammoPressure * .14f - opponent.uncertainty() * .10f,
                -.22f, .20f);

        return new Candidate(move, mode, score, danger, rangeError, fireBias);
    }

    private float projectileRisk(AgentCore.Vec2 start, AgentCore.Vec2 move,
                                 AgentCore.Track player, List<AgentCore.Track> projectiles) {
        if (projectiles.isEmpty()) return 0f;
        float risk = 0f;
        float maxSingle = 0f;
        for (int step = 1; step <= HORIZON_STEPS; step++) {
            float t = step * DT;
            float accelRamp = .72f + .28f * (step / (float)HORIZON_STEPS);
            AgentCore.Vec2 playerFuture = start
                    .add(move.mul(MOVE_SPEED * t * accelRamp))
                    .add(player.velocity().mul(Math.min(t, .12f) * .12f));
            for (AgentCore.Track shot : projectiles) {
                AgentCore.Vec2 shotFuture = shot.pos()
                        .add(shot.velocity().mul(t))
                        .add(shot.acceleration().mul(.5f * t * t));
                float collision = Math.max(.040f, player.radius() + shot.radius() + .022f);
                float d = shotFuture.sub(playerFuture).len();
                float shell = collision * 2.45f;
                if (d >= shell) continue;
                float proximity = 1f - clamp(d / shell, 0f, 1f);
                float temporal = 1f - (step - 1f) / HORIZON_STEPS;
                float single = proximity * (.54f + .46f * temporal) * Math.max(.22f, shot.confidence());
                risk += single * .42f;
                maxSingle = Math.max(maxSingle, single);
            }
        }
        return clamp(maxSingle * .72f + risk * .28f, 0f, 1.8f);
    }

    private float pathEdgeSafety(AgentCore.Vec2 start, AgentCore.Vec2 move, float aspect) {
        float worst = Float.MAX_VALUE;
        for (int step = 2; step <= HORIZON_STEPS; step += 2) {
            float t = step * DT;
            AgentCore.Vec2 p = start.add(move.mul(MOVE_SPEED * t));
            float edge = Math.min(Math.min(p.x(), aspect - p.x()), Math.min(p.y(), 1f - p.y()));
            worst = Math.min(worst, edge);
        }
        return clamp(worst / .22f, -1f, 1f);
    }

    private float rangeError(AgentCore.Vec2 start, AgentCore.Vec2 move,
                             TargetState target, BrawlerProfile profile) {
        float targetRange = profile.meleeLike()
                ? profile.attackRange() * .70f
                : (profile.preferredMinRange() + profile.preferredMaxRange()) * .5f;
        float sum = 0f;
        for (int step = 4; step <= HORIZON_STEPS; step += 3) {
            float t = step * DT;
            AgentCore.Vec2 playerFuture = start.add(move.mul(MOVE_SPEED * t));
            AgentCore.Vec2 targetFuture = target.pos()
                    .add(target.velocity().mul(t))
                    .add(target.acceleration().mul(.5f * t * t));
            float d = targetFuture.sub(playerFuture).len();
            sum += (d - targetRange) / Math.max(.07f, profile.attackRange());
        }
        return clamp(sum / 3f, -1.3f, 1.3f);
    }

    private float engagementValue(AgentCore.Vec2 start, AgentCore.Vec2 move,
                                  TargetState target, BrawlerProfile profile,
                                  PracticeOpponentModel.Estimate opponent) {
        float value = 0f;
        float width = Math.max(.025f, profile.attackWidth());
        for (int step = 3; step <= HORIZON_STEPS; step += 2) {
            float t = step * DT;
            AgentCore.Vec2 playerFuture = start.add(move.mul(MOVE_SPEED * t));
            AgentCore.Vec2 targetFuture = target.pos()
                    .add(target.velocity().mul(t))
                    .add(target.acceleration().mul(.5f * t * t));
            float d = targetFuture.sub(playerFuture).len();
            float rangeFit = 1f - clamp(Math.abs(d - profile.preferredMaxRange())
                    / Math.max(.08f, profile.attackRange()), 0f, 1f);
            float inRange = d <= profile.attackRange() * 1.02f ? 1f : 0f;
            float confidence = target.confidence() * (target.observed() ? 1f : .72f);
            float precision = clamp(width / (.06f + opponent.uncertainty() * .08f), .35f, 1.4f);
            value += (rangeFit * .45f + inRange * .40f + precision * .15f) * confidence;
        }
        return clamp(value / 4f, 0f, 1.35f);
    }

    private float futureLineQuality(AgentCore.Vec2 start, AgentCore.Vec2 move,
                                    TargetState target, BrawlerProfile profile,
                                    PracticeOpponentModel.Estimate opponent) {
        float t = profile.thrower() ? .56f : .40f;
        AgentCore.Vec2 playerFuture = start.add(move.mul(MOVE_SPEED * t));
        AgentCore.Vec2 targetFuture = target.pos()
                .add(target.velocity().mul(t))
                .add(target.acceleration().mul(.5f * t * t));
        float d = targetFuture.sub(playerFuture).len();
        float distanceQuality = 1f - clamp(d / Math.max(.08f, profile.attackRange() * 1.08f), 0f, 1f);
        float uncertaintyQuality = 1f - opponent.uncertainty();
        return clamp(distanceQuality * .62f + uncertaintyQuality * .38f, 0f, 1f);
    }

    private float ammoPressure(float ammo, BrawlerProfile profile) {
        float normalized = clamp(ammo / Math.max(1f, profile.ammoCapacity()), 0f, 1f);
        return 1f - normalized;
    }

    private float movementUnpredictability(AgentCore.Vec2 move, AgentCore.Vec2 previousMove, long nowMs) {
        if (move.len() < .05f) return -.08f;
        if (previousMove.len() < .05f) return .03f;
        float turn = 1f - clamp(move.norm().dot(previousMove.norm()), -1f, 1f);
        float phase = (float)Math.sin(nowMs / 610.0) * .5f + .5f;
        return clamp(turn * .08f + phase * .025f, 0f, .12f);
    }

    private float centerBias(AgentCore.Vec2 player, AgentCore.Vec2 move, float aspect) {
        AgentCore.Vec2 center = new AgentCore.Vec2(aspect * .5f, .50f);
        AgentCore.Vec2 toCenter = center.sub(player);
        if (toCenter.len() < .01f) return 0f;
        return move.dot(toCenter.norm());
    }

    private static AgentCore.Track metric(AgentCore.Track t, float aspect) {
        return new AgentCore.Track(t.id(), t.kind(), metric(t.pos(), aspect), metric(t.velocity(), aspect),
                metric(t.acceleration(), aspect), t.radius(), t.confidence(), t.ageFrames(), t.observed());
    }

    private static AgentCore.Vec2 metric(AgentCore.Vec2 v, float aspect) {
        return new AgentCore.Vec2(v.x() * aspect, v.y());
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
