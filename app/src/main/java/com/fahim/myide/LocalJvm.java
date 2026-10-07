package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class LocalJvm {

    public interface Progress { void onProgress(String message); }

    private final Context ctx;
    private final Progress progress;

    public LocalJvm(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    /** Ensures JDK + Kotlin assets are extracted to filesDir. Idempotent. */
    public synchronized void ensureExtracted() throws Exception {
        File jdkMarker = new File(ctx.getFilesDir(), "jdk/.ok");
        if (!jdkMarker.exists()) {
            say("Extracting JDK (first run)…");
            File jdkDir = new File(ctx.getFilesDir(), "jdk");
            deleteRecursive(jdkDir);
            copyAssetTree("jdk", jdkDir);
            // Reassemble lib/modules from split parts
            File libDir = new File(jdkDir, "lib");
            File[] parts = libDir.listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(File d, String n) {
                    return n.startsWith("modules.part");
                }
            });
            File modules = new File(libDir, "modules");
            if (parts != null && parts.length > 0 && !modules.exists()) {
                java.util.Arrays.sort(parts);
                java.io.FileOutputStream out = new java.io.FileOutputStream(modules);
                byte[] buf = new byte[65536];
                for (File part : parts) {
                    java.io.FileInputStream in = new java.io.FileInputStream(part);
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    part.delete();
                }
                out.close();
                say("modules reassembled: " + modules.length() + " bytes");
            }
            if (!modules.exists()) throw new RuntimeException("JDK extract failed: no lib/modules");
            jdkMarker.getParentFile().mkdirs();
            jdkMarker.createNewFile();
            say("JDK extracted");
        }

        File ktMarker = new File(ctx.getFilesDir(), "kotlin/.ok");
        if (!ktMarker.exists()) {
            say("Extracting Kotlin compiler (first run)…");
            File ktDir = new File(ctx.getFilesDir(), "kotlin");
            deleteRecursive(ktDir);
            copyAssetTree("kotlin", ktDir);
            ktMarker.getParentFile().mkdirs();
            ktMarker.createNewFile();
            say("Kotlin compiler extracted");
        }
    }

    /** Runs kotlinc on the given sourceRoots, output to classesDir. */
    public int runKotlinc(List<File> sourceRoots, File classesDir, File androidJar)
            throws Exception {
        ensureExtracted();

        File jdkDir = new File(ctx.getFilesDir(), "jdk");
        File ktDir  = new File(ctx.getFilesDir(), "kotlin");
        File javaHome = jdkDir;

        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File javaLauncher = new File(nativeDir, "liblauncher_java.so");
        if (!javaLauncher.isFile()) {
            throw new RuntimeException("java launcher not found: " + javaLauncher);
        }

        if (!classesDir.exists()) classesDir.mkdirs();

        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) return 0;

        StringBuilder cp = new StringBuilder();
        cp.append(new File(ktDir, "lib/kotlin-compiler.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-stdlib.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-reflect.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-script-runtime.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-daemon.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-daemon-client.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlinx-coroutines-core-jvm.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/annotations-13.0.jar").getAbsolutePath())
          .append(File.pathSeparator)
          .append(new File(ktDir, "lib/kotlin-metadata-jvm.jar").getAbsolutePath());

        List<String> args = new ArrayList<String>();
        args.add(javaLauncher.getAbsolutePath());
        args.add("-Djava.home=" + javaHome.getAbsolutePath());
        args.add("-Djava.io.tmpdir=" + ctx.getCacheDir().getAbsolutePath());
        args.add("-Djava.library.path=" + new File(jdkDir, "lib").getAbsolutePath()
                + File.pathSeparator + nativeDir);
        args.add("-Dkotlin.home=" + ktDir.getAbsolutePath());
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Xmx1024m");
        args.add("-cp");
        args.add(cp.toString());
        args.add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-jvm-target");
        args.add("1.8");
        args.add("-classpath");
        args.add(androidJar.getAbsolutePath()
                + File.pathSeparator
                + new File(ktDir, "lib/kotlin-stdlib.jar").getAbsolutePath());
        args.add("-d");
        args.add(classesDir.getAbsolutePath());
        for (File kt : ktFiles) args.add(kt.getAbsolutePath());

        say("Running kotlinc via bundled JVM…");
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", javaHome.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        Process p = pb.start();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) say(line);
        return p.waitFor();
    }

    // ---------------- helpers ----------------

    private void copyAssetTree(String assetPath, File destDir) throws Exception {
        String[] kids = ctx.getAssets().list(assetPath);
        if (kids == null || kids.length == 0) {
            // leaf file
            File parent = destDir.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = ctx.getAssets().open(assetPath);
            FileOutputStream out = new FileOutputStream(destDir);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return;
        }
        if (!destDir.exists()) destDir.mkdirs();
        for (String k : kids) {
            copyAssetTree(assetPath + "/" + k, new File(destDir, k));
        }
    }

    private static void copy(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        } catch (Throwable ignored) {}
    }

    /**
     * Creates symlinks in filesDir/jdk/lib/ and filesDir/jdk/lib/server/
     * pointing at the renamed .so files inside the APK native lib dir, using
     * the original names the JVM expects (libjava.so, libjvm.so, etc).
     */
    private void findKtFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findKtFiles(f, out);
            else if (f.getName().endsWith(".kt")) out.add(f);
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
}
