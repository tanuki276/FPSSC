package android.graphics;

/** Minimal in-memory fixture for testing the real AgentCore without the Android SDK. */
public final class Bitmap {
    private final int width, height;
    private final int[] pixels;
    public Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
        pixels = new int[width * height];
    }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getPixel(int x, int y) { return pixels[y * width + x]; }
    public void setPixel(int x, int y, int color) { pixels[y * width + x] = color; }
}
