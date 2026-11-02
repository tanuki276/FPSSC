package com.tanuki276.fpssc;

import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

public final class LiveCombatControllerTest {
    static void check(boolean ok,String s) { if(!ok) throw new AssertionError(s); }
    static Track track(int id,Kind k,float x,float y,float vx,float vy,boolean observed) {
        return new Track(id,k,new Vec2(x,y),new Vec2(vx,vy),new Vec2(0,0),.025f,.98f,10,observed);
    }
    static FramePixels frame(boolean wall) {
        int w=640,h=360; int[] pixels=new int[w*h]; java.util.Arrays.fill(pixels,0xff5a6e78);
        if(wall) for(int y=70;y<290;y++) for(int x=345;x<375;x++) pixels[y*w+x]=0xffce8060;
        android.graphics.Bitmap b=new android.graphics.Bitmap(w,h);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) b.setPixel(x,y,pixels[y*w+x]);
        return FramePixels.copy(b);
    }
    static WorldState world(float x,float y,float vy,long time) {
        return new WorldState(track(1,Kind.PLAYER,.50f,.50f,0,0,true),
                List.of(track(2,Kind.ENEMY,x,y,0,vy,true)),List.of(),time,0,new Vec2(0,0),.98f);
    }
    public static void main(String[] args) {
        long t=10000; FramePixels f=frame(false);
        LiveCombatController melee=new LiveCombatController(),ranged=new LiveCombatController();
        WorldState w=world(.75f,.50f,0,t);
        ControlCommand m=melee.decide(f,w,BrawlerProfile.byId("el_primo"),t,true,0);
        ControlCommand r=ranged.decide(f,w,BrawlerProfile.byId("piper"),t,true,0);
        check(m.move().x()>.2f && !m.fire(),"melee must approach outside own range");
        check(r.fire(),"ranged live profile must shoot target in its own range");
        float before=ranged.estimatedAmmo();
        ranged.decide(f,world(.75f,.50f,0,t+60),BrawlerProfile.byId("piper"),t+60,true,0);
        check(Math.abs(before-ranged.estimatedAmmo())<.01f,"undispatched recommendation must not consume ammo");
        ControlCommand after=ranged.decide(f,world(.75f,.50f,0,t+120),BrawlerProfile.byId("piper"),t+120,true,1);
        check(!after.fire() && ranged.estimatedAmmo()<2.2f,"dispatch feedback must debit ammo and impose cadence");
        LiveCombatController lead=new LiveCombatController();
        ControlCommand aim=lead.decide(f,world(.72f,.5f,.3f,t),BrawlerProfile.byId("colt"),t,true,0);
        Vec2 physicalAim=TouchProfile.screenVector(aim.aim(),640,360);
        check(physicalAim.y()>.05f,"moving enemy needs lead through actual touch conversion");
        ControlCommand paused=lead.decide(f,w,BrawlerProfile.byId("colt"),t+60,false,0);
        check(!paused.fire() && paused.move().len()==0,"disabled must release all actions");
        WorldState missing=new WorldState(track(1,Kind.PLAYER,.5f,.5f,0,0,false),w.enemies(),List.of(),t,0,new Vec2(0,0),.9f);
        check(lead.decide(f,missing,BrawlerProfile.byId("colt"),t,true,0).move().len()==0,"predicted self must not drive input");
        LiveCombatController occluded=new LiveCombatController();
        check(!occluded.decide(frame(true),world(.68f,.5f,0,t),BrawlerProfile.byId("colt"),t,true,0).fire(),"wall must suppress live shooting");
        CombatNavigator nav=new CombatNavigator();
        WorldState metricWorld=new WorldState(track(1,Kind.PLAYER,.889f,.5f,0,0,true),
                List.of(track(2,Kind.ENEMY,1.21f,.5f,0,0,true)),List.of(),t,0,new Vec2(0,0),.98f);
        nav.update(frame(true),metricWorld);
        ControlCommand hold=new ControlCommand(new Vec2(0,0),new Vec2(1,0),true,false,"hold");
        check(!nav.route(metricWorld,hold,.53f,false).fire(),"stationary aim must still check walls");
        check(nav.route(metricWorld,hold,.53f,true).fire(),"thrower may shoot through walls");
        WorldState stale=world(.7f,.5f,0,t);
        check(!lead.decide(f,stale,BrawlerProfile.byId("colt"),t+300,true,0).fire(),"stale world must not fire");
        AgentCore perception=new AgentCore(true);
        LiveCombatController pixelsToInput=new LiveCombatController();
        boolean fired=false;
        for(int i=0;i<5;i++) {
            android.graphics.Bitmap b=CombatRegressionTest.hud();
            CombatRegressionTest.rect(b,420,130,28,4,0xfff03050);
            CombatRegressionTest.rect(b,425,121,12,5,0xffffffff);
            FramePixels pixels=FramePixels.copy(b); long time=t+i*60;
            WorldState detected=perception.perceivePixels(pixels,time,0);
            fired |= pixelsToInput.decide(pixels,detected,BrawlerProfile.byId("colt"),time,true,0).fire();
        }
        check(fired,"HP pixels must flow through actual vision, policy and terrain routing into fire");
        LiveCombatController thrower=new LiveCombatController();
        ControlCommand lob=thrower.decide(f,world(.65f,.5f,0,t),BrawlerProfile.byId("dynamike"),t,true,0);
        check(lob.aim().len()<.8f && lob.aim().len()>.2f,"thrower stick must encode landing distance");
        System.out.println("LiveCombatControllerTest: profile, lead, dispatch feedback, disabled/stale/self gates, wall/thrower checks passed");
    }
}
