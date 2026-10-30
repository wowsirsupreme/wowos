package com.wowsir.wowos;

import android.service.dreams.DreamService;

/** Lets the idle screen also serve as the system screensaver (Daydream), if enabled. */
public class WowDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(new IdleView(this));
    }
}
