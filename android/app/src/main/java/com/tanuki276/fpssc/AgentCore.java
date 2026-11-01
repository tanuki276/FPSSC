package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only perception + decision core.
 *
 * The core intentionally stops at ControlCommand generation. It does not inject touches.
 * Accuracy is built from spatial detection + temporal tracking + latency-aware prediction.
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
            Vec2 acceleration,
            float radius,
            float confidence,
            int ageFrames,
            boolean observed
    ) {
        Vec2 project(float seconds) {
            float t = Math.max(0f, seconds);
            return pos.add(velocity.mul(t)).add(acceleration.mul(0.5f * t * t));
        }
    }

    record WorldState(
            Track player,
            List<Track> enemies,
            List<Track> projectiles,
            long frameTimeMs,
            float captureAgeMs,
            Vec2 globalFrameShift,
            float quality
    ) {}

    record ControlCommand(Vec2 move, Vec2 aim, boolean fire, boolean superAttack, String reason) {
        static ControlCommand idle(String reason) {
            return new ControlCommand(new Vec2(0, 0), new Vec2(0, 0), false, false, reason);
        }
    }

    private final VisionPipeline vision;
    AgentCore() { this(false); }
    AgentCore(boolean hudOnly) { vision = new VisionPipeline(hudOnly); }
    private final TacticalPolicy policy = new TacticalPolicy();
    private volatile WorldState lastState;
    private float pipelineLatencyEwmaMs = 12f;

    ControlCommand analyze(Bitmap frame, long nowMs) {
        return analyze(frame, nowMs, 0f);
    }

    ControlCommand analyze(Bitmap frame, long nowMs, float captureAgeMs) {
        WorldState state = vision.detect(frame, nowMs, captureAgeMs);
        lastState = state;
        return policy.decide(state, pipelineLatencyEwmaMs);
    }

    void observePipelineLatency(float elapsedMs) {
        if (!Float.isFinite(elapsedMs) || elapsedMs < 0f || elapsedMs > 500f) return;
        pipelineLatencyEwmaMs = pipelineLatencyEwmaMs * 0.86f + elapsedMs * 0.14f;
    }

    boolean hasObservedPlayer() {
        WorldState s = lastState;
        return s != null && s.player() != null && s.player().observed() && s.player().confidence() >= .38f;
    }

    String telemetry() {
        WorldState s = lastState;
        if (s == null) return "state=none";
        return "q=" + fmt(s.quality())
                + " enemies=" + s.enemies().size()
                + " shots=" + s.projectiles().size()
                + " shift=" + fmt(s.globalFrameShift().x()) + "," + fmt(s.globalFrameShift().y())
                + " queueExtra=" + fmt(s.captureAgeMs()) + "ms pipeline=" + fmt(pipelineLatencyEwmaMs) + "ms";
    }

    static final class VisionPipeline {
        private final boolean hudOnly;
        VisionPipeline() { this(false); }
        VisionPipeline(boolean hudOnly) { this.hudOnly = hudOnly; }
        private TrackManager playerTracks = new TrackManager(Kind.PLAYER, 0.12f, 2);
        private TrackManager enemyTracks = new TrackManager(Kind.ENEMY, 0.17f, 4);
        private TrackManager projectileTracks = new TrackManager(Kind.PROJECTILE, 0.20f, 3);
        private GlobalMotionEstimator globalMotion = new GlobalMotionEstimator();
        private long previousFrameMs;
        private int previousWidth;
        private int previousHeight;

        private void reset() {
            playerTracks = new TrackManager(Kind.PLAYER, 0.12f, 2);
            enemyTracks = new TrackManager(Kind.ENEMY, 0.17f, 4);
            projectileTracks = new TrackManager(Kind.PROJECTILE, 0.20f, 3);
            globalMotion = new GlobalMotionEstimator();
            previousFrameMs = 0;
        }

        WorldState detect(Bitmap b, long nowMs, float captureAgeMs) {
            if (b == null || b.getWidth() < 96 || b.getHeight() < 96) {
                reset();
                return new WorldState(null, Collections.emptyList(), Collections.emptyList(), nowMs, 0f, new Vec2(0, 0), 0f);
            }

            if (b.getWidth() != previousWidth || b.getHeight() != previousHeight
                    || nowMs <= previousFrameMs || nowMs - previousFrameMs > 500L) {
                reset();
            }
            previousWidth = b.getWidth();
            previousHeight = b.getHeight();
            float dt = previousFrameMs == 0
                    ? 1f / 30f
                    : clamp((nowMs - previousFrameMs) / 1000f, 1f / 120f, 0.20f);
            previousFrameMs = nowMs;

            MotionEstimate motion = globalMotion.estimate(b);
            Vec2 frameShift = motion.confidence() >= 0.16f ? motion.shift() : new Vec2(0, 0);

            List<Detection> bars = healthBars(b);
            List<Detection> playerDetections = new ArrayList<>();
            List<Detection> enemyDetections = new ArrayList<>();
            for (Detection d : bars) {
                if (d.kind() == Kind.PLAYER) playerDetections.add(d);
                else enemyDetections.add(d);
            }
            // Keep the synthetic baseline only when no game HUD is present.
            if (bars.isEmpty() && !hudOnly) {
                playerDetections = connectedComponents(b, Kind.PLAYER, 0.12f, 0.88f, 0.12f, 0.90f, 4);
                enemyDetections = connectedComponents(b, Kind.ENEMY, 0.03f, 0.97f, 0.04f, 0.94f, 14);
            }
            List<Detection> projectileDetections = connectedComponents(b, Kind.PROJECTILE, 0.22f, 0.78f, 0.08f, 0.83f, 28);

            playerDetections.sort(Comparator.comparingDouble(d -> -playerScore(d)));
            if (playerDetections.size() > 2) {
                playerDetections = new ArrayList<>(playerDetections.subList(0, 2));
            }

            // The camera tends to follow the player, so do not force global background motion
            // onto the player track. It is valuable for enemy/projectile association, however.
            List<Track> players = playerTracks.update(playerDetections, dt, new Vec2(0, 0));
            List<Track> enemies = enemyTracks.update(enemyDetections, dt, frameShift);
            List<Track> projectiles = projectileTracks.update(projectileDetections, dt, frameShift);

            Track player = players.stream()
                    .filter(t -> t.confidence() >= 0.25f)
                    .max(Comparator.comparingDouble(t -> t.confidence() + centerPrior(t.pos()) * 0.25f))
                    .orElse(null);

            enemies = visibleTracks(enemies, 0.27f, 14);
            projectiles = visibleTracks(projectiles, 0.20f, 28);
            projectiles.removeIf(t -> t.ageFrames() < 2 || t.velocity().len() < .08f || t.radius() > .03f);

            float quality = player == null ? 0f : player.confidence();
            if (!enemies.isEmpty()) quality = clamp(quality * 0.68f + meanConfidence(enemies) * 0.32f, 0f, 1f);
            quality *= 0.90f + motion.confidence() * 0.10f;

            return new WorldState(
                    player,
                    enemies,
                    projectiles,
                    nowMs,
                    clamp(captureAgeMs, 0f, 500f),
                    frameShift,
                    clamp(quality, 0f, 1f)
            );
        }

        /** Brawl Stars HUD anchors: compact horizontal health fill plus white HP text above. */
        private List<Detection> healthBars(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight();
            byte[] mask = new byte[w * h];
            for (int y = (int)(h * .10f); y < h * .83f; y++) {
                for (int x = (int)(w * .05f); x < w * .95f; x++) {
                    if ((x < w * .22f || x > w * .78f) && y > h * .55f) continue;
                    int c = b.getPixel(x, y), r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                    if (g > 145 && g > r * 1.25f && g > bl * 1.2f) mask[y * w + x] = 1;
                    else if (r > 160 && g < 115 && r > g * 1.6f && r > bl * 1.25f) mask[y * w + x] = 2;
                }
            }
            int[] queue = new int[mask.length];
            List<Detection> bars = new ArrayList<>();
            for (int start = 0; start < mask.length; start++) {
                byte kind = mask[start];
                if (kind == 0) continue;
                int head = 0, tail = 1, minX = start % w, maxX = minX, minY = start / w, maxY = minY;
                queue[0] = start;
                mask[start] = 0;
                while (head < tail) {
                    int p = queue[head++], x = p % w, y = p / w;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    for (int k = 0; k < 4; k++) {
                        int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0);
                        int ny = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int n = ny * w + nx;
                        if (mask[n] == kind) { mask[n] = 0; queue[tail++] = n; }
                    }
                }
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                if (bw < h * .025f || bw > h * .15f || bh < 1 || bh > h * .025f
                        || bw < bh * 2.2f || tail < bw * bh * .40f) continue;
                int fullWidth = Math.max(bw, Math.round(h * .09f));
                int white = 0;
                for (int y = Math.max(0, minY - Math.round(h * .035f)); y < minY; y++) {
                    for (int x = minX; x < Math.min(w, minX + fullWidth); x++) {
                        int c = b.getPixel(x, y);
                        if (Color.red(c) > 195 && Color.green(c) > 195 && Color.blue(c) > 195) white++;
                    }
                }
                if (white < Math.max(2, fullWidth / 8)) continue;
                int darkBorder = 0;
                for (int y = Math.max(0, minY - Math.round(h * .035f)); y <= Math.min(h - 1, maxY + 1); y++) {
                    int dark = 0;
                    for (int x = minX; x < Math.min(w, minX + fullWidth); x++) {
                        int c = b.getPixel(x, y);
                        if (Color.red(c) < 135 && Color.green(c) < 90 && Color.blue(c) < 120) dark++;
                    }
                    darkBorder = Math.max(darkBorder, dark);
                }
                if (darkBorder < fullWidth * .30f) continue;
                float cx = (minX + fullWidth / 2f) / w;
                float cy = (minY + bh / 2f + h * .085f) / h;
                if (cy > .83f) continue;
                bars.add(new Detection(kind == 1 ? Kind.PLAYER : Kind.ENEMY,
                        new Vec2(cx, cy), .025f, .88f));
            }
            // Orange ammunition segments immediately beneath our own HP are not enemies.
            bars.removeIf(d -> d.kind() == Kind.ENEMY && bars.stream().anyMatch(p ->
                    p.kind() == Kind.PLAYER && Math.abs(p.pos().x() - d.pos().x()) < .055f
                    && Math.abs(p.pos().y() - d.pos().y()) < .06f));
            return bars;
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
            for (Track t : tracks) if (t.confidence() >= minConfidence) out.add(t);
            out.sort(Comparator.comparingDouble((Track t) -> t.confidence()).reversed());
            if (out.size() > max) return new ArrayList<>(out.subList(0, max));
            return out;
        }

        private static float meanConfidence(List<Track> tracks) {
            float sum = 0f;
            for (Track t : tracks) sum += t.confidence();
            return tracks.isEmpty() ? 0f : sum / tracks.size();
        }

        /**
         * Sparse connected-components detector followed by a local full-resolution refinement.
         * Projectiles use a denser first pass because one missed sample can erase a small object.
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

            int targetGrid = kind == Kind.PROJECTILE ? 320 : 230;
            int step = Math.max(1, Math.round(w / (float) targetGrid));
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

            // Suppress isolated hot pixels for larger objects. Tiny projectiles skip this pass.
            if (kind != Kind.PROJECTILE) {
                byte[] cleaned = mask.clone();
                for (int gy = 1; gy < gh - 1; gy++) {
                    for (int gx = 1; gx < gw - 1; gx++) {
                        int p = gy * gw + gx;
                        if (mask[p] == 0) continue;
                        int neighbors = 0;
                        for (int oy = -1; oy <= 1; oy++) {
                            for (int ox = -1; ox <= 1; ox++) {
                                if (ox == 0 && oy == 0) continue;
                                neighbors += mask[(gy + oy) * gw + gx + ox];
                            }
                        }
                        if (neighbors < 2) cleaned[p] = 0;
                    }
                }
                mask = cleaned;
            }

            boolean[] seen = new boolean[mask.length];
            int[] queue = new int[mask.length];
            List<Detection> out = new ArrayList<>();
            int minCells = switch (kind) {
                case PLAYER -> 7;
                case ENEMY -> 6;
                case PROJECTILE -> 2;
            };

            for (int start = 0; start < mask.length; start++) {
                if (mask[start] == 0 || seen[start]) continue;

                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = true;
                int count = 0;
                long sx = 0, sy = 0;
                int minGx = gw, maxGx = 0, minGy = gh, maxGy = 0;

                while (head < tail) {
                    int p = queue[head++];
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
                                queue[tail++] = np;
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
                    case PLAYER -> smoothBand(radius, 0.018f, 0.100f);
                    case ENEMY -> smoothBand(radius, 0.012f, 0.110f);
                    case PROJECTILE -> smoothBand(radius, 0.0025f, 0.052f);
                };
                float densityScore = clamp(fill * 1.55f, 0f, 1f);
                float countScore = clamp(count / (kind == Kind.PROJECTILE ? 8f : 25f), 0f, 1f);
                float confidence = clamp(sizeScore * 0.28f + densityScore * 0.36f + countScore * 0.36f, 0f, 1f);

                Detection coarse = new Detection(kind, new Vec2(cx, cy), radius, confidence);
                out.add(refineDetection(b, coarse, step));
            }

            out.sort(Comparator.comparingDouble((Detection d) -> d.confidence()).reversed());
            if (out.size() > max) return new ArrayList<>(out.subList(0, max));
            return out;
        }

        private Detection refineDetection(Bitmap b, Detection coarse, int coarseStep) {
            int w = b.getWidth(), h = b.getHeight();
            int cx = Math.round(coarse.pos().x() * w);
            int cy = Math.round(coarse.pos().y() * h);
            int halfX = clampInt(Math.round(coarse.radius() * w * 1.55f), coarseStep * 2, Math.max(8, w / 13));
            int halfY = clampInt(Math.round(coarse.radius() * h * 1.55f), coarseStep * 2, Math.max(8, h / 13));
            int refineStep = coarse.kind() == Kind.PROJECTILE ? 1 : 2;

            float sx = 0f, sy = 0f, sw = 0f;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            int hits = 0;
            for (int y = Math.max(0, cy - halfY); y <= Math.min(h - 1, cy + halfY); y += refineStep) {
                for (int x = Math.max(0, cx - halfX); x <= Math.min(w - 1, cx + halfX); x += refineStep) {
                    int c = b.getPixel(x, y);
                    float weight = matchStrength(c, coarse.kind());
                    if (weight <= 0f) continue;
                    sx += x * weight;
                    sy += y * weight;
                    sw += weight;
                    hits++;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }

            if (hits < (coarse.kind() == Kind.PROJECTILE ? 2 : 5) || sw < 1f) return coarse;
            float px = sx / sw / w;
            float py = sy / sw / h;
            float bw = Math.max(refineStep, maxX - minX + refineStep) / (float) w;
            float bh = Math.max(refineStep, maxY - minY + refineStep) / (float) h;
            float radius = 0.5f * (float) Math.sqrt(bw * bw + bh * bh);
            float localSupport = clamp(hits / (coarse.kind() == Kind.PROJECTILE ? 12f : 48f), 0f, 1f);
            float confidence = clamp(coarse.confidence() * 0.68f + localSupport * 0.32f, 0f, 1f);
            return new Detection(coarse.kind(), new Vec2(px, py), radius, confidence);
        }

        private static float smoothBand(float value, float lo, float hi) {
            if (value < lo * 0.45f || value > hi * 1.8f) return 0.05f;
            if (value >= lo && value <= hi) return 1f;
            if (value < lo) return clamp((value - lo * 0.45f) / (lo * 0.55f), 0.05f, 1f);
            return clamp(1f - (value - hi) / (hi * 0.8f), 0.05f, 1f);
        }

        private boolean matches(int c, Kind kind) {
            return matchStrength(c, kind) > 0.32f;
        }

        /** Continuous color score is more tolerant of brightness changes than a binary RGB box. */
        private float matchStrength(int c, Kind kind) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            if (kind == Kind.PROJECTILE && (r < 175 || g < 135 || b > 145 || Math.min(r, g) - b < 65)) return 0f;
            int chroma = max - min;
            int sum = r + g + b;
            if (max < 55 || sum < 165 || chroma < 18) return 0f;

            float brightness = clamp((max - 55f) / 165f, 0f, 1f);
            float saturation = clamp(chroma / 135f, 0f, 1f);
            float hueLike;

            switch (kind) {
                case PLAYER -> {
                    float greenLead = clamp((g - r - 6f) / 85f, 0f, 1f);
                    float blueSupport = clamp((b - r + 8f) / 95f, 0f, 1f);
                    hueLike = greenLead * 0.58f + blueSupport * 0.42f;
                }
                case ENEMY -> {
                    float redGreen = clamp((r - g - 8f) / 110f, 0f, 1f);
                    float redBlue = clamp((r - b - 5f) / 110f, 0f, 1f);
                    hueLike = redGreen * 0.52f + redBlue * 0.48f;
                }
                case PROJECTILE -> {
                    float warm = clamp((r + g - b * 1.55f - 65f) / 220f, 0f, 1f);
                    float balance = 1f - clamp(Math.abs(r - g) / 140f, 0f, 1f);
                    hueLike = warm * 0.68f + balance * 0.32f;
                }
                default -> hueLike = 0f;
            }
            return clamp(hueLike * 0.62f + saturation * 0.23f + brightness * 0.15f, 0f, 1f);
        }
    }

    /**
     * Translation-only background motion estimate on a tiny luminance grid.
     * It is used for association, not to rewrite measured target velocities.
     */
    static final class GlobalMotionEstimator {
        private static final int GRID_W = 80;
        private int[] previous;
        private int previousH;

        MotionEstimate estimate(Bitmap b) {
            int hGrid = clampInt(Math.round(GRID_W * (b.getHeight() / (float) b.getWidth())), 44, 128);
            int[] current = sampleLuma(b, GRID_W, hGrid);
            if (previous == null || previousH != hGrid) {
                previous = current;
                previousH = hGrid;
                return new MotionEstimate(new Vec2(0, 0), 0f);
            }

            long zeroScore = scoreShift(previous, current, GRID_W, hGrid, 0, 0);
            long bestScore = zeroScore;
            int bestDx = 0, bestDy = 0;
            final int radius = 4;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    long score = scoreShift(previous, current, GRID_W, hGrid, dx, dy);
                    // Small motion prior avoids jumping to a distant equally-textured patch.
                    score += (long) (Math.abs(dx) + Math.abs(dy)) * 110L;
                    if (score < bestScore) {
                        bestScore = score;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }

            previous = current;
            previousH = hGrid;
            float gain = zeroScore <= 0 ? 0f : (zeroScore - bestScore) / (float) zeroScore;
            float confidence = clamp(gain * 3.2f, 0f, 1f);
            Vec2 shift = confidence < 0.08f
                    ? new Vec2(0, 0)
                    : new Vec2(bestDx / (float) GRID_W, bestDy / (float) hGrid);
            return new MotionEstimate(shift, confidence);
        }

        private int[] sampleLuma(Bitmap b, int gw, int gh) {
            int[] out = new int[gw * gh];
            int w = b.getWidth(), h = b.getHeight();
            for (int gy = 0; gy < gh; gy++) {
                int y = Math.min(h - 1, Math.round((gy + 0.5f) * h / gh));
                for (int gx = 0; gx < gw; gx++) {
                    int x = Math.min(w - 1, Math.round((gx + 0.5f) * w / gw));
                    int c = b.getPixel(x, y);
                    int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                    out[gy * gw + gx] = (77 * r + 150 * g + 29 * bl) >> 8;
                }
            }
            return out;
        }

        private long scoreShift(int[] prev, int[] cur, int w, int h, int dx, int dy) {
            long score = 0;
            int count = 0;
            int marginX = 7, marginY = 7;
            for (int y = marginY; y < h - marginY; y += 2) {
                int py = y - dy;
                if (py < marginY || py >= h - marginY) continue;
                for (int x = marginX; x < w - marginX; x += 2) {
                    int px = x - dx;
                    if (px < marginX || px >= w - marginX) continue;
                    int a = prev[py * w + px];
                    int c = cur[y * w + x];
                    int d = Math.abs(a - c);
                    // Robust loss: moving objects and flashes should not dominate the estimate.
                    score += Math.min(d, 42);
                    count++;
                }
            }
            return count == 0 ? Long.MAX_VALUE / 4 : score * 1024L / count;
        }
    }

    record MotionEstimate(Vec2 shift, float confidence) {}

    /** Kinematic multi-object tracker with global-association and acceleration smoothing. */
    static final class TrackManager {
        private static final float POSITION_ALPHA = 0.72f;
        private static final float VELOCITY_ALPHA = 0.34f;
        private static final float ACCEL_ALPHA = 0.20f;

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

        List<Track> update(List<Detection> detections, float dt, Vec2 globalFrameShift) {
            for (MutableTrack t : tracks) {
                t.predicted = t.pos
                        .add(t.velocity.mul(dt))
                        .add(t.acceleration.mul(0.5f * dt * dt))
                        .add(globalFrameShift);
                t.matched = false;
            }

            List<PairCost> pairs = new ArrayList<>();
            for (int ti = 0; ti < tracks.size(); ti++) {
                MutableTrack t = tracks.get(ti);
                for (int di = 0; di < detections.size(); di++) {
                    Detection d = detections.get(di);
                    float distance = d.pos().sub(t.predicted).len();
                    float dynamicGate = gate
                            + Math.min(0.11f, t.velocity.len() * dt * 1.7f)
                            + Math.min(0.05f, t.acceleration.len() * dt * dt);
                    if (distance > dynamicGate) continue;
                    float sizeRatio = Math.abs(d.radius() - t.radius) / Math.max(0.005f, t.radius);
                    float cost = distance
                            + Math.min(sizeRatio, 2f) * 0.025f
                            - d.confidence() * 0.020f
                            - Math.min(t.ageFrames, 10) * 0.0015f;
                    pairs.add(new PairCost(ti, di, cost));
                }
            }
            pairs.sort(Comparator.comparingDouble(PairCost::cost));

            Set<Integer> usedTracks = new HashSet<>();
            Set<Integer> usedDetections = new HashSet<>();
            for (PairCost pair : pairs) {
                if (usedTracks.contains(pair.trackIndex()) || usedDetections.contains(pair.detectionIndex())) continue;
                MutableTrack t = tracks.get(pair.trackIndex());
                Detection d = detections.get(pair.detectionIndex());
                t.correct(d, dt, globalFrameShift);
                usedTracks.add(pair.trackIndex());
                usedDetections.add(pair.detectionIndex());
            }

            float spawnThreshold = kind == Kind.PROJECTILE ? 0.25f : 0.31f;
            for (int di = 0; di < detections.size(); di++) {
                if (usedDetections.contains(di)) continue;
                Detection d = detections.get(di);
                if (d.confidence() < spawnThreshold) continue;
                MutableTrack n = new MutableTrack(nextId++, kind, d);
                n.matched = true;
                tracks.add(n);
            }

            for (MutableTrack t : tracks) if (!t.matched) t.miss(dt, globalFrameShift);
            tracks.removeIf(t -> t.misses > maxMisses || t.confidence < 0.09f || outOfBounds(t.pos));

            List<Track> out = new ArrayList<>(tracks.size());
            for (MutableTrack t : tracks) out.add(t.snapshot());
            return out;
        }

        private static boolean outOfBounds(Vec2 p) {
            return p.x() < -0.18f || p.x() > 1.18f || p.y() < -0.18f || p.y() > 1.18f;
        }

        private record PairCost(int trackIndex, int detectionIndex, float cost) {}

        private static final class MutableTrack {
            final int id;
            final Kind kind;
            Vec2 pos;
            Vec2 predicted;
            Vec2 velocity = new Vec2(0, 0);
            Vec2 acceleration = new Vec2(0, 0);
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

            void correct(Detection d, float dt, Vec2 globalFrameShift) {
                Vec2 previousVelocity = velocity;
                Vec2 innovation = d.pos().sub(predicted);
                Vec2 screenDelta = d.pos().sub(pos).sub(globalFrameShift);
                Vec2 observedVelocity = screenDelta.mul(1f / Math.max(dt, 1f / 120f));
                Vec2 observedAcceleration = observedVelocity.sub(previousVelocity)
                        .mul(1f / Math.max(dt, 1f / 120f));

                pos = predicted.add(innovation.mul(POSITION_ALPHA));
                velocity = velocity.mul(1f - VELOCITY_ALPHA)
                        .add(observedVelocity.mul(VELOCITY_ALPHA))
                        .clampLen(kind == Kind.PROJECTILE ? 7f : 4f);
                acceleration = acceleration.mul(1f - ACCEL_ALPHA)
                        .add(observedAcceleration.mul(ACCEL_ALPHA))
                        .clampLen(kind == Kind.PROJECTILE ? 22f : 12f);
                radius = radius * 0.64f + d.radius() * 0.36f;
                confidence = clamp(confidence * 0.56f + d.confidence() * 0.54f + 0.05f, 0f, 1f);
                ageFrames++;
                misses = 0;
                matched = true;
            }

            void miss(float dt, Vec2 globalFrameShift) {
                pos = pos
                        .add(velocity.mul(dt))
                        .add(acceleration.mul(0.5f * dt * dt))
                        .add(globalFrameShift);
                velocity = velocity.mul(0.91f);
                acceleration = acceleration.mul(0.72f);
                confidence *= kind == Kind.PROJECTILE ? 0.64f : 0.73f;
                ageFrames++;
                misses++;
                matched = false;
            }

            Track snapshot() {
                return new Track(id, kind, pos, velocity, acceleration, radius, confidence, ageFrames, matched);
            }
        }
    }

    static final class TacticalPolicy {
        private static final float PROJECTILE_SPEED = 1.75f;
        private Vec2 previousAim = new Vec2(0, 0);
        private int stableAimFrames;
        private int targetId = -1;

        ControlCommand decide(WorldState s, float pipelineLatencyMs) {
            Track p = s.player();
            if (p == null || !p.observed() || p.confidence() < 0.38f) {
                stableAimFrames = 0;
                return ControlCommand.idle("player uncertain");
            }

            float lagSec = clamp((s.captureAgeMs() + pipelineLatencyMs) / 1000f, 0f, 0.24f);
            Vec2 playerNow = p.project(lagSec);

            Threat threat = mostDangerousProjectile(p, playerNow, s.projectiles(), lagSec);
            if (threat != null && threat.score() > 0.43f) {
                Vec2 dodge = chooseDodge(playerNow, threat.track(), lagSec);
                return new ControlCommand(
                        dodge,
                        previousAim,
                        false,
                        false,
                        "dodge ttc=" + fmt(threat.ttc()) + " miss=" + fmt(threat.missDistance())
                );
            }

            Track enemy = selectEnemy(p, playerNow, s.enemies(), lagSec);
            if (enemy == null || enemy.confidence() < 0.34f) {
                stableAimFrames = 0;
                return ControlCommand.idle("no stable target");
            }

            if (targetId != enemy.id()) {
                targetId = enemy.id();
                stableAimFrames = 0;
                previousAim = new Vec2(0, 0);
            }
            Vec2 enemyNow = enemy.project(lagSec);
            Vec2 toEnemy = enemyNow.sub(playerNow);
            float distance = toEnemy.len();
            Vec2 relativeVelocity = enemy.velocity().sub(p.velocity());
            Vec2 relativeAcceleration = enemy.acceleration().sub(p.acceleration());
            Vec2 rawAim = interceptAim(playerNow, enemyNow, relativeVelocity, relativeAcceleration, PROJECTILE_SPEED);

            float aimBlend = clamp(0.34f + enemy.confidence() * 0.48f, 0.34f, 0.84f);
            Vec2 aim = previousAim.len() > 0.1f
                    ? previousAim.mul(1f - aimBlend).add(rawAim.mul(aimBlend)).norm()
                    : rawAim;

            float aimAgreement = previousAim.len() > 0.1f ? previousAim.dot(aim) : 1f;
            if (aimAgreement > 0.965f) stableAimFrames++;
            else stableAimFrames = Math.max(0, stableAimFrames - 1);
            previousAim = aim;

            boolean reliableTrack = enemy.observed() && enemy.confidence() >= 0.52f && enemy.ageFrames() >= 2;
            boolean stableAim = stableAimFrames >= 2 || distance < 0.18f;
            boolean fire = reliableTrack && stableAim && distance <= 0.50f;

            Vec2 move;
            String reason;
            if (distance < 0.19f) {
                move = toEnemy.mul(-1f).norm();
                reason = "kite close target";
            } else if (distance > 0.44f) {
                move = toEnemy.norm();
                reason = "close distance";
            } else {
                Vec2 tangent = new Vec2(-toEnemy.y(), toEnemy.x()).norm();
                float edgeBias = edgeSafety(playerNow.add(tangent.mul(0.14f)))
                        - edgeSafety(playerNow.add(tangent.mul(-0.14f)));
                if (edgeBias < 0f) tangent = tangent.mul(-1f);
                move = tangent.mul(0.32f);
                reason = "hold range / strafe";
            }

            if (!reliableTrack) {
                fire = false;
                reason += " (confirming target)";
            } else if (!stableAim) {
                fire = false;
                reason += " (settling aim)";
            }
            return new ControlCommand(move, aim, fire, false, reason);
        }

        private Track selectEnemy(Track player, Vec2 playerNow, List<Track> enemies, float lagSec) {
            Track best = null;
            float bestScore = -Float.MAX_VALUE;
            for (Track e : enemies) {
                Vec2 current = e.project(lagSec);
                float d = current.sub(playerNow).len();
                float score = e.confidence() * 1.18f
                        + Math.min(e.ageFrames(), 10) * 0.023f
                        - d * 0.68f
                        - Math.min(e.acceleration().len(), 6f) * 0.012f;
                if (!e.observed()) score -= 0.19f;
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            return best;
        }

        private Threat mostDangerousProjectile(
                Track player,
                Vec2 playerNow,
                List<Track> projectiles,
                float lagSec
        ) {
            Threat best = null;
            for (Track shot : projectiles) {
                if (shot.confidence() < 0.23f) continue;
                if (shot.ageFrames() < 2 && shot.confidence() < 0.58f) continue;

                Vec2 shotNow = shot.project(lagSec);
                Vec2 r = shotNow.sub(playerNow);
                Vec2 v = shot.velocity().sub(player.velocity());
                if (v.len() < 0.08f) continue;
                float vv = Math.max(v.len2(), 1e-5f);
                float t = clamp(-r.dot(v) / vv, 0f, 0.52f);
                Vec2 missVec = r.add(v.mul(t))
                        .add(shot.acceleration().sub(player.acceleration()).mul(0.5f * t * t));
                float miss = missVec.len();
                boolean approaching = r.dot(v) < 0f;
                // A fast approaching shot can still pass far outside the collision envelope.
                float collisionRadius = Math.max(0.035f, player.radius() + shot.radius() + 0.025f);
                if (!approaching || miss > collisionRadius) continue;

                float timeScore = 1f - clamp(t / 0.52f, 0f, 1f);
                float missScore = 1f - clamp(miss / 0.155f, 0f, 1f);
                float physicalScore = clamp(v.len() / 1.2f, 0f, 1f);
                float score = shot.confidence() * 0.22f
                        + timeScore * 0.30f
                        + missScore * 0.38f
                        + physicalScore * 0.10f;
                Threat candidate = new Threat(shot, t, miss, score);
                if (best == null || candidate.score() > best.score()) best = candidate;
            }
            return best;
        }

        private Vec2 chooseDodge(Vec2 player, Track projectile, float lagSec) {
            Vec2 dir = projectile.velocity()
                    .add(projectile.acceleration().mul(Math.min(0.12f, lagSec)))
                    .norm();
            Vec2 a = new Vec2(-dir.y(), dir.x());
            Vec2 b = a.mul(-1f);
            Vec2 pa = player.add(a.mul(0.17f));
            Vec2 pb = player.add(b.mul(0.17f));
            return edgeSafety(pa) >= edgeSafety(pb) ? a : b;
        }

        private float edgeSafety(Vec2 p) {
            float edge = Math.min(Math.min(p.x(), 1f - p.x()), Math.min(p.y(), 1f - p.y()));
            return clamp(edge / 0.22f, -1f, 1f);
        }

        /** Iterative intercept solver that includes relative acceleration. */
        private Vec2 interceptAim(
                Vec2 shooter,
                Vec2 target,
                Vec2 relativeVelocity,
                Vec2 relativeAcceleration,
                float projectileSpeed
        ) {
            Vec2 r = target.sub(shooter);
            float t = clamp(r.len() / Math.max(projectileSpeed, 0.05f), 0f, 0.45f);
            for (int i = 0; i < 4; i++) {
                Vec2 future = target
                        .add(relativeVelocity.mul(t))
                        .add(relativeAcceleration.mul(0.5f * t * t));
                float next = future.sub(shooter).len() / Math.max(projectileSpeed, 0.05f);
                t = clamp(t * 0.35f + next * 0.65f, 0f, 0.45f);
            }
            return target
                    .add(relativeVelocity.mul(t))
                    .add(relativeAcceleration.mul(0.5f * t * t))
                    .sub(shooter)
                    .norm();
        }

        private record Threat(Track track, float ttc, float missDistance, float score) {}
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
