package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;

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
        return prefs(ctx).getString(KEY, "");
    }

    public static void setPath(Context ctx, String path) {
        prefs(ctx).putString(KEY, path == null ? "" : path).apply();
    }

    public static String getPrebuilt(Context ctx) {
        return prefs(ctx).getString(KEY_PREBUILT, "");
    }

    public static void setPrebuilt(Context ctx, String name) {
        prefs(ctx).putString(KEY_PREBUILT, name == null ? "" : name).apply();
    }

    public static File getDir(Context ctx) {
        String p = getPath(ctx);
        if (p == null || p.isEmpty()) return null;
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
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            File bin = new File(k, "bin");
            if (bin.isDirectory()) return k.getName();
        }
        return null;
    }
}
