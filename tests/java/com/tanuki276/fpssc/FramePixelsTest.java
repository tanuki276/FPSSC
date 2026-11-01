package com.tanuki276.fpssc;
import java.nio.ByteBuffer;
public final class FramePixelsTest {
    private static int checks;
    private static void check(boolean b) { checks++; if(!b) throw new AssertionError(); }
    private static void invalid(Runnable action) {
        checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid plane accepted");
    }
    public static void main(String[] args) {
        ByteBuffer data=ByteBuffer.allocate(51); data.position(3);
        for(int y=0;y<2;y++) for(int x=0;x<3;x++) {
            int p=3+y*28+x*8;
            data.put(p,(byte)(x*20)); data.put(p+1,(byte)(y*30)); data.put(p+2,(byte)100); data.put(p+3,(byte)255);
        }
        FramePixels frame=FramePixels.sampleRgba(data,3,2,8,28,3,null);
        check(frame.getWidth()==3&&frame.getHeight()==2);
        check(frame.getPixel(2,1)==0xff281e64);
        check(data.position()==3);
        check(FramePixels.sampleRgba(data,3,2,8,28,3,frame)==frame);
        FramePixels small=FramePixels.sampleRgba(data,3,2,8,28,2,null);
        check(small.getWidth()==2&&small.getHeight()==1);
        check(small.getPixel(1,0)==0xff281e64);
        invalid(()->FramePixels.sampleRgba(data,3,2,3,28,3,null));
        invalid(()->FramePixels.sampleRgba(data,3,2,8,10,3,null));
        invalid(()->FramePixels.sampleRgba(data,3,2,8,28,0,null));
        data.limit(50);
        invalid(()->FramePixels.sampleRgba(data,3,2,8,28,3,null));
        System.out.println("FramePixels: "+checks+" checks passed");
    }
}
