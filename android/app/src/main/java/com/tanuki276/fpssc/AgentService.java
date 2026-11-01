package com.tanuki276.fpssc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "fpssc_agent";
    private static final int NOTIFICATION_ID = 2761;

    // 640px keeps small projectiles materially clearer than the old 480px analysis frame,
    // while still being much cheaper than running the detector over the native screenshot.
    private static final int ANALYSIS_WIDTH = 640;

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private AgentCore core;
    private MediaProjection.Callback projectionCallback;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width;
    private int height;
    private int densityDpi;
    private long frameCount;
    private long droppedFrames;

    @Override
    public void onCreate() {
        super.onCreate();
        captureThread = new HandlerThread("fpssc-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        NotificationChannel channel = new NotificationChannel(CHANNEL, "FPSSC agent", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = android.os.Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("starting capture"));
        captureHandler.post(() -> {
            // A consent token must not be reused; repeated starts keep the active session.
            if (projection != null) return;
            try {
                startCapture(resultCode, resultData);
            } catch (RuntimeException e) {
                Log.e("FPSSC", "capture setup failed", e);
                stopCapture();
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        core = new AgentCore();
        frameCount = 0;
        droppedFrames = 0;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        width = dm.widthPixels;
        height = dm.heightPixels;
        densityDpi = dm.densityDpi;

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return;
        }

        MediaProjection session = projection;
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                if (projection == session) {
                    stopCapture();
                    stopSelf();
                }
            }
        };
        projection.registerCallback(projectionCallback, captureHandler);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, captureHandler);
        display = projection.createVirtualDisplay(
                "FPSSC-capture", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);
    }

    private void onImage(ImageReader source) {
        // Setup, callbacks, and cleanup all run on the capture thread.
        if (source != reader) return;
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (IllegalStateException e) {
            Log.w("FPSSC", "capture reader unavailable", e);
            return;
        }
        if (image == null) return;
        if (!processing.compareAndSet(false, true)) {
            droppedFrames++;
            image.close();
            return;
        }

        Bitmap frame = null;
        Bitmap scaled = null;
        long callbackAt = SystemClock.elapsedRealtime();
        try {
            long imageTimestampNs = image.getTimestamp();
            long imageAgeMs = 0;
            if (imageTimestampNs > 0) {
                long nowNs = SystemClock.elapsedRealtimeNanos();
                imageAgeMs = Math.max(0L, Math.min(500L, (nowNs - imageTimestampNs) / 1_000_000L));
            }

            long convertStart = SystemClock.elapsedRealtime();
            frame = imageToBitmap(image);
            if (frame == null) return;

            int targetWidth = Math.min(ANALYSIS_WIDTH, frame.getWidth());
            int targetHeight = Math.max(1, Math.round(frame.getHeight() * (targetWidth / (float) frame.getWidth())));
            scaled = frame.getWidth() == targetWidth
                    ? frame
                    : Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, false);
            long convertEnd = SystemClock.elapsedRealtime();

            long visionStart = SystemClock.elapsedRealtime();
            AgentCore.ControlCommand command = core.analyze(scaled, visionStart, imageAgeMs);
            long end = SystemClock.elapsedRealtime();
            long visionMs = end - visionStart;
            long totalMs = end - callbackAt;
            core.observePipelineLatency(totalMs);
            frameCount++;

            // Preview-only: no system-wide touch injection is performed here.
            Log.i("FPSSC", "frame=" + frameCount
                    + " move=" + command.move()
                    + " aim=" + command.aim()
                    + " fire=" + command.fire()
                    + " reason=" + command.reason()
                    + " imageAgeMs=" + imageAgeMs
                    + " convertMs=" + (convertEnd - convertStart)
                    + " visionMs=" + visionMs
                    + " totalMs=" + totalMs
                    + " dropped=" + droppedFrames
                    + " size=" + scaled.getWidth() + "x" + scaled.getHeight()
                    + " " + core.telemetry());

            if (frameCount % 8 == 0 && (android.os.Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                getSystemService(NotificationManager.class).notify(
                        NOTIFICATION_ID,
                        notification(command.reason() + " · " + core.telemetry()));
            }
        } catch (RuntimeException t) {
            Log.e("FPSSC", "frame failed", t);
        } finally {
            if (scaled != null && scaled != frame) scaled.recycle();
            if (frame != null) frame.recycle();
            image.close();
            processing.set(false);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (pixelStride != 4) throw new IllegalStateException("Unsupported RGBA pixel stride: " + pixelStride);
        int rowBytes = Math.multiplyExact(imageWidth, pixelStride);
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(rowBytes, imageHeight));
        int origin = buffer.position();
        // The last row need not include padding; copy only actual image pixels.
        for (int y = 0; y < imageHeight; y++) {
            ByteBuffer row = buffer.duplicate();
            int start = Math.addExact(origin, Math.multiplyExact(y, rowStride));
            row.limit(Math.addExact(start, rowBytes));
            row.position(start);
            packed.put(row);
        }
        packed.flip();
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        try {
            bitmap.copyPixelsFromBuffer(packed);
            return bitmap;
        } catch (RuntimeException e) {
            bitmap.recycle();
            throw e;
        }
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("FPSSC Android")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private static void release(String resource, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.w("FPSSC", "Failed to release " + resource, e);
        }
    }

    private void stopCapture() {
        ImageReader oldReader = reader;
        VirtualDisplay oldDisplay = display;
        MediaProjection session = projection;
        MediaProjection.Callback callback = projectionCallback;
        reader = null;
        display = null;
        projection = null;
        projectionCallback = null;
        core = null;
        if (oldReader != null) release("image listener", () -> oldReader.setOnImageAvailableListener(null, null));
        if (oldDisplay != null) release("virtual display", oldDisplay::release);
        if (oldReader != null) release("image reader", oldReader::close);
        if (session != null) {
            if (callback != null) release("projection callback", () -> session.unregisterCallback(callback));
            release("media projection", session::stop);
        }
    }

    @Override public void onDestroy() {
        // Queue cleanup after any in-flight analysis, before terminating its looper.
        HandlerThread thread = captureThread;
        captureHandler.post(() -> {
            try {
                stopCapture();
            } finally {
                thread.quitSafely();
            }
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
