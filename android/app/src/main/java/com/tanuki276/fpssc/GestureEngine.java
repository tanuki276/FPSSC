package com.tanuki276.fpssc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One in-flight gesture, one replaceable command; the movement pointer continues between slices. */
final class GestureEngine {
    private record Command(AgentCore.ControlCommand controls,TouchProfile profile,long time,int width,int height) {}
    private static final long SLICE_MS=80;
    private final AccessibilityService service;
    private final BooleanSupplier allowed;
    private final Consumer<String> failure;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Command latest;
    private boolean busy;
    private GestureDescription.StrokeDescription movement;
    private float moveX,moveY;
    private long sequence;
    long sent,completed,cancelled;
    GestureEngine(AccessibilityService service,BooleanSupplier allowed,Consumer<String> failure) {
        this.service=service; this.allowed=allowed; this.failure=failure;
    }
    void offer(AgentCore.ControlCommand command,TouchProfile profile,long time,int width,int height) {
        latest=new Command(command,profile,time,width,height);
        pump();
    }
    void stop() { latest=null; if(!busy) release(); }
    boolean holdingMovement() { return movement!=null; }
    private void pump() {
        if(busy) return;
        Command c=latest;
        long age=c==null?Long.MAX_VALUE:SystemClock.elapsedRealtime()-c.time();
        if(c==null||age<0||age>200||!allowed.getAsBoolean()) { latest=null; release(); return; }
        boolean moving=c.controls().move().len()>.01f,fire=c.controls().fire()&&c.controls().aim().len()>.01f;
        if(!moving&&!fire) { release(); return; }
        GestureDescription.Builder builder=new GestureDescription.Builder();
        GestureDescription.StrokeDescription nextMovement=null;
        if(moving) {
            AgentCore.Vec2 v=TouchProfile.screenVector(c.controls().move(),c.width(),c.height());
            float x=clip(c.profile().moveX()*c.width()+v.x()*c.profile().radius()*c.height(),c.width());
            float y=clip(c.profile().moveY()*c.height()+v.y()*c.profile().radius()*c.height(),c.height());
            Path path=new Path();
            path.moveTo(movement==null?c.profile().moveX()*c.width():moveX,
                    movement==null?c.profile().moveY()*c.height():moveY);
            path.lineTo(x,y);
            nextMovement=movement==null?new GestureDescription.StrokeDescription(path,0,SLICE_MS,true)
                    :movement.continueStroke(path,0,SLICE_MS,true);
            builder.addStroke(nextMovement); moveX=x; moveY=y;
        } else if(movement!=null) builder.addStroke(endStroke());
        if(fire) {
            AgentCore.Vec2 v=TouchProfile.screenVector(c.controls().aim(),c.width(),c.height());
            Path path=new Path(); path.moveTo(c.profile().attackX()*c.width(),c.profile().attackY()*c.height());
            path.lineTo(clip(c.profile().attackX()*c.width()+v.x()*c.profile().radius()*c.height(),c.width()),
                    clip(c.profile().attackY()*c.height()+v.y()*c.profile().radius()*c.height(),c.height()));
            builder.addStroke(new GestureDescription.StrokeDescription(path,0,SLICE_MS,false));
        }
        movement=nextMovement;
        dispatch(builder.build(),true);
    }
    private static float clip(float coordinate,int limit) { return Math.max(1,Math.min(limit-1,coordinate)); }
    private GestureDescription.StrokeDescription endStroke() {
        Path path=new Path(); path.moveTo(moveX,moveY); path.lineTo(moveX,moveY);
        return movement.continueStroke(path,0,1,false);
    }
    private void release() {
        if(movement==null) return;
        GestureDescription.StrokeDescription ending=endStroke(); movement=null;
        dispatch(new GestureDescription.Builder().addStroke(ending).build(),false);
    }
    private void dispatch(GestureDescription gesture,boolean repeat) {
        busy=true;
        long token=++sequence;
        try {
            boolean accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription ignored) {
                    if(token!=sequence) return;
                    busy=false; completed++;
                    if(repeat) pump(); else if(latest!=null) pump();
                }
                @Override public void onCancelled(GestureDescription ignored) {
                    if(token!=sequence) return;
                    busy=false; cancelled++; movement=null; latest=null;
                    failure.accept("タッチ操作が中断されました。自動操作を再度 ON にしてください");
                }
            },main);
            if(!accepted) { busy=false; movement=null; latest=null; failure.accept("タッチ操作を送信できませんでした"); }
            else {
                sent++;
                main.postDelayed(()->{
                    if(busy&&token==sequence) {
                        busy=false; latest=null;
                        failure.accept("タッチ応答がありません。操作を停止しました");
                        // A final continuation releases a pointer held after a lost completion callback.
                        if(movement!=null) release();
                    }
                },400);
            }
        } catch(RuntimeException e) {
            busy=false; movement=null; latest=null;
            failure.accept("タッチエラー: "+e.getClass().getSimpleName());
        }
    }
}
