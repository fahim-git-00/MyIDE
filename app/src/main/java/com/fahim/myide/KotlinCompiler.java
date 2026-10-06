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

        File dexDir = new File(ctx.getFilesDir(), "kotlin-dex");
        if (!dexDir.exists()) dexDir.mkdirs();

        // ---- 1) Materialise jars from assets ----
        // Loaded into DexClassLoader:  ONLY the embeddable compiler.
        // Used as -classpath for the compiled code:  stdlib.
        // Deliberately NOT loaded:  kotlin-reflect (duplicate of shaded copy),
        //                           kotlin-script-runtime (not needed).
        File embeddable = ensureAsset("kotlin/kotlin-compiler-embeddable.jar", dexDir);
        File stdlib     = ensureAsset("kotlin/kotlin-stdlib.jar", dexDir);

        say("  embeddable: " + embeddable.length() + " bytes");
        say("  stdlib:     " + stdlib.length() + " bytes");

        // ---- 2) Sources ----
        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) { say("No .kt files found"); return; }
        say("Compiling " + ktFiles.size() + " Kotlin file(s) in-process");

        if (!classesDir.exists()) classesDir.mkdirs();

        // ---- 3) DexClassLoader with ONLY the embeddable jar ----
        DexClassLoader loader;
        try {
            loader = new DexClassLoader(
                    embeddable.getAbsolutePath(),
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
                    "K2JVMCompiler not found in dexed embeddable jar. "
                    + "Is app/src/main/assets/kotlin/kotlin-compiler-embeddable.jar "
                    + "the dexed one produced by CI?", e);
        }

        Object compiler = compilerClass.getDeclaredConstructor().newInstance();

        // ---- 4) Arguments ----
        // -classpath goes to the code being compiled.
        // -no-stdlib stops kotlinc from auto-appending; we append manually.
        String cp = androidJar.getAbsolutePath()
                  + File.pathSeparator + stdlib.getAbsolutePath();

        List<String> args = new ArrayList<String>();
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-jvm-target"); args.add("1.8");
        args.add("-classpath"); args.add(cp);
        args.add("-d"); args.add(classesDir.getAbsolutePath());
        for (File kt : ktFiles) args.add(kt.getAbsolutePath());

        String[] argArr = args.toArray(new String[0]);

        // ---- 5) exec() — does not call System.exit ----
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(buf, true);

        Object exitObj = null;
        boolean usedExec = false;

        try {
            Method exec = compilerClass.getMethod(
                    "exec", PrintStream.class, String[].class);
            exitObj = exec.invoke(compiler, errStream, (Object) argArr);
            usedExec = true;
        } catch (NoSuchMethodException nsme) {
            say("K2JVMCompiler.exec() unavailable — falling back to main()");
        } catch (InvocationTargetException ite) {
            flush(buf, errStream);
            Throwable cause = ite.getTargetException();
            throw new RuntimeException("Kotlin exec() threw: " + cause, cause);
        }

        if (!usedExec) {
            Method main = compilerClass.getMethod("main", String[].class);
            try {
                main.invoke(compiler, (Object) argArr);
            } catch (InvocationTargetException ite) {
                flush(buf, errStream);
                Throwable cause = ite.getTargetException();
                throw new RuntimeException("Kotlin main() threw: " + cause, cause);
            }
        }

        errStream.flush();
        String out = buf.toString("UTF-8");
        if (!out.isEmpty()) for (String line : out.split("\n")) say(line);

        String exitStr = exitObj == null ? "" : exitObj.toString();
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

    private void flush(ByteArrayOutputStream buf, PrintStream ps) {
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

    private File ensureAsset(String assetPath, File dir) throws IOException {
        String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out;

        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
        return out;
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
