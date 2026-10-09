package com.fahim.myide;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Application entry point.
 * Holds global singletons and preferences bootstrap.
 */
public class MyIdeApp extends Application {

    private static MyIdeApp instance;

    public static MyIdeApp get() {
        return instance;
    }

    public static Context ctx() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // First-run defaults
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        if (!prefs.getBoolean("_init", false)) {
            prefs.edit()
                .putBoolean("_init", true)
                .putInt("font_size", 13)
                .putInt("tab_size", 4)
                .putBoolean("word_wrap", false)
                .putBoolean("line_numbers", true)
                .putBoolean("auto_close", true)
                .putBoolean("auto_indent", true)
                .putBoolean("syntax_highlight", true)
                .putInt("min_sdk", 24)
                .putInt("target_sdk", 35)
                .putString("kotlin_mode", "remote")
                .putString("java_mode", "remote")
                .putInt("theme_mode", 0)
                .apply();
        }

        // Install a handler that logs uncaught exceptions to disk for debugging.
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.File dir = new java.io.File(getFilesDir(), "crash");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir,
                        "crash-" + System.currentTimeMillis() + ".txt");
                    java.io.PrintWriter pw = new java.io.PrintWriter(f);
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.close();
                } catch (Throwable ignored) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }
}
