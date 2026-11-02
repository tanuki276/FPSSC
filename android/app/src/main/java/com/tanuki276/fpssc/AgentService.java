package com.tanuki276.fpssc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
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

import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "fpssc_agent";
    private static final int NOTIFICATION_ID = 2761;
    private static final int ANALYSIS_WIDTH = 640;

    private final AtomicBoolean processing = new AtomicBoolean(false);
    static volatile String status = "停止中";
    static volatile String overlayStatus = "FSCSR · 画面共有待機";
    static volatile boolean active;
    static volatile long statusTime;

    private AgentCore core;
    private LiveCombatController combat;
    private FrameTiming frameTiming;
    private ControlLocator controlLocator;
    private PracticeVisionDiagnostics visionDiagnostics;
    private FramePixels analysisFrame;
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

    @Override public void onCreate() {
        super.onCreate();
        captureThread = new HandlerThread("fpssc-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        NotificationChannel channel = new NotificationChannel(CHANNEL, "FSCSR agent", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            TouchService.arm(false);
            stopSelf();
            return START_NOT_STICKY;
        }
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
            if (projection != null) return;
            try { startCapture(resultCode, resultData); }
            catch (RuntimeException e) {
                Log.e("FSCSR", "capture setup failed", e);
                stopCapture();
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void resetAnalyzers() {
        core = new AgentCore(true);
        combat = new LiveCombatController();
        frameTiming = new FrameTiming();
        controlLocator = new ControlLocator();
        visionDiagnostics = new PracticeVisionDiagnostics();
        analysisFrame = null;
    }

    private void startCapture(int resultCode, Intent resultData) {
        status = "画面を取得しています…";
        statusTime = SystemClock.elapsedRealtime();
        resetAnalyzers();
        frameCount = 0;
        droppedFrames = 0;

        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        densityDpi = dm.densityDpi;

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) { stopSelf(); return; }

        MediaProjection session = projection;
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onCapturedContentResize(int contentWidth,int contentHeight) {
                if(projection==session) resizeCapture(contentWidth,contentHeight);
            }
            @Override public void onStop() {
                if (projection == session) { stopCapture(); stopSelf(); }
            }
        };
        projection.registerCallback(projectionCallback, captureHandler);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, captureHandler);
        display = projection.createVirtualDisplay("FSCSR-capture", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);
        active=true;
    }

    private void resizeCapture(int newWidth,int newHeight) {
        if(display==null||newWidth<96||newHeight<96||(newWidth==width&&newHeight==height)) return;
        TouchService.pause();
        ImageReader replacement=ImageReader.newInstance(newWidth,newHeight,PixelFormat.RGBA_8888,3);
        ImageReader previous=reader;
        try {
            display.setSurface(null);
            display.resize(newWidth,newHeight,densityDpi);
            display.setSurface(replacement.getSurface());
            reader=replacement; width=newWidth; height=newHeight;
            reader.setOnImageAvailableListener(this::onImage,captureHandler);
            resetAnalyzers();
            status="画面サイズ変更: 認識を再初期化中";
        } catch(RuntimeException e) {
            replacement.close();
            status="画面サイズ変更に失敗しました。画面共有を再開始してください";
            stopSelf();
        } finally {
            if(previous!=null) { previous.setOnImageAvailableListener(null,null); previous.close(); }
        }
    }

    private void onImage(ImageReader source) {
        if (source != reader) return;
        Image image;
        try { image = source.acquireLatestImage(); }
        catch (IllegalStateException e) { Log.w("FSCSR", "capture reader unavailable", e); return; }
        if (image == null) return;
        if (!processing.compareAndSet(false, true)) {
            droppedFrames++;
            image.close();
            return;
        }

        long callbackAt = SystemClock.elapsedRealtime();
        try {
            long imageAgeMs = frameTiming.observe(image.getTimestamp(), callbackAt);
            if (imageAgeMs < 0) {
                controlLocator.clear();
                status = "画像の時刻が無効または重複しています。操作を保留中";
                return;
            }

            long convertStart = SystemClock.elapsedRealtime();
            Image.Plane[] planes=image.getPlanes();
            if(planes.length==0) return;
            Image.Plane plane=planes[0];
            analysisFrame=FramePixels.sampleRgba(plane.getBuffer(),image.getWidth(),image.getHeight(),
                    plane.getPixelStride(),plane.getRowStride(),ANALYSIS_WIDTH,analysisFrame);
            GameOverlay.excludeFrom(analysisFrame, width, height);
            long convertEnd = SystemClock.elapsedRealtime();

            long visionStart = SystemClock.elapsedRealtime();
            PracticeVisionDiagnostics.Result vision = visionDiagnostics.analyze(analysisFrame, visionStart);
            AgentCore.WorldState world = core.perceivePixels(analysisFrame, visionStart, imageAgeMs);
            ControlLocator.Layout layout = controlLocator.updatePixels(analysisFrame, visionStart, core.hasObservedPlayer());
            AgentCore.ControlCommand command = combat.decide(analysisFrame, world,
                    PracticeProfileStore.selected(this), visionStart, TouchService.isArmed() && layout != null,
                    TouchService.dispatchedAttacks());
            TouchService.submit(command, layout == null ? null : layout.profile(), callbackAt - imageAgeMs, width, height);

            long end = SystemClock.elapsedRealtime();
            long visionMs = end - visionStart;
            long totalMs = end - callbackAt;
            core.observePipelineLatency(totalMs);
            combat.observeLatency(totalMs);
            frameCount++;
            BrawlerProfile selected = PracticeProfileStore.selected(this);

            status = "解析中 · frame " + frameCount
                    + "\n選択プロファイル " + selected.label()
                    + "\n" + vision.detail()
                    + "\n" + command.reason()
                    + "\n移動 " + command.move() + " / 照準 " + command.aim()
                    + " / 射撃 " + command.fire()
                    + "\n" + core.telemetry()
                    + "\n" + controlLocator.status()
                    + (layout == null ? "" : "\n" + layout.profile())
                    + "\n処理時間 " + totalMs + " ms";

            overlayStatus = PracticeProfileStore.compactLabel(this)
                    + " · " + totalMs + "ms · " + vision.compact();
            statusTime = SystemClock.elapsedRealtime();

            Log.i("FSCSR", "frame=" + frameCount
                    + " profile=" + selected.id()
                    + " vision=" + vision.compact()
                    + " move=" + command.move()
                    + " aim=" + command.aim()
                    + " fire=" + command.fire()
                    + " reason=" + command.reason()
                    + " imageAgeMs=" + imageAgeMs
                    + " convertMs=" + (convertEnd-convertStart)
                    + " visionMs=" + visionMs
                    + " totalMs=" + totalMs
                    + " dropped=" + droppedFrames
                    + " size=" + analysisFrame.getWidth() + "x" + analysisFrame.getHeight()
                    + " " + core.telemetry());

            if (frameCount % 8 == 0 && (android.os.Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification(PracticeProfileStore.compactLabel(this) + " · " + vision.compact()));
            }
        } catch (RuntimeException t) {
            status = "解析エラー: " + t.getClass().getSimpleName();
            Log.e("FSCSR", "frame failed", t);
        } finally {
            image.close();
            processing.set(false);
        }
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.tanuki276.fpssc.R.drawable.ic_stat_agent)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止",
                        android.app.PendingIntent.getService(this, 1, new Intent(this, AgentService.class).setAction("stop"),
                                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .setContentTitle("FSCSR Android")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private static void release(String resource, Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { Log.w("FSCSR", "Failed to release " + resource, e); }
    }

    private void stopCapture() {
        active=false;
        TouchService.arm(false);
        ImageReader oldReader = reader;
        VirtualDisplay oldDisplay = display;
        MediaProjection session = projection;
        MediaProjection.Callback callback = projectionCallback;
        reader = null;
        display = null;
        projection = null;
        projectionCallback = null;
        core = null;
        visionDiagnostics = null;
        analysisFrame = null;
        status = "停止中";
        overlayStatus = "FSCSR · 停止中";
        if (oldReader != null) release("image listener", () -> oldReader.setOnImageAvailableListener(null, null));
        if (oldDisplay != null) release("virtual display", oldDisplay::release);
        if (oldReader != null) release("image reader", oldReader::close);
        if (session != null) {
            if (callback != null) release("projection callback", () -> session.unregisterCallback(callback));
            release("media projection", session::stop);
        }
    }

    @Override public void onDestroy() {
        TouchService.arm(false);
        HandlerThread thread = captureThread;
        captureHandler.post(() -> {
            try { stopCapture(); }
            finally { thread.quitSafely(); }
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
