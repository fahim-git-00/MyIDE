package com.fahim.myide;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

public final class LuaRunner {

    public interface Callback {
        void onResult(String output);
        void onError(String error);
    }

    private LuaRunner() {}

    private static String LOAD_ERROR = null;
    static {
        try {
            System.loadLibrary("lua");
        } catch (Throwable t) {
            LOAD_ERROR = t.toString();
        }
    }

    public static String getLoadError() { return LOAD_ERROR; }

    public static native String runScript(String code, String name);

    public static void runFile(File luaFile, Callback cb) {
        try {
            String code = readFile(luaFile);
            runSource(code, luaFile.getName(), cb);
        } catch (Throwable t) {
            if (cb != null) cb.onError(t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    public static void runSource(String code, String name, Callback cb) {
        if (LOAD_ERROR != null) {
            if (cb != null) cb.onError("lua load failed: " + LOAD_ERROR);
            return;
        }
        try {
            String out = runScript(code, name == null ? "script.lua" : name);
            if (cb != null) cb.onResult(out == null ? "" : out);
        } catch (Throwable t) {
            if (cb != null) cb.onError(t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
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
