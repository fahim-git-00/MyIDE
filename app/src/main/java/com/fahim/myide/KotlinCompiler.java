package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dalvik.system.DexClassLoader;

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

    // Bump this whenever compiler/stdlib/reflect jars change
    private static final String KT_CACHE_VER = "v5-1.7.22";

    private static File cacheDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "kotlin-" + KT_CACHE_VER);
        if (!d.exists()) d.mkdirs();
        // Kill legacy "kotlin/" cache dir
        File legacy = new File(ctx.getFilesDir(), "kotlin");
        if (legacy.exists()) deleteTree(legacy);
        return d;
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) deleteTree(x);
        }
        f.delete();
    }

    public static File ensureCompilerJar(Context ctx) throws Exception {
        File out = new File(cacheDir(ctx), "kotlin-compiler-embeddable.jar");
        if (out.isFile() && out.length() > 10_000_000L) return out;
        extractAsset(ctx, "kotlin/kotlin-compiler-embeddable.jar", out);
        return out;
    }

    public static File ensureStdlibJar(Context ctx) throws Exception {
        File out = new File(cacheDir(ctx), "kotlin-stdlib.jar");
        if (out.isFile() && out.length() > 100_000L) return out;
        extractAsset(ctx, "kotlin/kotlin-stdlib.jar", out);
        return out;
    }

    public static File ensureReflectJar(Context ctx) throws Exception {
        File out = new File(cacheDir(ctx), "kotlin-reflect.jar");
        if (out.isFile() && out.length() > 100_000L) return out;
        extractAsset(ctx, "kotlin/kotlin-reflect.jar", out);
        return out;
    }

    public static File ensureScriptRuntimeJar(Context ctx) throws Exception {
        File out = new File(cacheDir(ctx), "kotlin-script-runtime.jar");
        if (out.isFile() && out.length() > 1000L) return out;
        extractAsset(ctx, "kotlin/kotlin-script-runtime.jar", out);
        return out;
    }

    private static void extractAsset(Context ctx, String assetName, File out) throws IOException {
        InputStream in = ctx.getAssets().open(assetName);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    private static File ensureKotlinHome(Context ctx, File stdlibJar) throws IOException {
        File home = new File(ctx.getFilesDir(), "kotlin-home");
        File lib  = new File(home, "lib");
        if (!lib.exists()) lib.mkdirs();

        copyIfMissing(stdlibJar, new File(lib, "kotlin-stdlib.jar"));

        String[] stubs = {
            "kotlin-reflect.jar",
            "kotlin-script-runtime.jar",
            "kotlin-test.jar",
            "kotlin-annotations-jvm.jar",
            "kotlin-daemon.jar",
            "kotlin-compiler.jar"
        };
        for (String s : stubs) {
            File f = new File(lib, s);
            if (!f.exists()) writeEmptyJar(f);
        }
        return home;
    }

    private static void copyIfMissing(File src, File dst) throws IOException {
        if (dst.exists() && dst.length() > 0) return;
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    private static void writeEmptyJar(File f) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f));
        ZipEntry e = new ZipEntry("META-INF/MANIFEST.MF");
        zos.putNextEntry(e);
        zos.write("Manifest-Version: 1.0\n".getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();
    }

    public void compile(File compilerJar,
                        File stdlibJar,
                        File androidJar,
                        List<File> sourceRoots,
                        File genDir,
                        File classesDir,
                        List<File> extraJars) throws Exception {

        say("Loading kotlinc (embeddable)...");

        File reflectJar = ensureReflectJar(ctx);
        File stdlibOnLoader = ensureStdlibJar(ctx);
        File scriptJar = ensureScriptRuntimeJar(ctx);
        String dexPath = compilerJar.getAbsolutePath()
                + File.pathSeparator + stdlibOnLoader.getAbsolutePath()
                + File.pathSeparator + reflectJar.getAbsolutePath()
                + File.pathSeparator + scriptJar.getAbsolutePath();

        DexClassLoader loader = new DexClassLoader(
                dexPath,
                ctx.getCacheDir().getAbsolutePath(),
                null,
                ClassLoader.getSystemClassLoader());

        Class<?> mainClass = loader.loadClass(
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");

        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (genDir != null) findKtFiles(genDir, ktFiles);

        if (ktFiles.isEmpty()) {
            say("No .kt files found");
            return;
        }
        say("Compiling " + ktFiles.size() + " Kotlin file(s)...");
        if (!classesDir.exists()) classesDir.mkdirs();

        File kotlinHome = ensureKotlinHome(ctx, stdlibJar);
        say("kotlin-home: " + kotlinHome.getAbsolutePath());

        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        if (stdlibJar != null && stdlibJar.exists()) {
            cp.append(File.pathSeparator).append(stdlibJar.getAbsolutePath());
        }
        if (reflectJar != null && reflectJar.exists()) {
            cp.append(File.pathSeparator).append(reflectJar.getAbsolutePath());
        }
        for (File j : extraJars) {
            if (j != null && j.exists()) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        }

        List<String> args = new ArrayList<String>();
        args.add("-kotlin-home"); args.add(kotlinHome.getAbsolutePath());
        args.add("-no-jdk");
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-jvm-target"); args.add("1.8");
        args.add("-classpath"); args.add(cp.toString());
        args.add("-d"); args.add(classesDir.getAbsolutePath());
        args.add("-nowarn");
        args.add("-Xsuppress-version-warnings");
        args.add("-Xno-param-assertions");
        for (File f : ktFiles) args.add(f.getAbsolutePath());

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(outBuf, true));
        System.setErr(new PrintStream(outBuf, true));

        int exitCode = 1;
        try {
            Method exec = mainClass.getMethod("exec", PrintStream.class, String[].class);
            Object instance = mainClass.getDeclaredConstructor().newInstance();
            Object result = exec.invoke(instance,
                    new PrintStream(outBuf, true),
                    (Object) args.toArray(new String[0]));
            try {
                Method getCode = result.getClass().getMethod("getCode");
                exitCode = ((Integer) getCode.invoke(result)).intValue();
            } catch (Throwable t) {
                exitCode = 0;
            }
        } catch (InvocationTargetException ite) {
            String cause = causeChain(ite);
            say("kotlinc error:\n" + cause);
            throw new RuntimeException("kotlinc: " + cause, ite);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }

        String output = outBuf.toString();
        if (exitCode != 0) {
            say(output);
            throw new RuntimeException("Kotlin compile failed (exit " + exitCode + "):\n" + output);
        }

        List<File> produced = new ArrayList<File>();
        findClassFiles(classesDir, produced);
        if (produced.isEmpty()) {
            say(output);
            throw new RuntimeException("kotlinc produced no .class files");
        }
        say("Kotlin compiled: " + produced.size() + " classes");
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

    private void findClassFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findClassFiles(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        while (t != null && d < 10) {
            sb.append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage()).append('\n');
            Throwable next = (t instanceof InvocationTargetException)
                ? ((InvocationTargetException) t).getTargetException()
                : t.getCause();
            if (next == t) break;
            t = next;
            d++;
        }
        return sb.toString();
    }
}
