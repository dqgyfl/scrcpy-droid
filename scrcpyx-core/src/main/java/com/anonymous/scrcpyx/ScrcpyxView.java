package com.anonymous.scrcpyx;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.List;

/**
 * @author VV
 * @date 12/14/2025
 */
public class ScrcpyxView extends SurfaceView implements SurfaceHolder.Callback {

    public static final Scrcpy scrcpy = new Scrcpy();

    public ScrcpyxView(Context context) {
        super(context);
        initScrcpy();
    }

    public ScrcpyxView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initScrcpy();
    }

    private void initScrcpy() {
        Log.d("ScrcpyxView", "initScrcpy");
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        getHolder().addCallback(this);
        setOnTouchListener((view, event) -> {
            view.performClick();
            scrcpy.sendTouchEvent(event, event.getActionIndex(), getWidth(), getHeight());
            return true;
        });
        setOnKeyListener((v, keyCode, event) -> {
            scrcpy.sendKeyEvent(event);
            return false;
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        scrcpy.sendKeyEvent(event);
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        scrcpy.sendKeyEvent(event);
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("ScrcpyxView", "surfaceCreated");
        scrcpy.attachSurface(getHolder().getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("ScrcpyxView", "surfaceChanged" + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("ScrcpyxView", "surfaceDestroyed");
        scrcpy.detachSurface();
    }

    public void start(String scrcpyxAddr, String did, String app, List<String> args, Scrcpy.ServiceCallbacks callbacks) {
        Log.d("ScrcpyxView", "start");
        // fix it: stop is not waited
        scrcpy.start(scrcpyxAddr, did, app, args, callbacks);
    }

    public void stop() {
        Log.d("ScrcpyxView", "stop");
        scrcpy.stop();
    }

    public boolean isConnected() {
        return scrcpy.isConnected();
    }

}