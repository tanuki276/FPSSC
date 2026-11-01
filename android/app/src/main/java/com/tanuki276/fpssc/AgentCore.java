package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AgentCore {
    record Vec2(float x, float y) {
        Vec2 add(Vec2 o) { return new Vec2(x + o.x, y + o.y); }
        Vec2 sub(Vec2 o) { return new Vec2(x - o.x, y - o.y); }
        Vec2 mul(float s) { return new Vec2(x * s, y * s); }
        float len() { return (float)Math.hypot(x, y); }
        Vec2 norm() { float l = len(); return l < 1e-4f ? new Vec2(0, 0) : mul(1f / l); }
    }

    record Track(Vec2 pos, Vec2 velocity, float confidence) {}
    record WorldState(Track player, List<Track> enemies, List<Track> projectiles, long frameTimeMs) {}
    record ControlCommand(Vec2 move, Vec2 aim, boolean fire, boolean superAttack, String reason) {
        static ControlCommand idle(String reason) { return new ControlCommand(new Vec2(0,0), new Vec2(0,0), false, false, reason); }
    }

    private final VisionPipeline vision = new VisionPipeline();
    private final TacticalPolicy policy = new TacticalPolicy();

    ControlCommand analyze(Bitmap frame, long nowMs) {
        WorldState state = vision.detect(frame, nowMs);
        return policy.decide(state);
    }

    static final class VisionPipeline {
        private WorldState previous;

        WorldState detect(Bitmap b, long nowMs) {
            if (b == null || b.getWidth() < 64 || b.getHeight() < 64) {
                return new WorldState(null, List.of(), List.of(), nowMs);
            }

            Track player = detectPlayer(b);
            List<Track> enemies = detectEnemies(b);
            List<Track> projectiles = detectProjectiles(b);

            if (previous != null) {
                player = attachVelocity(player, previous.player(), nowMs - previous.frameTimeMs());
                enemies = attachVelocities(enemies, previous.enemies(), nowMs - previous.frameTimeMs());
                projectiles = attachVelocities(projectiles, previous.projectiles(), nowMs - previous.frameTimeMs());
            }

            previous = new WorldState(player, enemies, projectiles, nowMs);
            return previous;
        }

        private Track detectPlayer(Bitmap b) {
            // Research baseline: find the strongest cyan/green blob near screen center.
            return blobCentroid(b, Kind.PLAYER, 0.18f, 0.82f, 0.18f, 0.88f);
        }

        private List<Track> detectEnemies(Bitmap b) {
            return blobCentroids(b, Kind.ENEMY, 0.05f, 0.95f, 0.06f, 0.92f, 8);
        }

        private List<Track> detectProjectiles(Bitmap b) {
            return blobCentroids(b, Kind.PROJECTILE, 0.03f, 0.97f, 0.05f, 0.95f, 16);
        }

        private enum Kind { PLAYER, ENEMY, PROJECTILE }

        private boolean matches(int c, Kind kind) {
            int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
            return switch (kind) {
                case PLAYER -> g > 150 && bl > 120 && g > r + 20;
                case ENEMY -> r > 175 && r > g + 35 && r > bl + 25;
                case PROJECTILE -> r > 190 && g > 150 && bl < 125;
            };
        }

        private Track blobCentroid(Bitmap b, Kind kind, float x0n, float x1n, float y0n, float y1n) {
            List<Track> all = blobCentroids(b, kind, x0n, x1n, y0n, y1n, 1);
            return all.isEmpty() ? null : all.get(0);
        }

        private List<Track> blobCentroids(Bitmap b, Kind kind, float x0n, float x1n, float y0n, float y1n, int max) {
            int w = b.getWidth(), h = b.getHeight();
            int x0 = Math.max(0, Math.round(w * x0n));
            int x1 = Math.min(w, Math.round(w * x1n));
            int y0 = Math.max(0, Math.round(h * y0n));
            int y1 = Math.min(h, Math.round(h * y1n));
            int step = Math.max(2, w / 240);

            List<Track> candidates = new ArrayList<>();
            for (int gy = y0; gy < y1; gy += 24) {
                for (int gx = x0; gx < x1; gx += 24) {
                    long sx = 0, sy = 0; int n = 0;
                    int ex = Math.min(x1, gx + 32), ey = Math.min(y1, gy + 32);
                    for (int y = gy; y < ey; y += step) {
                        for (int x = gx; x < ex; x += step) {
                            if (matches(b.getPixel(x, y), kind)) { sx += x; sy += y; n++; }
                        }
                    }
                    if (n >= 6) {
                        candidates.add(new Track(new Vec2(sx / (float)n / w, sy / (float)n / h), new Vec2(0,0), Math.min(1f, n / 30f)));
                    }
                }
            }
            candidates.sort(Comparator.comparing((Track t) -> t.confidence()).reversed());
            if (candidates.size() > max) return new ArrayList<>(candidates.subList(0, max));
            return candidates;
        }

        private Track attachVelocity(Track current, Track old, long dtMs) {
            if (current == null || old == null || dtMs <= 0) return current;
            float dt = dtMs / 1000f;
            return new Track(current.pos(), current.pos().sub(old.pos()).mul(1f / dt), current.confidence());
        }

        private List<Track> attachVelocities(List<Track> current, List<Track> old, long dtMs) {
            if (dtMs <= 0 || old.isEmpty()) return current;
            List<Track> out = new ArrayList<>(current.size());
            for (Track t : current) {
                Track nearest = old.stream().min(Comparator.comparingDouble(o -> o.pos().sub(t.pos()).len())).orElse(null);
                out.add(attachVelocity(t, nearest, dtMs));
            }
            return out;
        }
    }

    static final class TacticalPolicy {
        ControlCommand decide(WorldState s) {
            Track p = s.player();
            if (p == null) return ControlCommand.idle("player not detected");

            Track danger = nearest(p, s.projectiles());
            if (danger != null && danger.pos().sub(p.pos()).len() < 0.16f) {
                Vec2 incoming = danger.velocity().len() > 0.01f ? danger.velocity().norm() : danger.pos().sub(p.pos()).norm();
                Vec2 dodge = new Vec2(-incoming.y(), incoming.x()).norm();
                return new ControlCommand(dodge, new Vec2(0,0), false, false, "dodge projectile");
            }

            Track enemy = nearest(p, s.enemies());
            if (enemy == null) return ControlCommand.idle("no target");

            Vec2 toEnemy = enemy.pos().sub(p.pos());
            float d = toEnemy.len();
            Vec2 predicted = enemy.pos().add(enemy.velocity().mul(0.10f));
            Vec2 aim = predicted.sub(p.pos()).norm();

            if (d < 0.20f) {
                return new ControlCommand(toEnemy.mul(-1).norm(), aim, true, false, "kite close target");
            }
            if (d > 0.42f) {
                return new ControlCommand(toEnemy.norm(), aim, false, false, "close distance");
            }
            return new ControlCommand(new Vec2(0,0), aim, true, false, "hold range and fire");
        }

        private Track nearest(Track p, List<Track> items) {
            return items.stream().min(Comparator.comparingDouble(t -> t.pos().sub(p.pos()).len())).orElse(null);
        }
    }
}
