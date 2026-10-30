package com.wowsir.wowos;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Full-screen idle screen (screensaver). Any key or touch dismisses it. */
public class IdleActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        setContentView(new IdleView(this));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();   // wake on any remote key
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        finish();
        return true;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        finish();
    }
}
