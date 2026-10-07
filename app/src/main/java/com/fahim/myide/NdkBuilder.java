package com.fahim.myide;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
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

    /** Picks the prebuilt folder matching the device ABI (arm64 -> linux-aarch64). */
    private static String pickPrebuilt(File ndkRoot) {
        File pre = new File(ndkRoot, "toolchains/llvm/prebuilt");
        if (!pre.isDirectory()) return null;
        File[] kids = pre.listFiles();
        if (kids == null) return null;

        // Prefer aarch64 on arm64 devices
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

    /** Copy directory recursively. */
    private static void copyDir(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new Exception("mkdir failed: " + dst);
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
            if (src.canExecute()) dst.setExecutable(true, false);
        }
    }

    /** Ensure the NDK lives somewhere executable (not /sdcard). Returns the usable path. */
    private File ensureExecutable(File ndk) {
        String abs = ndk.getAbsolutePath();
        boolean onSdcard = abs.startsWith("/sdcard")
                || abs.startsWith("/storage/emulated")
                || abs.startsWith("/mnt/sdcard");

        File local = new File(ctx.getFilesDir(), "ndk/" + ndk.getName());
        File marker = new File(local, ".copied");

        if (!onSdcard && new File(ndk, "ndk-build").canExecute()) {
            return ndk;
        }

        if (marker.exists() && new File(local, "ndk-build").canExecute()) {
            say("Using cached NDK at " + local.getAbsolutePath());
            return local;
        }

        say("Copying NDK to app storage (one-time, ~344 MB)...");
        say("  from: " + abs);
        say("  to:   " + local.getAbsolutePath());

        try {
            if (local.exists()) deleteRecursive(local);
            copyDir(ndk, local);

            File ndkBuild = new File(local, "ndk-build");
            if (ndkBuild.exists()) ndkBuild.setExecutable(true, false);

            File pre = new File(local, "toolchains/llvm/prebuilt");
            if (pre.isDirectory()) {
                File[] kids = pre.listFiles();
                if (kids != null) for (File k : kids) {
                    File bin = new File(k, "bin");
                    if (bin.isDirectory()) chmodR(bin);
                }
            }

            marker.createNewFile();
            say("NDK copied.");
        } catch (Exception e) {
            say("NDK copy failed: " + e);
            throw new RuntimeException("NDK copy failed", e);
        }
        return local;
    }

    private static void chmodR(File dir) {
        if (dir.isDirectory()) {
            dir.setExecutable(true, false);
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) chmodR(k);
        } else {
            dir.setExecutable(true, false);
        }
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
        File mk  = new File(jni, "Android.mk");
        if (!jni.isDirectory() || !mk.isFile()) {
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

        // Move to app storage if needed (noexec on /sdcard)
        File ndkUsable = ensureExecutable(ndk);
        String prebuiltUsable = pickPrebuilt(ndkUsable);
        if (prebuiltUsable == null)
            throw new RuntimeException("Copied NDK missing prebuilt tools");

        String ndkBuild = new File(ndkUsable, "ndk-build").getAbsolutePath();
        new File(ndkBuild).setExecutable(true, false);

        File libs = new File(projectRoot, "libs");
        if (!libs.exists()) libs.mkdirs();

        String binDir = new File(ndkUsable,
                "toolchains/llvm/prebuilt/" + prebuiltUsable + "/bin").getAbsolutePath();
        String path = binDir + ":" + (System.getenv("PATH") == null ? "" : System.getenv("PATH"));

        ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/sh", "-c",
                "cd '" + projectRoot.getAbsolutePath() + "' && "
                + "chmod +x '" + ndkBuild + "' 2>/dev/null; "
                + "'" + ndkBuild + "'"
                + " NDK_PROJECT_PATH=."
                + " APP_BUILD_SCRIPT=./jni/Android.mk"
                + " NDK_APPLICATION_MK=./jni/Application.mk"
                + " NDK_OUT=./.ndk-obj"
                + " NDK_LIBS_OUT=./libs"
        );
        pb.redirectErrorStream(true);
        pb.environment().put("NDK_ROOT", ndkUsable.getAbsolutePath());
        pb.environment().put("PATH", path);

        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) say("ndk: " + line);
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("ndk-build exited " + code);

        File arm = new File(libs, "arm64-v8a");
        if (!arm.isDirectory()) throw new RuntimeException("ndk-build produced no libs/arm64-v8a");

        File[] so = arm.listFiles();
        int n = 0;
        if (so != null) for (File f : so) if (f.getName().endsWith(".so")) n++;
        say("NDK produced " + n + " .so in libs/arm64-v8a");
        return true;
    }
}
