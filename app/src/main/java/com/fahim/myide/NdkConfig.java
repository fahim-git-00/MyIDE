package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;

public final class NdkConfig {

    private static final String PREF = "settings";
    private static final String KEY  = "ndk_path";
    private static final String KEY_PREBUILT = "ndk_prebuilt";

    private NdkConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String getPath(Context ctx) {
        String v = prefs(ctx).getString(KEY, "");
        return v == null ? "" : v;
    }

    public static void setPath(Context ctx, String path) {
        String v = (path == null) ? "" : path;
        prefs(ctx).edit().putString(KEY, v).apply();
    }

    public static String getPrebuilt(Context ctx) {
        String v = prefs(ctx).getString(KEY_PREBUILT, "");
        return v == null ? "" : v;
    }

    public static void setPrebuilt(Context ctx, String name) {
        String v = (name == null) ? "" : name;
        prefs(ctx).edit().putString(KEY_PREBUILT, v).apply();
    }

    public static File getDir(Context ctx) {
        String p = getPath(ctx);
        if (p.isEmpty()) return null;
        File f = new File(p);
        return f.isDirectory() ? f : null;
    }

    public static boolean isValid(File ndkRoot) {
        if (ndkRoot == null || !ndkRoot.isDirectory()) return false;
        if (!new File(ndkRoot, "ndk-build").isFile()) return false;
        if (!new File(ndkRoot, "toolchains").isDirectory()) return false;
        return findPrebuilt(ndkRoot) != null;
    }

    public static String findPrebuilt(File ndkRoot) {
        File pre = new File(ndkRoot, "toolchains/llvm/prebuilt");
        if (!pre.isDirectory()) return null;
        File[] kids = pre.listFiles();
        if (kids == null) return null;

        boolean arm64 = false;
        try {
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi != null && abi.startsWith("arm64")) { arm64 = true; break; }
            }
        } catch (Throwable ignored) {}

        String fallback = null;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            if (!new File(k, "bin").isDirectory()) continue;
            String n = k.getName();
            if (fallback == null) fallback = n;
            if (arm64 && (n.contains("aarch64") || n.contains("arm64"))) return n;
        }
        return fallback;
    }
}
