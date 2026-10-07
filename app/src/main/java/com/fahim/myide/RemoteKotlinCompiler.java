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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RemoteKotlinCompiler {

    public interface Progress {
        void onProgress(String message);
    }

    private static final String WORKFLOW_FILE = "kotlin-compile.yml";
    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final Progress progress;

    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("compile_repo", "fahim-git-00/MyIDE");
        return r == null || r.isEmpty() ? "fahim-git-00/MyIDE" : r;
    }

    public RemoteKotlinCompiler(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        if (progress != null) progress.onProgress(s);
    }

    private String token() {
        SharedPreferences p = ctx.getSharedPreferences("github", Context.MODE_PRIVATE);
        return p.getString("token", "");
    }

    public void setToken(String t) {
        SharedPreferences p = ctx.getSharedPreferences("github", Context.MODE_PRIVATE);
        p.edit().putString("token", t.trim()).apply();
    }

    public boolean hasToken() {
        return token() != null && token().length() > 0;
    }

    public void compile(List<File> sourceRoots, File classesDir) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set. Open ⋮ → GitHub Token.");

        List<File> ktFiles = new ArrayList<File>();
        for (File src : sourceRoots) findKtFiles(src, ktFiles);
        if (ktFiles.isEmpty()) {
            say("No .kt files found");
            return;
        }

        // Compute relative path for each .kt file so package structure is preserved.
        String payload = buildPayload(sourceRoots, ktFiles);
        say("Packaged " + ktFiles.size() + " Kotlin file(s), " + payload.length() + " chars");

        say("Triggering remote build...");
        long runId = triggerWorkflow(payload);

        say("Waiting for run " + runId + "...");
        waitForRun(runId);

        say("Downloading classes...");
        File zip = downloadArtifact(runId);

        say("Extracting classes...");
        if (!classesDir.exists()) classesDir.mkdirs();
        unzipTo(zip, classesDir);

        say("Remote Kotlin compile done");
    }

    private String buildPayload(List<File> roots, List<File> files) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append('~');
            sb.append(relativePath(roots, f)).append('|');
            sb.append(android.util.Base64.encodeToString(readAll(f), android.util.Base64.NO_WRAP));
        }
        return sb.toString();
    }

    private String relativePath(List<File> roots, File f) {
        String abs = f.getAbsolutePath();
        for (File root : roots) {
            String r = root.getAbsolutePath();
            if (abs.startsWith(r)) {
                String rel = abs.substring(r.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return rel;
            }
        }
        return f.getName();
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

    private long triggerWorkflow(String payload) throws Exception {
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
        if (chunks > 10) throw new RuntimeException("Payload too large: " + chunks + " chunks (max 10)");
        inputs.put("chunk_count", String.valueOf(chunks));
        for (int i = 0; i < chunks; i++) {
            int s = i * chunkSize;
            int e = Math.min(payload.length(), s + chunkSize);
            inputs.put("chunk_" + i, payload.substring(s, e));
        }
        body.put("inputs", inputs);

        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
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
                String log = fetchRunLog(runId);
                throw new RuntimeException("Remote Kotlin build failed: " + conclusion
                        + "\n--- GitHub log ---\n" + log);
            }
            say("Remote: " + status + "...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Remote build timed out");
    }

    private File downloadArtifact(long runId) throws Exception {
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
            if ("kotlin-classes".equals(a.optString("name"))) {
                artifactId = a.getLong("id");
                break;
            }
        }
        if (artifactId < 0) throw new RuntimeException("kotlin-classes artifact not found");

        URL dl = new URL(API + "/repos/" + repo() + "/actions/artifacts/" + artifactId + "/zip");
        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
        dc.setRequestProperty("Authorization", "token " + token());
        dc.setRequestProperty("Accept", "application/vnd.github+json");
        dc.setInstanceFollowRedirects(true);

        File out = new File(ctx.getCacheDir(), "kotlin-classes-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        return out;
    }

    private String fetchRunLog(long runId) {
        try {
            URL url = new URL(API + "/repos/" + repo()
                    + "/actions/runs/" + runId + "/jobs");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "token " + token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            JSONObject j = new JSONObject(readAll(c));
            JSONArray jobs = j.optJSONArray("jobs");
            if (jobs == null || jobs.length() == 0) return "(no jobs)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                long jobId = job.getLong("id");
                sb.append(fetchJobLog(jobId));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(log fetch failed: " + t.getMessage() + ")";
        }
    }

    private String fetchJobLog(long jobId) {
        try {
            URL url = new URL(API + "/repos/" + repo()
                    + "/actions/jobs/" + jobId + "/logs");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "token " + token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 400) return "(job log HTTP " + code + ")";
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "(job log failed: " + t.getMessage() + ")";
        }
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

    private void findKtFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findKtFiles(f, out);
            else if (f.getName().endsWith(".kt")) out.add(f);
        }
    }

    private void unzipTo(File zip, File destDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("kotlin-classes/")) {
                name = name.substring("kotlin-classes/".length());
            }
            if (name.isEmpty()) continue;
            File out = new File(destDir, name);
            if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath())) continue;
            if (e.isDirectory()) out.mkdirs();
            else {
                File p = out.getParentFile();
                if (p != null) p.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
            }
            zin.closeEntry();
        }
        zin.close();
    }
}
