package com.fahim.myide;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
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
        if (!NdkConfig.isValid(ndk))
            throw new RuntimeException("Invalid NDK at " + ndk.getAbsolutePath());

        String prebuilt = NdkConfig.findPrebuilt(ndk);
        if (prebuilt == null)
            throw new RuntimeException("NDK missing toolchains/llvm/prebuilt/*");
        say("NDK: " + ndk.getName() + " (" + prebuilt + ")");

        String ndkBuild = new File(ndk, "ndk-build").getAbsolutePath();
        File libs = new File(projectRoot, "libs");
        if (!libs.exists()) libs.mkdirs();

        String binDir = new File(ndk,
                "toolchains/llvm/prebuilt/" + prebuilt + "/bin").getAbsolutePath();
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
        pb.environment().put("NDK_ROOT", ndk.getAbsolutePath());
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
