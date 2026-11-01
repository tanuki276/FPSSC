package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

/** Detects visible joystick artwork. Search regions are broad; centers are never preset coordinates. */
final class ControlLocator {
    record Layout(TouchProfile profile, float confidence) {}
    private record Candidate(float x, float y, float radius, float score) {}
    private Layout locked;
    private Layout candidate;
    private int confirmations;
    private int width, height;
    private long lastVisibleMs;
    private long lastScanMs = -1000;
    private String status = "操作ボタンを検索中";

    Layout update(Bitmap frame, long nowMs, boolean inMatch) {
        if (frame.getWidth() != width || frame.getHeight() != height || !inMatch) {
            clear();
            width = frame.getWidth(); height = frame.getHeight();
        }
        if (!inMatch) { status = "対戦画面の認識を待っています"; return null; }
        if (nowMs - lastScanMs >= 120) {
            lastScanMs = nowMs;
            Layout found = detect(frame);
            if (found != null) {
                lastVisibleMs = nowMs;
                if (candidate != null && close(candidate.profile(), found.profile())) confirmations++;
                else { candidate = found; confirmations = 1; }
                if (locked != null && !close(locked.profile(), found.profile())) locked = null;
                if (confirmations >= 3 && locked == null) locked = found;
            } else {
                candidate = null;
                confirmations = 0;
            }
        }
        // Joystick artwork may be temporarily covered by a gesture or effects, not indefinitely.
        if (locked != null && nowMs - lastVisibleMs > 1500) locked = null;
        status = locked == null ? "操作ボタンを自動認識中 (" + confirmations + "/3)"
                : "操作位置を自動認識済み";
        return locked;
    }
    String status() { return status; }
    void clear() {
        locked = null; candidate = null; confirmations = 0;
        lastVisibleMs = 0; lastScanMs = -1000;
    }
    private static boolean close(TouchProfile a, TouchProfile b) {
        return Math.abs(a.moveX()-b.moveX()) < .02f && Math.abs(a.moveY()-b.moveY()) < .035f
                && Math.abs(a.attackX()-b.attackX()) < .02f && Math.abs(a.attackY()-b.attackY()) < .035f;
    }
    Layout detect(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w <= h || h < 96) return null;
        List<Candidate> movement = components(bitmap, true);
        List<Candidate> attack = components(bitmap, false);
        if (movement.isEmpty() || attack.isEmpty()) return null;
        Candidate m = movement.stream().max(java.util.Comparator.comparingDouble(Candidate::score)).orElseThrow();
        Candidate a = attack.stream().max(java.util.Comparator.comparingDouble(Candidate::score)).orElseThrow();
        try {
            return new Layout(new TouchProfile(m.x()/w, m.y()/h, a.x()/w, a.y()/h,
                    Math.max(.025f, Math.min(.12f, Math.min(m.radius(), a.radius()) * .85f / h))),
                    Math.min(m.score(), a.score()));
        } catch (IllegalArgumentException ignored) { return null; }
    }
    private List<Candidate> components(Bitmap b, boolean movement) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = Math.round(w * (movement ? .025f : .62f));
        int x1 = Math.round(w * (movement ? .36f : .98f));
        int y0 = Math.round(h * (movement ? .46f : .35f)), y1 = Math.round(h * .94f);
        int rw = x1-x0, rh = y1-y0;
        byte[] mask = new byte[rw*rh];
        for (int y=0; y<rh; y++) for (int x=0; x<rw; x++) {
            int c=b.getPixel(x+x0,y+y0), r=Color.red(c), g=Color.green(c), bl=Color.blue(c);
            boolean match = movement ? g>r*1.2f && bl>r*1.2f && g>65 && bl>70
                    : (r>g*1.25f && g>60 && bl<g*.85f)
                    || (r>130 && r>g*1.45f && r>bl*1.45f);
            if (match) mask[y*rw+x]=1;
        }
        int[] queue=new int[mask.length];
        List<Candidate> out=new ArrayList<>();
        for (int start=0; start<mask.length; start++) {
            if(mask[start]==0) continue;
            int head=0,tail=1,minX=start%rw,maxX=minX,minY=start/rw,maxY=minY;
            queue[0]=start; mask[start]=0;
            while(head<tail) {
                int p=queue[head++],x=p%rw,y=p/rw;
                minX=Math.min(minX,x); maxX=Math.max(maxX,x); minY=Math.min(minY,y); maxY=Math.max(maxY,y);
                for(int k=0;k<4;k++) {
                    int nx=x+(k==0?-1:k==1?1:0), ny=y+(k==2?-1:k==3?1:0);
                    if(nx<0||nx>=rw||ny<0||ny>=rh) continue;
                    int n=ny*rw+nx;
                    if(mask[n]!=0){ mask[n]=0; queue[tail++]=n; }
                }
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;
            float ratio=bw/(float)bh, fill=tail/(float)(bw*bh);
            if(bw<h*.06f||bh<h*.06f||bw>h*.24f||bh>h*.24f||ratio<.72f||ratio>1.35f||fill<.48f) continue;
            float cx=x0+(minX+maxX)/2f,cy=y0+(minY+maxY)/2f,rad=(bw+bh)/4f;
            int white=0,count=0;
            for(int y=Math.max(0,Math.round(cy-rad*.55f));y<=Math.min(h-1,cy+rad*.55f);y++)
                for(int x=Math.max(0,Math.round(cx-rad*.55f));x<=Math.min(w-1,cx+rad*.55f);x++) {
                    int c=b.getPixel(x,y);
                    if(Color.red(c)>200&&Color.green(c)>200&&Color.blue(c)>200) white++;
                    count++;
                }
            // The super button's white skull is not the normal attack joystick's dark crosshair.
            if(!movement && white>count*.055f) continue;
            float score=.55f+Math.min(.25f,fill*.25f)+(1-Math.abs(1-ratio))*.15f;
            out.add(new Candidate(cx,cy,rad,score));
        }
        return out;
    }
}
