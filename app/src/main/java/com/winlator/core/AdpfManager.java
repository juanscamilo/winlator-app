package com.winlator.core;

import android.app.Activity;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.winlator.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Android Dynamic Performance Framework integration.
 *
 * - Performance Hint API (Android 12+): the game's threads (box64/wine processes, which
 *   are children of the app and share its UID) are put in a hint session whose target is
 *   one display frame. Every presented frame reports how long it took, so the kernel
 *   raises clocks / moves the threads to the big cores as soon as a frame misses its
 *   budget, instead of waiting for the slower load-based governor. Reports are clamped
 *   to the target, so the session only ever boosts and never throttles a game that runs
 *   faster than the display.
 *   The compositor (GL render thread) gets its own session with honest reports, so the
 *   short composition work runs at an efficient clock and leaves thermal headroom.
 * - Thermal API (Android 10+): warns the user when the system starts throttling.
 *
 * Every call is best-effort: when the system refuses a session (e.g. a vendor rejecting
 * threads of other processes), the feature silently turns itself off.
 */
public class AdpfManager {
    private static final String TAG = "AdpfManager";
    private static final int MAX_GUEST_THREADS = 64;
    private static final List<String> SYSTEM_PROCESSES = Arrays.asList(
        "wineserver", "services.exe", "explorer.exe", "winedevice.exe", "plugplay.exe", "svchost.exe",
        "rpcss.exe", "winhandler.exe", "tabtip.exe", "conhost.exe", "start.exe", "wfm.exe", "rundll32.exe",
        "winemenubuilder.exe", "wineboot.exe", "cmd.exe"
    );

    private final Activity activity;
    private ScheduledExecutorService executor;
    private Object hintManager;
    private Object renderSession;
    private Object guestSession;
    private int[] guestTids = new int[0];
    private boolean guestSessionUnsupported = false;
    private long lastGuestFrameTime = 0;
    private int lastGuestWindowId = -1;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private int lastThermalWarning = 0;
    private volatile boolean running = false;

    public AdpfManager(Activity activity) {
        this.activity = activity;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintManager = activity.getSystemService(PerformanceHintManager.class);
            executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "AdpfManager"));
            executor.scheduleWithFixedDelay(this::refreshGuestThreads, 2, 3, TimeUnit.SECONDS);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerThermalListener();
    }

    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            closeSession(renderSession);
            closeSession(guestSession);
        }
        renderSession = null;
        guestSession = null;
        guestTids = new int[0];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unregisterThermalListener();
    }

    /** Called on the GL thread once the surface exists. */
    public synchronized void onRenderThreadStarted() {
        if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hintManager == null || renderSession != null) return;
        renderSession = createSession(new int[]{android.os.Process.myTid()});
    }

    /** Duration of one composition pass (GL thread). */
    public void reportRenderWork(long durationNanos) {
        Object session = renderSession;
        if (session == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            ((PerformanceHintManager.Session)session).updateTargetWorkDuration(DisplayClock.getFrameIntervalNanos());
            ((PerformanceHintManager.Session)session).reportActualWorkDuration(Math.max(1, durationNanos));
        }
        catch (Exception e) {
            renderSession = null;
        }
    }

    /** A guest window presented a new frame (X server thread). */
    public void onGuestFrame(int windowId) {
        Object session = guestSession;
        long now = System.nanoTime();
        if (windowId != lastGuestWindowId) {
            lastGuestWindowId = windowId;
            lastGuestFrameTime = now;
            return;
        }

        long frameTime = now - lastGuestFrameTime;
        lastGuestFrameTime = now;
        if (session == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || frameTime <= 0) return;

        long target = DisplayClock.getFrameIntervalNanos();
        // Ignore pauses (menus, loading screens) so they don't read as a giant missed frame.
        if (frameTime > target * 8) return;

        try {
            ((PerformanceHintManager.Session)session).updateTargetWorkDuration(target);
            ((PerformanceHintManager.Session)session).reportActualWorkDuration(Math.max(frameTime, target));
        }
        catch (Exception e) {
            guestSession = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private Object createSession(int[] tids) {
        if (hintManager == null || tids.length == 0) return null;
        try {
            return ((PerformanceHintManager)hintManager).createHintSession(tids, DisplayClock.getFrameIntervalNanos());
        }
        catch (Exception e) {
            Log.w(TAG, "createHintSession failed: "+e);
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void closeSession(Object session) {
        if (session == null) return;
        try {
            ((PerformanceHintManager.Session)session).close();
        }
        catch (Exception e) {}
    }

    private void refreshGuestThreads() {
        if (!running || guestSessionUnsupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;

        int[] tids = findGuestThreads();
        if (Arrays.equals(tids, guestTids)) return;

        synchronized (this) {
            if (!running) return;
            guestTids = tids;

            if (tids.length == 0) {
                closeSession(guestSession);
                guestSession = null;
                return;
            }

            if (guestSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    ((PerformanceHintManager.Session)guestSession).setThreads(tids);
                    return;
                }
                catch (Exception e) {
                    closeSession(guestSession);
                    guestSession = null;
                }
            }
            else {
                closeSession(guestSession);
                guestSession = null;
            }

            guestSession = createSession(tids);
            if (guestSession == null) {
                guestSessionUnsupported = true;
                Log.i(TAG, "The system does not accept performance hints for guest threads");
            }
        }
    }

    private static int[] findGuestThreads() {
        ArrayList<Integer> tids = new ArrayList<>();
        for (ProcessHelper.PStat process : ProcessHelper.getChildProcesses()) {
            if (!process.guestProcess) continue;
            String name = new File(process.name.replace('\\', '/')).getName().toLowerCase(Locale.ENGLISH);
            if (!name.endsWith(".exe") || SYSTEM_PROCESSES.contains(name)) continue;

            String[] tasks = new File("/proc/"+process.pid+"/task").list();
            if (tasks == null) continue;
            for (String task : tasks) {
                try {
                    tids.add(Integer.parseInt(task));
                }
                catch (NumberFormatException e) {}
                if (tids.size() >= MAX_GUEST_THREADS) break;
            }
            if (tids.size() >= MAX_GUEST_THREADS) break;
        }

        int[] result = new int[tids.size()];
        for (int i = 0; i < result.length; i++) result[i] = tids.get(i);
        Arrays.sort(result);
        return result;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void registerThermalListener() {
        PowerManager powerManager = activity.getSystemService(PowerManager.class);
        if (powerManager == null) return;
        thermalListener = status -> {
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && status > lastThermalWarning) {
                lastThermalWarning = status;
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.thermal_throttling_warning, Toast.LENGTH_LONG).show());
            }
            else if (status < PowerManager.THERMAL_STATUS_MODERATE) lastThermalWarning = 0;
        };
        try {
            powerManager.addThermalStatusListener(activity.getMainExecutor(), thermalListener);
        }
        catch (Exception e) {
            thermalListener = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void unregisterThermalListener() {
        if (thermalListener == null) return;
        PowerManager powerManager = activity.getSystemService(PowerManager.class);
        try {
            if (powerManager != null) powerManager.removeThermalStatusListener(thermalListener);
        }
        catch (Exception e) {}
        thermalListener = null;
    }
}
