package com.tanuki276.fpssc;
import android.graphics.Bitmap;
import javax.imageio.ImageIO;
import java.io.File;

/** Run locally with an explicitly supplied screenshot; no user image is stored in the repository. */
public final class ScreenshotRegressionTest {
    public static void main(String[] args) throws Exception {
        var image = ImageIO.read(new File(args[0]));
        java.nio.ByteBuffer rgba=java.nio.ByteBuffer.allocate(image.getWidth()*image.getHeight()*4);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int color=image.getRGB(x,y);
            rgba.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)255);
        }
        rgba.flip();
        FramePixels frame=FramePixels.sampleRgba(rgba,image.getWidth(),image.getHeight(),4,image.getWidth()*4,640,null);
        ControlLocator locator = new ControlLocator();
        var layout = locator.detectPixels(frame);
        System.out.println("automatic layout=" + layout);
        if (layout == null || Math.abs(layout.profile().moveX() - .149f) > .025f
                || Math.abs(layout.profile().moveY() - .753f) > .035f
                || Math.abs(layout.profile().attackX() - .84f) > .025f
                || Math.abs(layout.profile().attackY() - .61f) > .04f)
            throw new AssertionError("Control centers not detected");
        AgentCore core = new AgentCore(true);
        System.out.println(core.analyzePixels(frame, 1000,0));
        System.out.println(core.analyzePixels(frame, 1033,0));
        System.out.println(core.telemetry());
        var state = new AgentCore.VisionPipeline(true).detectPixels(frame, 1000, 0);
        if (state.player() == null || Math.abs(state.player().pos().x() - .48f) > .04f
                || Math.abs(state.player().pos().y() - .50f) > .05f) throw new AssertionError("Player anchor missed");
        if (state.enemies().size() != 4) throw new AssertionError("Expected 4 enemy robots, got " + state.enemies().size());
        if (!state.projectiles().isEmpty()) throw new AssertionError("Static scene reported moving shots");
        var command = core.analyzePixels(frame, 1066,0);
        if (command.move().len() < .1f || !command.fire()) throw new AssertionError("No movement/fire decision");
        if (new AgentCore.VisionPipeline(true).detect(new Bitmap(640, 287), 1000, 0).player() != null)
            throw new AssertionError("Blank game frame must not invent a player");
        System.out.println("Screenshot: 6 regression checks passed");
        System.out.println("player=" + state.player());
        System.out.println("enemies=" + state.enemies());
        System.out.println("projectiles=" + state.projectiles());
    }
}
