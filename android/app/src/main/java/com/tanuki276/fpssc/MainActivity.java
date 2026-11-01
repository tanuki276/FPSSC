package com.tanuki276.fpssc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 276;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView statusView;
    private android.widget.Switch automatic;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            statusView.setText(AgentService.status + "\n" + TouchService.diagnostics());
            if (automatic != null && automatic.isChecked() != TouchService.isArmed()) automatic.setChecked(TouchService.isArmed());
            ui.postDelayed(this, 500);
        }
    };
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("FPSSC " + BuildConfig.VERSION_NAME + "\n操作位置を自動認識");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("横画面で全画面共有を開始すると、操作ボタンの位置を自動認識します。自動操作はブロスタが前面のときだけ送信します。通知の「停止」で終了できます。");
        body.setPadding(0, 28, 0, 28);
        root.addView(body);

        Button start = new Button(this);
        start.setText("画面共有を開始");
        start.setOnClickListener(v -> {
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> stopService(new Intent(this, AgentService.class)));
        root.addView(stop);

        Button accessibility = new Button(this);
        accessibility.setText("操作サービスを有効にする（Android 設定）");
        accessibility.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);
        TextView automaticHelp = new TextView(this);
        automaticHelp.setText("数値入力や保存は不要です。対戦画面の移動・攻撃ボタンが見えると位置を自動認識します。見つからない場合は診断欄に理由が出ます。");
        root.addView(automaticHelp);
        automatic = new android.widget.Switch(this);
        automatic.setText("自動操作 ON / OFF");
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && !TouchService.connected()) {
                automatic.setChecked(false);
                android.widget.Toast.makeText(this, "Android 設定で FPSSC の操作サービスを有効にしてください", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            TouchService.arm(enabled);
        });
        root.addView(automatic);
        statusView = new TextView(this);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, 24, 0, 24);
        root.addView(statusView);
        Button share = new Button(this);
        share.setText("診断結果を共有");
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "FPSSC diagnostics\n" + AgentService.status + "\n" + TouchService.diagnostics()), "診断結果を共有")));
        root.addView(share);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 277);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || data == null) return;
        Intent service = new Intent(this, AgentService.class)
                .putExtra(AgentService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(AgentService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
    }
}
