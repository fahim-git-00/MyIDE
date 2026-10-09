package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Triggers all applicable remote compilers in PARALLEL threads.
 * Each thread hits its own GitHub Actions workflow. All must complete
 * before returning.
 */
public class MultiCompileOrchestrator {

    public interface Progress { void onProgress(String message); }

    private final Context ctx;
    private final Progress progress;

    public MultiCompileOrchestrator(Context ctx, Progress p) {
        this.ctx = ctx;
        this.progress = p;
    }

    private void say(String s) { if (progress != null) progress.onProgress(s); }

    public void compileAll(File projectRoot, File classesDir, File libsDir) throws Exception {
        List<Callable<Void>> jobs = new ArrayList<Callable<Void>>();

        // C/C++
        if (RemoteCCompiler.hasNativeSources(projectRoot)) {
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[C/C++] remote compile");
                    new RemoteCCompiler(ctx, new RemoteCCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[C/C++] " + m); }
                    }).compile(projectRoot);
                    return null;
                }
            });
        }

        // Rust
        if (RemoteRustCompiler.hasRustSources(projectRoot)) {
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[Rust] remote compile");
                    new RemoteRustCompiler(ctx, new RemoteRustCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[Rust] " + m); }
                    }).compile(projectRoot);
                    return null;
                }
            });
        }

        // Kotlin
        boolean hasKt = false;
        for (File src : listSourceRoots(projectRoot))
            if (hasExt(src, ".kt")) { hasKt = true; break; }
        if (hasKt) {
            final List<File> roots = listSourceRoots(projectRoot);
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[Kotlin] remote compile");
                    new RemoteKotlinCompiler(ctx, new RemoteKotlinCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[Kotlin] " + m); }
                    }).compile(roots, classesDir);
                    return null;
                }
            });
        }

        // Scala
        boolean hasScala = false;
        for (File src : listSourceRoots(projectRoot))
            if (hasExt(src, ".scala")) { hasScala = true; break; }
        if (hasScala) {
            final List<File> roots = listSourceRoots(projectRoot);
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[Scala] remote compile");
                    new RemoteScalaCompiler(ctx, new RemoteScalaCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[Scala] " + m); }
                    }).compile(roots, classesDir);
                    return null;
                }
            });
        }

        // Groovy
        boolean hasGroovy = false;
        for (File src : listSourceRoots(projectRoot))
            if (hasExt(src, ".groovy")) { hasGroovy = true; break; }
        if (hasGroovy) {
            final List<File> roots = listSourceRoots(projectRoot);
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[Groovy] remote compile");
                    new RemoteGroovyCompiler(ctx, new RemoteGroovyCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[Groovy] " + m); }
                    }).compile(roots, classesDir);
                    return null;
                }
            });
        }

        // Java remote — only when java_mode=remote
        String javaMode = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("java_mode", "local");
        if ("remote".equals(javaMode)) {
            final List<File> roots = listSourceRoots(projectRoot);
            final File aj = new File(ctx.getFilesDir(), "android.jar");
            jobs.add(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    say("[Java 21] remote compile");
                    new RemoteJavaCompiler(ctx, new RemoteJavaCompiler.Progress() {
                        @Override public void onProgress(String m) { say("[Java 21] " + m); }
                    }).compile(roots, classesDir, aj);
                    return null;
                }
            });
        }

        if (jobs.isEmpty()) {
            say("No remote jobs to run");
            return;
        }

        say("Running " + jobs.size() + " remote jobs in parallel...");
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (Callable<Void> j : jobs) futures.add(pool.submit(j));

        pool.shutdown();
        boolean failed = false;
        Exception firstError = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (Exception e) {
                failed = true;
                if (firstError == null) firstError = e;
                say("Job " + i + " failed: " + e.getMessage());
            }
        }

        say("All " + jobs.size() + " remote jobs finished");
        if (failed && firstError != null) throw firstError;
    }

    private List<File> listSourceRoots(File projectRoot) {
        List<File> out = new ArrayList<File>();
        File src = new File(projectRoot, "src");
        if (src.isDirectory()) out.add(src);
        File appSrc = new File(projectRoot, "app/src/main/java");
        if (appSrc.isDirectory()) out.add(appSrc);
        return out;
    }

    private boolean hasExt(File dir, String ext) {
        if (dir == null || !dir.exists()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals("build") || n.equals(".git") || n.equals(".myide")) continue;
                if (hasExt(f, ext)) return true;
            } else if (f.getName().endsWith(ext)) return true;
        }
        return false;
    }
}
