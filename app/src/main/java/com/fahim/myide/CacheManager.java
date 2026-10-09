package com.fahim.myide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CacheManager {

    private CacheManager() {}

    private static class Item {
        String label;
        File target;             // file or dir to delete (null = special)
        String specialId;        // "recent" etc.
        boolean checked = false;
    }

    public static void show(final Activity act) {
        final Context ctx = act;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);
        int pad = dp(ctx, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Cache Manager");
        title.setTextColor(0xFFDCDCAA);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Tick items to delete, then tap Delete.");
        sub.setTextColor(0xFF858585);
        sub.setTextSize(11f);
        sub.setPadding(0, dp(ctx, 4), 0, dp(ctx, 10));
        root.addView(sub);

        final List<Item> items = new ArrayList<Item>();
        buildItems(ctx, items);

        final ScrollView scroll = new ScrollView(ctx);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final List<CheckBox> boxes = new ArrayList<CheckBox>();

        String lastGroup = null;
        for (final Item it : items) {
            String grp = groupOf(it);
            if (!grp.equals(lastGroup)) {
                TextView h = new TextView(ctx);
                h.setText(grp);
                h.setTextColor(0xFF4FC3F7);
                h.setTextSize(12f);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
                list.addView(h);
                lastGroup = grp;
            }

            CheckBox cb = new CheckBox(ctx);
            cb.setText(it.label);
            cb.setTextColor(0xFFD4D4D4);
            cb.setTextSize(12f);
            cb.setTypeface(Typeface.MONOSPACE);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF4FC3F7));
            cb.setChecked(false);
            cb.setTag(it);
            list.addView(cb);
            boxes.add(cb);
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(ctx, 10), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFD4D4D4);
        cancel.setTextSize(13f);
        cancel.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        btns.addView(cancel);

        TextView del = new TextView(ctx);
        del.setText("Delete selected");
        del.setTextColor(0xFFFFFFFF);
        del.setTextSize(13f);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setBackgroundColor(0xFF9E2A2A);
        del.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        btns.addView(del);

        root.addView(btns);

        final AlertDialog dlg = new AlertDialog.Builder(act,
                android.R.style.Theme_Material_Dialog_Alert)
                .setView(root)
                .create();

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final List<Item> chosen = new ArrayList<Item>();
                for (CheckBox cb : boxes) {
                    Item it = (Item) cb.getTag();
                    it.checked = cb.isChecked();
                    if (it.checked) chosen.add(it);
                }
                if (chosen.isEmpty()) {
                    Toast.makeText(ctx, "Nothing selected", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmAndDelete(ctx, chosen, dlg);
            }
        });

        dlg.show();
    }

    private static void confirmAndDelete(final Context ctx,
                                         final List<Item> chosen,
                                         final AlertDialog parent) {
        StringBuilder sb = new StringBuilder();
        for (Item it : chosen) {
            sb.append("• ").append(it.label).append('\n');
        }
        new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete " + chosen.size() + " item(s)?")
                .setMessage(sb.toString())
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (Item it : chosen) {
                            if (it.specialId != null) {
                                if ("recent".equals(it.specialId)) {
                                    RecentProjects.clear(ctx);
                                    n++;
                                }
                            } else if (it.target != null) {
                                if (deleteRecursive(it.target)) n++;
                            }
                        }
                        Toast.makeText(ctx, "Deleted " + n + " item(s)",
                                Toast.LENGTH_SHORT).show();
                        if (parent != null) parent.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private static String groupOf(Item it) {
        if (it.specialId != null) return "RECENT";
        if (it.target == null) return "MISC";
        String p = it.target.getAbsolutePath();
        if (p.contains("/compile-cache/")) return "COMPILE CACHE";
        if (p.contains("/myide-m2/")) return "MAVEN CACHE";
        if (p.contains("/crash")) return "LOGS";
        if (p.contains("/logs")) return "LOGS";
        if (p.contains("/cache/")) return "DOWNLOADED ZIPS";
        if (p.contains("/bundled_aar/")) return "BUNDLED AARs";
        if (p.contains("/build_area/")) return "BUILD SCRATCH";
        if (p.contains("/ecj_res/")) return "ECJ RESOURCES";
        if (p.contains("/c-cache/")) return "C CACHE MARKERS";
        return "OTHER";
    }

    private static void buildItems(Context ctx, List<Item> out) {
        // Compile cache — per language, per hash
        File cc = CompileCache.root(ctx);
        File[] langs = cc.listFiles();
        if (langs != null) {
            for (File lang : langs) {
                if (!lang.isDirectory()) continue;
                File[] entries = lang.listFiles();
                if (entries == null) continue;
                for (File e : entries) {
                    if (e.getName().endsWith(".ok")) continue;
                    Item it = new Item();
                    it.label = "compile-cache/" + lang.getName() + "/"
                            + e.getName() + "  (" + dirSize(e) + " B)";
                    it.target = e;
                    out.add(it);
                }
            }
        }

        // Maven cache
        File m2 = new File(ctx.getCacheDir(), "myide-m2");
        if (m2.isDirectory()) {
            File[] groups = m2.listFiles();
            if (groups != null) for (File g : groups) {
                if (!g.isDirectory()) continue;
                Item it = new Item();
                it.label = "maven/" + g.getName() + "  (" + dirSize(g) + " B)";
                it.target = g;
                out.add(it);
            }
        }

        // Downloaded remote zips in app cache
        File cacheDir = ctx.getCacheDir();
        File[] cacheKids = cacheDir.listFiles();
        if (cacheKids != null) {
            for (File f : cacheKids) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (n.endsWith(".zip")) {
                    Item it = new Item();
                    it.label = "cache/" + n + "  (" + f.length() + " B)";
                    it.target = f;
                    out.add(it);
                }
            }
        }

        // Bundled AARs / build_area / ecj_res / c-cache markers in filesDir
        File filesDir = ctx.getFilesDir();
        addIfExists(out, new File(filesDir, "bundled_aar"), "files/bundled_aar");
        addIfExists(out, new File(filesDir, "build_area"), "files/build_area");
        addIfExists(out, new File(filesDir, "ecj_res"), "files/ecj_res");
        File cCache = new File(filesDir, "c-cache");
        if (cCache.isDirectory()) {
            File[] ks = cCache.listFiles();
            if (ks != null) for (File k : ks) {
                Item it = new Item();
                it.label = "c-cache/" + k.getName();
                it.target = k;
                out.add(it);
            }
        }

        // Crash logs
        File crash = new File(filesDir, "crash");
        if (crash.isDirectory()) {
            File[] cs = crash.listFiles();
            if (cs != null) for (File c : cs) {
                Item it = new Item();
                it.label = "crash/" + c.getName();
                it.target = c;
                out.add(it);
            }
        }

        // Build logs (external)
        File logs = ctx.getExternalFilesDir("logs");
        if (logs != null && logs.isDirectory()) {
            File[] ls = logs.listFiles();
            if (ls != null) for (File l : ls) {
                Item it = new Item();
                it.label = "logs/" + l.getName();
                it.target = l;
                out.add(it);
            }
        }

        // Recent projects
        Item rec = new Item();
        rec.label = "Clear recent projects list";
        rec.specialId = "recent";
        out.add(rec);
    }

    private static void addIfExists(List<Item> out, File f, String label) {
        if (f != null && f.exists()) {
            Item it = new Item();
            it.label = label + "  (" + dirSize(f) + " B)";
            it.target = f;
            out.add(it);
        }
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += dirSize(k);
        return total;
    }

    private static int dp(Context ctx, int v) {
        return (int)(v * ctx.getResources().getDisplayMetrics().density);
    }
}
