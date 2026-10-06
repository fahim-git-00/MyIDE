package com.fahim.myide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

public class KotlinCompiler {

    public interface Progress { void onProgress(String message); }

    private static final String[] JAR_NAMES = {
            "kotlin-compiler-embeddable.jar",
            "kotlin-stdlib.jar",
            "kotlin-reflect.jar",
            "kotlin-script-runtime.jar"
    };

    private final Context ctx;
    private final Progress progress;

    public KotlinCompiler(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    public void compile(List<File> sourceRoots,
                        File classesDir,
                        File androidJar) throws Exception {

        // ---- 1) Materialise dexed Kotlin jars from assets ----
        File dexDir = new File(ctx.getFilesDir(), "kotlin-dex");
        if (!dexDir.exists()) dexDir.mkdirs();

        List<File> jarFiles = new ArrayList<File>();
        for (String name : JAR_NAMES) {
            File f = new File(dexDir, name);
            if (!f.exists() || f.length() == 0) {
                try {
                    copyAsset("kotlin/" + name, f);
                } catch (IOException e) {
                    say("asset kotlin/" + name + " missing ("
                            + e.getMessage() + ")");
                    if ("kotlin-compiler-embeddable.jar".equals(name)
                            || "kotlin-stdlib.jar".equals(name)) {
                        throw new RuntimeException(
                                "Required Kotlin jar missing from assets: " + name
                                + ". Make sure app/src/main/assets/kotlin/ contains it "
                                + "and that CI dexes it.");
                    }
                    continue;
                }
            }
            jarFiles.add(f);
            say("  " + name + " (" + f.length() + " bytes)");
        }

        if (jarFiles.isEmpty()) {
            throw new RuntimeException("No Kotlin jars in assets/kotlin/. "
                    + "Switch Kotlin Mode to Remote in Settings.");
        }

        // ---- 2) Collect .kt sources ----
        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) {
            say("No .kt files found");
            return;
        }
        say("Compiling " + ktFiles.size() + " Kotlin file(s) in-process");

        // ---- 3) Classpath ----
        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        for (File f : jarFiles) {
            cp.append(File.pathSeparator).append(f.getAbsolutePath());
        }

        StringBuilder dexPath = new StringBuilder();
        for (File f : jarFiles) {
            if (dexPath.length() > 0) dexPath.append(File.pathSeparator);
            dexPath.append(f.getAbsolutePath());
        }

        if (!classesDir.exists()) classesDir.mkdirs();

        // ---- 4) Load compiler through DexClassLoader ----
        DexClassLoader loader;
        try {
            loader = new DexClassLoader(
                    dexPath.toString(),
                    ctx.getCacheDir().getAbsolutePath(),
                    null,
                    ctx.getClassLoader());
        } catch (Throwable t) {
            throw new RuntimeException("DexClassLoader failed: " + t.getMessage(), t);
        }

        Class<?> compilerClass;
        try {
            compilerClass = loader.loadClass(
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "K2JVMCompiler not found in dexed jars. "
                    + "kotlin-compiler-embeddable.jar may not be dexed or is corrupt.",
                    e);
        }

        Object compiler = compilerClass.getDeclaredConstructor().newInstance();

        // ---- 5) Args ----
        List<String> args = new ArrayList<String>();
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-jvm-target"); args.add("1.8");
        args.add("-classpath"); args.add(cp.toString());
        args.add("-d"); args.add(classesDir.getAbsolutePath());
        for (File kt : ktFiles) args.add(kt.getAbsolutePath());

        String[] argArr = args.toArray(new String[0]);

        // ---- 6) Prefer exec() — doesn't call System.exit ----
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(outBuf, true);

        Object exitCodeObj = null;
        boolean usedExec = false;

        try {
            Method exec = compilerClass.getMethod(
                    "exec", PrintStream.class, String[].class);
            exitCodeObj = exec.invoke(compiler, errStream, (Object) argArr);
            usedExec = true;
        } catch (NoSuchMethodException nsme) {
            say("K2JVMCompiler.exec() not available — falling back to main()");
        } catch (InvocationTargetException ite) {
            dumpCompilerOutput(outBuf, errStream);
            throw new RuntimeException("Kotlin exec() threw: "
                    + ite.getTargetException(), ite.getTargetException());
        }

        if (!usedExec) {
            Method main = compilerClass.getMethod("main", String[].class);
            try {
                main.invoke(compiler, (Object) argArr);
            } catch (InvocationTargetException ite) {
                dumpCompilerOutput(outBuf, errStream);
                throw new RuntimeException("Kotlin main() threw: "
                        + ite.getTargetException(), ite.getTargetException());
            }
        }

        errStream.flush();
        String compilerOut = outBuf.toString("UTF-8");
        if (!compilerOut.isEmpty()) {
            for (String line : compilerOut.split("\n")) say(line);
        }

        String exitStr = exitCodeObj == null ? "" : exitCodeObj.toString();
        if (exitStr.contains("COMPILATION_ERROR")
                || exitStr.contains("INTERNAL_ERROR")
                || exitStr.contains("SCRIPT_EXECUTION_ERROR")) {
            throw new RuntimeException("Kotlin compilation failed: " + exitStr);
        }

        int count = countClasses(classesDir);
        say("Kotlin done: " + count + " classes");
        if (count == 0) {
            throw new RuntimeException("Kotlin compiler produced no .class files. "
                    + "See compiler output above.");
        }
    }

    private void dumpCompilerOutput(ByteArrayOutputStream buf, PrintStream ps) {
        try {
            ps.flush();
            String s = buf.toString("UTF-8");
            if (!s.isEmpty()) for (String line : s.split("\n")) say(line);
        } catch (Throwable ignored) {}
    }

    private int countClasses(File dir) {
        if (dir == null || !dir.exists()) return 0;
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            if (f.isDirectory()) n += countClasses(f);
            else if (f.getName().endsWith(".class")) n++;
        }
        return n;
    }

    private void copyAsset(String assetPath, File out) throws IOException {
        File p = out.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
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
}
