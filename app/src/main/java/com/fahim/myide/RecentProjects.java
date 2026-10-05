package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores up to 10 most-recently-used project paths.
 * Order: most recent first.
 */
public final class RecentProjects {

    private static final String PREFS = "recent";
    private static final String KEY = "paths";
    private static final int MAX = 10;
    private static final String SEP = "\n";

    private RecentProjects() {}

    public static void add(Context ctx, File project) {
        if (project == null) return;
        String p = project.getAbsolutePath();
        Set<String> set = new LinkedHashSet<String>(list(ctx));
        set.remove(p);
        List<String> ordered = new ArrayList<String>();
        ordered.add(p);
        for (String s : set) {
            if (!s.equals(p)) ordered.add(s);
            if (ordered.size() >= MAX) break;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(ordered.get(i));
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    public static List<String> list(Context ctx) {
        String raw = prefs(ctx).getString(KEY, "");
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(SEP)) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (new File(s).isDirectory()) out.add(s);
        }
        return out;
    }

    public static void remove(Context ctx, String path) {
        List<String> cur = new ArrayList<String>(list(ctx));
        cur.remove(path);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(cur.get(i));
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    public static boolean isEmpty(Context ctx) {
        return list(ctx).isEmpty();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
