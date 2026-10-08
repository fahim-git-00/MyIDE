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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RemoteCCompiler {

    public interface Progress { void onProgress(String message); }

    private static final String WORKFLOW_FILE = "c-compile.yml";
    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final Progress progress;

    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("compile_repo", "fahim-git-00/MyIDE");
        return r == null || r.isEmpty() ? "fahim-git-00/MyIDE" : r;
    }

    public RemoteCCompiler(Context ctx, Progress progress) {
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

    public boolean hasToken() {
        String t = token();
        return t != null && t.length() > 0;
    }

    /** Returns true if jni/ has any .c or .cpp files. */
    public static boolean hasNativeSources(File projectRoot) {
        File jni = new File(projectRoot, "jni");
        if (!jni.isDirectory()) return false;
        return findNativeFiles(jni).size() > 0;
    }

    private static List<File> findNativeFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            if (f.isDirectory()) out.addAll(findNativeFiles(f));
            else {
                String n = f.getName().toLowerCase();
                // Skip lua_jni.c — that's handled by RemoteLuaCompiler
                if (n.equals("lua_jni.c") || n.equals("luajit_jni.c")
                        || n.equals("quickjs_jni.c")) continue;
                if (n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".cc")
                        || n.endsWith(".h") || n.endsWith(".hpp")
                        || n.equals("android.mk") || n.equals("application.mk")
                        || n.equals("cmakelists.txt")) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Hash of all jni/ files — used for cache check. */
    private static String hashSources(File projectRoot) throws Exception {
        File jni = new File(projectRoot, "jni");
        List<File> files = findNativeFiles(jni);
        files.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        for (File f : files) {
            md.update(f.getName().getBytes("UTF-8"));
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private File cacheMarker(File projectRoot) {
        return new File(ctx.getFilesDir(), "c-cache/" + hashSourcesSafe(projectRoot) + ".ok");
    }

    private String hashSourcesSafe(File projectRoot) {
        try { return hashSources(projectRoot); }
        catch (Exception e) { return "err"; }
    }

    public void compile(File projectRoot) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set");
        if (!hasNativeSources(projectRoot)) {
            say("No jni/*.c or *.cpp — skipping C compile");
            return;
        }

        File libsDir = new File(projectRoot, "libs/arm64-v8a");
        if (!libsDir.exists()) libsDir.mkdirs();

        // Cache check
        String hash = hashSources(projectRoot);
        File marker = new File(ctx.getFilesDir(), "c-cache/" + hash + ".ok");
        if (marker.exists() && hasAnySo(libsDir)) {
            say("C sources unchanged — using cached .so");
            return;
        }

        List<File> sources = findNativeFiles(new File(projectRoot, "jni"));
        say("Packaging " + sources.size() + " C/C++ file(s)...");

        String payload = buildPayload(new File(projectRoot, "jni"), sources);
        say("Triggering remote C build...");
        long runId = triggerWorkflow(payload);

        say("Waiting for run " + runId + "...");
        waitForRun(runId);

        say("Downloading libs...");
        File zip = downloadArtifact(runId);

        say("Extracting...");
        unzipLibs(zip, libsDir);

        marker.getParentFile().mkdirs();
        marker.createNewFile();
        say("C compile done");
    }

    private boolean hasAnySo(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) if (f.getName().endsWith(".so")) return true;
        return false;
    }

    private String buildPayload(File root, List<File> files) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append('~');
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            sb.append(rel).append('|');
            sb.append(android.util.Base64.encodeToString(readAll(f), android.util.Base64.NO_WRAP));
        }
        return sb.toString();
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
        if (chunks > 10) throw new RuntimeException("Payload too large: " + chunks);
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
                throw new RuntimeException("Remote C build failed: " + conclusion
                        + "\n--- GitHub log ---\n" + log);
            }
            say("Remote: " + status + "...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Remote C build timed out");
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
            if ("c-libs".equals(a.optString("name"))) {
                artifactId = a.getLong("id");
                break;
            }
        }
        if (artifactId < 0) throw new RuntimeException("c-libs artifact not found");

        URL dl = new URL(API + "/repos/" + repo() + "/actions/artifacts/" + artifactId + "/zip");
        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
        dc.setRequestProperty("Authorization", "token " + token());
        dc.setRequestProperty("Accept", "application/vnd.github+json");
        dc.setInstanceFollowRedirects(true);

        File out = new File(ctx.getCacheDir(), "c-libs-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        return out;
    }

    private void unzipLibs(File zip, File libsDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.endsWith("/")) continue;
            // Keep only arm64-v8a .so files
            if (!name.endsWith(".so")) continue;
            if (!name.contains("arm64-v8a")) continue;

            String baseName = new File(name).getName();
            File out = new File(libsDir, baseName);
            FileOutputStream fos = new FileOutputStream(out);
            int n;
            while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            say("  saved " + baseName);
        }
        zin.close();
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
}
