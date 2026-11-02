package com.tanuki276.fpssc;

/** Explicit resume prevents automated gestures interrupting a pin menu or a held finger. */
final class ManualInputGate {
    private volatile boolean paused;
    boolean paused() { return paused; }
    void pause() { paused=true; }
    void resume() { paused=false; }
    boolean allows(boolean armed) { return armed && !paused; }
}
