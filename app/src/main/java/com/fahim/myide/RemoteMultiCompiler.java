package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RemoteMultiCompiler {

    public interface Progress { void onProgress(String message); }

    private static final String WORKFLOW = "multilang-build.yml";
    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final Progress progress;

    public RemoteMultiCompiler(Context ctx, Progress p) { this.ctx = ctx; this.progress = p; }

    private void say(String s) { if (progress != null) progress.onProgress(s); }
    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("compile_repo", "fahim-git-00/MyIDE");
        return (r == null || r.isEmpty()) ? "fahim-git-00/MyIDE" : r;
    }
    private String token() {
        return ctx.getSharedPreferences("github", Context.MODE_PRIVATE).getString("token", "");
    }
    public boolean hasToken() { String t = token(); return t != null && !t.isEmpty(); }

    /**
     * Compiles every remote-supported language in one GitHub Actions run.
     * Extracts combined output into classesDir (for .class + runtime jars)
     * and libsDir (for .so files).
     */
    public void compileAll(File projectRoot, File classesDir, File libsDir) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set");

        List<File> files = new ArrayList<File>();
        collectSources(projectRoot, files);
        if (files.isEmpty()) { say("No remote sources"); return; }

        String hash = CompileCache.hashFiles(files);
        say("Combined hash: " + hash.substring(0, 12) + "...");

        if (CompileCache.isHit(ctx, "multi", hash)) {
            say("Cache hit — reusing compiled output");
            File co = CompileCache.outDir(ctx, "multi", hash);
            int n = CompileCache.restoreTo(co, classesDir);
            say("Restored " + n + " class file(s)");
            File[] kids = co.listFiles();
            if (kids != null) for (File f : kids) {
                if (f.getName().endsWith(".so")) {
                    try { CompileCache.copyDirContents(co, libsDir); break; } catch (Throwable ignored) {}
                }
            }
            say("Multi compile done (cached)");
            return;
        }
        say("Cache miss — remote multi-compile");

        String payload = buildPayload(projectRoot, files);
        long runId = trigger(payload);
        say("Waiting for combined run " + runId + "...");
        waitForRun(runId);

        say("Downloading combined output...");
        File zip = downloadArtifact(runId, "combined-output");

        say("Extracting...");
        if (!classesDir.exists()) classesDir.mkdirs();
        if (!libsDir.exists()) libsDir.mkdirs();
        extractCombined(zip, classesDir, libsDir);

        File cacheOut = CompileCache.outDir(ctx, "multi", hash);
        CompileCache.copyDirContents(classesDir, cacheOut);
        CompileCache.copyDirContents(libsDir, cacheOut);
        CompileCache.markDone(ctx, "multi", hash);
        say("Multi compile done");
    }

    private void collectSources(File root, List<File> out) {
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals("build") || n.equals(".git") || n.equals(".myide")
                        || n.equals("libs") || n.equals("bin") || n.equals("obj")) continue;
                collectSources(f, out);
            } else {
                if (n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".scala")
                        || n.endsWith(".groovy") || n.endsWith(".c") || n.endsWith(".cpp")
                        || n.endsWith(".cc") || n.endsWith(".h") || n.endsWith(".hpp")
                        || n.endsWith(".rs") || n.equals("Android.mk") || n.equals("Application.mk")
                        || n.equals("Cargo.toml") || n.equals("Cargo.lock")) {
                    out.add(f);
                }
            }
        }
    }

    private String buildPayload(File root, List<File> files) throws Exception {
        StringBuilder sb = new StringBuilder();
        String rootAbs = root.getAbsolutePath();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append('~');
            String rel = f.getAbsolutePath().substring(rootAbs.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            sb.append(rel).append('|');
            sb.append(android.util.Base64.encodeToString(readAll(f), android.util.Base64.NO_WRAP));
        }
        return sb.toString();
    }

    private byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); return out.toByteArray();
    }

    private long trigger(String payload) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + WORKFLOW + "/dispatches");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("ref", "main");
        JSONObject inputs = new JSONObject();
        int chunkSize = 60000;
        int chunks = (payload.length() + chunkSize - 1) / chunkSize;
        if (chunks > 10) throw new RuntimeException("Payload too large: " + chunks);
        inputs.put("chunk_count", String.valueOf(chunks));
        for (int i = 0; i < chunks; i++) {
            int s = i * chunkSize, e = Math.min(payload.length(), s + chunkSize);
            inputs.put("chunk_" + i, payload.substring(s, e));
        }
        body.put("inputs", inputs);

        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        if (code != 204 && code != 200 && code != 201)
            throw new RuntimeException("Trigger failed: HTTP " + code + "\n" + readError(c));
        Thread.sleep(4000);
        long id = findRecentRun();
        if (id <= 0) throw new RuntimeException("Could not find new run");
        return id;
    }

    private long findRecentRun() throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + WORKFLOW + "/runs?per_page=1");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray runs = j.optJSONArray("workflow_runs");
        if (runs == null || runs.length() == 0) return -1;
        return runs.getJSONObject(0).getLong("id");
    }

    private void waitForRun(long runId) throws Exception {
        long start = System.currentTimeMillis(), timeoutMs = 20 * 60 * 1000;
        while (System.currentTimeMillis() - start < timeoutMs) {
            URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "token " + token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            JSONObject j = new JSONObject(readAll(c));
            String status = j.optString("status", "");
            String conclusion = j.optString("conclusion", "");
            if ("completed".equals(status)) {
                if ("success".equals(conclusion)) return;
                throw new RuntimeException("Multi build failed: " + conclusion);
            }
            say("Remote: " + status + "...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Multi build timed out");
    }

    private File downloadArtifact(long runId, String name) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId + "/artifacts");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray arr = j.optJSONArray("artifacts");
        if (arr == null) throw new RuntimeException("No artifacts");
        long aid = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            if (name.equals(a.optString("name"))) { aid = a.getLong("id"); break; }
        }
        if (aid < 0) throw new RuntimeException(name + " artifact not found");
        URL dl = new URL(API + "/repos/" + repo() + "/actions/artifacts/" + aid + "/zip");
        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
        dc.setRequestProperty("Authorization", "token " + token());
        dc.setRequestProperty("Accept", "application/vnd.github+json");
        dc.setInstanceFollowRedirects(true);
        File out = new File(ctx.getCacheDir(), "multi-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); in.close();
        return out;
    }

    private void extractCombined(File zip, File classesDir, File libsDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e; byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.endsWith("/")) continue;

            File dst;
            if (name.endsWith(".so")) {
                String base = new File(name).getName();
                dst = new File(libsDir, base);
                say("  native: " + base);
            } else {
                dst = new File(classesDir, name);
            }
            File p = dst.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream fos = new FileOutputStream(dst);
            int n; while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
        }
        zin.close();
    }

    private String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); return out.toString("UTF-8");
    }
    private String readError(HttpURLConnection c) {
        try { return readAll(c); } catch (Exception e) { return ""; }
    }
}
