package com.tanuki276.fpssc;

import java.util.List;
import static com.tanuki276.fpssc.AgentCore.*;

/** Capture-to-command coordinator. No Android dependencies; exercised with pixel sequences. */
final class LiveCombatController {
    private PracticeCombatPolicy policy;
    private CombatNavigator navigator = new CombatNavigator();
    private String profileId;
    private long previousTime, previousShots;
    private int width, height;
    private float latencyMs = 12;

    void observeLatency(float ms) {
        if(Float.isFinite(ms) && ms>=0 && ms<=500) latencyMs=latencyMs*.86f+ms*.14f;
    }

    float estimatedAmmo() { return policy==null?0:policy.estimatedAmmo(); }

    ControlCommand decide(FramePixels frame, WorldState world, BrawlerProfile profile,
                          long now, boolean enabled, long dispatchedShots) {
        if(frame==null || world==null || !enabled) {
            policy=null; previousShots=dispatchedShots; previousTime=0;
            navigator=new CombatNavigator();
            return ControlCommand.idle("combat paused / acquiring controls");
        }
        boolean reset=policy==null || width!=frame.getWidth() || height!=frame.getHeight()
                || now<=previousTime || now-previousTime>700;
        if(reset) {
            policy=new PracticeCombatPolicy(); policy.setProfile(profile);
            policy.useDispatchedShotFeedback(); navigator=new CombatNavigator();
            profileId=profile.id(); previousShots=dispatchedShots;
        } else if(!profile.id().equals(profileId)) {
            policy.setProfile(profile); profileId=profile.id();
        }
        width=frame.getWidth(); height=frame.getHeight(); previousTime=now;
        policy.recordDispatchedShots(Math.max(0,dispatchedShots-previousShots),now);
        previousShots=dispatchedShots;
        Track player=world.player();
        if(player==null || !player.observed() || player.confidence()<.38f
                || now-world.frameTimeMs()>200 || now<world.frameTimeMs() || world.captureAgeMs()>200) {
            return ControlCommand.idle("combat: current player/frame uncertain");
        }
        float aspect=width/(float)height;
        // Extrapolate only a bounded source queue + pipeline + half gesture-slice delay.
        float lag=Math.max(0,Math.min(.20f,(world.captureAgeMs()+latencyMs+40)/1000f));
        Track predictedPlayer=advance(player,lag);
        WorldState predicted=new WorldState(predictedPlayer,
                world.enemies().stream().map(t->advance(t,lag)).toList(),
                world.projectiles().stream().map(t->advance(t,lag)).toList(),
                now,world.captureAgeMs(),world.globalFrameShift(),world.quality());
        WorldState physical=physical(world,aspect);
        navigator.update(frame,physical);
        // Prefer shootable targets, rather than retaining the nearest enemy behind a wall.
        List<Track> hittable=predicted.enemies().stream().filter(e->profile.thrower()
                || !navigator.blocked(metric(predictedPlayer.pos(),aspect),metric(e.pos(),aspect),false)).toList();
        java.util.Set<Integer> blocked=new java.util.HashSet<>();
        for(Track enemy:predicted.enemies()) if(!hittable.contains(enemy)) blocked.add(enemy.id());
        policy.excludeTargets(blocked);
        WorldState combatWorld=new WorldState(predictedPlayer,hittable,predicted.projectiles(),now,
                predicted.captureAgeMs(),predicted.globalFrameShift(),predicted.quality());
        PracticeCombatPolicy.Action action=policy.decide(combatWorld,aspect,now);
        // No live super/hypercharge commands until their artwork and charge state are recognized.
        ControlCommand desired=new ControlCommand(action.move(),action.aim(),action.fire(),false,
                "live " + action.reason());
        ControlCommand routed=navigator.route(physical,desired,profile.attackRange(),profile.thrower());
        Vec2 aim=routed.aim();
        if(profile.thrower()) {
            float distance=profile.attackRange();
            for(Track enemy:hittable) {
                Vec2 delta=metric(enemy.pos().sub(predictedPlayer.pos()),aspect);
                if(delta.norm().dot(aim)>.92f) distance=Math.min(distance,delta.len());
            }
            aim=aim.mul(Math.max(.08f,Math.min(1,distance/profile.attackRange())));
        }
        return new ControlCommand(screenDirection(routed.move(),aspect),screenDirection(aim,aspect),
                routed.fire(),false,routed.reason());
    }

    private static Track advance(Track t,float lag) {
        Vec2 displacement=t.velocity().mul(lag).add(t.acceleration().clampLen(1.5f).mul(.5f*lag*lag));
        return new Track(t.id(),t.kind(),t.pos().add(displacement),t.velocity(),t.acceleration(),
                t.radius(),t.confidence(),t.ageFrames(),t.observed());
    }
    private static Track physical(Track t,float a) {
        return new Track(t.id(),t.kind(),metric(t.pos(),a),metric(t.velocity(),a),metric(t.acceleration(),a),
                t.radius(),t.confidence(),t.ageFrames(),t.observed());
    }
    private static WorldState physical(WorldState w,float a) {
        return new WorldState(physical(w.player(),a),w.enemies().stream().map(t->physical(t,a)).toList(),
                w.projectiles().stream().map(t->physical(t,a)).toList(),w.frameTimeMs(),w.captureAgeMs(),
                metric(w.globalFrameShift(),a),w.quality());
    }
}
