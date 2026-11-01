package com.tanuki276.fpssc;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 276;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("FPSSC Android\nreal-time vision + policy research harness");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Captures the screen with MediaProjection, builds a world state, and emits preview control commands. No live-game input injection is included.");
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

        setContentView(root);
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
