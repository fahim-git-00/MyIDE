package com.fahim.myide;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SoPacker {

    private SoPacker() {}

    public static void pack(File projectRoot, File inApk, File outApk) throws Exception {
        File libs = new File(projectRoot, "libs");
        List<File> soFiles = new ArrayList<File>();
        List<String> entryNames = new ArrayList<String>();

        if (libs.isDirectory()) {
            File[] abis = libs.listFiles();
            if (abis != null) for (File abi : abis) {
                if (!abi.isDirectory()) continue;
                File[] sos = abi.listFiles();
                if (sos == null) continue;
                for (File so : sos) {
                    if (!so.isFile() || !so.getName().endsWith(".so")) continue;
                    soFiles.add(so);
                    entryNames.add("lib/" + abi.getName() + "/" + so.getName());
                }
            }
        }

        File jniLibs = new File(projectRoot, "app/src/main/jniLibs");
        if (jniLibs.isDirectory()) {
            File[] abis = jniLibs.listFiles();
            if (abis != null) for (File abi : abis) {
                if (!abi.isDirectory()) continue;
                File[] sos = abi.listFiles();
                if (sos == null) continue;
                for (File so : sos) {
                    if (!so.isFile() || !so.getName().endsWith(".so")) continue;
                    soFiles.add(so);
                    entryNames.add("lib/" + abi.getName() + "/" + so.getName());
                }
            }
        }

        if (soFiles.isEmpty()) {
            if (inApk.equals(outApk)) return;
            copyFile(inApk, outApk);
            return;
        }

        // Deduplicate: keep last occurrence per entry name
        java.util.Map<String, File> map = new java.util.LinkedHashMap<String, File>();
        for (int i = 0; i < soFiles.size(); i++) {
            map.put(entryNames.get(i), soFiles.get(i));
        }
        soFiles.clear(); entryNames.clear();
        for (java.util.Map.Entry<String, File> e : map.entrySet()) {
            entryNames.add(e.getKey());
            soFiles.add(e.getValue());
        }

        ZipInputStream zin = new ZipInputStream(new FileInputStream(inApk));
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outApk));
        byte[] buf = new byte[8192];
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("lib/") && name.endsWith(".so")) continue;
            zout.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
            zout.closeEntry();
        }
        zin.close();

        for (int i = 0; i < soFiles.size(); i++) {
            File so = soFiles.get(i);
            zout.putNextEntry(new ZipEntry(entryNames.get(i)));
            FileInputStream fin = new FileInputStream(so);
            int n;
            while ((n = fin.read(buf)) > 0) zout.write(buf, 0, n);
            fin.close();
            zout.closeEntry();
        }
        zout.close();
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
