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
            statusView.setText(AgentService.status + "\n" + TouchService.inputStatus);
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
        title.setText("FPSSC Android\nreal-time vision + policy research harness");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("横画面で操作位置を設定し、画面共有は全画面を選択してください。自動操作はブロスタが前面のときだけ送信します。通知の「停止」で終了できます。");
        body.setPadding(0, 28, 0, 28);
        root.addView(body);

        Button start = new Button(this);
        start.setText("Start capture");
        start.setOnClickListener(v -> {
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopService(new Intent(this, AgentService.class)));
        root.addView(stop);

        Button accessibility = new Button(this);
        accessibility.setText("操作サービスを有効にする（Android 設定）");
        accessibility.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);
        android.content.SharedPreferences preferences = getSharedPreferences("controls", MODE_PRIVATE);
        String[] keys = {"moveX", "moveY", "attackX", "attackY", "radius"};
        String[] labels = {"移動中心 X (0–1)", "移動中心 Y (0–1)", "攻撃中心 X (0–1)", "攻撃中心 Y (0–1)", "ドラッグ半径 / 画面高さ (0.01–0.15)"};
        float[] defaults = {.15f, .8f, .88f, .75f, .06f};
        android.widget.EditText[] fields = new android.widget.EditText[keys.length];
        for (int i = 0; i < keys.length; i++) {
            TextView label = new TextView(this);
            label.setText(labels[i]);
            root.addView(label);
            fields[i] = new android.widget.EditText(this);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            fields[i].setText(Float.toString(preferences.getFloat(keys[i], defaults[i])));
            root.addView(fields[i]);
        }
        Button save = new Button(this);
        save.setText("操作位置を保存（実際の配置に合わせてください）");
        save.setOnClickListener(v -> {
            TouchService.arm(false);
            try {
                float[] values = new float[5];
                for (int i = 0; i < 5; i++) values[i] = Float.parseFloat(fields[i].getText().toString());
                new TouchProfile(values[0], values[1], values[2], values[3], values[4]);
                android.content.SharedPreferences.Editor editor = preferences.edit();
                for (int i = 0; i < 5; i++) editor.putFloat(keys[i], values[i]);
                editor.putBoolean("calibrated", true).apply();
                android.widget.Toast.makeText(this, "保存しました", android.widget.Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                android.widget.Toast.makeText(this, "位置は 0–1、半径は 0.01–0.15 で指定してください", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);
        automatic = new android.widget.Switch(this);
        automatic.setText("自動操作 ON / OFF");
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && (!TouchService.connected() || !preferences.getBoolean("calibrated", false))) {
                automatic.setChecked(false);
                android.widget.Toast.makeText(this, "操作サービスの有効化と操作位置の保存が必要です", android.widget.Toast.LENGTH_LONG).show();
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
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "FPSSC diagnostics\n" + AgentService.status), "診断結果を共有")));
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
