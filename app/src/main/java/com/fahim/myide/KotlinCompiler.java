package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    /** Extracts assets/kotlin/kotlin-compiler-embeddable.jar to filesDir once. */
    public static File ensureCompilerJar(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), "kotlin/kotlin-compiler-embeddable.jar");
        File dir = out.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        if (out.isFile() && out.length() > 10_000_000L) return out;

        InputStream in = ctx.getAssets().open("kotlin/kotlin-compiler-embeddable.jar");
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        return out;
    }

    /** Extracts assets/kotlin/kotlin-stdlib.jar to filesDir once. */
    public static File ensureStdlibJar(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), "kotlin/kotlin-stdlib.jar");
        File dir = out.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        if (out.isFile() && out.length() > 100_000L) return out;

        InputStream in = ctx.getAssets().open("kotlin/kotlin-stdlib.jar");
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        return out;
    }

    public void compile(File compilerJar,
                        File stdlibJar,
                        File androidJar,
                        List<File> sourceRoots,
                        File genDir,
                        File classesDir,
                        List<File> extraJars) throws Exception {

        say("Loading kotlinc (embeddable)...");

        DexClassLoader loader = new DexClassLoader(
                compilerJar.getAbsolutePath(),
                ctx.getCacheDir().getAbsolutePath(),
                null,
                ctx.getClassLoader());

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

        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        if (stdlibJar != null && stdlibJar.exists()) {
            cp.append(File.pathSeparator).append(stdlibJar.getAbsolutePath());
        }
        for (File j : extraJars) {
            if (j != null && j.exists()) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        }

        List<String> args = new ArrayList<String>();
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
