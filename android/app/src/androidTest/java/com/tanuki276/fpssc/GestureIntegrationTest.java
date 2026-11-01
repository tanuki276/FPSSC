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
    private static String quoted(String value) { return "'"+value.replace("'","'\\''")+"'"; }
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
            shell("settings put secure enabled_accessibility_services "+quoted(previous.equals("null")?component:previous+":"+component));
            shell("settings put secure accessibility_enabled 1");
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(TouchService.instrumentationInstance()==null&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            if(TouchService.instrumentationInstance()==null) {
                System.err.println("ACCESSIBILITY STATE: "+shell("dumpsys accessibility"));
                System.err.println("SERVICE LOG: "+shell("logcat -d -s FPSSC AndroidRuntime"));
            }
            assertNotNull("Accessibility service did not connect",TouchService.instrumentationInstance());
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
            assertNull("Gesture engine failure",failure.get());
            assertEquals("Movement restarted instead of continuing",1,GestureTestActivity.leftDowns);
            assertTrue("Attack strokes missing",GestureTestActivity.attackDowns>=2);
            assertTrue("Two fingers were not delivered",GestureTestActivity.maxPointers>=2);
            assertTrue("Movement events missing",GestureTestActivity.moves>2);
            assertEquals("Pointer was not released",0,GestureTestActivity.activePointers);
            assertFalse(engine.get().holdingMovement());
            instrumentation.runOnMainSync(GestureTestActivity::reset);
            allowed.set(true);
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
            if(engine.get()!=null) instrumentation.runOnMainSync(()->engine.get().stop());
            TouchService.arm(false);
            if(activity!=null) { GestureTestActivity closing=activity; instrumentation.runOnMainSync(closing::finish); }
            shell(previous.equals("null")?"settings delete secure enabled_accessibility_services":"settings put secure enabled_accessibility_services "+quoted(previous));
            shell(enabled.equals("null")?"settings delete secure accessibility_enabled":"settings put secure accessibility_enabled "+quoted(enabled));
        }
    }
}
