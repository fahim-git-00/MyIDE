package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class KotlinCompiler {

    public interface Progress { void onProgress(String message); }

    private final Context ctx;
    private final Progress progress;

    public KotlinCompiler(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    public void compile(List<File> sourceRoots,
                        File classesDir,
                        File androidJar) throws Exception {

        LocalJvm jvm = new LocalJvm(ctx, new LocalJvm.Progress() {
            @Override public void onProgress(String m) { say(m); }
        });
        int rc = jvm.runKotlinc(sourceRoots, classesDir, androidJar);
        if (rc != 0) {
            throw new RuntimeException("kotlinc exited with " + rc);
        }

        int n = countClasses(classesDir);
        say("Kotlin done: " + n + " classes");
        if (n == 0) {
            throw new RuntimeException("kotlinc produced no .class files");
        }
    }

    private int countClasses(File dir) {
        if (dir == null || !dir.exists()) return 0;
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            if (f.isDirectory()) n += countClasses(f);
            else if (f.getName().endsWith(".class")) n++;
        }
        return n;
    }

    // ---------------- helpers ----------------

    private String relativePath(List<File> roots, File f) {
        String abs = f.getAbsolutePath();
        for (File root : roots) {
            String r = root.getAbsolutePath();
            if (abs.startsWith(r)) {
                String rel = abs.substring(r.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return rel;
            }
        }
        return f.getName();
    }

    private int copyTree(File srcDir, File dstDir) throws Exception {
        int count = 0;
        File[] kids = srcDir.listFiles();
        if (kids == null) return 0;
        if (!dstDir.exists()) dstDir.mkdirs();
        for (File k : kids) {
            File d = new File(dstDir, k.getName());
            if (k.isDirectory()) {
                count += copyTree(k, d);
            } else {
                File parent = d.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                copyFile(k, d);
                count++;
            }
        }
        return count;
    }

    private void findKtFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findKtFiles(f, out);
            else if (f.getName().endsWith(".kt")) out.add(f);
        }
    }

    private File ensureAsset(String assetPath, File dir) throws Exception {
        String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out;
        if (!dir.exists()) dir.mkdirs();
        InputStream in = ctx.getAssets().open(assetPath);
        FileOutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
        return out;
    }

    private void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
