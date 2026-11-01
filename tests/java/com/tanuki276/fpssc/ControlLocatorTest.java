package com.tanuki276.fpssc;
import android.graphics.Bitmap;

public final class ControlLocatorTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if(!value) throw new AssertionError(message); }
    private static void circle(Bitmap b, int x, int y, int radius, int color) {
        for(int py=y-radius;py<=y+radius;py++) for(int px=x-radius;px<=x+radius;px++)
            if(px>=0&&py>=0&&px<b.getWidth()&&py<b.getHeight()&&(px-x)*(px-x)+(py-y)*(py-y)<=radius*radius) b.setPixel(px,py,color);
    }
    private static Bitmap scene(int w,int h,float mx,float my,float ax,float ay,boolean attack) {
        Bitmap b=new Bitmap(w,h);
        circle(b,Math.round(mx*w),Math.round(my*h),Math.round(h*.055f),0xff287f9a);
        if(attack) circle(b,Math.round(ax*w),Math.round(ay*h),Math.round(h*.060f),0xffbc8038);
        // A gold super button with a white skull must not win over the attack joystick.
        circle(b,Math.round(w*.70f),Math.round(h*.73f),Math.round(h*.065f),0xffffb020);
        circle(b,Math.round(w*.70f),Math.round(h*.73f),Math.round(h*.032f),0xffffffff);
        return b;
    }
    public static void main(String[] args) {
        ControlLocator l=new ControlLocator();
        Bitmap a=scene(640,300,.16f,.75f,.86f,.60f,true);
        var d=l.detect(a);
        check(d!=null,"buttons should be detected");
        check(Math.abs(d.profile().attackX()-.86f)<.02f,"gold super button selected as attack");
        check(l.detect(new Bitmap(640,300))==null,"blank screen accepted");
        check(l.detect(scene(640,300,.16f,.75f,.86f,.60f,false))==null,"super-only screen accepted");
        check(l.update(a,1000,true)==null,"locked before stability");
        check(l.update(a,1120,true)==null,"locked before third observation");
        check(l.update(a,1240,true)!=null,"stable layout not locked");
        check(l.update(new Bitmap(640,300),1300,true)!=null,"short occlusion should tolerate existing lock");
        check(l.update(new Bitmap(640,300),2900,true)==null,"expired lock retained");
        Bitmap shifted=scene(640,300,.25f,.65f,.91f,.81f,true);
        check(l.detect(shifted)!=null&&Math.abs(l.detect(shifted).profile().moveX()-.25f)<.02f,"shifted controls not found");
        check(l.update(a,3100,false)==null,"menu should clear layout");
        check(l.update(a,3220,true)==null,"old scene lock leaked");
        check(l.detect(scene(960,450,.11f,.82f,.82f,.58f,true))!=null,"scaled controls not found");
        Bitmap red = scene(640,300,.16f,.75f,.86f,.60f,false);
        circle(red,550,180,18,0xffdf3540);
        check(l.detect(red)!=null,"red attack button not found");
        ControlLocator changed = new ControlLocator();
        changed.update(a,1000,true); changed.update(a,1120,true); changed.update(a,1240,true);
        check(changed.update(shifted,1360,true)==null,"old position should be withheld on a conflicting layout");
        check(changed.update(shifted,1480,true)==null,"new position needs stability");
        check(changed.update(shifted,1600,true)!=null,"new layout not reacquired");
        check(changed.update(scene(960,450,.11f,.82f,.82f,.58f,true),1720,true)==null,"resize must invalidate lock");
        System.out.println("ControlLocator: "+checks+" regression checks passed");
    }
}
