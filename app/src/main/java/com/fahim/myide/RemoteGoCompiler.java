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

public class RemoteGoCompiler {
    public interface Progress { void onProgress(String message); }
    private static final String WORKFLOW_FILE = "go-compile.yml";
    private static final String API = "https://api.github.com";
    private final Context ctx; private final Progress progress;

    public RemoteGoCompiler(Context ctx, Progress progress) { this.ctx = ctx; this.progress = progress; }
    private void say(String s) { if (progress != null) progress.onProgress(s); }
    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("compile_repo", "fahim-git-00/MyIDE");
        return r == null || r.isEmpty() ? "fahim-git-00/MyIDE" : r;
    }
    private String token() {
        return ctx.getSharedPreferences("github", Context.MODE_PRIVATE).getString("token", "");
    }
    public boolean hasToken() { String t = token(); return t != null && t.length() > 0; }

    public static boolean hasGoSources(File projectRoot) {
        File jni = new File(projectRoot, "jni");
        if (!jni.isDirectory()) return false;
        return hasGo(jni);
    }
    private static boolean hasGo(File dir) {
        File[] kids = dir.listFiles(); if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) { if (hasGo(f)) return true; }
            else {
                String n = f.getName();
                if (n.endsWith(".go") || n.equals("go.mod")) return true;
            }
        }
        return false;
    }

    public void compile(File projectRoot) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set");
        File jni = new File(projectRoot, "jni");
        if (!hasGo(jni)) { say("No Go sources"); return; }

        File libs = new File(projectRoot, "libs/arm64-v8a");
        if (!libs.exists()) libs.mkdirs();

        List<File> files = new ArrayList<File>();
        collect(jni, files);
        say("Packaging " + files.size() + " Go file(s)...");

        String payload = buildPayload(jni, files);
        String modName = "native";
        long runId = trigger(payload, modName);
        say("Waiting for run " + runId + "...");
        waitForRun(runId);

        say("Downloading libs...");
        File zip = downloadArtifact(runId, "go-libs");
        unzipLibs(zip, libs);
        say("Go compile done");
    }

    private void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles(); if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collect(f, out);
            else {
                String n = f.getName();
                if (n.endsWith(".go") || n.equals("go.mod") || n.equals("go.sum")) out.add(f);
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

    private long trigger(String payload, String modName) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches");
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
        inputs.put("module_name", modName);
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
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/" + WORKFLOW_FILE + "/runs?per_page=1");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray runs = j.optJSONArray("workflow_runs");
        if (runs == null || runs.length() == 0) return -1;
        return runs.getJSONObject(0).getLong("id");
    }
    private void waitForRun(long runId) throws Exception {
        long start = System.currentTimeMillis(), timeoutMs = 15 * 60 * 1000;
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
                throw new RuntimeException("Go build failed: " + conclusion);
            }
            say("Remote: " + status + "...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Go build timed out");
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
        File out = new File(ctx.getCacheDir(), "go-libs-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); in.close();
        return out;
    }
    private void unzipLibs(File zip, File libsDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e; byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.endsWith("/") || !name.endsWith(".so")) continue;
            String base = new File(name).getName();
            File out = new File(libsDir, base);
            FileOutputStream fos = new FileOutputStream(out);
            int n; while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            say("  saved " + base);
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
