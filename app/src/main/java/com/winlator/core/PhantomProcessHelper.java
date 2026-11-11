package com.winlator.core;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;

/**
 * Android 12+ kills "phantom" child processes of apps (more than 32 system-wide, or ones
 * using a lot of CPU). Every Windows process is such a child process here, so multi-process
 * programs (Steam with its Chromium helpers, browsers, launchers) can lose processes and end
 * up running without a window. Android 14+ has a developer option to turn this off
 * ("Disable child process restrictions"); this helper points the user to it.
 */
public abstract class PhantomProcessHelper {
    private static final String PREF_NOTICE_COUNT = "phantom_process_notice_count";
    private static final byte MAX_NOTICES = 3;

    public static boolean isRestrictionActive(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            String value = Settings.Global.getString(activity.getContentResolver(), "settings_enable_monitor_phantom_procs");
            return !"false".equals(value);
        }
        catch (Exception e) {
            return false;
        }
    }

    public static void showNoticeIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !isRestrictionActive(activity)) return;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        int count = preferences.getInt(PREF_NOTICE_COUNT, 0);
        if (count >= MAX_NOTICES) return;
        preferences.edit().putInt(PREF_NOTICE_COUNT, count + 1).apply();

        ContentDialog.confirm(activity, R.string.phantom_process_notice, () -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            }
            catch (Exception e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                }
                catch (Exception e2) {}
            }
        });
    }
}
