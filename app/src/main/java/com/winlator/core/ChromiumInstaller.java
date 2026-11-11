package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.win32.MSLink;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Bundled Chromium (ungoogled-chromium, BSD-3-Clause), shared by all containers.
 *
 * The CI packs it as assets/chromium/chromium.tzst (+ version.txt); builds without those
 * assets simply don't ship it. It is extracted once to <rootfs>/opt/chromium (Z:\opt\chromium)
 * and re-extracted only when the bundled version changes.
 */
public abstract class ChromiumInstaller {
    private static final String ASSET_ARCHIVE = "chromium/chromium.tzst";
    private static final String ASSET_VERSION = "chromium/version.txt";
    public static final String DOS_PATH = "Z:/opt/chromium/chrome.exe";

    public static File getInstallDir(Context context) {
        return new File(RootFS.find(context).getRootDir(), "opt/chromium");
    }

    public static boolean isInstalled(Context context) {
        return new File(getInstallDir(context), "chrome.exe").isFile();
    }

    private static String getBundledVersion(Context context) {
        byte[] data = FileUtils.read(context, ASSET_VERSION);
        return data != null ? new String(data, StandardCharsets.UTF_8).trim() : null;
    }

    /** Returns true if Chromium is available after the call. May take a while on first run. */
    public static boolean installIfNeeded(Context context) {
        String bundledVersion = getBundledVersion(context);
        if (bundledVersion == null || bundledVersion.isEmpty()) return isInstalled(context);

        File installDir = getInstallDir(context);
        File versionFile = new File(installDir, ".version");
        if (isInstalled(context) && bundledVersion.equals(FileUtils.readString(versionFile))) return true;

        FileUtils.delete(installDir);
        File rootDir = RootFS.find(context).getRootDir();
        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, ASSET_ARCHIVE, rootDir);
        if (success && isInstalled(context)) {
            FileUtils.writeString(versionFile, bundledVersion);
            return true;
        }

        FileUtils.delete(installDir);
        return false;
    }

    /** Adds a desktop shortcut to the container (once; the user may delete it afterwards). */
    public static void createDesktopShortcut(Context context, Container container) {
        if (!isInstalled(context) || container.getExtra("chromiumShortcut").equals("1")) return;

        File desktopDir = new File(container.getRootDir(), ".wine/drive_c/users/"+RootFS.USER+"/Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return;

        MSLink.LinkInfo linkInfo = new MSLink.LinkInfo();
        linkInfo.targetPath = DOS_PATH;
        linkInfo.arguments = "";
        linkInfo.iconLocation = DOS_PATH;
        MSLink.createFile(linkInfo, new File(desktopDir, "Chromium.lnk"));
        container.putExtra("chromiumShortcut", "1");
        container.saveData();
    }
}
