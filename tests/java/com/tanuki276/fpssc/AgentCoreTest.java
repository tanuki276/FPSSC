package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

public final class AgentCoreTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Track track(int id, Kind kind, float x, float y, float vx, float vy, boolean observed) {
        return new Track(id, kind, new Vec2(x, y), new Vec2(vx, vy), new Vec2(0, 0), .02f, .95f, 8, observed);
    }
    private static WorldState world(Track player, List<Track> enemies, List<Track> shots) {
        return new WorldState(player, enemies, shots, 1000, 0, new Vec2(0, 0), 1);
    }
    public static void main(String[] args) {
        Track player = track(1, Kind.PLAYER, .5f, .5f, 0, 0, true);
        Track enemy = track(2, Kind.ENEMY, .8f, .5f, 0, 0, true);
        TacticalPolicy policy = new TacticalPolicy();
        WorldState clear = world(player, List.of(enemy), List.of());
        policy.decide(clear, 0);
        check(policy.decide(clear, 0).fire(), "stable visible target should fire");
        Track farShot = track(3, Kind.PROJECTILE, .7f, .9f, -1, 0, true);
        check(!policy.decide(world(player, List.of(enemy), List.of(farShot)), 0).reason().startsWith("dodge"), "distant miss must not trigger dodge");
        Track incoming = track(3, Kind.PROJECTILE, .7f, .5f, -1, 0, true);
        ControlCommand dodge = policy.decide(world(player, List.of(enemy), List.of(incoming)), 0);
        check(dodge.reason().startsWith("dodge") && !dodge.fire(), "collision course must trigger dodge");
        check(!policy.decide(world(player, List.of(track(2, Kind.ENEMY, .8f, .5f, 0, 0, false)), List.of()), 0).fire(), "lost target must not fire");
        check(!policy.decide(world(player, List.of(track(4, Kind.ENEMY, .8f, .5f, 0, 0, true)), List.of()), 0).fire(), "new target must settle aim");
        check(policy.decide(world(track(1, Kind.PLAYER, .5f, .5f, 0, 0, false), List.of(enemy), List.of()), 0).move().len() == 0, "lost player must idle");
        AgentCore core = new AgentCore();
        core.observePipelineLatency(Float.NaN);
        core.analyze(new Bitmap(160, 160), 1000);
        check(!core.telemetry().contains("NaN"), "nonfinite latency must be ignored");
        VisionPipeline vision = new VisionPipeline();
        Bitmap image = new Bitmap(240, 240);
        for (int y = 108; y < 132; y++) for (int x = 108; x < 132; x++) image.setPixel(x, y, 0xff32aaf5);
        check(vision.detect(image, 1000, 0).player() != null, "synthetic player must be detected");
        check(vision.detect(new Bitmap(240, 240), 2000, 0).player() == null, "capture gap must clear stale tracks");
        vision.detect(image, 2100, 0);
        check(vision.detect(new Bitmap(260, 240), 2133, 0).player() == null, "resize must clear stale tracks");
        vision.detect(image, 2200, 0);
        vision.detect(null, 2233, 0);
        check(vision.detect(new Bitmap(240, 240), 2266, 0).player() == null, "invalid frame must clear stale tracks");
        VisionPipeline hud = new VisionPipeline(true);
        Bitmap hudImage = new Bitmap(640, 287);
        for (int y = 110; y < 116; y++) for (int x = 294; x < 324; x++) hudImage.setPixel(x, y, 0xff50dc50);
        for (int y = 105; y < 109; y++) for (int x = 300; x < 310; x++) hudImage.setPixel(x, y, 0xffffffff);
        check(hud.detect(hudImage, 1000, 0).player() != null, "HP fill plus text must identify player");
        Bitmap greenOnly = new Bitmap(640, 287);
        for (int y = 110; y < 116; y++) for (int x = 294; x < 324; x++) greenOnly.setPixel(x, y, 0xff50dc50);
        check(new VisionPipeline(true).detect(greenOnly, 1000, 0).player() == null, "Green scenery without HP text must not identify player");
        check(new VisionPipeline(true).detect(image, 1000, 0).player() == null, "Game mode must not fall back to generic color player");
        System.out.println("AgentCore: " + checks + " regression checks passed");
    }
}
