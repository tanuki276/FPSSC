package com.tanuki276.fpssc;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GestureIntegrationTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private UiAutomation automation;
    private String shell(String command) throws Exception {
        try(ParcelFileDescriptor p=automation.executeShellCommand(command);
            var stream=new ParcelFileDescriptor.AutoCloseInputStream(p)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[1024]; int count;
            while((count=stream.read(buffer))!=-1) out.write(buffer,0,count);
            return out.toString("UTF-8").trim();
        }
    }
    private static String settingValue(String value) {
        // executeShellCommand tokenizes directly; shell quotes would become literal setting bytes.
        if(!value.matches("[A-Za-z0-9_./:]+")) throw new IllegalArgumentException("Unexpected service list");
        return value;
    }
    private void tap(int[] point) {
        long now=SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,point[0],point[1],0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+40,android.view.MotionEvent.ACTION_UP,point[0],point[1],0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(down,true)); assertTrue(automation.injectInputEvent(up,true)); }
        finally { down.recycle(); up.recycle(); }
    }
    @Test public void continuedMovementAttackReleaseAndForegroundGate() throws Exception {
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String previous=shell("settings get secure enabled_accessibility_services");
        String enabled=shell("settings get secure accessibility_enabled");
        GestureTestActivity activity=null;
        AtomicReference<GestureEngine> engine=new AtomicReference<>();
        try {
            activity=(GestureTestActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),GestureTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(800);
            String component="com.tanuki276.fpssc/com.tanuki276.fpssc.TouchService";
            shell("settings put secure enabled_accessibility_services "+settingValue(previous.equals("null")?component:previous+":"+component));
            shell("settings put secure accessibility_enabled 1");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(TouchService.instrumentationInstance()==null&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            if(TouchService.instrumentationInstance()==null) {
                System.err.println("ACCESSIBILITY STATE: "+shell("dumpsys accessibility"));
                System.err.println("SERVICE LOG: "+shell("logcat -d -s FPSSC AndroidRuntime"));
            }
            assertNotNull("Accessibility service did not connect",TouchService.instrumentationInstance());
            instrumentation.runOnMainSync(()->assertTrue("Transparent overlay was not attached",TouchService.instrumentationInstance().overlayVisible()));
            SystemClock.sleep(400);
            AtomicReference<int[]> togglePoint=new AtomicReference<>();
            instrumentation.runOnMainSync(()->togglePoint.set(TouchService.instrumentationInstance().instrumentationOverlayToggleCenter()));
            AgentService.active=true;
            tap(togglePoint.get()); SystemClock.sleep(150);
            assertTrue("Overlay ON tap was not delivered",TouchService.isArmed());
            tap(togglePoint.get()); SystemClock.sleep(150);
            assertFalse("Overlay OFF tap did not stop automatic mode",TouchService.isArmed());
            AgentService.active=false;
            // Shared shell-owned capture survives Gradle's test APK uninstall/cleanup.
            shell("screencap -p /sdcard/Download/fscsr-overlay.png");
            instrumentation.runOnMainSync(GestureTestActivity::reset);
            DisplayMetrics dm=new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            assertTrue("Fixture must be landscape",dm.widthPixels>dm.heightPixels);
            AtomicBoolean allowed=new AtomicBoolean(true);
            AtomicReference<String> failure=new AtomicReference<>();
            instrumentation.runOnMainSync(()->engine.set(TouchService.instrumentationInstance().instrumentationEngine(allowed::get,failure::set)));
            TouchProfile profile=new TouchProfile(.15f,.75f,.85f,.60f,.06f);
            AgentCore.ControlCommand command=new AgentCore.ControlCommand(new AgentCore.Vec2(1,0),new AgentCore.Vec2(-1,0),true,false,"fixture");
            for(int i=0;i<16;i++) {
                instrumentation.runOnMainSync(()->engine.get().offer(command,profile,SystemClock.elapsedRealtime(),dm.widthPixels,dm.heightPixels));
                SystemClock.sleep(40);
            }
            allowed.set(false);
            instrumentation.runOnMainSync(()->engine.get().stop());
            SystemClock.sleep(300);
            assertNull("Gesture engine failure: sent="+engine.get().sent+" completed="+engine.get().completed+" cancelled="+engine.get().cancelled,failure.get());
            assertEquals("Movement restarted instead of continuing",1,GestureTestActivity.leftDowns);
            assertTrue("Attack strokes missing",GestureTestActivity.attackDowns>=2);
            assertTrue("Dispatched attack feedback missing",engine.get().attacksSent>=2);
            assertTrue("Dispatch count diverged from actual touch events",
                    Math.abs(engine.get().attacksSent-GestureTestActivity.attackDowns)<=1);
            assertTrue("Two fingers were not delivered",GestureTestActivity.maxPointers>=2);
            assertTrue("Movement events missing",GestureTestActivity.moves>2);
            assertEquals("Pointer was not released",0,GestureTestActivity.activePointers);
            assertFalse(engine.get().holdingMovement());
            // Follow movement yields the screen to real pin taps, keeps armed state, and resumes explicitly.
            instrumentation.runOnMainSync(GestureTestActivity::reset);
            TouchService.arm(true);
            instrumentation.runOnMainSync(()->engine.set(TouchService.instrumentationInstance().instrumentationFollowEngine(
                    TouchService::canAutomate,failure::set)));
            AgentCore.ControlCommand follow=new AgentCore.ControlCommand(new AgentCore.Vec2(1,0),
                    new AgentCore.Vec2(0,0),false,false,"follow fixture");
            for(int i=0;i<8;i++) {
                instrumentation.runOnMainSync(()->engine.get().offer(follow,profile,SystemClock.elapsedRealtime(),dm.widthPixels,dm.heightPixels));
                SystemClock.sleep(40);
            }
            AtomicReference<int[]> pinPoint=new AtomicReference<>();
            instrumentation.runOnMainSync(()->pinPoint.set(TouchService.instrumentationInstance().instrumentationPinsCenter()));
            tap(pinPoint.get());
            SystemClock.sleep(160);
            assertTrue("Follow must stay ON during pins",TouchService.isArmed());
            assertTrue(TouchService.manualPaused());
            assertFalse("Follow pointer not released for pin menu",engine.get().holdingMovement());
            int leftBefore=GestureTestActivity.leftDowns;
            int pinsBefore=GestureTestActivity.attackDowns;
            tap(new int[]{(int)(dm.widthPixels*.85f),(int)(dm.heightPixels*.6f)});
            for(int i=0;i<6;i++) {
                instrumentation.runOnMainSync(()->engine.get().offer(follow,profile,SystemClock.elapsedRealtime(),dm.widthPixels,dm.heightPixels));
                SystemClock.sleep(40);
            }
            assertEquals("Automation restarted during manual pins",leftBefore,GestureTestActivity.leftDowns);
            assertEquals("Manual pin tap was not delivered",pinsBefore+1,GestureTestActivity.attackDowns);
            assertEquals("Manual touch was not released",0,GestureTestActivity.activePointers);
            instrumentation.runOnMainSync(TouchService::resumeFollowing);
            for(int i=0;i<6;i++) {
                instrumentation.runOnMainSync(()->engine.get().offer(follow,profile,SystemClock.elapsedRealtime(),dm.widthPixels,dm.heightPixels));
                SystemClock.sleep(40);
            }
            assertTrue("Explicit follow resume did not move",GestureTestActivity.leftDowns>leftBefore);
            assertEquals("Follow mode injected an attack",pinsBefore+1,GestureTestActivity.attackDowns);
            instrumentation.runOnMainSync(()->{ TouchService.arm(false); engine.get().stop(); });
            SystemClock.sleep(160);
            assertFalse("OFF must clear manual state",TouchService.manualPaused());
            instrumentation.runOnMainSync(GestureTestActivity::reset);
            allowed.set(true);
            instrumentation.runOnMainSync(()->engine.set(TouchService.instrumentationInstance().instrumentationEngine(allowed::get,failure::set)));
            instrumentation.runOnMainSync(()->engine.get().offer(command,profile,SystemClock.elapsedRealtime()-1000,dm.widthPixels,dm.heightPixels));
            SystemClock.sleep(150);
            assertEquals("Stale command injected",0,GestureTestActivity.leftDowns);
            instrumentation.runOnMainSync(()->{
                TouchService.arm(true);
                TouchService.submit(command,profile,SystemClock.elapsedRealtime(),dm.widthPixels,dm.heightPixels);
            });
            SystemClock.sleep(250);
            assertEquals("Production gate injected into FPSSC instead of game",0,GestureTestActivity.leftDowns);
            assertTrue(TouchService.diagnostics().contains("com.tanuki276.fpssc"));
            TouchService.arm(false);
        } finally {
            AgentService.active=false;
            if(engine.get()!=null) instrumentation.runOnMainSync(()->engine.get().stop());
            TouchService.arm(false);
            if(activity!=null) { GestureTestActivity closing=activity; instrumentation.runOnMainSync(closing::finish); }
            shell(previous.equals("null")?"settings delete secure enabled_accessibility_services":"settings put secure enabled_accessibility_services "+settingValue(previous));
            shell(enabled.equals("null")?"settings delete secure accessibility_enabled":"settings put secure accessibility_enabled "+settingValue(enabled));
        }
    }
}
