package com.fahim.myide;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
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

        extractAsset(ctx, "proot/libtalloc.so.2", new File(libDir, "libtalloc.so.2"));
        extractAsset(ctx, "proot/libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"));

        File tar = new File(ctx.getFilesDir(), "alpine.tar.gz");
        extractAsset(ctx, "rootfs/alpine-rootfs.tgz", tar);

        if (cb != null) cb.onProgress("Extracting Alpine Linux…");
        int n = TarGzExtractor.extract(tar, root, new TarGzExtractor.Progress() {
            @Override public void onProgress(String p) {}
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

        ensureRealFile(root, "bin/sh",      "bin/busybox");
        ensureRealFile(root, "usr/bin/env", "bin/busybox");
        ensureRealFile(root, "bin/env",     "bin/busybox");
        ensureRealFile(root, "usr/bin/sh",  "bin/busybox");

        healMuslLoader(root, cb);

        try {
            String[] chmodPaths = {
                    new File(root, "bin").getAbsolutePath(),
                    new File(root, "usr").getAbsolutePath(),
                    new File(root, "sbin").getAbsolutePath(),
                    new File(root, "lib").getAbsolutePath()
            };
            for (String p : chmodPaths) {
                new ProcessBuilder("/system/bin/sh", "-c", "chmod -R 0755 '" + p + "'")
                        .redirectErrorStream(true).start().waitFor();
            }
        } catch (Throwable ignored) {}

        File apkRepo = new File(root, "etc/apk/repositories");
        writeText(apkRepo,
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/community\n");

        File resolv = new File(root, "etc/resolv.conf");
        writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");

        // Make sure every file inside the rootfs is executable.
        try {
            new ProcessBuilder("/system/bin/sh", "-c",
                    "chmod -R 0755 " + root.getAbsolutePath())
                .redirectErrorStream(true).start().waitFor();
        } catch (Throwable t) {
            if (cb != null) cb.onProgress("chmod failed: " + t);
        }

        if (cb != null) cb.onProgress("proot smoke test…");

        try {
            File libDirDbg = new File(root, "lib");
            File[] libs = libDirDbg.listFiles();
            if (libs != null) {
                for (File f : libs) {
                    cb.onProgress("  lib: " + f.getName()
                            + " len=" + f.length()
                            + " canExec=" + f.canExecute());
                }
            } else {
                cb.onProgress("  lib: (empty or missing)");
            }
            File busyboxDbg = new File(root, "bin/busybox");
            cb.onProgress("  busybox: len=" + busyboxDbg.length()
                    + " canExec=" + busyboxDbg.canExecute());
            File binShDbg = new File(root, "bin/sh");
            cb.onProgress("  bin/sh: len=" + binShDbg.length()
                    + " canExec=" + binShDbg.canExecute());
            byte[] hdr = new byte[64];
            FileInputStream fis = new FileInputStream(busyboxDbg);
            fis.read(hdr);
            fis.close();
            if (hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                cb.onProgress("  busybox: ELF class=" + hdr[4]
                        + " machine=" + (hdr[18] & 0xff) + "," + (hdr[19] & 0xff));
            } else {
                cb.onProgress("  busybox: NOT ELF");
            }
        } catch (Throwable t) {
            cb.onProgress("  debug dump failed: " + t);
        }

        int probe = exec(ctx, new String[]{"/bin/sh", "-c", "echo proot_ok"}, cb);
        if (probe != 0) {
            throw new RuntimeException("proot cannot exec /bin/sh — local Kotlin "
                    + "compilation is unavailable on this device. Use Kotlin mode = "
                    + "Remote in Settings, or install a Termux proot build.");
        }

        if (cb != null) cb.onProgress("Installing OpenJDK 17 + Kotlin (~350 MB, one-time)…");
        int apkRc = exec(ctx,
                new String[]{"/bin/sh", "-c",
                        "apk update && apk add --no-cache openjdk17-jdk kotlin"},
                cb);
        if (apkRc != 0) throw new RuntimeException("apk add failed: " + apkRc);

        exec(ctx,
                new String[]{"/bin/sh", "-c", "kotlinc -version 2>&1 || true"},
                cb);

        new File(root, MARKER).createNewFile();
    }

    private static void healMuslLoader(File root, Progress cb) {
        String abi = "arm64-v8a";
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                abi = Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignored) {}

        String[] archNames;
        if (abi.startsWith("arm64") || abi.equals("aarch64")) {
            archNames = new String[]{"aarch64", "arm64"};
        } else if (abi.startsWith("armeabi") || abi.equals("arm")) {
            archNames = new String[]{"armhf", "arm"};
        } else if (abi.equals("x86_64") || abi.equals("x64")) {
            archNames = new String[]{"x86_64", "x86-64"};
        } else if (abi.equals("x86") || abi.equals("i386") || abi.equals("i686")) {
            archNames = new String[]{"i386", "i686", "x86"};
        } else {
            archNames = new String[]{"aarch64", "arm64", "armhf"};
        }

        File libRoot = new File(root, "lib");
        File usrLib  = new File(root, "usr/lib");

        for (String a : archNames) {
            File loader = new File(libRoot, "ld-musl-" + a + ".so.1");
            if (loader.isFile() && loader.length() > 0) continue;
            File libc = new File(libRoot, "libc.musl-" + a + ".so.1");
            if (libc.isFile() && libc.length() > 0) {
                try {
                    copyFile(libc, loader);
                    loader.setExecutable(true, false);
                    if (cb != null) cb.onProgress("  healed loader: " + loader.getName());
                    continue;
                } catch (Throwable ignored) {}
            }
            File usrLibc = new File(usrLib, "libc.musl-" + a + ".so.1");
            if (usrLibc.isFile()) {
                try {
                    copyFile(usrLibc, loader);
                    loader.setExecutable(true, false);
                    if (cb != null) cb.onProgress("  healed loader (usr): " + loader.getName());
                } catch (Throwable ignored) {}
            }
        }

        for (String a : archNames) {
            File libc = new File(libRoot, "libc.musl-" + a + ".so.1");
            if (libc.isFile() && libc.length() > 0) continue;
            File loader = new File(libRoot, "ld-musl-" + a + ".so.1");
            if (loader.isFile() && loader.length() > 0) {
                try {
                    copyFile(loader, libc);
                    if (cb != null) cb.onProgress("  healed libc: " + libc.getName());
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void ensureRealFile(File root, String relPath, String relSource) {
        File dst = new File(root, relPath);
        File src = new File(root, relSource);
        if (!src.isFile()) return;
        if (dst.isFile() && dst.length() > 0) return;
        try {
            File p = dst.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            copyFile(src, dst);
            dst.setExecutable(true, false);
        } catch (Throwable ignored) {}
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
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

        full.add("--link2symlink");
        full.add("--kill-on-exit");

        full.add("-r"); full.add(root.getAbsolutePath());
        full.add("-0");
        full.add("-w"); full.add("/root");
        full.add("-b"); full.add("/dev");
        full.add("-b"); full.add("/proc");
        full.add("-b"); full.add("/sys");

        // Kernel resolves ELF interpreter path *before* proot sees the exec,
        // so /lib and /usr/lib must resolve on the host at the same path.
        full.add("-b");
        full.add(new File(rootfsDir(ctx), "lib").getAbsolutePath() + ":/lib");
        full.add("-b");
        full.add(new File(rootfsDir(ctx), "usr/lib").getAbsolutePath() + ":/usr/lib");


        full.add("/bin/sh");
        full.add("-c");

        // Build the script. If the caller already gave us /bin/sh -c "...", use
        // their inner script verbatim (no double-wrapping). Otherwise shell-quote.
        StringBuilder script = new StringBuilder();
        script.append("export HOME=/root ")
              .append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
              .append("TERM=xterm LANG=C.UTF-8 ")
              .append("JAVA_HOME=/usr/lib/jvm/java-17-openjdk ")
              .append("KOTLIN_HOME=/usr/lib/kotlin ; ");

        if (cmd.length == 3
                && ("/bin/sh".equals(cmd[0]) || "sh".equals(cmd[0]))
                && "-c".equals(cmd[1])) {
            // Already a shell script — use as-is
            script.append(cmd[2]);
        } else {
            for (String c : cmd) {
                script.append(shellQuote(c)).append(' ');
            }
        }
        full.add(script.toString());

        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        Map<String,String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", rootfsDir(ctx).getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PROOT_VERBOSE", "1");
        if (cb != null) {
            StringBuilder dbg = new StringBuilder("$ proot");
            for (String a : full) dbg.append(' ').append(a);
            cb.onProgress(dbg.toString());
        }

        Process p = pb.start();
        drain(p, cb);
        return p.waitFor();
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
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
