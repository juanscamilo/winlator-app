package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.GPUHelper;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;

public class TurnipConfigDialog extends ContentDialog {
    private static final GPUHelper.VkPresentMode DEFAULT_PRESENT_MODE = GPUHelper.VkPresentMode.MAILBOX;

    public TurnipConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.turnip_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Turnip "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final CheckBox cbDirectRendering = findViewById(R.id.CBDirectRendering);
        final Spinner sPresentMode = findViewById(R.id.SPresentMode);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        cbDirectRendering.setChecked(config.getBoolean("directRendering", true));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        sPresentMode.setSelection(config.getInt("presentMode", DEFAULT_PRESENT_MODE.ordinal()), false);

        String version = config.get("version");
        GeneralComponents.initViews(GeneralComponents.Type.TURNIP, findViewById(R.id.TurnipToolbox), sVersion, version, DefaultVersion.TURNIP);

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", StringUtils.parseNumber(sVersion.getSelectedItem()));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));
            newConfig.put("directRendering", cbDirectRendering.isChecked() ? "1" : "0");
            newConfig.put("presentMode", sPresentMode.getSelectedItemPosition());
            anchor.setTag(newConfig.toString());
        });
    }

    private static boolean isForceSYSMEM(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        // Adreno 740 (Snapdragon 8 Gen 2 / Galaxy S23) is fully supported by Turnip's tiled
        // GMEM renderer, which is considerably faster than SYSMEM on this GPU.
        return modelId >= 700 && modelId != 710 && modelId != 720 && modelId != 732 && modelId != 740;
    }

    private static boolean isForceGMEM(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        return modelId == 710 || modelId == 720 || modelId == 732;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String maxDeviceMemory = config.get("maxDeviceMemory", "0");
        if (!maxDeviceMemory.equals("0")) envVars.put("TU_OVERRIDE_HEAP_SIZE", maxDeviceMemory);
        if (config.getBoolean("directRendering", true)) envVars.put("MESA_VK_WSI_NATIVE_MEM_IMPORTED", "1");

        int presentModeIdx = config.getInt("presentMode", DEFAULT_PRESENT_MODE.ordinal());
        String presentMode = GPUHelper.VkPresentMode.values()[presentModeIdx].value();
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        // Accumulate the TU_DEBUG flags instead of overwriting them: previously the
        // "sysmem"/"gmem" flag was discarded by the final "deck_emu" assignment.
        String tuDebug = envVars.get("TU_DEBUG");
        if (tuDebug == null) tuDebug = "";
        if (isForceSYSMEM(context) && !hasTuDebugFlag(tuDebug, "sysmem")) {
            tuDebug = appendTuDebugFlag(tuDebug, "sysmem");
        }
        else if (isForceGMEM(context) && !hasTuDebugFlag(tuDebug, "gmem")) {
            tuDebug = appendTuDebugFlag(tuDebug, "gmem");
        }
        if (!hasTuDebugFlag(tuDebug, "deck_emu")) tuDebug = appendTuDebugFlag(tuDebug, "deck_emu");
        envVars.put("TU_DEBUG", tuDebug);
    }

    private static boolean hasTuDebugFlag(String tuDebug, String flag) {
        for (String value : tuDebug.split(",")) if (value.trim().equals(flag)) return true;
        return false;
    }

    private static String appendTuDebugFlag(String tuDebug, String flag) {
        return (!tuDebug.isEmpty() ? tuDebug+"," : "")+flag;
    }
}