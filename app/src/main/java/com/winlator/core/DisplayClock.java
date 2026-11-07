package com.winlator.core;

/**
 * Refresh rate of the panel the X server is presented on. Used by the Present extension
 * (vsync timestamps / FIFO pacing) and by the compositor/ADPF frame budget, which used
 * to assume a fixed 60Hz and capped vsync'd games at 60 FPS on 90/120Hz screens.
 */
public abstract class DisplayClock {
    private static volatile float refreshRate = 60.0f;
    private static volatile long frameIntervalNanos = 1000000000L / 60;

    public static void setRefreshRate(float rate) {
        if (rate < 24.0f || rate > 360.0f) return;
        refreshRate = rate;
        frameIntervalNanos = (long)(1000000000.0 / rate);
    }

    public static float getRefreshRate() {
        return refreshRate;
    }

    public static long getFrameIntervalNanos() {
        return frameIntervalNanos;
    }
}
