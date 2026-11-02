package com.tanuki276.fpssc;

import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

public final class TeammateFollowerTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static Track ally(int id,float x,float y,boolean seen) {
        return new Track(id,Kind.ALLY,new Vec2(x,y),new Vec2(0,0),new Vec2(0,0),.025f,.95f,5,seen);
    }
    static WorldState world(long now,Track... allies) {
        return new WorldState(LiveCombatControllerTest.track(1,Kind.PLAYER,.5f,.5f,0,0,true),
                List.of(LiveCombatControllerTest.track(99,Kind.ENEMY,.3f,.5f,0,0,true)),List.of(),now,0,new Vec2(0,0),.95f,List.of(allies));
    }
    public static void main(String[] args) {
        FramePixels frame=LiveCombatControllerTest.frame(false); TeammateFollower follower=new TeammateFollower();
        ControlCommand c=null; long t=10000;
        for(int i=0;i<3;i++) c=follower.decide(frame,world(t+i*60,ally(4,.8f,.5f,true)),t+i*60,true);
        check(c.move().x()>.5f && !c.fire() && !c.superAttack(),"follow must move to ally, never attack nearby enemy");
        c=follower.decide(frame,world(t+180,ally(4,.8f,.5f,true),ally(5,.4f,.5f,true)),t+180,true);
        check(c.move().x()>0,"new closer ally must not steal lock");
        check(follower.decide(frame,world(t+240,ally(4,.8f,.5f,false)),t+240,true).move().len()==0,"occluded ally must stop movement immediately");
        check(follower.decide(frame,world(t+300,ally(4,.58f,.5f,true)),t+300,true).move().len()==0,"stop near ally");
        check(follower.decide(frame,world(t+360,ally(4,.62f,.5f,true)),t+360,true).move().len()==0,"distance hysteresis prevents jitter");
        check(follower.decide(frame,world(t+420,ally(4,.70f,.5f,true)),t+420,true).move().x()>0,"resume when ally leaves band");
        check(follower.decide(frame,world(t+480),t+480,false).move().len()==0,"manual pause must release follow");
        check(follower.decide(frame,world(t+540),t+540,true).move().len()==0,"no ally means no enemy following");
        check(follower.decide(frame,world(t),t+600,true).move().len()==0,"stale world cannot follow");
        ManualInputGate gate=new ManualInputGate();
        check(gate.allows(true),"armed follow can move"); gate.pause();
        for(int i=0;i<100;i++) check(!gate.allows(true),"manual window must not expire during pins");
        gate.resume(); check(gate.allows(true)&&!gate.allows(false),"explicit resume respects armed state");
        AgentCore core=new AgentCore(true);
        for(int i=0;i<3;i++) {
            android.graphics.Bitmap b=CombatRegressionTest.hud();
            CombatRegressionTest.rect(b,430,130,28,4,0xff409cff);
            CombatRegressionTest.rect(b,435,121,12,5,0xffffffff);
            FramePixels pixels=FramePixels.copy(b);
            WorldState detected=core.perceivePixels(pixels,t+i*60,0);
            check(detected.allies().size()==1 && detected.enemies().isEmpty(),"blue HP must be ally, not enemy");
        }
        android.graphics.Bitmap blue=CombatRegressionTest.hud();
        CombatRegressionTest.rect(blue,430,130,28,4,0xff409cff);
        check(core.perceivePixels(FramePixels.copy(blue),t+400,0).allies().stream().noneMatch(Track::observed),"plain blue artwork without HP text must not be observed as ally");
        // A wall on the follow ray must cause a detour, with no attack output.
        TeammateFollower wall=new TeammateFollower();
        for(int i=0;i<3;i++) c=wall.decide(LiveCombatControllerTest.frame(true),world(t+i*60,ally(4,.8f,.5f,true)),t+i*60,true);
        check(Math.abs(c.move().y())>.1f && !c.fire(),"route follow around recognized wall");
        System.out.println("TeammateFollowerTest: ally pixels, confirmation, sticky target, spacing, occlusion, walls and manual gate passed");
    }
}
