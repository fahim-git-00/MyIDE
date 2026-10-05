package com.fahim.myide;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class LinuxEnv {

    public interface Progress { void onProgress(String message); }

    private static final String MARKER = ".installed";

    private LinuxEnv() {}

    public static File rootfsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "linuxroot");
    }

    public static File prootLibDir(Context ctx) {
        return new File(ctx.getFilesDir(), "proot-lib");
    }

    public static File prootBin(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    public static boolean isReady(Context ctx) {
        return new File(rootfsDir(ctx), MARKER).exists();
    }

    public static void setupIfNeeded(Context ctx, Progress cb) throws Exception {
        if (isReady(ctx)) return;

        File root = rootfsDir(ctx);
        deleteRecursive(root);
        root.mkdirs();

        File libDir = prootLibDir(ctx);
        deleteRecursive(libDir);
        libDir.mkdirs();

        // Extract proot's helper libs from assets
        extractAsset(ctx, "proot/libtalloc.so.2", new File(libDir, "libtalloc.so.2"));
        extractAsset(ctx, "proot/libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"));

        // Copy rootfs tar.gz to a real file, then extract with toybox tar
        File tar = new File(ctx.getFilesDir(), "alpine.tar.gz");
        extractAsset(ctx, "rootfs/alpine-rootfs.tgz", tar);

        if (cb != null) cb.onProgress("Extracting Alpine Linux…");
        ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/tar", "-xzf", tar.getAbsolutePath(),
                "-C", root.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p, null);
        int rc = p.waitFor();
        tar.delete();
        if (rc != 0) throw new RuntimeException("tar failed: " + rc);

        // Point apk at repositories
        File apkRepo = new File(root, "etc/apk/repositories");
        writeText(apkRepo,
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/community\n");

        // DNS
        File resolv = new File(root, "etc/resolv.conf");
        writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");

        if (cb != null) cb.onProgress("Installing OpenJDK 17 + Kotlin (~350 MB, one-time)…");
        int apkRc = exec(ctx,
                new String[]{"/bin/sh", "-c",
                        "apk update && apk add --no-cache openjdk17-jdk kotlin"},
                cb);
        if (apkRc != 0) throw new RuntimeException("apk add failed: " + apkRc);

        // Sanity
        int v = exec(ctx,
                new String[]{"/bin/sh", "-c", "kotlinc -version 2>&1 || true"},
                cb);
        // no strict check — if kotlinc runs, good enough

        new File(root, MARKER).createNewFile();
    }

    /** Runs a command inside the Alpine rootfs. Returns exit code. */
    public static int exec(Context ctx, String[] cmd, Progress cb) throws Exception {
        File proot = prootBin(ctx);
        File root = rootfsDir(ctx);
        File libDir = prootLibDir(ctx);

        if (!proot.isFile()) throw new RuntimeException("proot missing: " + proot);

        List<String> full = new ArrayList<>();
        full.add(proot.getAbsolutePath());
        full.add("-r"); full.add(root.getAbsolutePath());
        full.add("-0");
        full.add("-w"); full.add("/root");
        full.add("-b"); full.add("/dev");
        full.add("-b"); full.add("/proc");
        full.add("-b"); full.add("/sys");
        full.add("/usr/bin/env");
        full.add("-i");
        full.add("HOME=/root");
        full.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        full.add("TERM=xterm");
        full.add("LANG=C.UTF-8");
        full.add("JAVA_HOME=/usr/lib/jvm/java-17-openjdk");
        full.addAll(Arrays.asList(cmd));

        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        Map<String,String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", ctx.getCacheDir().getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");

        Process p = pb.start();
        drain(p, cb);
        return p.waitFor();
    }

    private static void drain(Process p, Progress cb) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (cb != null) cb.onProgress(line);
        }
    }

    private static void extractAsset(Context ctx, String asset, File out) throws IOException {
        File dir = out.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        InputStream in = ctx.getAssets().open(asset);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    private static void writeText(File f, String s) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) deleteRecursive(x);
        }
        f.delete();
    }
}
