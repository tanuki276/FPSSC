package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only perception + decision core.
 *
 * Accuracy goals:
 *  - connected components instead of overlapping fixed tiles
 *  - stable multi-frame tracks with prediction and confidence decay
 *  - velocity smoothing instead of raw frame-to-frame differencing
 *  - time-to-closest-approach projectile threat scoring
 *  - intercept-based aim prediction
 *
 * This class only emits ControlCommand objects. It does not inject input.
 */
final class AgentCore {
    record Vec2(float x, float y) {
        Vec2 add(Vec2 o) { return new Vec2(x + o.x, y + o.y); }
        Vec2 sub(Vec2 o) { return new Vec2(x - o.x, y - o.y); }
        Vec2 mul(float s) { return new Vec2(x * s, y * s); }
        float dot(Vec2 o) { return x * o.x + y * o.y; }
        float len2() { return x * x + y * y; }
        float len() { return (float) Math.sqrt(len2()); }
        Vec2 norm() { float l = len(); return l < 1e-5f ? new Vec2(0, 0) : mul(1f / l); }
        Vec2 clampLen(float max) { float l = len(); return l <= max || l < 1e-5f ? this : mul(max / l); }
    }

    enum Kind { PLAYER, ENEMY, PROJECTILE }

    record Detection(Kind kind, Vec2 pos, float radius, float confidence) {}

    record Track(
            int id,
            Kind kind,
            Vec2 pos,
            Vec2 velocity,
            float radius,
            float confidence,
            int ageFrames,
            boolean observed
    ) {}

    record WorldState(
            Track player,
            List<Track> enemies,
            List<Track> projectiles,
            long frameTimeMs,
            float quality
    ) {}

    record ControlCommand(Vec2 move, Vec2 aim, boolean fire, boolean superAttack, String reason) {
        static ControlCommand idle(String reason) {
            return new ControlCommand(new Vec2(0, 0), new Vec2(0, 0), false, false, reason);
        }
    }

    private final VisionPipeline vision = new VisionPipeline();
    private final TacticalPolicy policy = new TacticalPolicy();

    ControlCommand analyze(Bitmap frame, long nowMs) {
        return policy.decide(vision.detect(frame, nowMs));
    }

    static final class VisionPipeline {
        private final TrackManager playerTracks = new TrackManager(Kind.PLAYER, 0.13f, 2);
        private final TrackManager enemyTracks = new TrackManager(Kind.ENEMY, 0.18f, 4);
        private final TrackManager projectileTracks = new TrackManager(Kind.PROJECTILE, 0.22f, 3);
        private long previousFrameMs;

        WorldState detect(Bitmap b, long nowMs) {
            if (b == null || b.getWidth() < 96 || b.getHeight() < 96) {
                return new WorldState(null, List.of(), List.of(), nowMs, 0f);
            }

            float dt = previousFrameMs == 0 ? 1f / 30f : clamp((nowMs - previousFrameMs) / 1000f, 1f / 120f, 0.20f);
            previousFrameMs = nowMs;

            List<Detection> playerDetections = connectedComponents(b, Kind.PLAYER, 0.12f, 0.88f, 0.12f, 0.90f, 4);
            List<Detection> enemyDetections = connectedComponents(b, Kind.ENEMY, 0.03f, 0.97f, 0.04f, 0.94f, 12);
            List<Detection> projectileDetections = connectedComponents(b, Kind.PROJECTILE, 0.02f, 0.98f, 0.04f, 0.96f, 24);

            // Player detections receive a center prior. This prevents bright UI fragments near
            // the edges from stealing the player track while preserving camera movement.
            playerDetections.sort(Comparator.comparingDouble(d -> -playerScore(d)));
            if (playerDetections.size() > 2) {
                playerDetections = new ArrayList<>(playerDetections.subList(0, 2));
            }

            List<Track> players = playerTracks.update(playerDetections, dt);
            List<Track> enemies = enemyTracks.update(enemyDetections, dt);
            List<Track> projectiles = projectileTracks.update(projectileDetections, dt);

            Track player = players.stream()
                    .filter(t -> t.confidence() >= 0.25f)
                    .max(Comparator.comparingDouble(t -> t.confidence() + centerPrior(t.pos()) * 0.25f))
                    .orElse(null);

            enemies = visibleTracks(enemies, 0.28f, 12);
            projectiles = visibleTracks(projectiles, 0.22f, 24);

            float quality = player == null ? 0f : player.confidence();
            if (!enemies.isEmpty()) quality = clamp(quality * 0.70f + meanConfidence(enemies) * 0.30f, 0f, 1f);
            return new WorldState(player, enemies, projectiles, nowMs, quality);
        }

        private float playerScore(Detection d) {
            return d.confidence() * 0.72f + centerPrior(d.pos()) * 0.28f;
        }

        private static float centerPrior(Vec2 p) {
            float dx = p.x() - 0.5f;
            float dy = p.y() - 0.52f;
            return clamp(1f - (float) Math.sqrt(dx * dx + dy * dy) / 0.70f, 0f, 1f);
        }

        private static List<Track> visibleTracks(List<Track> tracks, float minConfidence, int max) {
            List<Track> out = new ArrayList<>();
            for (Track t : tracks) {
                if (t.confidence() >= minConfidence) out.add(t);
            }
            out.sort(Comparator.comparingDouble((Track t) -> t.confidence()).reversed());
            if (out.size() > max) return new ArrayList<>(out.subList(0, max));
            return out;
        }

        private static float meanConfidence(List<Track> tracks) {
            float sum = 0;
            for (Track t : tracks) sum += t.confidence();
            return tracks.isEmpty() ? 0f : sum / tracks.size();
        }

        /**
         * Sparse connected-components detector. It samples the frame on a regular grid, then
         * groups spatially connected matching samples. Unlike the old overlapping-tile detector,
         * each visual object normally yields one candidate instead of several duplicates.
         */
        private List<Detection> connectedComponents(
                Bitmap b, Kind kind,
                float x0n, float x1n, float y0n, float y1n,
                int max
        ) {
            int w = b.getWidth(), h = b.getHeight();
            int x0 = Math.max(0, Math.round(w * x0n));
            int x1 = Math.min(w, Math.round(w * x1n));
            int y0 = Math.max(0, Math.round(h * y0n));
            int y1 = Math.min(h, Math.round(h * y1n));

            int step = Math.max(2, Math.round(w / 220f));
            int gw = Math.max(1, (x1 - x0 + step - 1) / step);
            int gh = Math.max(1, (y1 - y0 + step - 1) / step);
            byte[] mask = new byte[gw * gh];

            for (int gy = 0; gy < gh; gy++) {
                int y = Math.min(h - 1, y0 + gy * step);
                for (int gx = 0; gx < gw; gx++) {
                    int x = Math.min(w - 1, x0 + gx * step);
                    if (matches(b.getPixel(x, y), kind)) mask[gy * gw + gx] = 1;
                }
            }

            boolean[] seen = new boolean[mask.length];
            int[] q = new int[mask.length];
            List<Detection> out = new ArrayList<>();
            int minCells = switch (kind) {
                case PLAYER -> 9;
                case ENEMY -> 7;
                case PROJECTILE -> 2;
            };

            for (int start = 0; start < mask.length; start++) {
                if (mask[start] == 0 || seen[start]) continue;

                int head = 0, tail = 0;
                q[tail++] = start;
                seen[start] = true;
                int count = 0;
                long sx = 0, sy = 0;
                int minGx = gw, maxGx = 0, minGy = gh, maxGy = 0;

                while (head < tail) {
                    int p = q[head++];
                    int gx = p % gw;
                    int gy = p / gw;
                    count++;
                    sx += gx;
                    sy += gy;
                    minGx = Math.min(minGx, gx); maxGx = Math.max(maxGx, gx);
                    minGy = Math.min(minGy, gy); maxGy = Math.max(maxGy, gy);

                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (ox == 0 && oy == 0) continue;
                            int nx = gx + ox, ny = gy + oy;
                            if (nx < 0 || nx >= gw || ny < 0 || ny >= gh) continue;
                            int np = ny * gw + nx;
                            if (mask[np] != 0 && !seen[np]) {
                                seen[np] = true;
                                q[tail++] = np;
                            }
                        }
                    }
                }

                if (count < minCells) continue;

                float cx = (x0 + (sx / (float) count) * step) / w;
                float cy = (y0 + (sy / (float) count) * step) / h;
                float bw = Math.max(step, (maxGx - minGx + 1) * step) / (float) w;
                float bh = Math.max(step, (maxGy - minGy + 1) * step) / (float) h;
                float radius = 0.5f * (float) Math.sqrt(bw * bw + bh * bh);
                float bboxCells = Math.max(1f, (maxGx - minGx + 1f) * (maxGy - minGy + 1f));
                float fill = count / bboxCells;
                float sizeScore = switch (kind) {
                    case PLAYER -> smoothBand(radius, 0.020f, 0.095f);
                    case ENEMY -> smoothBand(radius, 0.014f, 0.105f);
                    case PROJECTILE -> smoothBand(radius, 0.003f, 0.050f);
                };
                float densityScore = clamp(fill * 1.5f, 0f, 1f);
                float countScore = clamp(count / (kind == Kind.PROJECTILE ? 10f : 28f), 0f, 1f);
                float confidence = clamp(sizeScore * 0.30f + densityScore * 0.35f + countScore * 0.35f, 0f, 1f);

                out.add(new Detection(kind, new Vec2(cx, cy), radius, confidence));
            }

            out.sort(Comparator.comparingDouble((Detection d) -> d.confidence()).reversed());
            if (out.size() > max) return new ArrayList<>(out.subList(0, max));
            return out;
        }

        private static float smoothBand(float value, float lo, float hi) {
            if (value < lo * 0.45f || value > hi * 1.8f) return 0.05f;
            if (value >= lo && value <= hi) return 1f;
            if (value < lo) return clamp((value - lo * 0.45f) / (lo * 0.55f), 0.05f, 1f);
            return clamp(1f - (value - hi) / (hi * 0.8f), 0.05f, 1f);
        }

        /** Color model expressed as brightness/chroma relationships rather than one hard RGB cut. */
        private boolean matches(int c, Kind kind) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int chroma = max - min;
            int sum = r + g + b;
            if (max < 65 || sum < 190 || chroma < 22) return false;

            return switch (kind) {
                case PLAYER ->
                        g >= 120 && b >= 92 && g > r + 14 && b > r + 4 && (g + b) > r * 2 + 55;
                case ENEMY ->
                        r >= 145 && r > g + 22 && r > b + 18 && (r - Math.min(g, b)) >= 35;
                case PROJECTILE ->
                        r >= 165 && g >= 120 && b <= 155 && r + g > b * 2 + 115 && Math.abs(r - g) < 115;
            };
        }
    }

    /** Lightweight alpha-beta-style multi-object tracker with gated association. */
    static final class TrackManager {
        private static final float POSITION_ALPHA = 0.72f;
        private static final float VELOCITY_ALPHA = 0.38f;

        private final Kind kind;
        private final float gate;
        private final int maxMisses;
        private final List<MutableTrack> tracks = new ArrayList<>();
        private int nextId = 1;

        TrackManager(Kind kind, float gate, int maxMisses) {
            this.kind = kind;
            this.gate = gate;
            this.maxMisses = maxMisses;
        }

        List<Track> update(List<Detection> detections, float dt) {
            for (MutableTrack t : tracks) {
                t.predicted = t.pos.add(t.velocity.mul(dt));
                t.matched = false;
            }

            Set<Integer> used = new HashSet<>();
            // High-confidence observations claim tracks first.
            List<Detection> ordered = new ArrayList<>(detections);
            ordered.sort(Comparator.comparingDouble((Detection d) -> d.confidence()).reversed());

            for (Detection d : ordered) {
                int bestIndex = -1;
                float bestCost = Float.MAX_VALUE;
                for (int i = 0; i < tracks.size(); i++) {
                    if (used.contains(i)) continue;
                    MutableTrack t = tracks.get(i);
                    float distance = d.pos().sub(t.predicted).len();
                    float dynamicGate = gate + Math.min(0.10f, t.velocity.len() * dt * 1.8f);
                    if (distance > dynamicGate) continue;
                    float sizePenalty = Math.abs(d.radius() - t.radius) * 0.55f;
                    float cost = distance + sizePenalty - d.confidence() * 0.025f;
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestIndex = i;
                    }
                }

                if (bestIndex >= 0) {
                    used.add(bestIndex);
                    tracks.get(bestIndex).correct(d, dt);
                } else if (d.confidence() >= 0.32f) {
                    MutableTrack n = new MutableTrack(nextId++, kind, d);
                    n.matched = true;
                    tracks.add(n);
                    used.add(tracks.size() - 1);
                }
            }

            for (MutableTrack t : tracks) {
                if (!t.matched) t.miss(dt);
            }
            tracks.removeIf(t -> t.misses > maxMisses || t.confidence < 0.10f || outOfBounds(t.pos));

            List<Track> out = new ArrayList<>(tracks.size());
            for (MutableTrack t : tracks) out.add(t.snapshot());
            return out;
        }

        private static boolean outOfBounds(Vec2 p) {
            return p.x() < -0.15f || p.x() > 1.15f || p.y() < -0.15f || p.y() > 1.15f;
        }

        private static final class MutableTrack {
            final int id;
            final Kind kind;
            Vec2 pos;
            Vec2 predicted;
            Vec2 velocity = new Vec2(0, 0);
            float radius;
            float confidence;
            int ageFrames = 1;
            int misses;
            boolean matched;

            MutableTrack(int id, Kind kind, Detection d) {
                this.id = id;
                this.kind = kind;
                this.pos = d.pos();
                this.predicted = d.pos();
                this.radius = d.radius();
                this.confidence = d.confidence();
            }

            void correct(Detection d, float dt) {
                Vec2 innovation = d.pos().sub(predicted);
                Vec2 observedVelocity = d.pos().sub(pos).mul(1f / Math.max(dt, 1f / 120f));
                pos = predicted.add(innovation.mul(POSITION_ALPHA));
                velocity = velocity.mul(1f - VELOCITY_ALPHA).add(observedVelocity.mul(VELOCITY_ALPHA)).clampLen(4f);
                radius = radius * 0.65f + d.radius() * 0.35f;
                confidence = clamp(confidence * 0.58f + d.confidence() * 0.52f + 0.05f, 0f, 1f);
                ageFrames++;
                misses = 0;
                matched = true;
            }

            void miss(float dt) {
                pos = predicted;
                velocity = velocity.mul(0.92f);
                confidence *= 0.72f;
                ageFrames++;
                misses++;
                matched = false;
            }

            Track snapshot() {
                return new Track(id, kind, pos, velocity, radius, confidence, ageFrames, matched);
            }
        }
    }

    static final class TacticalPolicy {
        private static final float PROJECTILE_SPEED = 1.75f; // normalized screen units / second, research default
        private Vec2 previousAim = new Vec2(0, 0);

        ControlCommand decide(WorldState s) {
            Track p = s.player();
            if (p == null || p.confidence() < 0.38f) {
                return ControlCommand.idle("player uncertain");
            }

            Threat threat = mostDangerousProjectile(p, s.projectiles());
            if (threat != null && threat.score > 0.44f) {
                Vec2 dodge = chooseDodge(p.pos(), threat.track);
                return new ControlCommand(dodge, previousAim, false, false,
                        "dodge ttc=" + fmt(threat.ttc) + " miss=" + fmt(threat.missDistance));
            }

            Track enemy = selectEnemy(p, s.enemies());
            if (enemy == null || enemy.confidence() < 0.34f) {
                return ControlCommand.idle("no stable target");
            }

            Vec2 toEnemy = enemy.pos().sub(p.pos());
            float d = toEnemy.len();
            Vec2 aim = interceptAim(p.pos(), enemy.pos(), enemy.velocity(), PROJECTILE_SPEED);
            float aimBlend = clamp(0.35f + enemy.confidence() * 0.45f, 0.35f, 0.80f);
            if (previousAim.len() > 0.1f) aim = previousAim.mul(1f - aimBlend).add(aim.mul(aimBlend)).norm();
            previousAim = aim;

            boolean reliable = enemy.confidence() >= 0.52f && enemy.ageFrames() >= 2;
            boolean fire = reliable && d <= 0.50f;
            Vec2 move;
            String reason;

            if (d < 0.19f) {
                move = toEnemy.mul(-1f).norm();
                reason = "kite close target";
            } else if (d > 0.44f) {
                move = toEnemy.norm();
                reason = "close distance";
            } else {
                // Gentle strafe avoids a completely stationary policy while preserving aim.
                Vec2 tangent = new Vec2(-toEnemy.y(), toEnemy.x()).norm();
                move = tangent.mul(0.32f);
                reason = "hold range / strafe";
            }

            if (!reliable) {
                fire = false;
                reason += " (confirming target)";
            }
            return new ControlCommand(move, aim, fire, false, reason);
        }

        private Track selectEnemy(Track p, List<Track> enemies) {
            Track best = null;
            float bestScore = -Float.MAX_VALUE;
            for (Track e : enemies) {
                float d = e.pos().sub(p.pos()).len();
                float score = e.confidence() * 1.15f + Math.min(e.ageFrames(), 8) * 0.025f - d * 0.70f;
                if (!e.observed()) score -= 0.20f;
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            return best;
        }

        private Threat mostDangerousProjectile(Track player, List<Track> projectiles) {
            Threat best = null;
            for (Track shot : projectiles) {
                if (shot.confidence() < 0.25f || shot.velocity().len() < 0.08f) continue;

                Vec2 r = shot.pos().sub(player.pos());
                Vec2 v = shot.velocity().sub(player.velocity());
                float vv = Math.max(v.len2(), 1e-5f);
                float t = clamp(-r.dot(v) / vv, 0f, 0.55f);
                float miss = r.add(v.mul(t)).len();
                boolean approaching = r.dot(v) < 0f;
                if (!approaching) continue;

                float timeScore = 1f - clamp(t / 0.55f, 0f, 1f);
                float missScore = 1f - clamp(miss / 0.16f, 0f, 1f);
                float score = shot.confidence() * 0.25f + timeScore * 0.35f + missScore * 0.40f;
                Threat candidate = new Threat(shot, t, miss, score);
                if (best == null || candidate.score > best.score) best = candidate;
            }
            return best;
        }

        private Vec2 chooseDodge(Vec2 player, Track projectile) {
            Vec2 dir = projectile.velocity().norm();
            Vec2 a = new Vec2(-dir.y(), dir.x());
            Vec2 b = a.mul(-1f);
            Vec2 pa = player.add(a.mul(0.16f));
            Vec2 pb = player.add(b.mul(0.16f));
            return edgeSafety(pa) >= edgeSafety(pb) ? a : b;
        }

        private float edgeSafety(Vec2 p) {
            float edge = Math.min(Math.min(p.x(), 1f - p.x()), Math.min(p.y(), 1f - p.y()));
            return clamp(edge / 0.22f, -1f, 1f);
        }

        /** Solve |relative + targetVelocity*t| = projectileSpeed*t. */
        private Vec2 interceptAim(Vec2 shooter, Vec2 target, Vec2 targetVelocity, float projectileSpeed) {
            Vec2 r = target.sub(shooter);
            float a = targetVelocity.len2() - projectileSpeed * projectileSpeed;
            float b = 2f * r.dot(targetVelocity);
            float c = r.len2();
            float t = 0f;

            if (Math.abs(a) < 1e-5f) {
                if (Math.abs(b) > 1e-5f) t = -c / b;
            } else {
                float disc = b * b - 4f * a * c;
                if (disc >= 0f) {
                    float root = (float) Math.sqrt(disc);
                    float t1 = (-b - root) / (2f * a);
                    float t2 = (-b + root) / (2f * a);
                    t = smallestPositive(t1, t2);
                }
            }

            if (!(t > 0f) || Float.isNaN(t) || Float.isInfinite(t)) {
                t = clamp(r.len() / projectileSpeed, 0f, 0.35f);
            }
            t = clamp(t, 0f, 0.45f);
            return target.add(targetVelocity.mul(t)).sub(shooter).norm();
        }

        private float smallestPositive(float a, float b) {
            boolean ap = a > 0f, bp = b > 0f;
            if (ap && bp) return Math.min(a, b);
            if (ap) return a;
            if (bp) return b;
            return -1f;
        }

        private record Threat(Track track, float ttc, float missDistance, float score) {}
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
