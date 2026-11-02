package com.tanuki276.fpssc;

import android.graphics.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Practice/diagnostic-only perception layer. It does not emit controls.
 * It temporally validates crate-like objects, bush coverage, and small moving objects so
 * single-frame color effects do not immediately become trusted detections.
 */
final class PracticeVisionDiagnostics {
    record Result(
            int crateCandidates,
            int stableCrates,
            float crateConfidence,
            float bushCoverage,
            int movingCandidates,
            int stableMovingObjects,
            float motionConfidence,
            float frameChange
    ) {
        String compact() {
            return "box="+stableCrates+"/"+crateCandidates
                    +" bush="+pct(bushCoverage)
                    +" motion="+stableMovingObjects+"/"+movingCandidates;
        }
        String detail() {
            return "vision: crates="+stableCrates+" confidence="+fmt(crateConfidence)
                    +" bush="+pct(bushCoverage)
                    +" moving="+stableMovingObjects+" confidence="+fmt(motionConfidence)
                    +" change="+fmt(frameChange);
        }
        private static String pct(float v) { return Math.round(v*100f)+"%"; }
        private static String fmt(float v) { return String.format(java.util.Locale.US,"%.2f",v); }
    }

    private record Blob(float x,float y,float w,float h,int area,float score) {
        float distance(Blob other) {
            float dx=x-other.x,dy=y-other.y;
            return (float)Math.sqrt(dx*dx+dy*dy);
        }
    }

    private int width,height;
    private byte[] previousLuma;
    private List<Blob> previousCrates=List.of();
    private List<Blob> previousMotion=List.of();
    private long previousTimeMs;

    Result analyze(FramePixels frame,long nowMs) {
        if(frame==null||frame.getWidth()<96||frame.getHeight()<96) {
            reset();
            return new Result(0,0,0f,0f,0,0,0f,0f);
        }
        if(frame.getWidth()!=width||frame.getHeight()!=height||nowMs<=previousTimeMs||nowMs-previousTimeMs>700) {
            width=frame.getWidth(); height=frame.getHeight();
            previousLuma=null;
            previousCrates=List.of(); previousMotion=List.of();
        }

        boolean[] crateMask=new boolean[width*height];
        boolean[] motionMask=new boolean[width*height];
        byte[] currentLuma=new byte[width*height];
        int bush=0, playable=0, changed=0;

        for(int y=Math.max(1,(int)(height*.06f));y<height*.91f;y++) {
            for(int x=Math.max(1,(int)(width*.02f));x<width*.98f;x++) {
                int index=y*width+x;
                int c=frame.getPixel(x,y);
                int r=Color.red(c),g=Color.green(c),b=Color.blue(c);
                int luma=(r*54+g*183+b*19)>>8;
                currentLuma[index]=(byte)luma;
                playable++;

                boolean bushPixel=isBush(r,g,b);
                if(bushPixel) bush++;
                if(isCrate(r,g,b)) crateMask[index]=true;

                if(previousLuma!=null && y<height*.86f) {
                    int old=previousLuma[index]&255;
                    int diff=Math.abs(luma-old);
                    if(diff>=30) changed++;
                    // Motion candidates deliberately reject terrain-colored pixels and large HUD areas.
                    if(diff>=38 && !bushPixel && !crateMask[index] && !isNearBlack(r,g,b)
                            && x>width*.035f && x<width*.965f && y>height*.09f && y<height*.82f)
                        motionMask[index]=true;
                }
            }
        }

        List<Blob> crates=components(crateMask,true);
        List<Blob> moving=components(motionMask,false);
        int stableCrates=temporalMatches(crates,previousCrates,.055f);
        int stableMoving=temporalMatches(moving,previousMotion,.085f);

        float crateConfidence=crates.isEmpty()?0f:clamp((stableCrates+.35f)/(crates.size()+.35f),0f,1f);
        float motionConfidence=moving.isEmpty()?0f:clamp((stableMoving+.25f)/(moving.size()+.25f),0f,1f);
        float bushCoverage=playable==0?0f:bush/(float)playable;
        float frameChange=playable==0?0f:changed/(float)playable;

        previousLuma=currentLuma;
        previousCrates=top(crates,16);
        previousMotion=top(moving,32);
        previousTimeMs=nowMs;
        return new Result(crates.size(),stableCrates,crateConfidence,bushCoverage,
                moving.size(),stableMoving,motionConfidence,frameChange);
    }

    private void reset() {
        width=height=0; previousLuma=null;
        previousCrates=List.of(); previousMotion=List.of(); previousTimeMs=0;
    }

    private List<Blob> components(boolean[] mask,boolean crate) {
        List<Blob> out=new ArrayList<>();
        boolean[] seen=new boolean[mask.length];
        int[] queue=new int[mask.length];
        for(int start=0;start<mask.length;start++) {
            if(!mask[start]||seen[start]) continue;
            int head=0,tail=1; queue[0]=start; seen[start]=true;
            int minX=start%width,maxX=minX,minY=start/width,maxY=minY;
            while(head<tail) {
                int p=queue[head++],x=p%width,y=p/width;
                minX=Math.min(minX,x); maxX=Math.max(maxX,x);
                minY=Math.min(minY,y); maxY=Math.max(maxY,y);
                for(int k=0;k<4;k++) {
                    int nx=x+(k==0?-1:k==1?1:0),ny=y+(k==2?-1:k==3?1:0);
                    if(nx<0||ny<0||nx>=width||ny>=height) continue;
                    int n=ny*width+nx;
                    if(mask[n]&&!seen[n]) { seen[n]=true; queue[tail++]=n; }
                }
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;
            float area=tail/(float)(height*height);
            float aspect=bw/(float)Math.max(1,bh);
            float fill=tail/(float)Math.max(1,bw*bh);
            if(crate) {
                if(area<.00022f||area>.012f||aspect<.52f||aspect>1.85f||fill<.30f) continue;
                float sizeScore=1f-Math.min(1f,Math.abs(area-.0018f)/.0045f);
                float shapeScore=1f-Math.min(1f,Math.abs(1f-aspect)/.9f);
                out.add(new Blob((minX+maxX)*.5f/width,(minY+maxY)*.5f/height,
                        bw/(float)width,bh/(float)height,tail,clamp(fill*.45f+shapeScore*.30f+sizeScore*.25f,0f,1f)));
            } else {
                if(area<.000015f||area>.0022f||fill<.16f) continue;
                // Fast effects tend to be compact; broad flashes are rejected by the area cap.
                float compact=clamp(fill/.65f,0f,1f);
                out.add(new Blob((minX+maxX)*.5f/width,(minY+maxY)*.5f/height,
                        bw/(float)width,bh/(float)height,tail,compact));
            }
        }
        out.sort(Comparator.comparingDouble((Blob b)->b.score()).reversed());
        return out;
    }

    private static int temporalMatches(List<Blob> current,List<Blob> previous,float maxDistance) {
        if(current.isEmpty()||previous.isEmpty()) return 0;
        boolean[] used=new boolean[previous.size()];
        int stable=0;
        for(Blob c:current) {
            int best=-1; float distance=maxDistance;
            for(int i=0;i<previous.size();i++) {
                if(used[i]) continue;
                Blob p=previous.get(i);
                float d=c.distance(p);
                float scale=Math.max(c.w()+c.h(),p.w()+p.h());
                float allowed=maxDistance+scale*.35f;
                if(d<distance&&d<allowed) { distance=d; best=i; }
            }
            if(best>=0) { used[best]=true; stable++; }
        }
        return stable;
    }

    private static List<Blob> top(List<Blob> input,int limit) {
        return input.size()<=limit?new ArrayList<>(input):new ArrayList<>(input.subList(0,limit));
    }

    private static boolean isCrate(int r,int g,int b) {
        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        return r>135&&g>72&&g<205&&b<125&&r>g*1.08f&&g>b*1.12f&&(max-min)>48;
    }

    private static boolean isBush(int r,int g,int b) {
        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        return g>78&&g>r*1.10f&&g>b*1.05f&&(max-min)>32&&r<175;
    }

    private static boolean isNearBlack(int r,int g,int b) { return r<24&&g<24&&b<24; }
    private static float clamp(float v,float lo,float hi) { return Math.max(lo,Math.min(hi,v)); }
}
