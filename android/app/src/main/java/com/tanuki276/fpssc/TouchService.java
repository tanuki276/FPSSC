package com.tanuki276.fpssc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/** Short, nonqueued two-finger gestures. No root, shell commands, or background-app touches. */
public final class TouchService extends AccessibilityService {
    private static final String GAME = "com.supercell.brawlstars";
    private static volatile TouchService instance;
    private static volatile boolean armed;
    private volatile String foreground = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;
    private final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
    private long generation;
    private long lastAccepted;
    static volatile String inputStatus = "操作サービス未接続";

    static boolean isArmed() { return armed; }
    static boolean connected() { return instance != null; }
    static void arm(boolean enabled) {
        armed = enabled && connected();
        inputStatus = armed ? "自動操作待機中" : "自動操作 OFF";
        if (instance != null) instance.main.post(() -> {
            if (instance != null) instance.generation++;
        });
    }
    static void submit(AgentCore.ControlCommand command, TouchProfile profile, long frameTime, int width, int height) {
        TouchService service = instance;
        if (service == null || !armed) return;
        // Never enqueue an unbounded stream of old commands on the UI thread.
        if (!service.pending.compareAndSet(false, true)) return;
        service.main.post(() -> {
            try { service.apply(command, profile, frameTime, width, height); }
            finally { service.pending.set(false); }
        });
    }
    @Override protected void onServiceConnected() {
        instance = this;
        arm(false);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            foreground = event.getPackageName().toString();
        }
    }
    @Override public void onInterrupt() { arm(false); }
    @Override public void onDestroy() {
        arm(false);
        instance = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private void apply(AgentCore.ControlCommand command, TouchProfile profile, long frameTime, int width, int height) {
        long now = SystemClock.elapsedRealtime();
        if (!armed || busy) return;
        if (!GAME.equals(foreground)) {
            inputStatus = "待機: ブロスタが前面ではありません (" + foreground + ")";
            return;
        }
        if (now - frameTime > 200 || now < frameTime) {
            inputStatus = "操作保留: 判断が古い " + (now - frameTime) + " ms";
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        if (width <= height || width != metrics.widthPixels || height != metrics.heightPixels) {
            inputStatus = "停止: 横画面の全画面共有で開始してください";
            return;
        }
        if (now - lastAccepted < 100) return;
        if (profile == null) { inputStatus = "操作保留: 操作ボタンの自動認識を待っています"; return; }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        boolean movement = command.move().len() > .01f;
        boolean fire = command.fire() && command.aim().len() > .01f;
        if (!movement && !fire) { inputStatus = "操作保留: " + command.reason(); return; }
        if (movement) builder.addStroke(stroke(profile.moveX(), profile.moveY(), command.move(), profile.radius(), width, height));
        if (fire) builder.addStroke(stroke(profile.attackX(), profile.attackY(), command.aim(), profile.radius(), width, height));
        busy = true;
        lastAccepted = now;
        long token = ++generation;
        try {
            boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gesture) { busy = false; }
                @Override public void onCancelled(GestureDescription gesture) {
                    busy = false;
                    if (generation == token) { armed = false; inputStatus = "操作が中断されました。再度 ON にしてください"; }
                }
            }, main);
            if (!accepted) { busy = false; armed = false; inputStatus = "操作を送信できませんでした"; }
            else inputStatus = "操作中: move=" + movement + " fire=" + fire;
        } catch (RuntimeException e) {
            busy = false;
            arm(false);
            inputStatus = "操作エラー: " + e.getClass().getSimpleName();
        }
    }
    private static GestureDescription.StrokeDescription stroke(float x, float y, AgentCore.Vec2 direction,
            float radius, int width, int height) {
        AgentCore.Vec2 vector = TouchProfile.screenVector(direction, width, height);
        Path path = new Path();
        path.moveTo(x * width, y * height);
        path.lineTo(Math.max(1, Math.min(width - 1, x * width + vector.x() * radius * height)),
                Math.max(1, Math.min(height - 1, y * height + vector.y() * radius * height)));
        return new GestureDescription.StrokeDescription(path, 0, 90);
    }
}
