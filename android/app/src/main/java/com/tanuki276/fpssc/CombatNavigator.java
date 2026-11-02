package com.tanuki276.fpssc;

import static com.tanuki276.fpssc.AgentCore.*;
import android.graphics.Color;

/** Local, conservative terrain avoidance. Coordinates use screen height as one unit. */
final class CombatNavigator {
    private int cols, rows;
    private float aspect;
    private byte[] terrain;
    private Vec2 lastPlayer, lastMove = new Vec2(0, 0), escape = new Vec2(0, 0);
    private long lastTime, stalledSince, escapeUntil;

    void update(FramePixels frame, WorldState world) {
        aspect = frame.getWidth() / (float) frame.getHeight();
        cols = Math.max(1, frame.getWidth() / 6);
        rows = Math.max(1, frame.getHeight() / 6);
        byte[] raw = new byte[cols * rows];
        terrain = new byte[raw.length];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            int wall = 0, water = 0;
            for (int dy = 1; dy <= 5; dy += 2) for (int dx = 1; dx <= 5; dx += 2) {
                int c = frame.getPixel(Math.min(frame.getWidth()-1, x*6+dx), Math.min(frame.getHeight()-1, y*6+dy));
                int r=Color.red(c), g=Color.green(c), b=Color.blue(c);
                // Brick tops and saturated water. Unknown map palettes are not claimed as mapped.
                if (r>175 && g>85 && g<175 && b>65 && b<165 && r>g*1.25f && r>b*1.35f) wall++;
                if (r<85 && g>100 && b>155 && b>g*1.05f) water++;
            }
            raw[y*cols+x] = wall>=6 ? (byte)1 : water>=6 ? (byte)2 : 0;
        }
        // Require a connected patch; discard projectile-sized colored fragments.
        boolean[] seen = new boolean[raw.length];
        int[] queue = new int[raw.length];
        for (int i=0;i<raw.length;i++) {
            if (seen[i] || raw[i]==0) continue;
            int head=0, tail=1; queue[0]=i; seen[i]=true;
            while(head<tail) {
                int p=queue[head++], x=p%cols, y=p/cols;
                for(int k=0;k<4;k++) {
                    int nx=x+(k==0?-1:k==1?1:0), ny=y+(k==2?-1:k==3?1:0);
                    if(nx<0||ny<0||nx>=cols||ny>=rows) continue;
                    int n=ny*cols+nx;
                    if(!seen[n] && raw[n]==raw[i]) { seen[n]=true; queue[tail++]=n; }
                }
            }
            if(tail>=6) for(int n=0;n<tail;n++) terrain[queue[n]]=raw[i];
        }
        // Character artwork can share a wall palette; never classify its immediate body as terrain.
        java.util.ArrayList<Track> actors=new java.util.ArrayList<>(world.enemies());
        if(world.player()!=null) actors.add(world.player());
        for(Track actor:actors) {
            if(!actor.observed()) continue;
            int left=Math.max(0,(int)((actor.pos().x()-.055f)/aspect*cols));
            int right=Math.min(cols-1,(int)((actor.pos().x()+.055f)/aspect*cols));
            int top=Math.max(0,(int)((actor.pos().y()-.055f)*rows));
            int bottom=Math.min(rows-1,(int)((actor.pos().y()+.055f)*rows));
            for(int y=top;y<=bottom;y++) for(int x=left;x<=right;x++) {
                float dx=(x+.5f)/cols*aspect-actor.pos().x(), dy=(y+.5f)/rows-actor.pos().y();
                if(dx*dx+dy*dy<.055f*.055f) terrain[y*cols+x]=0;
            }
        }
        long now=world.frameTimeMs();
        Track player=world.player();
        if(player==null || !player.observed() || now<=lastTime || now-lastTime>500) {
            stalledSince=0; escapeUntil=0; lastPlayer=null;
        }
        if(player!=null && player.observed()) {
            if(lastPlayer!=null && lastMove.len()>.2f) {
                float progress=player.pos().sub(lastPlayer).sub(world.globalFrameShift()).len();
                if(progress<.006f) {
                    if(stalledSince==0) stalledSince=now;
                } else stalledSince=0;
            } else stalledSince=0;
            lastPlayer=player.pos();
        }
        lastTime=now;
    }

    boolean blocked(Vec2 from, Vec2 to, boolean movement) {
        if(terrain==null) return false;
        Vec2 delta=to.sub(from);
        int steps=Math.max(1,(int)Math.ceil(delta.len()*rows*2));
        float margin=movement?.025f:0;
        for(int i=1;i<=steps;i++) {
            Vec2 p=from.add(delta.mul(i/(float)steps));
            if(movement && (p.x()<.04f || p.x()>aspect-.04f || p.y()<.08f || p.y()>.94f)) return true;
            for(int ox=-1;ox<=1;ox++) for(int oy=-1;oy<=1;oy++) {
                int x=(int)((p.x()+ox*margin)/aspect*cols), y=(int)((p.y()+oy*margin)*rows);
                if(x<0||y<0||x>=cols||y>=rows) continue;
                byte cell=terrain[y*cols+x];
                if(cell==1 || (movement && cell==2)) return true;
            }
        }
        return false;
    }

    ControlCommand route(WorldState s, ControlCommand c) {
        return route(s, c, .65f, false);
    }

    ControlCommand route(WorldState s, ControlCommand c, float range, boolean throughWalls) {
        Track player=s.player();
        if(player==null || !player.observed()) { lastMove=new Vec2(0,0); return c; }
        Vec2 p=player.pos(), desired=c.move().norm();
        boolean fire=c.fire();
        String reason=c.reason();
        // Stop shooting if the actual aim ray meets solid terrain before the nearest aligned target.
        float targetDistance=range;
        for(Track e:s.enemies()) {
            Vec2 d=e.pos().sub(p);
            if(d.norm().dot(c.aim())>.92f) targetDistance=Math.min(targetDistance,d.len());
        }
        boolean dodging=c.reason().contains("dodge") || c.reason().contains("EVADE");
        boolean occluded=!throughWalls && c.aim().len()>.1f && blocked(p.add(c.aim().mul(.045f)),p.add(c.aim().mul(targetDistance)),false);
        if(occluded) { fire=false; reason+=" / wall blocks aim"; if(!dodging) desired=c.aim(); }
        if(stalledSince!=0 && s.frameTimeMs()-stalledSince>750 && s.frameTimeMs()>=escapeUntil) {
            escapeUntil=s.frameTimeMs()+650;
            escape=new Vec2(-lastMove.y(),lastMove.x()).norm();
            if(blocked(p,p.add(escape.mul(.12f)),true)) escape=escape.mul(-1);
            stalledSince=0;
        }
        if(c.move().len()<.01f && !occluded) {
            lastMove=new Vec2(0,0);
            return new ControlCommand(lastMove,c.aim(),fire,c.superAttack(),reason);
        }
        boolean escaping=!dodging && s.frameTimeMs()<escapeUntil;
        if(escaping) { desired=escape; reason+=" / unstuck"; }
        Vec2 best=new Vec2(0,0); float score=-Float.MAX_VALUE;
        for(int i=0;i<24;i++) {
            double angle=i*Math.PI*2/24;
            Vec2 v=new Vec2((float)Math.cos(angle),(float)Math.sin(angle));
            if(blocked(p,p.add(v.mul(.10f)),true)) continue;
            float candidate=v.dot(desired)+.12f*v.dot(lastMove.norm());
            if(!blocked(p,p.add(v.mul(.20f)),true)) candidate+=.2f;
            if(candidate>score) { score=candidate; best=v; }
        }
        if(best.len()==0) reason+=" / no clear path";
        else if(best.dot(desired)<.9f) reason+=" / avoid terrain";
        lastMove=best.mul(escaping || occluded ? 1 : c.move().len());
        return new ControlCommand(lastMove,c.aim(),fire,c.superAttack(),reason);
    }
}
