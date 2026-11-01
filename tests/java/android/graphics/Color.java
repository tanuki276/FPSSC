package android.graphics;

public final class Color {
    public static int red(int color) { return (color >>> 16) & 255; }
    public static int green(int color) { return (color >>> 8) & 255; }
    public static int blue(int color) { return color & 255; }
}
