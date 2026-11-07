package com.winlator.renderer.upscaler;

import java.util.Locale;

/** Spatial upscaler applied when the X screen resolution is lower than the display's. */
public enum Upscaler {
    /** Plain bilinear stretch (original behaviour). */
    BILINEAR,
    /** Snapdragon Game Super Resolution 1. */
    SGSR;

    public static Upscaler fromString(String value) {
        if (value == null) return BILINEAR;
        try {
            return valueOf(value.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException e) {
            return BILINEAR;
        }
    }
}
