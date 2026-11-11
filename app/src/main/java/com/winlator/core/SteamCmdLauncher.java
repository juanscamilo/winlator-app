package com.winlator.core;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;

import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * SteamCMD: Valve's official console Steam client. It signs in and downloads the games the
 * account owns without the Steam desktop client, whose Chromium-based UI is very heavy
 * under Box64. Downloaded on demand from Valve's CDN into C:\steamcmd of the container.
 */
public abstract class SteamCmdLauncher {
    private static final String DOWNLOAD_URL = "https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip";

    private interface ReadyCallback {
        void run();
    }

    public static void launch(final XServerDisplayActivity activity) {
        ensureInstalled(activity, () -> run(activity, null));
    }

    /** Runs SteamCMD with a script of commands; it stays open so the progress is visible. */
    public static void runScript(final XServerDisplayActivity activity, final String script) {
        ensureInstalled(activity, () -> {
            final File scriptFile = new File(getInstallDir(activity), "winlator_script.txt");
            FileUtils.writeString(scriptFile, script);
            run(activity, "+runscript winlator_script.txt");
            // The script may contain the password: remove it once SteamCMD has read it.
            (new Handler(Looper.getMainLooper())).postDelayed(scriptFile::delete, 90000);
        });
    }

    private static File getInstallDir(XServerDisplayActivity activity) {
        return new File(activity.getContainer().getRootDir(), ".wine/drive_c/steamcmd");
    }

    private static void ensureInstalled(final XServerDisplayActivity activity, final ReadyCallback callback) {
        final File installDir = getInstallDir(activity);
        final File executable = new File(installDir, "steamcmd.exe");

        if (executable.isFile()) {
            callback.run();
            return;
        }

        if (!installDir.isDirectory() && !installDir.mkdirs()) {
            AppUtils.showToast(activity, R.string.steamcmd_download_failed);
            return;
        }

        final File zipFile = new File(installDir, "steamcmd.zip");
        HttpUtils.download(activity, DOWNLOAD_URL, zipFile, (success) -> {
            boolean extracted = success && ZipUtils.extract(zipFile, installDir);
            zipFile.delete();
            if (extracted && executable.isFile()) {
                callback.run();
            }
            else AppUtils.showToast(activity, R.string.steamcmd_download_failed);
        });
    }

    private static void run(XServerDisplayActivity activity, String parameters) {
        activity.getWinHandler().exec("C:\\steamcmd\\steamcmd.exe", parameters);
        if (parameters == null) AppUtils.showToast(activity, R.string.steamcmd_hint);
    }
}
