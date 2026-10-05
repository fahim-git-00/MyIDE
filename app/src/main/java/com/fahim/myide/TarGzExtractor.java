package com.fahim.myide;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Minimal tar.gz extractor. Handles USTAR + GNU longname/longlink + symlinks.
 */
public final class TarGzExtractor {

    private TarGzExtractor() {}

    public interface Progress { void onProgress(String path); }

    public static void extract(File targz, File dest, Progress cb) throws IOException {
        if (!dest.exists()) dest.mkdirs();
        String destCanon = dest.getCanonicalPath();

        InputStream fin = new FileInputStream(targz);
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(fin, 65536));
        BufferedInputStream in = new BufferedInputStream(gz, 65536);

        byte[] header = new byte[512];
        byte[] buf = new byte[32768];
        String longName = null;
        String longLink = null;

        while (true) {
            int read = readFully(in, header, 512);
            if (read < 512) break;

            boolean zero = true;
            for (int i = 0; i < 512; i++) if (header[i] != 0) { zero = false; break; }
            if (zero) break;

            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;

            long size = readOctal(header, 124, 12);
            char type = (char) header[156];
            String linkName = readString(header, 157, 100);

            if (type == 'L') { // GNU long name
                longName = readStringBytes(in, (int) size);
                continue;
            }
            if (type == 'K') { // GNU long link
                longLink = readStringBytes(in, (int) size);
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { linkName = longLink; longLink = null; }

            File out = new File(dest, name);
            String outCanon = out.getCanonicalPath();
            if (!outCanon.startsWith(destCanon + File.separator) && !outCanon.equals(destCanon)) {
                skip(in, size);
                continue;
            }

            switch (type) {
                case '5': // directory
                    out.mkdirs();
                    break;
                case '2': // symlink
                    out.getParentFile().mkdirs();
                    try {
                        if (out.exists()) out.delete();
                        Runtime.getRuntime().exec(new String[]{
                                "ln", "-sf", linkName, out.getAbsolutePath()
                        }).waitFor();
                    } catch (Exception e) {
                        // fallback: write a plain file with link target
                        FileOutputStream fos = new FileOutputStream(out);
                        fos.write(linkName.getBytes("UTF-8"));
                        fos.close();
                    }
                    break;
                case '0': case '\0': case '7': // regular
                    out.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(out);
                    long remaining = size;
                    while (remaining > 0) {
                        int want = (int) Math.min(buf.length, remaining);
                        int got = in.read(buf, 0, want);
                        if (got <= 0) break;
                        fos.write(buf, 0, got);
                        remaining -= got;
                    }
                    fos.close();
                    // preserve executable bit
                    if ((readOctal(header, 100, 8) & 0100) != 0) out.setExecutable(true, false);
                    if (cb != null) cb.onProgress(name);
                    break;
                default:
                    skip(in, size);
                    break;
            }

            long pad = (512 - (size % 512)) % 512;
            skip(in, pad);
        }
        in.close();
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static String readString(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readStringBytes(InputStream in, int size) throws IOException {
        byte[] b = new byte[size];
        readFully(in, b, size);
        int end = size;
        while (end > 0 && b[end - 1] == 0) end--;
        int pad = (512 - (size % 512)) % 512;
        if (pad > 0) skip(in, pad);
        return new String(b, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long readOctal(byte[] h, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xff;
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') continue;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            left -= s;
        }
    }
}
