package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RemoteNativeFetcher {

    public interface Progress { void onProgress(String msg); }

    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final Progress progress;

    public RemoteNativeFetcher(Context ctx, Progress p) { this.ctx = ctx; this.progress = p; }

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

    public void fetch(String workflowFile, String artifactName, File destProjectRoot) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set");

        String lang = workflowFile.replace("-build.yml", "");
        say("Triggering " + workflowFile + " ...");
        long runId = trigger(workflowFile);
        say("Waiting for run " + runId + " ...");
        waitForRun(workflowFile, runId);

        say("Downloading artifact " + artifactName + " ...");
        File zip = downloadArtifact(workflowFile, runId, artifactName);

        say("Extracting .so files ...");
        File jniLibs = new File(destProjectRoot, "app/src/main/jniLibs");
        File fallback = new File(destProjectRoot, "libs");
        int n = extractSo(zip, jniLibs);
        if (n == 0) n = extractSo(zip, fallback);
        say("Installed " + n + " .so file(s)");

        // Save to cache (language-scoped, hash of workflow only)
        String hash = workflowFile;
        File cacheOut = CompileCache.outDir(ctx, lang, hash);
        File dst = n > 0 ? new File(jniLibs, "arm64-v8a") : new File(fallback, "arm64-v8a");
        int saved = CompileCache.copyDirContents(dst, cacheOut);
        CompileCache.markDone(ctx, lang, hash);
        say("Cached " + saved + " .so under " + lang + "/");
    }

    /** Restore cached .so for a language (used by ApkBuilder before fetch). */
    public static boolean restoreCached(Context ctx, String workflowFile, File destProjectRoot) {
        String lang = workflowFile.replace("-build.yml", "");
        String hash = workflowFile;
        if (!CompileCache.isHit(ctx, lang, hash)) return false;

        File cacheOut = CompileCache.outDir(ctx, lang, hash);
        File jniLibs = new File(destProjectRoot, "app/src/main/jniLibs/arm64-v8a");
        File fallback = new File(destProjectRoot, "libs/arm64-v8a");
        int n = CompileCache.restoreTo(cacheOut, jniLibs);
        if (n == 0) n = CompileCache.restoreTo(cacheOut, fallback);
        return n > 0;
    }

    private long trigger(String workflowFile) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + workflowFile + "/dispatches");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("ref", "main");
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();

        int code = c.getResponseCode();
        if (code != 204 && code != 200 && code != 201) {
            throw new RuntimeException("Trigger failed: HTTP " + code + "\n" + readError(c));
        }
        Thread.sleep(4000);
        long id = findRecentRun(workflowFile);
        if (id <= 0) throw new RuntimeException("Could not find new run");
        return id;
    }

    private long findRecentRun(String workflowFile) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + workflowFile + "/runs?per_page=1");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray runs = j.optJSONArray("workflow_runs");
        if (runs == null || runs.length() == 0) return -1;
        return runs.getJSONObject(0).getLong("id");
    }

    private void waitForRun(String workflowFile, long runId) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 20 * 60 * 1000) {
            URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "token " + token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            JSONObject j = new JSONObject(readAll(c));
            String status = j.optString("status", "");
            if ("completed".equals(status)) {
                String concl = j.optString("conclusion", "");
                if ("success".equals(concl)) return;
                throw new RuntimeException("Workflow failed: " + concl);
            }
            say("Remote: " + status + " ...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Workflow timed out");
    }

    private File downloadArtifact(String workflowFile, long runId, String name) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId + "/artifacts");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray arr = j.optJSONArray("artifacts");
        long aid = -1;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            if (name.equals(a.optString("name"))) { aid = a.getLong("id"); break; }
        }
        if (aid < 0) throw new RuntimeException(name + " not found in artifacts");

        URL dl = new URL(API + "/repos/" + repo() + "/actions/artifacts/" + aid + "/zip");
        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
        dc.setRequestProperty("Authorization", "token " + token());
        dc.setRequestProperty("Accept", "application/vnd.github+json");
        dc.setInstanceFollowRedirects(true);

        File out = new File(ctx.getCacheDir(), name + "-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); in.close();
        return out;
    }

    private int extractSo(File zip, File jniLibsRoot) throws Exception {
        File dstDir = new File(jniLibsRoot, "arm64-v8a");
        if (!dstDir.exists()) dstDir.mkdirs();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e; byte[] buf = new byte[8192];
        int count = 0;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.endsWith("/") || !name.endsWith(".so")) continue;
            String base = new File(name).getName();
            File out = new File(dstDir, base);
            FileOutputStream fos = new FileOutputStream(out);
            int n; while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            say("  " + base);
            count++;
        }
        zin.close();
        return count;
    }

    private String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
    private String readError(HttpURLConnection c) {
        try { return readAll(c); } catch (Exception e) { return ""; }
    }
}
