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

public class RemotePythonRunner {

    public interface Progress {
        void onProgress(String message);
    }

    public interface Callback {
        void onResult(int exitCode, String output, String error);
    }

    private static final String WORKFLOW_FILE = "python-run.yml";
    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final Progress progress;

    public RemotePythonRunner(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("compile_repo", "fahim-git-00/MyIDE");
        return r == null || r.isEmpty() ? "fahim-git-00/MyIDE" : r;
    }

    private String token() {
        SharedPreferences p = ctx.getSharedPreferences("github", Context.MODE_PRIVATE);
        return p.getString("token", "");
    }

    public boolean hasToken() {
        String t = token();
        return t != null && t.length() > 0;
    }

    public void run(String scriptName, String code, String stdin, Callback cb) {
        try {
            say("run() start: " + scriptName);
            say("token present: " + hasToken());
            say("repo: " + repo());
            if (!hasToken()) throw new RuntimeException(
                    "GitHub token not set. Open menu -> GitHub Token.");

            String payload = buildSingleFilePayload(scriptName, code);
            say("Packaged " + scriptName + ", " + payload.length() + " chars");

            say("Triggering remote Python run...");
            long runId = triggerWorkflow(payload, stdin, scriptName);

            say("Waiting for run " + runId + "...");
            waitForRun(runId);

            say("Downloading output...");
            String result = downloadAndRead(runId);

            int exit = parseExitCode(result);
            String body = stripMarkers(result);

            if (cb != null) cb.onResult(exit, body, exit == 0 ? null : body);
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String trace = t.getClass().getName() + ": " + t.getMessage() + "\n" + sw.toString();
            say("ERROR: " + trace);
            if (cb != null) cb.onResult(-1, null, trace);
        }
    }

    public void runProject(File projectRoot, String entryName, String stdin, Callback cb) {
        try {
            if (!hasToken()) throw new RuntimeException(
                    "GitHub token not set. Open menu -> GitHub Token.");

            List<File> pyFiles = new ArrayList<File>();
            findPyFiles(projectRoot, pyFiles);
            if (pyFiles.isEmpty()) throw new RuntimeException("No .py files found");

            String payload = buildMultiFilePayload(projectRoot, pyFiles);
            say("Packaged " + pyFiles.size() + " file(s), " + payload.length() + " chars");

            say("Triggering remote Python run...");
            long runId = triggerWorkflow(payload, stdin, entryName);

            say("Waiting for run " + runId + "...");
            waitForRun(runId);

            say("Downloading output...");
            String result = downloadAndRead(runId);

            int exit = parseExitCode(result);
            String body = stripMarkers(result);

            if (cb != null) cb.onResult(exit, body, exit == 0 ? null : body);
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String trace = t.getClass().getName() + ": " + t.getMessage() + "\n" + sw.toString();
            say("ERROR: " + trace);
            if (cb != null) cb.onResult(-1, null, trace);
        }
    }

    private String buildSingleFilePayload(String name, String code) throws Exception {
        String b64 = android.util.Base64.encodeToString(
                code.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return name + "|" + b64;
    }

    private String buildMultiFilePayload(File root, List<File> files) throws Exception {
        StringBuilder sb = new StringBuilder();
        String rootAbs = root.getAbsolutePath();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append('~');
            String rel = f.getAbsolutePath().substring(rootAbs.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            sb.append(rel).append('|');
            sb.append(android.util.Base64.encodeToString(
                    readAll(f), android.util.Base64.NO_WRAP));
        }
        return sb.toString();
    }

    private void findPyFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals(".git") || n.equals("build") || n.equals("__pycache__")) continue;
                findPyFiles(f, out);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".py") || n.equals("requirements.txt")) out.add(f);
            }
        }
    }

    private byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private long triggerWorkflow(String payload, String stdin, String scriptName) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/"
                + WORKFLOW_FILE + "/dispatches");
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
        if (chunks > 10) throw new RuntimeException("Payload too large: " + chunks + " chunks (max 10)");
        inputs.put("chunk_count", String.valueOf(chunks));
        for (int i = 0; i < chunks; i++) {
            int s = i * chunkSize;
            int e = Math.min(payload.length(), s + chunkSize);
            inputs.put("chunk_" + i, payload.substring(s, e));
        }
        inputs.put("stdin", stdin == null ? "" : stdin);
        inputs.put("script_name", scriptName == null ? "main.py" : scriptName);
        body.put("inputs", inputs);

        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = c.getResponseCode();
        if (code != 204 && code != 200 && code != 201) {
            throw new RuntimeException("Trigger failed: HTTP " + code + "\n" + readError(c));
        }

        say("Triggered, waiting for run to appear...");
        Thread.sleep(4000);

        long id = findRecentRun();
        if (id <= 0) throw new RuntimeException("Could not find new run");
        return id;
    }

    private long findRecentRun() throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/workflows/"
                + WORKFLOW_FILE + "/runs?per_page=1");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray runs = j.optJSONArray("workflow_runs");
        if (runs == null || runs.length() == 0) return -1;
        return runs.getJSONObject(0).getLong("id");
    }

    private void waitForRun(long runId) throws Exception {
        long start = System.currentTimeMillis();
        long timeoutMs = 10 * 60 * 1000;
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
                if ("failure".equals(conclusion) || "cancelled".equals(conclusion)) {
                    say("Remote run " + conclusion + " - trying to fetch output anyway");
                    return;
                }
                throw new RuntimeException("Remote run failed: " + conclusion);
            }
            say("Remote: " + status + "...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Remote run timed out");
    }

    private String downloadAndRead(long runId) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId + "/artifacts");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray arr = j.optJSONArray("artifacts");
        if (arr == null || arr.length() == 0) throw new RuntimeException("No artifacts");

        long artifactId = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            if ("python-output".equals(a.optString("name"))) {
                artifactId = a.getLong("id");
                break;
            }
        }
        if (artifactId < 0) throw new RuntimeException("python-output artifact not found");

        URL dl = new URL(API + "/repos/" + repo() + "/actions/artifacts/" + artifactId + "/zip");
        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
        dc.setRequestProperty("Authorization", "token " + token());
        dc.setRequestProperty("Accept", "application/vnd.github+json");
        dc.setInstanceFollowRedirects(true);

        File zipFile = new File(ctx.getCacheDir(), "python-out-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(zipFile);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();

        return readResultFromZip(zipFile);
    }

    private String readResultFromZip(File zip) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            if (e.getName().endsWith("result.txt")) {
                int n;
                while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                break;
            }
        }
        zin.close();
        return out.toString("UTF-8");
    }

    private int parseExitCode(String result) {
        try {
            int i = result.indexOf("=== EXIT ");
            if (i < 0) return 0;
            int s = i + "=== EXIT ".length();
            int e = result.indexOf(" ===", s);
            if (e < 0) return 0;
            return Integer.parseInt(result.substring(s, e).trim());
        } catch (Throwable t) { return 0; }
    }

    private String stripMarkers(String result) {
        int s = result.indexOf("===\n");
        if (s < 0) return result;
        s += 4;
        int e = result.indexOf("\n=== END ===");
        if (e < 0) e = result.length();
        return result.substring(s, e);
    }

    private String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private String readError(HttpURLConnection c) {
        try { return readAll(c); } catch (Exception e) { return ""; }
    }
}
