package com.fahim.myide;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

public final class JsRunner {

    public interface Callback {
        void onResult(String output);
        void onError(String error);
    }

    private JsRunner() {}

    private static String LOAD_ERROR = null;
    static {
        try {
            System.loadLibrary("quickjs");
        } catch (Throwable t) {
            LOAD_ERROR = t.toString();
        }
    }
    public static String getLoadError() { return LOAD_ERROR; }

    /** Native method — implemented in quickjs_jni.c */
    public static native String runScript(String code, String name);

    /** Reads a .js file and runs it. */
    public static void runFile(File jsFile, Callback cb) {
        try {
            String code = readFile(jsFile);
            String out = runScript(code, jsFile.getName());
            if (cb != null) cb.onResult(out == null ? "" : out);
        } catch (Throwable t) {
            if (cb != null) cb.onError(t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    public static void runSource(String code, String name, Callback cb) {
        if (LOAD_ERROR != null) {
            if (cb != null) cb.onError("quickjs load failed: " + LOAD_ERROR);
            return;
        }
        try {
            String out = runScript(code, name == null ? "script.js" : name);
            if (cb != null) cb.onResult(out == null ? "" : out);
        } catch (Throwable t) {
            if (cb != null) cb.onError(t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    /** True when the native lib is loadable. */
    public static boolean isAvailable() {
        try {
            runScript("1", "test");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
