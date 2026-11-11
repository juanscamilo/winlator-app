package com.winlator.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;

import androidx.preference.PreferenceManager;

import com.winlator.BuildConfig;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-tap diagnostic report: everything needed to investigate "it doesn't open" / "it's slow"
 * problems, written to Download/Winlator/. No personal data beyond device model and the
 * names of the running Windows programs.
 */
public abstract class DiagnosticReport {
    public static File generate(XServerDisplayActivity activity, GuestLogBuffer guestLog) {
        StringBuilder sb = new StringBuilder();
        section(sb, "DEVICE");
        sb.append("Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("SoC: ").append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : Build.HARDWARE).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("GPU: ").append(GPUHelper.glGetRenderer(activity)).append(" / ").append(GPUHelper.glGetVersion(activity)).append('\n');

        try {
            ActivityManager activityManager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            sb.append("Memory: avail=").append(memoryInfo.availMem >> 20).append("MB total=").append(memoryInfo.totalMem >> 20)
              .append("MB low=").append(memoryInfo.lowMemory).append('\n');
        }
        catch (Exception e) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager powerManager = activity.getSystemService(PowerManager.class);
            if (powerManager != null) sb.append("Thermal status: ").append(powerManager.getCurrentThermalStatus()).append(" (0=none .. 3=severe)\n");
        }
        sb.append("Child process restriction active: ").append(PhantomProcessHelper.isRestrictionActive(activity)).append('\n');
        sb.append("Display refresh: ").append(DisplayClock.getRefreshRate()).append("Hz\n");

        section(sb, "PERFORMANCE");
        sb.append(PerfStats.summary()).append('\n');
        PerfStats.reset();

        section(sb, "CONTAINER");
        Container container = activity.getContainer();
        if (container != null) {
            sb.append("Screen: ").append(container.getScreenSize()).append('\n');
            sb.append("Graphics driver: ").append(container.getGraphicsDriver()).append(" | ").append(container.getGraphicsDriverConfig()).append('\n');
            sb.append("DX wrapper: ").append(container.getDXWrapper()).append(" | ").append(container.getDXWrapperConfig()).append('\n');
            sb.append("Box64 preset: ").append(container.getBox64Preset()).append('\n');
            sb.append("CPU list: ").append(container.getCPUList(true)).append('\n');
            sb.append("Env: ").append(container.getEnvVars()).append('\n');
        }

        section(sb, "SETTINGS");
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.contains("profile") || key.contains("preset") && String.valueOf(entry.getValue()).length() > 200) continue;
            sb.append(key).append('=').append(entry.getValue()).append('\n');
        }

        section(sb, "PROCESSES (pid state rssMB name)");
        for (ProcessHelper.PStat process : ProcessHelper.getChildProcesses()) {
            sb.append(process.pid).append(' ').append(process.state).append(' ')
              .append(ProcessHelper.getMemoryUsage(process.pid) >> 20).append("MB ").append(process.name).append('\n');
        }

        section(sb, "WINDOWS (id class name WxH mapped)");
        try {
            XServer xServer = activity.getXServer();
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                appendWindows(sb, xServer.windowManager.rootWindow, 0);
            }
        }
        catch (Exception e) {
            sb.append("error: ").append(e).append('\n');
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            section(sb, "RECENT APP EXITS");
            try {
                ActivityManager activityManager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 8);
                for (ApplicationExitInfo info : exits) {
                    sb.append(new Date(info.getTimestamp())).append(" reason=").append(info.getReason())
                      .append(" status=").append(info.getStatus()).append(" desc=").append(info.getDescription()).append('\n');
                }
            }
            catch (Exception e) {}
        }

        section(sb, "WINE / BOX64 OUTPUT (last lines)");
        sb.append(guestLog != null ? guestLog.dump() : "(not captured)\n");

        section(sb, "LOGCAT (this app)");
        try {
            java.lang.Process process = (new ProcessBuilder("logcat", "-d", "-t", "250", "*:W")).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 300) sb.append(line).append('\n');
            }
        }
        catch (Exception e) {
            sb.append("error: ").append(e).append('\n');
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator");
        if (!dir.isDirectory() && !dir.mkdirs()) dir = activity.getExternalFilesDir(null);
        if (dir == null) dir = activity.getFilesDir();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "diagnostic-"+timestamp+".txt");
        return FileUtils.writeString(file, sb.toString()) ? file : null;
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n==== ").append(title).append(" ====\n");
    }

    private static void appendWindows(StringBuilder sb, Window window, int depth) {
        if (depth > 6) return;
        for (Window child : window.getChildren()) {
            String className = child.getClassName();
            String name = child.getName();
            if (!className.isEmpty() || !name.isEmpty()) {
                for (int i = 0; i < depth; i++) sb.append("  ");
                sb.append(child.id).append(' ').append(className).append(" \"").append(name).append("\" ")
                  .append(child.getWidth()).append('x').append(child.getHeight())
                  .append(child.attributes.isMapped() ? " mapped" : " unmapped").append('\n');
            }
            appendWindows(sb, child, depth + 1);
        }
    }
}
