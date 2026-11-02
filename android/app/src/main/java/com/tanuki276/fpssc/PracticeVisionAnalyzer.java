package com.tanuki276.fpssc;

import android.graphics.Color;
import java.util.ArrayDeque;

/**
 * Practice/diagnostic-only temporal vision layer. It does not feed touch commands.
 * It stabilizes box-like and fast-motion observations across frames so detector tuning can be
 * inspected without letting one noisy frame dominate the result.
 */
final class PracticeVisionAnalyzer {
    record Snapshot(int boxCandidates, int motionCandidates, float boxConfidence,
                    float motionConfidence, float sceneStability) {
        String compact() {
            return "vision box=" + boxCandidates + "(" + pct(boxConfidence) + ")"
                    + " motion=" + motionCandidates + "(" + pct(motionConfidence) + ")"
                    + " stable=" + pct(sceneStability);
        }
        private static int pct(float v) { return Math.round(Math.max(0f, Math.min(1f, v)) * 100f); }
    }

    private static final int STEP = 4;
    private int cols, rows;
    private short[] previousLuma;
    private final ArrayDeque<Integer> boxHistory = new ArrayDeque<>();
    private final ArrayDeque<Integer> motionHistory = new ArrayDeque<>();
    private float stability=.5f;

    Snapshot analyze(FramePixels frame) {
        if(frame==null || frame.getWidth()<96 || frame.getHeight()<96)
            return new Snapshot(0,0,0,0,0);
        int newCols=(frame.getWidth()+STEP-1)/STEP;
        int newRows=(frame.getHeight()+STEP-1)/STEP;
        if(previousLuma==null || cols!=newCols || rows!=newRows) {
            cols=newCols; rows=newRows;
            previousLuma=new short[cols*rows];
            sampleLuma(frame,previousLuma);
            boxHistory.clear(); motionHistory.clear(); stability=.5f;
            return new Snapshot(0,0,0,0,stability);
        }

        byte[] boxMask=new byte[cols*rows];
        byte[] motionMask=new byte[cols*rows];
        short[] current=new short[cols*rows];
        int changed=0, usable=0;
        for(int gy=0;gy<rows;gy++) for(int gx=0;gx<cols;gx++) {
            int x=Math.min(frame.getWidth()-1,gx*STEP+STEP/2);
            int y=Math.min(frame.getHeight()-1,gy*STEP+STEP/2);
            int c=frame.getPixel(x,y);
            int r=Color.red(c),g=Color.green(c),b=Color.blue(c);
            int lum=(r*3+g*6+b)/10;
            int i=gy*cols+gx;
            current[i]=(short)lum;

            float nx=x/(float)frame.getWidth(), ny=y/(float)frame.getHeight();
            boolean hud=ny>.86f || (ny>.67f && (nx<.20f || nx>.80f));
            if(hud) continue;
            usable++;

            int max=Math.max(r,Math.max(g,b)), min=Math.min(r,Math.min(g,b));
            int sat=max-min;
            // Yellow/orange destructible-object palette: conservative and shape-filtered below.
            if(r>145 && g>82 && g<205 && b<105 && r>g*1.08f && g>b*1.25f && sat>65)
                boxMask[i]=1;

            int delta=Math.abs(lum-(previousLuma[i]&0xffff));
            if(delta>28) changed++;
            // Fast-motion candidates require both temporal change and local color information.
            if(delta>48 && sat>35 && max>75) motionMask[i]=1;
        }

        int boxes=countComponents(boxMask,3,55,2.7f,0.36f);
        int moving=countComponents(motionMask,1,24,4.5f,0.20f);
        previousLuma=current;

        push(boxHistory,boxes,7);
        push(motionHistory,moving,7);
        int stableBoxes=median(boxHistory), stableMotion=median(motionHistory);
        float boxAgreement=agreement(boxHistory,stableBoxes);
        float motionAgreement=agreement(motionHistory,stableMotion);
        float changedRatio=usable==0?1f:changed/(float)usable;
        float frameStability=1f-Math.min(1f,changedRatio*4.2f);
        stability=stability*.72f+frameStability*.28f;

        float boxConfidence=clamp(boxAgreement*.72f+stability*.28f,0,1);
        float motionConfidence=clamp(motionAgreement*.62f+(1f-stability)*.38f,0,1);
        return new Snapshot(stableBoxes,stableMotion,boxConfidence,motionConfidence,stability);
    }

    private void sampleLuma(FramePixels frame,short[] out) {
        for(int gy=0;gy<rows;gy++) for(int gx=0;gx<cols;gx++) {
            int x=Math.min(frame.getWidth()-1,gx*STEP+STEP/2);
            int y=Math.min(frame.getHeight()-1,gy*STEP+STEP/2);
            int c=frame.getPixel(x,y);
            out[gy*cols+gx]=(short)((Color.red(c)*3+Color.green(c)*6+Color.blue(c))/10);
        }
    }

    private int countComponents(byte[] mask,int minCells,int maxCells,float maxAspect,float minFill) {
        boolean[] seen=new boolean[mask.length];
        int[] queue=new int[mask.length];
        int count=0;
        for(int start=0;start<mask.length;start++) {
            if(mask[start]==0 || seen[start]) continue;
            int head=0,tail=1,minX=start%cols,maxX=minX,minY=start/cols,maxY=minY;
            queue[0]=start; seen[start]=true;
            while(head<tail) {
                int p=queue[head++],x=p%cols,y=p/cols;
                minX=Math.min(minX,x); maxX=Math.max(maxX,x); minY=Math.min(minY,y); maxY=Math.max(maxY,y);
                for(int k=0;k<4;k++) {
                    int nx=x+(k==0?-1:k==1?1:0), ny=y+(k==2?-1:k==3?1:0);
                    if(nx<0||ny<0||nx>=cols||ny>=rows) continue;
                    int n=ny*cols+nx;
                    if(mask[n]!=0&&!seen[n]) { seen[n]=true; queue[tail++]=n; }
                }
            }
            if(tail<minCells||tail>maxCells) continue;
            int w=maxX-minX+1,h=maxY-minY+1;
            float aspect=Math.max(w,h)/(float)Math.max(1,Math.min(w,h));
            float fill=tail/(float)(w*h);
            if(aspect<=maxAspect && fill>=minFill) count++;
        }
        return count;
    }

    private static void push(ArrayDeque<Integer> q,int value,int cap) {
        q.addLast(value); while(q.size()>cap) q.removeFirst();
    }
    private static int median(ArrayDeque<Integer> q) {
        if(q.isEmpty()) return 0;
        int[] a=new int[q.size()]; int i=0; for(int v:q)a[i++]=v;
        java.util.Arrays.sort(a); return a[a.length/2];
    }
    private static float agreement(ArrayDeque<Integer> q,int median) {
        if(q.isEmpty()) return 0;
        float error=0; for(int v:q) error+=Math.abs(v-median);
        return clamp(1f-error/(q.size()*(1f+median)*1.8f),0,1);
    }
    private static float clamp(float v,float lo,float hi) { return Math.max(lo,Math.min(hi,v)); }
}
