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

/** Compact translucent practice controls plus an optional touch-through diagnostic strip. */
final class GameOverlay {
    private final AccessibilityService service;
    private final WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout controls;
    private TextView diagnostics;
    private Button toggle;
    private Button profile;
    private Button details;
    private boolean expanded;
    private static volatile int[][] exclusions=new int[0][];

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if(controls==null) return;
            toggle.setText(TouchService.isArmed()?"AI ON":"AI OFF");
            profile.setText(PracticeProfileStore.compactLabel(service));
            diagnostics.setVisibility(expanded?View.VISIBLE:View.GONE);
            diagnostics.setMaxLines(expanded?10:2);
            if(expanded) diagnostics.setText("FSCSR " + BuildConfig.VERSION_NAME + "\n" + AgentService.status
                    + "\n" + TouchService.diagnostics());
            updateExclusions();
            handler.postDelayed(this,300);
        }
    };

    GameOverlay(AccessibilityService service) {
        this.service=service;
        manager=service.getSystemService(WindowManager.class);
    }

    private int dp(int v) { return Math.round(v*service.getResources().getDisplayMetrics().density); }

    private WindowManager.LayoutParams params(int width,int height,int flags,int y) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|flags,
                PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;
        p.x=dp(6);
        p.y=y;
        return p;
    }

    private void compactButton(Button b,int widthDp) {
        b.setTextSize(10f);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4),0,dp(4),0);
        b.setAlpha(.82f);
        controls.addView(b,new LinearLayout.LayoutParams(dp(widthDp),dp(32)));
    }

    void show() {
        if(controls!=null) return;
        controls=new LinearLayout(service);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(1),dp(1),dp(1),dp(1));
        controls.setBackgroundColor(0x30202020);
        controls.setAlpha(.90f);

        toggle=new Button(service);
        toggle.setText("AI OFF");
        toggle.setOnClickListener(v->{
            if(!AgentService.active) {
                android.widget.Toast.makeText(service,"先にFSCSRで画面共有を開始してください",android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            TouchService.arm(!TouchService.isArmed());
            toggle.setText(TouchService.isArmed()?"AI ON":"AI OFF");
        });
        compactButton(toggle,58);

        profile=new Button(service);
        profile.setText(PracticeProfileStore.compactLabel(service));
        profile.setOnClickListener(v->{
            PracticeProfileStore.step(service,1);
            profile.setText(PracticeProfileStore.compactLabel(service));
        });
        profile.setOnLongClickListener(v->{
            PracticeProfileStore.step(service,-1);
            profile.setText(PracticeProfileStore.compactLabel(service));
            return true;
        });
        compactButton(profile,112);

        details=new Button(service);
        details.setText("⋯");
        details.setOnClickListener(v->{
            expanded=!expanded;
            details.setText(expanded?"×":"⋯");
            diagnostics.setVisibility(expanded?View.VISIBLE:View.GONE);
            updateExclusions();
        });
        compactButton(details,30);

        diagnostics=new TextView(service);
        diagnostics.setTextSize(9f);
        diagnostics.setTextColor(0xffeeeeee);
        diagnostics.setShadowLayer(2,1,1,0xff000000);
        diagnostics.setBackgroundColor(0x24202020);
        diagnostics.setPadding(dp(4),dp(1),dp(4),dp(1));
        diagnostics.setMaxLines(10);
        diagnostics.setAlpha(.84f);
        diagnostics.setVisibility(View.GONE);

        try {
            manager.addView(controls,params(dp(203),dp(34),0,0));
            manager.addView(diagnostics,params(dp(300),WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,dp(35)));
            controls.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateExclusions());
            diagnostics.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateExclusions());
            handler.post(refresh);
        } catch(RuntimeException e) {
            hide();
            TouchService.inputStatus="オーバーレイ表示失敗: "+e.getClass().getSimpleName();
        }
    }

    private void updateExclusions() {
        if(controls==null) return;
        java.util.ArrayList<int[]> regions=new java.util.ArrayList<>();
        for(View v:new View[]{controls,diagnostics}) {
            if(v.getVisibility()!=View.VISIBLE || !v.isAttachedToWindow()) continue;
            int[] location=new int[2];
            v.getLocationOnScreen(location);
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
        controls=null;
        diagnostics=null;
        exclusions=new int[0][];
    }

    int[] toggleCenter() {
        int[] point=new int[2];
        toggle.getLocationOnScreen(point);
        point[0]+=toggle.getWidth()/2;
        point[1]+=toggle.getHeight()/2;
        return point;
    }

    boolean visible() { return controls!=null && controls.isAttachedToWindow(); }
}
