package com.tanuki276.fpssc;

import android.graphics.Bitmap;

public final class PracticeVisionDiagnosticsTest {
    private static void check(boolean ok,String message) {
        if(!ok) throw new AssertionError(message);
    }
    private static int rgb(int r,int g,int b) { return 0xff000000|(r<<16)|(g<<8)|b; }

    private static Bitmap frame(int w,int h,int movingX) {
        Bitmap b=new Bitmap(w,h);
        int ground=rgb(90,110,120);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) b.setPixel(x,y,ground);

        int bush=rgb(60,145,70);
        for(int y=28;y<62;y++) for(int x=18;x<72;x++) b.setPixel(x,y,bush);

        int crate=rgb(190,125,55);
        for(int y=76;y<87;y++) for(int x=120;x<132;x++) b.setPixel(x,y,crate);

        int shot=rgb(245,75,70);
        for(int y=104;y<108;y++) for(int x=movingX;x<movingX+5;x++) b.setPixel(x,y,shot);
        return b;
    }

    private static Bitmap sceneFlash(int w,int h) {
        Bitmap b=new Bitmap(w,h);
        int c=rgb(190,185,178);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) b.setPixel(x,y,c);
        return b;
    }

    public static void main(String[] args) {
        PracticeVisionDiagnostics vision=new PracticeVisionDiagnostics();
        int w=200,h=130;

        PracticeVisionDiagnostics.Result first=vision.analyze(FramePixels.copy(frame(w,h,45)),1000);
        check(first.crateCandidates()>=1,"crate-like object should be detected as a candidate");
        check(first.stableCrates()==0,"first-frame crate should not yet be trusted temporally");
        check(first.bushCoverage()>.03f,"bush patch should contribute measurable coverage");

        PracticeVisionDiagnostics.Result second=vision.analyze(FramePixels.copy(frame(w,h,53)),1060);
        check(second.stableCrates()>=1,"persistent crate should become temporally stable");
        check(second.crateConfidence()>.45f,"stable crate confidence should rise");
        check(second.movingCandidates()>=1,"moving compact object should create motion candidates");

        PracticeVisionDiagnostics.Result third=vision.analyze(FramePixels.copy(frame(w,h,61)),1120);
        check(third.stableMovingObjects()>=1,"consistent moving object should become temporally stable");
        check(third.motionConfidence()>.20f,"stable motion should have non-trivial confidence");
        check(third.frameChange()<.12f,"small moving object should not look like a full-frame scene change");
        check(third.compact().contains("box="),"compact diagnostics should expose box count");

        PracticeVisionDiagnostics.Result flash=vision.analyze(FramePixels.copy(sceneFlash(w,h)),1180);
        check(flash.frameChange()>.16f,"large scene transition should be recognized as global change");
        check(flash.stableMovingObjects()==0,"global scene change must not be trusted as moving objects");
        check(flash.motionConfidence()==0f,"global scene change must zero motion confidence");

        System.out.println("PracticeVisionDiagnosticsTest OK");
    }
}
