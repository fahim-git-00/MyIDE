package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Per-language compile cache under:
 *   /storage/emulated/0/Android/data/com.fahim.myide/files/compile-cache/<lang>/
 *
 * Layout per language:
 *   <lang>/<hash>.ok        marker file (empty)
 *   <lang>/<hash>/          folder holding outputs ( .so / .class / .jar )
 *
 * A cache hit means: source hash unchanged AND marker exists AND folder non-empty.
 */
public final class CompileCache {

    private CompileCache() {}

    public static File root(Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "compile-cache");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File langDir(Context ctx, String lang) {
        File f = new File(root(ctx), lang);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File outDir(Context ctx, String lang, String hash) {
        File f = new File(langDir(ctx, lang), hash);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File marker(Context ctx, String lang, String hash) {
        return new File(langDir(ctx, lang), hash + ".ok");
    }

    /** True if we have a marker AND the output folder has at least one file. */
    public static boolean isHit(Context ctx, String lang, String hash) {
        if (!marker(ctx, lang, hash).exists()) return false;
        File[] kids = outDir(ctx, lang, hash).listFiles();
        return kids != null && kids.length > 0;
    }

    public static void markDone(Context ctx, String lang, String hash) {
        try { marker(ctx, lang, hash).createNewFile(); }
        catch (Throwable ignored) {}
    }

    /** SHA-1 of a list of files (name + contents). */
    public static String hashFiles(java.util.List<File> files) {
        try {
            java.util.List<File> sorted = new java.util.ArrayList<File>(files);
            java.util.Collections.sort(sorted, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                }
            });
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (File f : sorted) {
                md.update(f.getName().getBytes("UTF-8"));
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "err" + System.currentTimeMillis();
        }
    }

    /** Copy all files from srcDir into dstDir (flat, keeps names). */
    public static int copyDirContents(File srcDir, File dstDir) {
        if (srcDir == null || !srcDir.isDirectory()) return 0;
        if (!dstDir.exists()) dstDir.mkdirs();
        File[] kids = srcDir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (!f.isFile()) continue;
            try {
                InputStream in = new FileInputStream(f);
                OutputStream out = new FileOutputStream(new File(dstDir, f.getName()));
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                in.close();
                out.close();
                n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    /** Copy cache files back into the project's libs/arm64-v8a (for .so) or classes dir. */
    public static int restoreTo(File srcDir, File dstDir) {
        return copyDirContents(srcDir, dstDir);
    }
}
