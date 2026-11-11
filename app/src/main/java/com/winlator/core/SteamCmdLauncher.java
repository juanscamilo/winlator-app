package com.winlator.core;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;

import java.io.File;

/**
 * SteamCMD: Valve's official console Steam client. It signs in and downloads the games the
 * account owns without the Steam desktop client, whose Chromium-based UI is very heavy
 * under Box64. Downloaded on demand from Valve's CDN into C:\steamcmd of the container.
 */
public abstract class SteamCmdLauncher {
    private static final String DOWNLOAD_URL = "https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip";

    public static void launch(final XServerDisplayActivity activity) {
        final File installDir = new File(activity.getContainer().getRootDir(), ".wine/drive_c/steamcmd");
        final File executable = new File(installDir, "steamcmd.exe");

        if (executable.isFile()) {
            run(activity);
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
                run(activity);
            }
            else AppUtils.showToast(activity, R.string.steamcmd_download_failed);
        });
    }

    private static void run(XServerDisplayActivity activity) {
        // cmd /k keeps the console open; games go to C:\steamcmd\steamapps\common.
        activity.getWinHandler().exec("C:\\windows\\system32\\cmd.exe", "/k C:\\steamcmd\\steamcmd.exe");
        AppUtils.showToast(activity, R.string.steamcmd_hint);
    }
}
