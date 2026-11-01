package com.tanuki276.fpssc;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

/** Capture-thread-owned ARGB frame. Live capture samples RGBA directly, without native-sized Bitmaps. */
final class FramePixels {
    private final int width, height;
    private final int[] pixels;
    FramePixels(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid dimensions");
        this.width=width; this.height=height; pixels=new int[Math.multiplyExact(width,height)];
    }
    int getWidth() { return width; }
    int getHeight() { return height; }
    int getPixel(int x,int y) { return pixels[y*width+x]; }
    static FramePixels copy(Bitmap source) {
        if(source==null) return null;
        FramePixels out=new FramePixels(source.getWidth(),source.getHeight());
        source.getPixels(out.pixels,0,out.width,0,0,out.width,out.height);
        return out;
    }
    static FramePixels sampleRgba(ByteBuffer input,int width,int height,int pixelStride,int rowStride,
                                 int targetWidth,FramePixels reuse) {
        if(width<=0||height<=0||pixelStride<4||targetWidth<=0
                ||rowStride<(long)(width-1)*pixelStride+4) throw new IllegalArgumentException("Invalid RGBA plane");
        long required=(long)input.position()+(long)(height-1)*rowStride+(long)(width-1)*pixelStride+4;
        if(required>input.limit()) throw new IllegalArgumentException("Truncated RGBA plane");
        int tw=Math.min(width,targetWidth),th=Math.max(1,Math.round(height*(tw/(float)width)));
        FramePixels out=reuse!=null&&reuse.width==tw&&reuse.height==th?reuse:new FramePixels(tw,th);
        int origin=input.position();
        for(int y=0;y<th;y++) {
            int sy=Math.min(height-1,(int)((y+.5)*height/th));
            for(int x=0;x<tw;x++) {
                int sx=Math.min(width-1,(int)((x+.5)*width/tw));
                int offset=origin+sy*rowStride+sx*pixelStride;
                out.pixels[y*tw+x]=0xff000000|((input.get(offset)&255)<<16)
                        |((input.get(offset+1)&255)<<8)|(input.get(offset+2)&255);
            }
        }
        return out;
    }
}
