package com.tanuki276.fpssc;

import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

/** Movement-only follower: observed blue HP anchors, sticky identity and distance hysteresis. */
final class TeammateFollower {
    private CombatNavigator navigator = new CombatNavigator();
    private int targetId=-1, candidateId=-1, confirmations, width, height;
    private long lastTime, lastSeen;
    private boolean approaching;

    void reset() { targetId=candidateId=-1; confirmations=0; approaching=false; lastSeen=0; navigator=new CombatNavigator(); }
    ControlCommand decide(FramePixels frame, WorldState world, long now, boolean enabled) {
        if(!enabled || frame==null || world==null) { reset(); lastTime=0; return ControlCommand.idle("追従 OFF / 手動操作中"); }
        if(now<=lastTime || now-lastTime>700 || width!=frame.getWidth() || height!=frame.getHeight()) reset();
        lastTime=now; width=frame.getWidth(); height=frame.getHeight();
        Track player=world.player();
        if(player==null || !player.observed() || player.confidence()<.38f || now<world.frameTimeMs()
                || now-world.frameTimeMs()>200 || world.captureAgeMs()>200) {
            reset(); return ControlCommand.idle("追従: 自分の位置を確認中");
        }
        float aspect=width/(float)height;
        Track target=null;
        for(Track ally:world.allies()) if(reliable(ally) && ally.id()==targetId) target=ally;
        if(target==null && targetId!=-1 && now-lastSeen<=600) return ControlCommand.idle("追従: 味方を見失いました・停止");
        if(target==null) {
            targetId=-1;
            float closest=Float.MAX_VALUE;
            for(Track ally:world.allies()) {
                if(!reliable(ally)) continue;
                float distance=metric(ally.pos().sub(player.pos()),aspect).len();
                if(distance<closest) { closest=distance; target=ally; }
            }
            if(target==null) { reset(); return ControlCommand.idle("追従: 青いHP表示の味方を探しています"); }
            if(candidateId==target.id()) confirmations++; else {candidateId=target.id(); confirmations=1;}
            if(confirmations<3) return ControlCommand.idle("追従: 味方を確認中 "+confirmations+"/3");
            targetId=target.id();
        }
        lastSeen=now;
        Vec2 delta=metric(target.pos().sub(player.pos()),aspect);
        float distance=delta.len();
        if(distance<.19f) approaching=false;
        else if(distance>.25f) approaching=true;
        if(!approaching) return ControlCommand.idle("追従: 味方 #"+targetId+" の近くで待機");
        Vec2 direction=delta.add(metric(target.velocity(),aspect).mul(.10f)).norm();
        float strength=Math.max(.30f,Math.min(1,(distance-.17f)/.20f));
        WorldState physical=new WorldState(physical(player,aspect),List.of(),List.of(),now,world.captureAgeMs(),
                metric(world.globalFrameShift(),aspect),world.quality(),
                world.allies().stream().map(t->physical(t,aspect)).collect(java.util.stream.Collectors.toList()));
        navigator.update(frame,physical);
        ControlCommand routed=navigator.route(physical,new ControlCommand(direction.mul(strength),new Vec2(0,0),false,false,
                "追従: 味方 #"+targetId+" へ移動"));
        return new ControlCommand(screenDirection(routed.move(),aspect),new Vec2(0,0),false,false,routed.reason());
    }
    private static boolean reliable(Track t) { return t.kind()==Kind.ALLY && t.observed() && t.confidence()>=.52f && t.ageFrames()>=2; }
    private static Track physical(Track t,float a) {
        return new Track(t.id(),t.kind(),metric(t.pos(),a),metric(t.velocity(),a),metric(t.acceleration(),a),
                t.radius(),t.confidence(),t.ageFrames(),t.observed());
    }
}
