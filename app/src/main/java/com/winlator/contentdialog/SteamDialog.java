package com.winlator.contentdialog;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.HttpUtils;
import com.winlator.core.SteamCmdLauncher;
import com.winlator.core.UnitUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple Steam screen on top of SteamCMD (Valve's official console client): sign in, search
 * the store, download owned games and start the installed ones, without typing commands.
 */
public class SteamDialog extends ContentDialog {
    private static final String SEARCH_URL = "https://store.steampowered.com/api/storesearch/?cc=us&l=english&term=";
    private final XServerDisplayActivity activity;
    private final SharedPreferences preferences;
    private final EditText etUser;
    private final EditText etPassword;
    private final LinearLayout llInstalled;
    private final LinearLayout llResults;

    public SteamDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.steam_dialog);
        this.activity = activity;
        setTitle("Steam");
        setIcon(R.drawable.icon_task_manager);
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);

        etUser = findViewById(R.id.ETSteamUser);
        etPassword = findViewById(R.id.ETSteamPassword);
        llInstalled = findViewById(R.id.LLSteamInstalled);
        llResults = findViewById(R.id.LLSteamResults);
        etUser.setText(preferences.getString("steam_username", ""));

        final EditText etSearch = findViewById(R.id.ETSteamSearch);
        Runnable search = () -> search(etSearch.getText().toString().trim());
        findViewById(R.id.BTSteamSearch).setOnClickListener((v) -> search.run());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search.run();
                return true;
            }
            return false;
        });

        findViewById(R.id.BTSteamConsole).setOnClickListener((v) -> {
            dismiss();
            SteamCmdLauncher.launch(activity);
        });

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        loadInstalledGames();
    }

    private File getSteamAppsDir() {
        return new File(activity.getContainer().getRootDir(), ".wine/drive_c/steamcmd/steamapps");
    }

    private TextView addRow(LinearLayout parent, String text, String buttonText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padding = (int)UnitUtils.dpToPx(4);
        row.setPadding(0, padding, 0, padding);

        TextView textView = new TextView(activity);
        textView.setText(text);
        textView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(textView);

        if (buttonText != null) {
            Button button = new Button(activity, null, 0, R.style.ButtonNeutral);
            button.setText(buttonText);
            button.setOnClickListener(listener);
            row.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        parent.addView(row);
        return textView;
    }

    private void loadInstalledGames() {
        llInstalled.removeAllViews();
        File[] manifests = getSteamAppsDir().listFiles((dir, name) -> name.startsWith("appmanifest_") && name.endsWith(".acf"));
        if (manifests == null || manifests.length == 0) {
            addRow(llInstalled, activity.getString(R.string.steam_no_games), null, null);
            return;
        }

        for (File manifest : manifests) {
            String content = FileUtils.readString(manifest);
            if (content == null) continue;
            final String name = acfValue(content, "name");
            final String installDir = acfValue(content, "installdir");
            if (name == null || installDir == null) continue;
            addRow(llInstalled, name, activity.getString(R.string.steam_play), (v) -> showExecutables(name, installDir));
        }
    }

    private static String acfValue(String content, String key) {
        Matcher matcher = Pattern.compile("\""+key+"\"\\s+\"([^\"]*)\"").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void collectExecutables(File dir, int depth, ArrayList<File> result) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectExecutables(file, depth + 1, result);
            }
            else {
                String name = file.getName().toLowerCase(Locale.ENGLISH);
                boolean helper = name.contains("unins") || name.contains("crash") || name.contains("redist") || name.contains("setup") || name.startsWith("vc_") || name.startsWith("dx");
                if (name.endsWith(".exe") && !helper) result.add(file);
            }
        }
    }

    private void showExecutables(String gameName, String installDir) {
        File gameDir = new File(getSteamAppsDir(), "common/"+installDir);
        ArrayList<File> executables = new ArrayList<>();
        collectExecutables(gameDir, 0, executables);

        llInstalled.removeAllViews();
        addRow(llInstalled, gameName+":", activity.getString(R.string.steam_back), (v) -> loadInstalledGames());
        if (executables.isEmpty()) addRow(llInstalled, activity.getString(R.string.steam_no_executable), null, null);

        String basePath = gameDir.getPath();
        for (final File executable : executables) {
            final String relativePath = executable.getPath().substring(basePath.length() + 1);
            addRow(llInstalled, relativePath, activity.getString(R.string.steam_play), (v) -> {
                String dosPath = "C:\\steamcmd\\steamapps\\common\\"+installDir+"\\"+relativePath.replace("/", "\\");
                activity.getWinHandler().exec(dosPath, null);
                dismiss();
            });
        }
    }

    private void search(String term) {
        if (term.isEmpty()) return;
        llResults.removeAllViews();
        addRow(llResults, activity.getString(R.string.loading), null, null);

        String url;
        try {
            url = SEARCH_URL+URLEncoder.encode(term, "UTF-8");
        }
        catch (Exception e) {
            return;
        }

        HttpUtils.download(url, (content) -> activity.runOnUiThread(() -> {
            llResults.removeAllViews();
            try {
                JSONArray items = (new JSONObject(content)).getJSONArray("items");
                for (int i = 0; i < items.length() && i < 12; i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!"app".equals(item.optString("type", "app"))) continue;
                    final int appId = item.getInt("id");
                    final String name = item.getString("name");
                    addRow(llResults, name, activity.getString(R.string.steam_download), (v) -> download(appId, name));
                }
                if (llResults.getChildCount() == 0) addRow(llResults, activity.getString(R.string.steam_no_results), null, null);
            }
            catch (Exception e) {
                addRow(llResults, activity.getString(R.string.steam_search_failed), null, null);
            }
        }));
    }

    private void download(int appId, String name) {
        final String user = etUser.getText().toString().trim();
        final String password = etPassword.getText().toString();
        if (user.isEmpty()) {
            AppUtils.showToast(activity, R.string.steam_enter_username);
            return;
        }
        preferences.edit().putString("steam_username", user).apply();

        // SteamCMD script: the password (if given) is only written to a temporary file inside
        // the app's private storage and removed right after SteamCMD has read it. Without a
        // password SteamCMD reuses its saved session.
        StringBuilder script = new StringBuilder();
        script.append("@ShutdownOnFailedCommand 0\n@NoPromptForPassword 0\n");
        script.append("login ").append(user);
        if (!password.isEmpty()) script.append(" \"").append(password.replace("\"", "\\\"")).append('"');
        script.append('\n').append("app_update ").append(appId).append('\n');

        dismiss();
        SteamCmdLauncher.runScript(activity, script.toString());
        AppUtils.showToast(activity, activity.getString(R.string.steam_downloading)+" "+name);
    }
}
