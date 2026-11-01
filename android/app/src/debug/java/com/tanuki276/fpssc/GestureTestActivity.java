package com.tanuki276.fpssc;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.MotionEvent;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Only in debug builds; instrumentation observes real OS touch delivery here. */
public final class GestureTestActivity extends Activity {
    static volatile int leftDowns,attackDowns,moves,maxPointers,activePointers;
    static void reset() { leftDowns=0; attackDowns=0; moves=0; maxPointers=0; activePointers=0; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        setContentView(new View(this) {
            private final Paint paint=new Paint();
            @Override protected void onDraw(Canvas c) {
                c.drawColor(0xff243447); paint.setColor(0xff287f9a);
                c.drawCircle(getWidth()*.15f,getHeight()*.75f,getHeight()*.06f,paint);
                paint.setColor(0xffbc8038);
                c.drawCircle(getWidth()*.85f,getHeight()*.60f,getHeight()*.06f,paint);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                int action=event.getActionMasked();
                maxPointers=Math.max(maxPointers,event.getPointerCount());
                if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN) {
                    if(event.getX(event.getActionIndex())<getWidth()*.5f) leftDowns++; else attackDowns++;
                    activePointers=event.getPointerCount();
                } else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL) activePointers=0;
                else if(action==MotionEvent.ACTION_POINTER_UP) activePointers=event.getPointerCount()-1;
                else if(action==MotionEvent.ACTION_MOVE) moves++;
                return true;
            }
        });
    }
}
