package com.fahim.myide;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class GradleBuilder {

    public interface Progress { void onProgress(String message); }

    public static class Result {
        public boolean success;
        public File apk;
        public String log;
        public Result(boolean s, File a, String l) { success = s; apk = a; log = l; }
    }

    private final Context ctx;
    private final Progress progress;

    public GradleBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            say("Gradle: preparing...");

            File appBuildGradle = new File(projectRoot, "app/build.gradle");
            File appBuildGradleKts = new File(projectRoot, "app/build.gradle.kts");
            File rootBuildGradle = new File(projectRoot, "build.gradle");
            File rootBuildGradleKts = new File(projectRoot, "build.gradle.kts");

            if (!appBuildGradle.exists() && !appBuildGradleKts.exists()) {
                return fail(log,
                        "No app/build.gradle(.kts) in project.\n" +
                        "Gradle mode needs a real Gradle project.");
            }

            File home = new File(System.getenv("HOME") == null
                    ? "/data/data/com.termux/files/home"
                    : System.getenv("HOME"));
            File sdk = new File(home, "android-sdk");

            File localProps = new File(projectRoot, "local.properties");
            writeFile(localProps, "sdk.dir=" + sdk.getAbsolutePath() + "\n");

            File outDir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!outDir.exists()) outDir.mkdirs();
            File logFile = new File(outDir, "gradle-build.log");
            File outApk = new File(outDir, "app-debug.apk");

            String gradleCmd = findGradle();
            if (gradleCmd == null) {
                return fail(log,
                        "Gradle not found.\n" +
                        "In Termux: pkg install gradle\n" +
                        "Then retry.");
            }

            say("Gradle: running assembleDebug…");

            String wrapper = new File(projectRoot, "gradlew").getAbsolutePath();
            String[] cmd;
            if (new File(wrapper).canExecute()) {
                cmd = new String[]{ wrapper, "assembleDebug", "--no-daemon", "--console=plain" };
            } else {
                cmd = new String[]{ gradleCmd, "assembleDebug", "--no-daemon", "--console=plain" };
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot);
            pb.redirectErrorStream(true);

            pb.environment().put("ANDROID_HOME", sdk.getAbsolutePath());
            pb.environment().put("ANDROID_SDK_ROOT", sdk.getAbsolutePath());
            String path = pb.environment().get("PATH");
            if (path == null) path = "";
            path = sdk.getAbsolutePath() + "/cmdline-tools/latest/bin:"
                 + sdk.getAbsolutePath() + "/platform-tools:"
                 + path;
            pb.environment().put("PATH", path);
            pb.environment().put("JAVA_HOME",
                    "/data/data/com.termux/files/usr/opt/openjdk");

            Process p = pb.start();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = r.readLine()) != null) {
                buf.write((line + "\n").getBytes("UTF-8"));
                say("Gradle: " + line);
            }
            int code = p.waitFor();

            writeFile(logFile, buf.toString("UTF-8"));

            if (code != 0) {
                String msg = "Gradle exited with " + code + "\n" + buf.toString("UTF-8");
                log.append(msg);
                return new Result(false, null, msg);
            }

            File apkDir = new File(projectRoot, "app/build/outputs/apk/debug");
            File[] apks = apkDir.listFiles();
            File produced = null;
            if (apks != null) {
                for (File f : apks) {
                    if (f.getName().endsWith(".apk")) { produced = f; break; }
                }
            }
            if (produced == null) {
                return fail(log, "Gradle finished but no APK in " + apkDir);
            }

            copyFile(produced, outApk);
            say("Gradle: done");
            return new Result(true, outApk, log.toString());

        } catch (Throwable t) {
            String msg = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            log.append(msg).append('\n');
            say(msg);
            return new Result(false, null, log.toString());
        }
    }

    private String findGradle() {
        String[] candidates = {
                "/data/data/com.termux/files/usr/bin/gradle",
                "/data/data/com.termux/files/usr/opt/openjdk/bin/gradle",
                "/system/bin/gradle"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.canExecute()) return c;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                File f = new File(dir, "gradle");
                if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    private Result fail(StringBuilder log, String msg) {
        log.append(msg).append('\n');
        say(msg);
        return new Result(false, null, msg);
    }

    private void writeFile(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
