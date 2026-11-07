package com.winlator;

import android.app.Application;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes uncaught exceptions to Download/Winlator/crash.txt (or the app's external files
 * dir as a fallback) so crashes can be diagnosed without adb.
 */
public class WinlatorApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            }
            catch (Throwable ignored) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, throwable);
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator");
        if (!dir.isDirectory() && !dir.mkdirs()) dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();

        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(dir, "crash.txt"), true))) {
            writer.println("==== "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())+" ====");
            writer.println("Device: "+Build.MANUFACTURER+" "+Build.MODEL+" ("+(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : Build.HARDWARE)+"), Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")");
            writer.println("App: "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")");
            writer.println("Thread: "+thread.getName());
            throwable.printStackTrace(writer);
            writer.println();
        }
    }
}
