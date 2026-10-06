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

        // 1) Ensure Alpine + OpenJDK + kotlinc is installed (one-time)
        LinuxEnv.setupIfNeeded(ctx, new LinuxEnv.Progress() {
            @Override public void onProgress(String m) { say(m); }
        });

        // 2) Collect .kt sources
        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) { say("No .kt files found"); return; }
        say("Compiling " + ktFiles.size() + " Kotlin file(s) via kotlinc (Alpine)");

        if (!classesDir.exists()) classesDir.mkdirs();

        // 3) Copy inputs into rootfs (so proot can see them)
        File inRoot = new File(LinuxEnv.rootfsDir(ctx), "work/in");
        File outRoot = new File(LinuxEnv.rootfsDir(ctx), "work/out");
        deleteRecursive(inRoot);
        deleteRecursive(outRoot);
        inRoot.mkdirs();
        outRoot.mkdirs();

        // Copy android.jar + stdlib + reflect + sources
        File stdlib = ensureAsset("kotlin/kotlin-stdlib.jar",
                new File(ctx.getFilesDir(), "kotlin-dex"));
        File reflect = ensureAsset("kotlin/kotlin-reflect.jar",
                new File(ctx.getFilesDir(), "kotlin-dex"));

        copyFile(androidJar, new File(inRoot, "android.jar"));
        copyFile(stdlib,     new File(inRoot, "kotlin-stdlib.jar"));
        copyFile(reflect,    new File(inRoot, "kotlin-reflect.jar"));

        // Preserve relative package structure for each .kt
        for (File kt : ktFiles) {
            String rel = relativePath(sourceRoots, kt);
            File dst = new File(inRoot, rel);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(kt, dst);
        }

        // 4) Build kotlinc command
        StringBuilder ktArgs = new StringBuilder();
        ktArgs.append("/usr/bin/kotlinc ");
        ktArgs.append("-no-stdlib -no-reflect ");
        ktArgs.append("-jvm-target 1.8 ");
        ktArgs.append("-classpath '/work/in/android.jar:/work/in/kotlin-stdlib.jar:/work/in/kotlin-reflect.jar' ");
        ktArgs.append("-d /work/out ");
        for (File kt : ktFiles) {
            String rel = relativePath(sourceRoots, kt);
            ktArgs.append("/work/in/").append(rel).append(' ');
        }

        // 5) Run inside proot
        int rc = LinuxEnv.exec(ctx,
                new String[]{"/bin/sh", "-c", ktArgs.toString()},
                new LinuxEnv.Progress() {
                    @Override public void onProgress(String m) { say(m); }
                });

        if (rc != 0) {
            throw new RuntimeException("kotlinc exited with " + rc);
        }

        // 6) Copy .class files back out of rootfs
        if (!outRoot.exists()) {
            throw new RuntimeException("kotlinc produced no output dir");
        }
        int n = copyTree(outRoot, classesDir);
        say("Kotlin done: " + n + " files");

        if (n == 0) {
            throw new RuntimeException("kotlinc produced no .class files");
        }
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
