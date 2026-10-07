package com.fahim.myide;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class NdkBuilder {

    public interface Progress { void onProgress(String message); }

    private final Context ctx;
    private final Progress progress;

    public NdkBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    private static String pickPrebuilt(File ndkRoot) {
        File pre = new File(ndkRoot, "toolchains/llvm/prebuilt");
        if (!pre.isDirectory()) return null;
        File[] kids = pre.listFiles();
        if (kids == null) return null;

        boolean arm64 = false;
        try {
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi != null && abi.startsWith("arm64")) { arm64 = true; break; }
            }
        } catch (Throwable ignored) {}

        String fallback = null;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            if (!new File(k, "bin").isDirectory()) continue;
            String n = k.getName();
            if (fallback == null) fallback = n;
            if (arm64 && (n.contains("aarch64") || n.contains("arm64"))) return n;
        }
        return fallback;
    }

    private static void copyDir(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new Exception("mkdir " + dst);
            File[] kids = src.listFiles();
            if (kids == null) return;
            for (File k : kids) copyDir(k, new File(dst, k.getName()));
        } else {
            File p = dst.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
        }
    }

    private File ensureLocal(File ndk) {
        String abs = ndk.getAbsolutePath();
        boolean onSdcard = abs.startsWith("/sdcard")
                || abs.startsWith("/storage/emulated")
                || abs.startsWith("/mnt/sdcard");

        File local = new File(ctx.getFilesDir(), "ndk/" + ndk.getName());
        File marker = new File(local, ".copied");

        if (!onSdcard) return ndk;
        if (marker.exists()) {
            say("Using cached NDK: " + local.getAbsolutePath());
            return local;
        }

        say("Copying NDK to app storage (one-time, ~344 MB)...");
        try {
            if (local.exists()) deleteRecursive(local);
            copyDir(ndk, local);
            marker.createNewFile();
            say("NDK copied.");
        } catch (Exception e) {
            throw new RuntimeException("NDK copy failed: " + e);
        }
        return local;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    public boolean buildIfNeeded(File projectRoot) throws Exception {
        File jni = new File(projectRoot, "jni");
        if (!jni.isDirectory() || !new File(jni, "Android.mk").isFile()) {
            say("No jni/Android.mk — skipping NDK");
            return false;
        }

        File ndk = NdkConfig.getDir(ctx);
        if (ndk == null)
            throw new RuntimeException("NDK not selected (Build -> Select NDK Folder)");

        String prebuilt = pickPrebuilt(ndk);
        if (prebuilt == null)
            throw new RuntimeException("NDK missing toolchains/llvm/prebuilt/*");
        say("NDK: " + ndk.getName() + " (" + prebuilt + ")");

        File ndkUsable = ensureLocal(ndk);
        String prebuiltUsable = pickPrebuilt(ndkUsable);
        if (prebuiltUsable == null)
            throw new RuntimeException("NDK missing prebuilt after copy");

        File ndkBuild = new File(ndkUsable, "ndk-build");
        if (!ndkBuild.isFile())
            throw new RuntimeException("ndk-build missing: " + ndkBuild);

        File libs = new File(projectRoot, "libs");
        if (!libs.exists()) libs.mkdirs();

        // proot binary
        File proot = new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
        if (!proot.isFile()) throw new RuntimeException("proot missing");

        File libDir = new File(ctx.getFilesDir(), "proot-lib");
        libDir.mkdirs();
        File talloc = new File(libDir, "libtalloc.so.2");
        if (!talloc.isFile()) {
            say("Extracting proot libs (one-time)...");
            extractAsset("proot/libtalloc.so.2", talloc);
            extractAsset("proot/libandroid-shmem.so",
                    new File(libDir, "libandroid-shmem.so"));
            say("proot libs ready.");
        }

        String binDir = new File(ndkUsable,
                "toolchains/llvm/prebuilt/" + prebuiltUsable + "/bin").getAbsolutePath();

        // Build proot command: bind rootfs paths so ndk-build can run.
        StringBuilder cmd = new StringBuilder();
        cmd.append("'").append(proot.getAbsolutePath()).append("'")
           .append(" -0 --link2symlink --kill-on-exit")
           .append(" -r /")
           .append(" -w '").append(projectRoot.getAbsolutePath()).append("'")
           .append(" -b /dev -b /proc -b /sys")
           .append(" -b '").append(ctx.getFilesDir().getAbsolutePath()).append("'")
           .append(" -b '").append(projectRoot.getAbsolutePath()).append("'")
           .append(" /system/bin/sh -c \"")
           .append("export PATH='").append(binDir).append("':$PATH; ")
           .append("export NDK_ROOT='").append(ndkUsable.getAbsolutePath()).append("'; ")
           .append("cd '").append(projectRoot.getAbsolutePath()).append("'; ")
           .append("'").append(ndkBuild.getAbsolutePath()).append("'")
           .append(" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk")
           .append(" NDK_APPLICATION_MK=./jni/Application.mk")
           .append(" NDK_OUT=./.ndk-obj NDK_LIBS_OUT=./libs\"");

        say("$ proot ndk-build");

        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd.toString());
        pb.redirectErrorStream(true);
        pb.environment().put("PROOT_TMP_DIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        pb.environment().put("LD_LIBRARY_PATH",
                new File(ctx.getFilesDir(), "proot-lib").getAbsolutePath());

        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) say("ndk: " + line);
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("ndk-build exited " + code);

        File arm = new File(libs, "arm64-v8a");
        if (!arm.isDirectory()) throw new RuntimeException("No libs/arm64-v8a");
        File[] so = arm.listFiles();
        int n = 0;
        if (so != null) for (File f : so) if (f.getName().endsWith(".so")) n++;
        say("NDK produced " + n + " .so");
        return true;
    }

    private void extractAsset(String asset, File out) throws Exception {
        File p = out.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        java.io.InputStream in = ctx.getAssets().open(asset);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }
}
