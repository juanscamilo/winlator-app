package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

/**
 * Display/runtime tweaks for high-end phones such as the Galaxy S23 (Snapdragon 8 Gen 2,
 * 120Hz LTPO panel). Android/One UI default to 60Hz for apps that do not ask for more,
 * which caps the presentation rate of the X server surface regardless of the game FPS.
 */
public abstract class DisplayPerformanceHelper {
    public static boolean isHighRefreshRateEnabled(Activity activity) {
        return PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("prefer_high_refresh_rate", true);
    }

    /** Selects the display mode with the highest refresh rate at the current resolution. */
    public static float requestHighestRefreshRate(Activity activity) {
        Window window = activity.getWindow();
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? activity.getDisplay() : window.getWindowManager().getDefaultDisplay();
        if (display == null) return 0;

        Display.Mode best = getHighestRefreshRateMode(display);
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredDisplayModeId = best.getModeId();
        params.preferredRefreshRate = best.getRefreshRate();
        window.setAttributes(params);
        return best.getRefreshRate();
    }

    public static Display.Mode getHighestRefreshRateMode(Display display) {
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        return best;
    }

    /** Tells SurfaceFlinger the content rate of a surface (Android 11+), so LTPO panels stay at it. */
    public static void setSurfaceFrameRate(Surface surface, float frameRate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface == null || !surface.isValid() || frameRate <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            }
            else surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }
        catch (Exception e) {}
    }

    /**
     * Keeps {@link DisplayClock} in sync with the refresh rate the panel is actually running at
     * (the mode switch requested above is applied asynchronously). Returns the listener so it
     * can be unregistered with {@link #stopTrackingRefreshRate}.
     */
    public static DisplayManager.DisplayListener trackRefreshRate(Activity activity) {
        final DisplayManager displayManager = (DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        final Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        if (displayManager == null || display == null) return null;

        final int displayId = display.getDisplayId();
        DisplayClock.setRefreshRate(display.getRefreshRate());

        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int id) {}

            @Override
            public void onDisplayRemoved(int id) {}

            @Override
            public void onDisplayChanged(int id) {
                if (id != displayId) return;
                Display changed = displayManager.getDisplay(id);
                if (changed != null) DisplayClock.setRefreshRate(changed.getRefreshRate());
            }
        };
        displayManager.registerDisplayListener(listener, new Handler(Looper.getMainLooper()));
        return listener;
    }

    public static void stopTrackingRefreshRate(Activity activity, DisplayManager.DisplayListener listener) {
        if (listener == null) return;
        DisplayManager displayManager = (DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) displayManager.unregisterDisplayListener(listener);
    }
}
