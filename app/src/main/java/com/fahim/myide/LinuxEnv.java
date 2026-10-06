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

        // Copy rootfs tar.gz to a real file, then extract
        File tar = new File(ctx.getFilesDir(), "alpine.tar.gz");
        extractAsset(ctx, "rootfs/alpine-rootfs.tgz", tar);

        if (cb != null) cb.onProgress("Extracting Alpine Linux…");
        int n = TarGzExtractor.extract(tar, root, new TarGzExtractor.Progress() {
            @Override public void onProgress(String p) {
                if (cb != null && p != null && !p.startsWith("extract:")) {}
            }
        });
        tar.delete();
        if (cb != null) cb.onProgress("Extracted " + n + " entries");

        File binSh = new File(root, "bin/sh");
        File binBb = new File(root, "bin/busybox");
        if (cb != null) cb.onProgress("bin/sh exists=" + binSh.exists()
                + " bin/busybox exists=" + binBb.exists());
        if (!binSh.exists() && !binBb.exists()) {
            throw new RuntimeException("rootfs extract failed: no /bin/sh or /bin/busybox");
        }

        // Heal missing symlinks that TarGzExtractor may have dropped.
        // /usr/bin/env is the one proot needs to spawn processes.
        ensureRealFile(root, "usr/bin/env", "bin/busybox");
        ensureRealFile(root, "bin/env",     "bin/busybox");
        ensureRealFile(root, "usr/bin/sh",  "bin/busybox");
        ensureRealFile(root, "bin/sh",      "bin/busybox");

        // Make all files under /bin, /usr, /sbin readable/executable
        try {
            ProcessBuilder chmod = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "chmod -R 755 " + new File(root, "bin").getAbsolutePath()
                    + " " + new File(root, "usr").getAbsolutePath()
                    + " " + new File(root, "sbin").getAbsolutePath());
            chmod.redirectErrorStream(true);
            chmod.start().waitFor();
        } catch (Throwable ignored) {}

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

        // Sanity check
        exec(ctx,
                new String[]{"/bin/sh", "-c", "kotlinc -version 2>&1 || true"},
                cb);

        new File(root, MARKER).createNewFile();
    }

    /**
     * Copy {@code relSource} over {@code relPath} when the latter is missing
     * or is not a real file. Needed because TarGzExtractor materialises
     * symlinks as copies of their target; when the target wasn't yet present
     * at extract time, the symlink got dropped entirely.
     */
    private static void ensureRealFile(File root, String relPath, String relSource) {
        File dst = new File(root, relPath);
        File src = new File(root, relSource);
        if (!src.isFile()) return;
        if (dst.isFile()) {
            // Both exist; still re-copy busybox shims to be safe if zero-length
            if (dst.length() == 0) {
                try { copyFile(src, dst); dst.setExecutable(true, false); }
                catch (Throwable ignored) {}
            }
            return;
        }
        try {
            File p = dst.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            copyFile(src, dst);
            dst.setExecutable(true, false);
        } catch (Throwable ignored) {}
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    /** Runs a command inside the Alpine rootfs. Returns exit code. */
    public static int exec(Context ctx, String[] cmd, Progress cb) throws Exception {
        File proot = prootBin(ctx);
        File root = rootfsDir(ctx);
        File libDir = prootLibDir(ctx);

        if (!proot.isFile()) throw new RuntimeException("proot missing: " + proot);

        List<String> full = new ArrayList<String>();
        full.add(proot.getAbsolutePath());
        full.add("-r"); full.add(root.getAbsolutePath());
        full.add("-0");
        full.add("-w"); full.add("/root");
        full.add("-b"); full.add("/dev");
        full.add("-b"); full.add("/proc");
        full.add("-b"); full.add("/sys");

        // Use busybox directly, then ask it to run its "env" applet.
        // Avoids proot's execve("/usr/bin/env") path which fails when
        // the env symlink was dropped during tarball extraction.
        full.add("/bin/busybox");
        full.add("env");
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
