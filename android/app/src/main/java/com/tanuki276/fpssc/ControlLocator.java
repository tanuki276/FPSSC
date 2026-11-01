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
    private String detectionDetail = "未走査";

    Layout update(Bitmap frame, long nowMs, boolean inMatch) {
        return updatePixels(FramePixels.copy(frame),nowMs,inMatch);
    }
    Layout updatePixels(FramePixels frame, long nowMs, boolean inMatch) {
        if (frame.getWidth() != width || frame.getHeight() != height || !inMatch) {
            clear();
            width = frame.getWidth(); height = frame.getHeight();
        }
        if (!inMatch) { status = "対戦画面の認識を待っています"; return null; }
        if (nowMs - lastScanMs >= 120) {
            lastScanMs = nowMs;
            Layout found = detectPixels(frame);
            if (found != null) {
                lastVisibleMs = nowMs;
                if (candidate != null && close(candidate.profile(), found.profile())) confirmations++;
                else { candidate = found; confirmations = 1; }
                if (locked != null && !sameControlArtwork(locked.profile(), found.profile())) locked = null;
                if (confirmations >= 3 && locked == null) locked = found;
            } else {
                candidate = null;
                confirmations = 0;
            }
        }
        // Joystick artwork may be temporarily covered by a gesture or effects, not indefinitely.
        if (locked != null && nowMs - lastVisibleMs > 1500) locked = null;
        status = locked == null ? "操作ボタンを自動認識中 (" + confirmations + "/3) " + detectionDetail
                : "操作位置を自動認識済み";
        return locked;
    }
    String status() { return status; }
    void clear() {
        locked = null; candidate = null; confirmations = 0;
        lastVisibleMs = 0; lastScanMs = -1000;
    }
    private boolean sameControlArtwork(TouchProfile anchor,TouchProfile observed) {
        // A dragged thumb moves inside the joystick. Keep the confirmed base center;
        // do not mistake our own injected drag for a user changing the control layout.
        float aspect=width/(float)height;
        float tolerance=anchor.radius()*1.6f;
        return Math.hypot((anchor.moveX()-observed.moveX())*aspect,anchor.moveY()-observed.moveY())<tolerance
                &&Math.hypot((anchor.attackX()-observed.attackX())*aspect,anchor.attackY()-observed.attackY())<tolerance;
    }
    private static boolean close(TouchProfile a, TouchProfile b) {
        return Math.abs(a.moveX()-b.moveX()) < .02f && Math.abs(a.moveY()-b.moveY()) < .035f
                && Math.abs(a.attackX()-b.attackX()) < .02f && Math.abs(a.attackY()-b.attackY()) < .035f;
    }
    Layout detect(Bitmap bitmap) { return detectPixels(FramePixels.copy(bitmap)); }
    Layout detectPixels(FramePixels bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w <= h || h < 96) { detectionDetail = "横画面ではありません"; return null; }
        List<Candidate> movement = components(bitmap, true);
        List<Candidate> attack = components(bitmap, false);
        detectionDetail = "移動候補=" + movement.size() + " 攻撃候補=" + attack.size();
        if (movement.isEmpty() || attack.isEmpty()) return null;
        Candidate m = movement.stream().max(java.util.Comparator.comparingDouble(Candidate::score)).orElseThrow();
        Candidate a = attack.stream().max(java.util.Comparator.comparingDouble(Candidate::score)).orElseThrow();
        try {
            return new Layout(new TouchProfile(m.x()/w, m.y()/h, a.x()/w, a.y()/h,
                    Math.max(.025f, Math.min(.12f, Math.min(m.radius(), a.radius()) * .85f / h))),
                    Math.min(m.score(), a.score()));
        } catch (IllegalArgumentException ignored) { return null; }
    }
    private List<Candidate> components(FramePixels b, boolean movement) {
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
            if(bw<h*.06f||bh<h*.06f||bw>h*.40f||bh>h*.40f||ratio<.72f||ratio>1.35f) continue;
            if (fill < .48f) {
                // Idle joysticks may show a hollow rim instead of a filled thumb pad.
                boolean[] sectors = new boolean[24];
                int onRim = 0;
                float centerX=(minX+maxX)/2f, centerY=(minY+maxY)/2f;
                for (int i=0;i<tail;i++) {
                    float dx=(queue[i]%rw-centerX)/(bw/2f), dy=(queue[i]/rw-centerY)/(bh/2f);
                    float distance=(float)Math.sqrt(dx*dx+dy*dy);
                    if (distance > .72f && distance < 1.12f) {
                        onRim++;
                        int sector=Math.min(23,(int)((Math.atan2(dy,dx)+Math.PI)/(2*Math.PI)*24));
                        sectors[sector]=true;
                    }
                }
                int coverage=0;
                for(boolean sector:sectors) if(sector) coverage++;
                if(coverage<20 || onRim<tail*.80f || fill<.045f) continue;
            }
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
