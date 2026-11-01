package com.tanuki276.fpssc;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Active-window verification and bounded command delivery to the continued-gesture backend. */
public final class TouchService extends AccessibilityService {
    private static final String GAME="com.supercell.brawlstars";
    private static volatile TouchService instance;
    private static volatile boolean armed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean pending=new java.util.concurrent.atomic.AtomicBoolean();
    private GestureEngine engine;
    private volatile String foreground="未確認";
    private static volatile String lastGameStatus="まだゲーム中の操作要求はありません";
    private static volatile long lastGameAt;
    static volatile String inputStatus="操作サービス未接続";

    static boolean connected() { return instance!=null; }
    static boolean isArmed() { return armed; }
    static void arm(boolean enabled) {
        armed=enabled&&connected();
        inputStatus=armed?"自動操作待機中":"自動操作 OFF";
        TouchService service=instance;
        if(!armed&&service!=null) service.main.post(()->service.engine.stop());
    }
    static void pause() {
        TouchService service=instance;
        if(service!=null) service.main.post(()->service.engine.stop());
    }
    static String diagnostics() {
        TouchService s=instance;
        GestureEngine e=s==null?null:s.engine;
        return inputStatus+"\n直近のゲーム中: "+lastGameStatus
                +(lastGameAt==0?"":" ("+(SystemClock.elapsedRealtime()-lastGameAt)+" ms前)")
                +(e==null?"":"\n送信="+e.sent+" 完了="+e.completed+" 中断="+e.cancelled+" 移動継続="+e.holdingMovement());
    }
    private void gameStatus(String value) {
        inputStatus=value; lastGameStatus=value; lastGameAt=SystemClock.elapsedRealtime();
    }
    static void submit(AgentCore.ControlCommand command,TouchProfile profile,long time,int width,int height) {
        TouchService s=instance;
        if(s==null||!armed||!s.pending.compareAndSet(false,true)) return;
        s.main.post(()->{
            try { s.apply(command,profile,time,width,height); }
            finally { s.pending.set(false); }
        });
    }
    @Override protected void onServiceConnected() {
        instance=this;
        engine=new GestureEngine(this,()->armed&&gameActive(),message->{ arm(false); gameStatus(message); });
        arm(false);
    }
    // Input decisions query the active window, not a cached event package from FPSSC's setup screen.
    private boolean gameActive() {
        AccessibilityNodeInfo root=null;
        try {
            root=getRootInActiveWindow();
            foreground=root==null||root.getPackageName()==null?"ウィンドウ未取得（操作サービスを OFF/ON）":root.getPackageName().toString();
            return GAME.equals(foreground);
        } catch(RuntimeException e) { foreground="取得失敗"; return false; }
        finally { if(root!=null) root.recycle(); }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(armed&&!gameActive()&&engine!=null) engine.stop();
    }
    @Override public void onInterrupt() { arm(false); }
    @Override public void onDestroy() {
        arm(false); instance=null;
        if(engine!=null) engine.stop();
        super.onDestroy();
    }
    private void apply(AgentCore.ControlCommand command,TouchProfile profile,long time,int width,int height) {
        if(!armed) return;
        if(!gameActive()) {
            inputStatus="待機: 前面アプリ="+foreground;
            engine.stop(); return;
        }
        long age=SystemClock.elapsedRealtime()-time;
        if(age<0||age>200) { gameStatus("操作保留: 判断が古い "+age+" ms"); engine.stop(); return; }
        DisplayMetrics dm=new DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        if(width<=height||width!=dm.widthPixels||height!=dm.heightPixels) {
            gameStatus("操作保留: 横画面の全画面共有で再開始してください"); engine.stop(); return;
        }
        if(profile==null) { gameStatus("操作保留: 操作ボタンを自動認識中"); engine.stop(); return; }
        gameStatus("操作要求: move="+command.move()+" fire="+command.fire());
        engine.offer(command,profile,time,width,height);
    }
    // Package-private, no exported entry point; instrumentation can test actual dispatch without spoofing a game package.
    static TouchService instrumentationInstance() { return BuildConfig.DEBUG?instance:null; }
    GestureEngine instrumentationEngine(java.util.function.BooleanSupplier allowed,java.util.function.Consumer<String> failure) {
        if(!BuildConfig.DEBUG) throw new IllegalStateException("Debug tests only");
        return new GestureEngine(this,allowed,failure);
    }
}
