package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

        LinuxEnv.setupIfNeeded(ctx, new LinuxEnv.Progress() {
            @Override public void onProgress(String m) { say(m); }
        });

        // Collect .kt files and find common root
        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) {
            say("No .kt files found");
            return;
        }
        say("Compiling " + ktFiles.size() + " Kotlin file(s)…");

        File rootfs = LinuxEnv.rootfsDir(ctx);

        // Clean staging areas inside rootfs
        File tmpInRoot = new File(rootfs, "tmp/myide");
        LinuxEnv.deleteRecursive(tmpInRoot);
        File tmpSrc = new File(tmpInRoot, "src");
        File tmpOut = new File(tmpInRoot, "out");
        tmpSrc.mkdirs();
        tmpOut.mkdirs();

        // Pick a src root to compute relative paths
        File baseRoot = null;
        for (File f : ktFiles) {
            if (baseRoot == null) { baseRoot = f.getParentFile(); continue; }
            baseRoot = commonParent(baseRoot, f.getParentFile());
        }
        if (baseRoot == null) baseRoot = ktFiles.get(0).getParentFile();

        for (File f : ktFiles) {
            String rel = relativize(baseRoot, f);
            File dst = new File(tmpSrc, rel);
            copyFile(f, dst);
        }
        say("Staged " + ktFiles.size() + " file(s) inside Linux");

        // Copy android.jar into rootfs once
        File ajInRoot = new File(tmpInRoot, "android.jar");
        copyFile(androidJar, ajInRoot);

        // Auto-detect stdlib location inside Alpine rootfs
        say("Locating kotlin-stdlib inside rootfs…");
        File kHome = findKotlinHome(rootfs);
        if (kHome == null) {
            throw new RuntimeException("kotlin-stdlib.jar not found inside rootfs. "
                    + "Did 'apk add kotlin' succeed?");
        }
        File stdlibInRoot = findKotlinStdlib(kHome);
        if (stdlibInRoot == null) {
            throw new RuntimeException("kotlin-stdlib.jar not found under " + kHome);
        }
        say("  kotlin home: " + kHome.getAbsolutePath());
        say("  stdlib: " + stdlibInRoot.getAbsolutePath());

        String kotlinHomeRel = "/" + relativize(rootfs, kHome);
        String stdlibRel = "/" + relativize(rootfs, stdlibInRoot);

        // kotlinc inside — do NOT pass -no-stdlib; explicitly add the jar
        // in case the auto-detect via KOTLIN_HOME fails.
        String script =
            "cd /tmp/myide && " +
            "KOTLIN_HOME=" + shQuote(kotlinHomeRel) + " " +
            "/usr/bin/kotlinc src " +
            "-classpath /tmp/myide/android.jar:" + shQuote(stdlibRel) + " " +
            "-jvm-target 1.8 -no-reflect " +
            "-d out 2>&1";

        say("Running kotlinc inside Alpine…");
        int rc = LinuxEnv.exec(ctx, new String[]{"/bin/sh", "-c", script},
                new LinuxEnv.Progress() {
                    @Override public void onProgress(String m) { say(m); }
                });

        if (rc != 0) {
            say("kotlinc exit code " + rc);
            throw new RuntimeException("Kotlin compile failed (exit " + rc + ")");
        }

        // Copy produced .class files out
        if (!classesDir.exists()) classesDir.mkdirs();
        int n = copyTree(tmpOut, classesDir);
        say("Kotlin compiled: " + n + " classes");

        LinuxEnv.deleteRecursive(tmpInRoot);
    }

    // ---------- helpers ----------

    private static File findKotlinHome(File rootfs) {
        File[] candidates = {
                new File(rootfs, "usr/lib/kotlin"),
                new File(rootfs, "usr/share/kotlin"),
                new File(rootfs, "opt/kotlin"),
                new File(rootfs, "usr/local/lib/kotlin"),
                new File(rootfs, "usr/lib/kotlin-compiler")
        };
        for (File c : candidates) {
            if (c.isDirectory() && findKotlinStdlib(c) != null) return c;
        }
        // Fallback: search /usr/lib shallowly
        File usrLib = new File(rootfs, "usr/lib");
        File[] kids = usrLib.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory() && k.getName().startsWith("kotlin")) {
                    if (findKotlinStdlib(k) != null) return k;
                }
            }
        }
        return null;
    }

    private static File findKotlinStdlib(File kotlinHome) {
        File[] candidates = {
                new File(kotlinHome, "lib/kotlin-stdlib.jar"),
                new File(kotlinHome, "lib/kotlin-stdlib-jdk8.jar"),
                new File(kotlinHome, "kotlin-stdlib.jar")
        };
        for (File c : candidates) if (c.isFile()) return c;
        return null;
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private int copyTree(File src, File dst) throws IOException {
        if (!src.exists()) return 0;
        int count = 0;
        File[] kids = src.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) {
                t.mkdirs();
                count += copyTree(f, t);
            } else {
                copyFile(f, t);
                count++;
            }
        }
        return count;
    }

    private static File commonParent(File a, File b) {
        try {
            String pa = a.getCanonicalPath();
            String pb = b.getCanonicalPath();
            while (!pb.startsWith(pa)) {
                File p = a.getParentFile();
                if (p == null) return a;
                a = p;
                pa = a.getCanonicalPath();
            }
            return a;
        } catch (IOException e) {
            return a;
        }
    }

    private static String relativize(File base, File f) {
        try {
            String b = base.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.startsWith(b)) {
                String r = p.substring(b.length());
                if (r.startsWith("/")) r = r.substring(1);
                return r;
            }
        } catch (IOException ignored) {}
        return f.getName();
    }

    private static void copyFile(File src, File dst) throws IOException {
        File d = dst.getParentFile();
        if (d != null && !d.exists()) d.mkdirs();
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
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
}
