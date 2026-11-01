package com.tanuki276.fpssc;
import android.graphics.Bitmap;
import javax.imageio.ImageIO;
import java.io.File;

/** Run locally with an explicitly supplied screenshot; no user image is stored in the repository. */
public final class ScreenshotRegressionTest {
    public static void main(String[] args) throws Exception {
        var image = ImageIO.read(new File(args[0]));
        int w = 640, h = Math.round(image.getHeight() * w / (float) image.getWidth());
        Bitmap frame = new Bitmap(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            frame.setPixel(x, y, image.getRGB(Math.min(image.getWidth()-1, (int)((x+.5f)*image.getWidth()/w)),
                    Math.min(image.getHeight()-1, (int)((y+.5f)*image.getHeight()/h))));
        }
        ControlLocator locator = new ControlLocator();
        var layout = locator.detect(frame);
        System.out.println("automatic layout=" + layout);
        if (layout == null || Math.abs(layout.profile().moveX() - .149f) > .025f
                || Math.abs(layout.profile().moveY() - .753f) > .035f
                || Math.abs(layout.profile().attackX() - .84f) > .025f
                || Math.abs(layout.profile().attackY() - .61f) > .04f)
            throw new AssertionError("Control centers not detected");
        AgentCore core = new AgentCore(true);
        System.out.println(core.analyze(frame, 1000));
        System.out.println(core.analyze(frame, 1033));
        System.out.println(core.telemetry());
        var state = new AgentCore.VisionPipeline(true).detect(frame, 1000, 0);
        if (state.player() == null || Math.abs(state.player().pos().x() - .48f) > .04f
                || Math.abs(state.player().pos().y() - .50f) > .05f) throw new AssertionError("Player anchor missed");
        if (state.enemies().size() != 4) throw new AssertionError("Expected 4 enemy robots, got " + state.enemies().size());
        if (!state.projectiles().isEmpty()) throw new AssertionError("Static scene reported moving shots");
        var command = core.analyze(frame, 1066);
        if (command.move().len() < .1f || !command.fire()) throw new AssertionError("No movement/fire decision");
        if (new AgentCore.VisionPipeline(true).detect(new Bitmap(640, 287), 1000, 0).player() != null)
            throw new AssertionError("Blank game frame must not invent a player");
        System.out.println("Screenshot: 6 regression checks passed");
        System.out.println("player=" + state.player());
        System.out.println("enemies=" + state.enemies());
        System.out.println("projectiles=" + state.projectiles());
    }
}
