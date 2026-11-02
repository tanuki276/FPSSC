package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

public final class CombatRegressionTest {
    static int checks;
    static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static Track actor(int id,Kind kind,float x,float y) {
        return new Track(id,kind,new Vec2(x,y),new Vec2(0,0),new Vec2(0,0),.02f,.95f,8,true);
    }
    static WorldState world(long now) {
        return new WorldState(actor(1,Kind.PLAYER,.8f,.5f),List.of(actor(2,Kind.ENEMY,1.1f,.5f)),List.of(),now,0,new Vec2(0,0),1);
    }
    static void rect(Bitmap b,int x,int y,int w,int h,int color) {
        for(int yy=y;yy<y+h;yy++) for(int xx=x;xx<x+w;xx++) b.setPixel(xx,yy,color);
    }
    static Bitmap hud() {
        Bitmap b=new Bitmap(640,320);
        rect(b,300,130,28,4,0xff50dc50); rect(b,305,121,12,5,0xffffffff);
        return b;
    }
    public static void main(String[] args) {
        // Policy vectors are physical directions, output stays compatible with the gesture converter.
        Vec2 physical=new Vec2(1,1).norm();
        Vec2 output=screenDirection(physical,2.2f);
        Vec2 finger=TouchProfile.screenVector(output,2200,1000);
        check(Math.abs(finger.x()-finger.y())<.001,"Aim must be physically 45 degrees on a wide display");
        Bitmap low=hud(); rect(low,440,110,4,4,0xffff416e); rect(low,444,101,14,5,0xffffffff);
        check(new VisionPipeline(true).detect(low,1000,0).enemies().size()==1,"Low pink enemy HP should remain visible");
        Bitmap edge=hud(); rect(edge,90,195,25,4,0xfff03050); rect(edge,95,186,12,5,0xffffffff);
        check(new VisionPipeline(true).detect(edge,1000,0).enemies().size()==1,"Enemy above joystick at screen edge must be detected");
        Bitmap brick=new Bitmap(640,320); rect(brick,280,100,24,120,0xffe98c80);
        CombatNavigator nav=new CombatNavigator(); nav.update(FramePixels.copy(brick),world(1000));
        check(nav.blocked(new Vec2(.8f,.5f),new Vec2(1.1f,.5f),true),"Brick must block movement");
        check(nav.blocked(new Vec2(.8f,.5f),new Vec2(1.1f,.5f),false),"Brick must block shots");
        ControlCommand forward=new ControlCommand(new Vec2(1,0),new Vec2(1,0),true,false,"approach");
        ControlCommand routed=nav.route(world(1000),forward);
        check(!routed.fire(),"Do not shoot through brick");
        check(Math.abs(routed.move().y())>.2,"Detour around a blocking wall");
        Bitmap water=new Bitmap(640,320); rect(water,280,100,24,120,0xff209cdd);
        nav.update(FramePixels.copy(water),world(1033));
        check(nav.blocked(new Vec2(.8f,.5f),new Vec2(1.1f,.5f),true),"Water must block walking");
        check(!nav.blocked(new Vec2(.8f,.5f),new Vec2(1.1f,.5f),false),"Water must not block shooting");
        CombatNavigator stuck=new CombatNavigator(); FramePixels empty=FramePixels.copy(new Bitmap(640,320));
        ControlCommand escape=forward;
        for(long t=1000;t<=2000;t+=100) { stuck.update(empty,world(t)); escape=stuck.route(world(t),forward); }
        check(escape.reason().contains("unstuck") && Math.abs(escape.move().y())>.8,"Stationary commanded motion must trigger a bounded sideways escape");
        FramePixels masked=FramePixels.copy(low); masked.maskRect(.65f,.28f,.78f,.4f);
        check(new VisionPipeline(true).detectPixels(masked,1000,0).enemies().isEmpty(),"Overlay exclusion must hide false HUD pixels");
        VisionPipeline shots=new VisionPipeline(true);
        Bitmap first=hud(); rect(first,420,175,4,4,0xffff50c8);
        Bitmap second=hud(); rect(second,412,175,4,4,0xffff50c8);
        shots.detect(first,1000,0);
        check(!shots.detect(second,1050,0).projectiles().isEmpty(),"Moving pink projectile must be tracked");
        System.out.println("Combat: "+checks+" regression checks passed");
    }
}
