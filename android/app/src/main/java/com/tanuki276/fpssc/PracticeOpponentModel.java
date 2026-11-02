package com.tanuki276.fpssc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulation/practice-only motion model. It learns coarse movement habits from perception tracks
 * and exposes multi-hypothesis predictions to the detached practice policy.
 */
final class PracticeOpponentModel {
    record Estimate(float reversalRate, float stopRate, float turnBias, float uncertainty, int samples) {}
    record Prediction(AgentCore.Vec2 point, float quality, float uncertainty) {}
    private record Hypothesis(AgentCore.Vec2 point, float weight) {}

    private static final class State {
        AgentCore.Vec2 lastPos = new AgentCore.Vec2(0, 0);
        AgentCore.Vec2 lastVelocity = new AgentCore.Vec2(0, 0);
        long lastMs;
        float reversalRate;
        float stopRate;
        float turnBias;
        float instability;
        int samples;
    }

    private final Map<Integer, State> states = new HashMap<>();

    void observe(List<AgentCore.Track> enemies, float aspect, long nowMs) {
        for (AgentCore.Track raw : enemies) {
            if (!raw.observed() || raw.confidence() < .20f) continue;
            AgentCore.Vec2 pos = metric(raw.pos(), aspect);
            AgentCore.Vec2 velocity = metric(raw.velocity(), aspect);
            State state = states.computeIfAbsent(raw.id(), ignored -> new State());

            if (state.lastMs > 0 && nowMs > state.lastMs && nowMs - state.lastMs < 700L) {
                float dt = Math.max(.015f, (nowMs - state.lastMs) / 1000f);
                float oldSpeed = state.lastVelocity.len();
                float newSpeed = velocity.len();
                float reversal = 0f;
                float turn = 0f;
                if (oldSpeed > .05f && newSpeed > .05f) {
                    AgentCore.Vec2 a = state.lastVelocity.norm();
                    AgentCore.Vec2 b = velocity.norm();
                    float dot = clamp(a.dot(b), -1f, 1f);
                    reversal = clamp((.25f - dot) / 1.25f, 0f, 1f);
                    turn = clamp(a.x() * b.y() - a.y() * b.x(), -1f, 1f);
                }
                float stopped = oldSpeed > .12f && newSpeed < .055f ? 1f : 0f;
                float jerk = velocity.sub(state.lastVelocity).len() / dt;
                float instability = clamp(jerk / 7f, 0f, 1f);

                state.reversalRate = ema(state.reversalRate, reversal, .28f);
                state.stopRate = ema(state.stopRate, stopped, .24f);
                state.turnBias = ema(state.turnBias, turn, .20f);
                state.instability = ema(state.instability, instability, .22f);
            }

            state.lastPos = pos;
            state.lastVelocity = velocity;
            state.lastMs = nowMs;
            state.samples++;
        }

        states.entrySet().removeIf(e -> e.getValue().lastMs != 0 && nowMs - e.getValue().lastMs > 4500L);
    }

    Estimate estimate(int id) {
        State s = states.get(id);
        if (s == null) return new Estimate(0f, 0f, 0f, .18f, 0);
        float confidence = clamp(s.samples / 8f, 0f, 1f);
        float uncertainty = clamp(.08f + s.instability * .56f + s.reversalRate * .22f + s.stopRate * .14f,
                .05f, .82f);
        uncertainty *= .55f + .45f * confidence;
        return new Estimate(s.reversalRate, s.stopRate, s.turnBias, uncertainty, s.samples);
    }

    Prediction predict(
            int id,
            AgentCore.Vec2 shooter,
            AgentCore.Vec2 pos,
            AgentCore.Vec2 velocity,
            AgentCore.Vec2 acceleration,
            float flightTime,
            float attackWidth
    ) {
        Estimate model = estimate(id);
        float t = clamp(flightTime, 0f, .95f);
        AgentCore.Vec2 inertial = pos.add(velocity.mul(t)).add(acceleration.mul(.5f * t * t));
        AgentCore.Vec2 underLead = pos.add(velocity.mul(t * .42f)).add(acceleration.mul(.5f * t * t * .32f));
        AgentCore.Vec2 reverse = pos.add(velocity.mul(-t * .28f));
        AgentCore.Vec2 turn = inertial;
        if (velocity.len() > .04f) {
            AgentCore.Vec2 perp = new AgentCore.Vec2(-velocity.y(), velocity.x()).norm();
            turn = inertial.add(perp.mul(model.turnBias() * velocity.len() * t * .46f));
        }

        List<Hypothesis> hypotheses = new ArrayList<>();
        float adaptive = clamp(model.samples() / 6f, 0f, 1f);
        float baseWeight = 1.20f - adaptive * (model.reversalRate() * .50f + model.stopRate() * .35f);
        hypotheses.add(new Hypothesis(inertial, Math.max(.18f, baseWeight)));
        hypotheses.add(new Hypothesis(underLead, .12f + model.stopRate() * .85f + model.uncertainty() * .22f));
        hypotheses.add(new Hypothesis(reverse, .06f + model.reversalRate() * .92f));
        if (Math.abs(model.turnBias()) > .05f)
            hypotheses.add(new Hypothesis(turn, .08f + Math.abs(model.turnBias()) * .65f));

        float total = 0f;
        for (Hypothesis h : hypotheses) total += h.weight();
        float sigma = Math.max(.018f, attackWidth * .62f + model.uncertainty() * (.035f + t * .055f));

        Hypothesis best = hypotheses.get(0);
        float bestUtility = -Float.MAX_VALUE;
        for (Hypothesis candidate : hypotheses) {
            float utility = 0f;
            for (Hypothesis possible : hypotheses) {
                float d = candidate.point().sub(possible.point()).len();
                float proximity = 1f / (1f + (d * d) / Math.max(1e-5f, sigma * sigma));
                utility += (possible.weight() / Math.max(.01f, total)) * proximity;
            }
            // Slightly prefer shorter angular corrections when two hypotheses are nearly tied.
            AgentCore.Vec2 direct = pos.sub(shooter).norm();
            AgentCore.Vec2 candidateDir = candidate.point().sub(shooter).norm();
            utility += clamp(candidateDir.dot(direct), -1f, 1f) * .025f;
            if (utility > bestUtility) {
                bestUtility = utility;
                best = candidate;
            }
        }

        float quality = clamp(bestUtility * (1f - model.uncertainty() * .38f), 0f, 1f);
        return new Prediction(best.point(), quality, model.uncertainty());
    }

    private static AgentCore.Vec2 metric(AgentCore.Vec2 v, float aspect) {
        return new AgentCore.Vec2(v.x() * aspect, v.y());
    }

    private static float ema(float oldValue, float sample, float alpha) {
        return oldValue * (1f - alpha) + sample * alpha;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
