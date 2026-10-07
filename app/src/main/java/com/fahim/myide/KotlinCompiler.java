package com.fahim.myide;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * Local Kotlin compilation is not available on unrooted Android.
 * Use Kotlin mode = Remote (GitHub Actions).
 */
public class KotlinCompiler {

    public interface Progress { void onProgress(String message); }

    private final Context ctx;
    private final Progress progress;

    public KotlinCompiler(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    public void compile(List<File> sourceRoots,
                        File classesDir,
                        File androidJar) throws Exception {
        if (progress != null) {
            progress.onProgress("Local Kotlin compile is disabled on this build.");
            progress.onProgress("Switch to Kotlin mode = Remote (GitHub Actions).");
        }
        throw new RuntimeException(
                "Local Kotlin compile is not available. "
              + "Open Settings → Kotlin mode → Remote.");
    }
}
