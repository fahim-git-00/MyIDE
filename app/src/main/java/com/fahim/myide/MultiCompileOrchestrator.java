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
        // Everything (Groovy + Java + Scala/Kotlin if present) in one remote job.
        new RemoteMultiCompiler(ctx, new RemoteMultiCompiler.Progress() {
            @Override public void onProgress(String m) { say(m); }
        }).compileAll(projectRoot, classesDir);
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
