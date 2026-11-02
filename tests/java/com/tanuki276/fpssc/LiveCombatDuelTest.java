package com.tanuki276.fpssc;

import java.util.*;
import static com.tanuki276.fpssc.AgentCore.*;

/** Decision-only closed-loop comparison; known ground-truth tracks, no claim about live vision. */
public final class LiveCombatDuelTest {
    static final float DT=.06f, ASPECT=640f/360;
    static final class Bullet {
        Vec2 pos,velocity; boolean own; float remaining=.53f;
        Bullet(Vec2 p,Vec2 v,boolean o) { pos=p; velocity=v; own=o; }
    }
    record Result(int wins,int dealt,int taken,int fired,int hits) {}
    static Track actor(int id,Kind k,Vec2 p,Vec2 v) {
        return new Track(id,k,new Vec2(p.x()/ASPECT,p.y()),new Vec2(v.x()/ASPECT,v.y()),
                new Vec2(0,0),.025f,.98f,12,true);
    }
    static Vec2 constrain(Vec2 p) { return new Vec2(Math.max(.08f,Math.min(ASPECT-.08f,p.x())),Math.max(.12f,Math.min(.85f,p.y()))); }
    static Result run(boolean advanced) {
        int wins=0,dealt=0,taken=0,fired=0,hits=0;
        FramePixels frame=LiveCombatControllerTest.frame(false);
        for(int seed=0;seed<16;seed++) {
            LiveCombatController controller=new LiveCombatController();
            TacticalPolicy old=new TacticalPolicy(); old.arenaWidth=ASPECT;
            Vec2 p=new Vec2(.65f,.5f), pv=new Vec2(0,0),e=new Vec2(1.25f,.5f);
            List<Bullet> bullets=new ArrayList<>(); int hp=8,ehp=6,shots=0;
            float ammo=3; long lastShot=0;
            for(int tick=0;tick<500 && hp>0 && ehp>0;tick++) {
                long now=10000+tick*60;
                Vec2 ev=new Vec2((float)Math.cos(tick*.07+seed)*.05f,
                        (float)Math.sin(tick*.055+seed)*.20f);
                List<Track> incoming=new ArrayList<>(); int id=100;
                for(Bullet b:bullets) if(!b.own) incoming.add(actor(id++,Kind.PROJECTILE,b.pos,b.velocity));
                WorldState w=new WorldState(actor(1,Kind.PLAYER,p,pv),List.of(actor(2,Kind.ENEMY,e,ev)),
                        incoming,now,0,new Vec2(0,0),.98f);
                ControlCommand c;
                if(advanced) {
                    c=controller.decide(frame,w,BrawlerProfile.byId("colt"),now,true,shots);
                    c=new ControlCommand(TouchProfile.screenVector(c.move(),640,360),
                            TouchProfile.screenVector(c.aim(),640,360),c.fire(),false,c.reason());
                } else {
                    WorldState physical=new WorldState(new Track(1,Kind.PLAYER,p,pv,new Vec2(0,0),.025f,.98f,12,true),
                            List.of(new Track(2,Kind.ENEMY,e,ev,new Vec2(0,0),.025f,.98f,12,true)),
                            incoming.stream().map(s->new Track(s.id(),s.kind(),metric(s.pos(),ASPECT),metric(s.velocity(),ASPECT),
                                    s.acceleration(),s.radius(),s.confidence(),s.ageFrames(),true)).toList(),now,0,new Vec2(0,0),.98f);
                    c=old.decide(physical,12);
                }
                ammo=Math.min(3,ammo+DT/1.7f);
                if(c.fire() && ammo>=1 && now-lastShot>=290) {
                    bullets.add(new Bullet(p,c.aim().norm().mul(2.55f),true));
                    ammo--; shots++; fired++; lastShot=now;
                }
                // Bot fires direct aimed projectiles at regular cadence; equal rules for both agents.
                if(tick%12==0 && e.sub(p).len()<.6f) bullets.add(new Bullet(e,p.sub(e).norm().mul(1.4f),false));
                pv=c.move().clampLen(1).mul(.48f); p=constrain(p.add(pv.mul(DT))); e=constrain(e.add(ev.mul(DT)));
                Iterator<Bullet> it=bullets.iterator();
                while(it.hasNext()) {
                    Bullet b=it.next(); Vec2 step=b.velocity.mul(DT),target=b.own?e:p;
                    float f=Math.max(0,Math.min(1,target.sub(b.pos).dot(step)/Math.max(1e-6f,step.len2())));
                    boolean hit=target.sub(b.pos.add(step.mul(f))).len()<.045f;
                    b.pos=b.pos.add(step); b.remaining-=step.len();
                    if(hit) { if(b.own) {ehp--;dealt++;hits++;} else {hp--;taken++;} }
                    if(hit||b.remaining<=0) it.remove();
                }
            }
            if(ehp<=0 && hp>0) wins++;
        }
        return new Result(wins,dealt,taken,fired,hits);
    }
    public static void main(String[] args) {
        Result baseline=run(false),live=run(true);
        System.out.println("Closed-loop synthetic duels (16 deterministic motion phases; perfect tracks): legacy="+baseline+" live="+live);
        if(live.dealt()==0 || live.fired()==0) throw new AssertionError("live controller cannot attack synthetic opponent");
        if(live.wins()<baseline.wins()) throw new AssertionError("live duel wins regressed against legacy");
    }
}
