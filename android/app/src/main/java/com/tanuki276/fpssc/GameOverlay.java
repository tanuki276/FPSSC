package com.tanuki276.fpssc;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small trusted control window plus a separate, touch-through transparent diagnostic window. */
final class GameOverlay {
    private final AccessibilityService service;
    private final WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout controls;
    private TextView diagnostics;
    private Button toggle;
    private boolean expanded;
    private static volatile int[][] exclusions=new int[0][];
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if(controls==null) return;
            toggle.setText(TouchService.isArmed()?"自動 ON":"自動 OFF");
            diagnostics.setMaxLines(expanded?13:4);
            diagnostics.setText(expanded ? "FSCSR " + BuildConfig.VERSION_NAME + "\n" + AgentService.status
                    + "\n" + TouchService.diagnostics() : AgentService.overlayStatus + "\n" + TouchService.inputStatus);
            updateExclusions();
            handler.postDelayed(this,350);
        }
    };
    GameOverlay(AccessibilityService service) {
        this.service=service; manager=service.getSystemService(WindowManager.class);
    }
    private int dp(int v) { return Math.round(v*service.getResources().getDisplayMetrics().density); }
    private WindowManager.LayoutParams params(int width,int height,int flags,int y) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|flags,
                PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT; p.x=dp(8); p.y=y;
        return p;
    }
    void show() {
        if(controls!=null) return;
        controls=new LinearLayout(service);
        controls.setBackgroundColor(0x55202020);
        toggle=new Button(service); toggle.setText("自動 OFF");
        toggle.setTextSize(12); toggle.setMinHeight(0); toggle.setMinimumHeight(0);
        toggle.setOnClickListener(v->{
            if(!AgentService.active) {
                android.widget.Toast.makeText(service,"先にFSCSRで画面共有を開始してください",android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            TouchService.arm(!TouchService.isArmed());
            toggle.setText(TouchService.isArmed()?"自動 ON":"自動 OFF");
        });
        controls.addView(toggle,new LinearLayout.LayoutParams(dp(110),dp(42)));
        Button details=new Button(service); details.setText("診断 ＋"); details.setTextSize(12);
        details.setMinHeight(0); details.setMinimumHeight(0);
        details.setOnClickListener(v->{ expanded=!expanded; details.setText(expanded?"診断 −":"診断 ＋"); });
        controls.addView(details,new LinearLayout.LayoutParams(dp(90),dp(42)));
        diagnostics=new TextView(service);
        diagnostics.setTextSize(10); diagnostics.setTextColor(0xffeeeeee);
        diagnostics.setShadowLayer(2,1,1,0xff000000);
        diagnostics.setBackgroundColor(0x35202020);
        diagnostics.setPadding(dp(4),dp(2),dp(4),dp(2));
        diagnostics.setMaxLines(13);
        try {
            manager.addView(controls,params(dp(200),dp(42),0,0));
            manager.addView(diagnostics,params(dp(290),WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,dp(44)));
            controls.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateExclusions());
            diagnostics.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateExclusions());
            handler.post(refresh);
        } catch(RuntimeException e) { hide(); TouchService.inputStatus="オーバーレイ表示失敗: "+e.getClass().getSimpleName(); }
    }
    private void updateExclusions() {
        if(controls==null) return;
        java.util.ArrayList<int[]> regions=new java.util.ArrayList<>();
        for(View v:new View[]{controls,diagnostics}) {
            if(v.getVisibility()!=View.VISIBLE || !v.isAttachedToWindow()) continue;
            int[] location=new int[2]; v.getLocationOnScreen(location);
            regions.add(new int[]{location[0]-2,location[1]-2,location[0]+v.getWidth()+2,location[1]+v.getHeight()+2});
        }
        exclusions=regions.toArray(new int[0][]);
    }
    static void excludeFrom(FramePixels frame,int displayWidth,int displayHeight) {
        for(int[] r:exclusions) frame.maskRect(r[0]/(float)displayWidth,r[1]/(float)displayHeight,
                r[2]/(float)displayWidth,r[3]/(float)displayHeight);
    }
    void hide() {
        handler.removeCallbacks(refresh);
        for(View v:new View[]{controls,diagnostics}) {
            if(v!=null && v.isAttachedToWindow()) manager.removeView(v);
        }
        controls=null; diagnostics=null; exclusions=new int[0][];
    }
    boolean visible() { return controls!=null && controls.isAttachedToWindow(); }
}
