package com.fahim.myide;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Theme + preference helper. All UI reads prefs through here so we have
 * one source of truth for theme mode, font size, tab size, etc.
 */
public final class ThemeHelper {

    public static final int THEME_DARK   = 0;
    public static final int THEME_LIGHT  = 1;
    public static final int THEME_SYSTEM = 2;

    public static final String PREFS = "settings";

    private ThemeHelper() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- theme ----

    public static int getThemeMode(Context ctx) {
        return prefs(ctx).getInt("theme_mode", THEME_DARK);
    }

    public static void setThemeMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt("theme_mode", mode).apply();
    }

    /**
     * Apply theme by tweaking the UiMode of the Configuration so
     * DeviceDefault.DayNight picks the right variant.
     */
    public static void applyTheme(Activity activity) {
        int mode = getThemeMode(activity);
        if (mode == THEME_SYSTEM) return;

        Configuration cfg = new Configuration(activity.getResources().getConfiguration());
        int nightMask = Configuration.UI_MODE_NIGHT_MASK;
        if (mode == THEME_DARK) {
            cfg.uiMode = (cfg.uiMode & ~nightMask) | Configuration.UI_MODE_NIGHT_YES;
        } else {
            cfg.uiMode = (cfg.uiMode & ~nightMask) | Configuration.UI_MODE_NIGHT_NO;
        }
        activity.getResources().updateConfiguration(cfg,
                activity.getResources().getDisplayMetrics());
    }

    // ---- editor ----

    public static int getFontSize(Context ctx) {
        int v = prefs(ctx).getInt("font_size", 13);
        if (v < 8) v = 8;
        if (v > 32) v = 32;
        return v;
    }

    public static void setFontSize(Context ctx, int v) {
        if (v < 8) v = 8;
        if (v > 32) v = 32;
        prefs(ctx).edit().putInt("font_size", v).apply();
    }

    public static int getTabSize(Context ctx) {
        int v = prefs(ctx).getInt("tab_size", 4);
        if (v < 1) v = 1;
        if (v > 8) v = 8;
        return v;
    }

    public static void setTabSize(Context ctx, int v) {
        prefs(ctx).edit().putInt("tab_size", v).apply();
    }

    public static boolean isWordWrap(Context ctx) {
        return prefs(ctx).getBoolean("word_wrap", false);
    }

    public static boolean isLineNumbers(Context ctx) {
        return prefs(ctx).getBoolean("line_numbers", true);
    }

    public static boolean isAutoClose(Context ctx) {
        return prefs(ctx).getBoolean("auto_close", true);
    }

    public static boolean isAutoIndent(Context ctx) {
        return prefs(ctx).getBoolean("auto_indent", true);
    }

    public static boolean isSyntaxHighlight(Context ctx) {
        return prefs(ctx).getBoolean("syntax_highlight", true);
    }

    // ---- build ----

    public static int getMinSdk(Context ctx) {
        int v = prefs(ctx).getInt("min_sdk", 24);
        return v < 1 ? 24 : v;
    }

    public static void setMinSdk(Context ctx, int v) {
        prefs(ctx).edit().putInt("min_sdk", v).apply();
    }

    public static int getTargetSdk(Context ctx) {
        int v = prefs(ctx).getInt("target_sdk", 35);
        return v < 1 ? 35 : v;
    }

    public static void setTargetSdk(Context ctx, int v) {
        prefs(ctx).edit().putInt("target_sdk", v).apply();
    }

    public static String getKotlinMode(Context ctx) {
        return prefs(ctx).getString("kotlin_mode", "auto");
    }

    public static void setKotlinMode(Context ctx, String mode) {
        prefs(ctx).edit().putString("kotlin_mode", mode).apply();
    }

    // ---- misc ----

    public static boolean isSidebarVisible(Context ctx) {
        return prefs(ctx).getBoolean("ui_sidebar", true);
    }

    public static void setSidebarVisible(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("ui_sidebar", v).apply();
    }

    public static int getSidebarWidth(Context ctx) {
        int v = prefs(ctx).getInt("ui_sidebar_w", 280);
        if (v < 200) v = 200;
        if (v > 520) v = 520;
        return v;
    }

    public static void setSidebarWidth(Context ctx, int v) {
        prefs(ctx).edit().putInt("ui_sidebar_w", v).apply();
    }

    public static int getPanelHeight(Context ctx) {
        int v = prefs(ctx).getInt("ui_panel_h", 220);
        if (v < 100) v = 100;
        if (v > 800) v = 800;
        return v;
    }

    public static void setPanelHeight(Context ctx, int v) {
        prefs(ctx).edit().putInt("ui_panel_h", v).apply();
    }

    public static int getLastPanel(Context ctx) {
        return prefs(ctx).getInt("ui_panel_tab", 0);
    }

    public static void setLastPanel(Context ctx, int v) {
        prefs(ctx).edit().putInt("ui_panel_tab", v).apply();
    }

    /** True when the device reports night mode currently. */
    public static boolean isNight(Context ctx) {
        int mask = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean isAtLeast(int api) {
        return Build.VERSION.SDK_INT >= api;
    }
}
