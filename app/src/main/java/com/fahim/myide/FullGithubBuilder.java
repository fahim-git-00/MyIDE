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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Full build on GitHub:
 *  1. push project tree to <repo>/projects/<name>/
 *  2. trigger android-build.yml with project_path input
 *  3. download built APK
 *  4. save to /sdcard/MyIDE/builds/
 *
 * Uses the same GitHub token as the other remote compilers.
 */
public class FullGithubBuilder {

    public interface Progress { void onProgress(String message); }

    private static final String API = "https://api.github.com";
    private static final String WORKFLOW = "android-build.yml";

    private final Context ctx;
    private final Progress progress;

    public FullGithubBuilder(Context ctx, Progress p) { this.ctx = ctx; this.progress = p; }

    private void say(String s) { if (progress != null) progress.onProgress(s); }

    private String repo() {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String r = sp.getString("github_build_repo", "");
        if (r == null || r.isEmpty()) throw new RuntimeException(
                "Set 'GitHub Build Repo' in Settings first (owner/repo)");
        return r.trim();
    }

    private String token() {
        return ctx.getSharedPreferences("github", Context.MODE_PRIVATE).getString("token", "");
    }

    private boolean hasToken() { String t = token(); return t != null && !t.isEmpty(); }

    public void build(File projectRoot) throws Exception {
        if (!hasToken()) throw new RuntimeException("GitHub token not set. Menu -> GitHub Token.");

        String projectName = projectRoot.getName();

        // ---- 1. Ensure Gradle files exist ----
        ensureGradleFiles(projectRoot, projectName);

        // ---- 2. Push every file under projectRoot to repo/projects/<name>/ ----
        say("Pushing project to " + repo() + "/projects/" + projectName + "/ ...");
        List<File> files = new ArrayList<File>();
        collect(projectRoot, files);
        say("  " + files.size() + " file(s) to push");

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String rel = f.getAbsolutePath().substring(projectRoot.getAbsolutePath().length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            String path = "projects/" + projectName + "/" + rel;
            putFile(path, readAll(f), "push " + rel);
            if (i % 5 == 0) say("  pushed " + (i + 1) + "/" + files.size());
        }
        say("  push done");

        // ---- 3. Ensure workflow file exists ----
        ensureWorkflow();

        // ---- 4. Trigger workflow ----
        say("Triggering " + WORKFLOW + " ...");
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        long runId = triggerWorkflow(projectName, ts);
        say("Waiting for run " + runId + " ...");
        waitForRun(runId);

        // ---- 5. Download APK ----
        say("Downloading APK ...");
        File zip = downloadArtifact(runId, "myide-apk");
        File apk = extractApk(zip);

        // ---- 6. Save to /sdcard/MyIDE/builds/ ----
        File outDir = new File("/sdcard/MyIDE/builds");
        if (!outDir.exists()) outDir.mkdirs();
        File dest = new File(outDir, projectName + "-" + ts + ".apk");
        copyFile(apk, dest);
        say("APK saved: " + dest.getAbsolutePath());
    }

    // ============================================================
    // Gradle file scaffolding
    // ============================================================

    private void ensureGradleFiles(File root, String projectName) throws Exception {
        // Detect if this is already a real Gradle project
        if (new File(root, "settings.gradle").isFile()
                && new File(root, "app/build.gradle").isFile()) {
            return;
        }

        // Minimal wrapper project. Real files stay at root; sourceSets point there.
        writeIfMissing(new File(root, "settings.gradle"),
            "rootProject.name = '" + projectName + "'\n" +
            "include ':app'\n");

        writeIfMissing(new File(root, "build.gradle"),
            "buildscript {\n" +
            "    repositories { google(); mavenCentral() }\n" +
            "    dependencies {\n" +
            "        classpath 'com.android.tools.build:gradle:8.7.2'\n" +
            "    }\n" +
            "}\n" +
            "allprojects {\n" +
            "    repositories { google(); mavenCentral() }\n" +
            "}\n");

        writeIfMissing(new File(root, "gradle.properties"),
            "org.gradle.jvmargs=-Xmx2048m\n" +
            "android.useAndroidX=true\n" +
            "android.nonTransitiveRClass=true\n");

        // Read package from AndroidManifest.xml
        String pkg = readPackage(root);

        File appDir = new File(root, "app");
        appDir.mkdirs();

        writeIfMissing(new File(appDir, "build.gradle"),
            "apply plugin: 'com.android.application'\n\n" +
            "android {\n" +
            "    namespace '" + pkg + "'\n" +
            "    compileSdk 34\n\n" +
            "    defaultConfig {\n" +
            "        applicationId \"" + pkg + "\"\n" +
            "        minSdk 24\n" +
            "        targetSdk 34\n" +
            "        versionCode 1\n" +
            "        versionName \"1.0\"\n" +
            "    }\n\n" +
            "    sourceSets {\n" +
            "        main {\n" +
            "            manifest.srcFile '../AndroidManifest.xml'\n" +
            "            java.srcDirs = ['../src']\n" +
            "            res.srcDirs = ['../res']\n" +
            "            assets.srcDirs = ['../assets']\n" +
            "        }\n" +
            "    }\n\n" +
            "    compileOptions {\n" +
            "        sourceCompatibility JavaVersion.VERSION_17\n" +
            "        targetCompatibility JavaVersion.VERSION_17\n" +
            "    }\n" +
            "}\n\n" +
            "dependencies {\n" +
            "    implementation fileTree(dir: '../libs', include: ['*.jar'])\n" +
            "}\n");
    }

    private String readPackage(File root) {
        try {
            File mf = new File(root, "AndroidManifest.xml");
            if (!mf.isFile()) return "com.example.app";
            String xml = new String(readAll(mf), "UTF-8");
            int i = xml.indexOf("package=\"");
            if (i < 0) return "com.example.app";
            int s = i + 9;
            int e = xml.indexOf('"', s);
            if (e <= s) return "com.example.app";
            return xml.substring(s, e);
        } catch (Throwable t) {
            return "com.example.app";
        }
    }

    private void writeIfMissing(File f, String content) throws Exception {
        if (f.exists()) return;
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        writeFile(f, content);
    }

    // ============================================================
    // GitHub Contents API — put one file
    // ============================================================

    private void putFile(String path, byte[] content, String label) throws Exception {
        String encodedPath = encodePath(path);
        // Check if file exists to get sha
        String sha = getFileSha(encodedPath);

        JSONObject body = new JSONObject();
        body.put("message", "MyIDE: " + label);
        body.put("content", android.util.Base64.encodeToString(
                content, android.util.Base64.NO_WRAP));
        if (sha != null) body.put("sha", sha);

        URL url = new URL(API + "/repos/" + repo() + "/contents/" + encodedPath);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("PUT");
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = c.getResponseCode();
        if (code != 200 && code != 201) {
            throw new RuntimeException("PUT " + path + " failed: HTTP " + code
                    + "\n" + readError(c));
        }
    }

    private String getFileSha(String encodedPath) {
        try {
            URL url = new URL(API + "/repos/" + repo() + "/contents/" + encodedPath);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "token " + token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) return null;
            JSONObject j = new JSONObject(readAll(c));
            return j.optString("sha", null);
        } catch (Throwable t) { return null; }
    }

    private String encodePath(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (sb.length() > 0) sb.append('/');
            try { sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20")); }
            catch (Exception e) { sb.append(seg); }
        }
        return sb.toString();
    }

    // ============================================================
    // Workflow file
    // ============================================================

    private void ensureWorkflow() throws Exception {
        String yml = workflowYaml();
        putFile(".github/workflows/" + WORKFLOW,
                yml.getBytes(StandardCharsets.UTF_8), "workflow");
    }

    private String workflowYaml() {
        return "name: android-build\n\n" +
            "on:\n" +
            "  workflow_dispatch:\n" +
            "    inputs:\n" +
            "      project_path:\n" +
            "        required: true\n" +
            "      tag:\n" +
            "        required: false\n" +
            "        default: 'build'\n\n" +
            "jobs:\n" +
            "  build:\n" +
            "    runs-on: ubuntu-latest\n" +
            "    steps:\n" +
            "      - uses: actions/checkout@v4\n" +
            "      - uses: actions/setup-java@v4\n" +
            "        with:\n" +
            "          distribution: temurin\n" +
            "          java-version: '17'\n" +
            "      - uses: gradle/actions/setup-gradle@v3\n" +
            "        with:\n" +
            "          gradle-version: 8.9\n" +
            "      - name: Build\n" +
            "        run: |\n" +
            "          cd ${{ inputs.project_path }}\n" +
            "          if [ ! -f gradlew ]; then gradle wrapper --gradle-version 8.9; fi\n" +
            "          ./gradlew assembleDebug --no-daemon --stacktrace\n" +
            "      - uses: actions/upload-artifact@v4\n" +
            "        if: always()\n" +
            "        with:\n" +
            "          name: myide-apk\n" +
            "          path: ${{ inputs.project_path }}/app/build/outputs/apk/debug/*.apk\n" +
            "          if-no-files-found: warn\n";
    }

    // ============================================================
    // Trigger + wait
    // ============================================================

    private long triggerWorkflow(String projectName, String tag) throws Exception {
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
        inputs.put("project_path", "projects/" + projectName);
        inputs.put("tag", tag);
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
                throw new RuntimeException("Build failed: " + conclusion);
            }
            say("Remote: " + status + " ...");
            Thread.sleep(5000);
        }
        throw new RuntimeException("Build timed out");
    }

    // ============================================================
    // Download artifact
    // ============================================================

    private File downloadArtifact(long runId, String name) throws Exception {
        URL url = new URL(API + "/repos/" + repo() + "/actions/runs/" + runId + "/artifacts");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "token " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject j = new JSONObject(readAll(c));
        JSONArray arr = j.optJSONArray("artifacts");
        if (arr == null || arr.length() == 0) throw new RuntimeException("No artifacts");
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

        File out = new File(ctx.getCacheDir(), "fullbuild-" + runId + ".zip");
        InputStream in = dc.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); in.close();
        return out;
    }

    private File extractApk(File zip) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "extracted-apk");
        if (tmp.exists()) deleteRec(tmp);
        tmp.mkdirs();

        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e; byte[] buf = new byte[8192];
        File found = null;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            if (!e.getName().endsWith(".apk")) continue;
            File out = new File(tmp, new File(e.getName()).getName());
            FileOutputStream fos = new FileOutputStream(out);
            int n; while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            found = out;
            break;
        }
        zin.close();
        if (found == null) throw new RuntimeException("No APK in artifact");
        return found;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals(".git") || n.equals("build") || n.equals("app/build")) continue;
                if (n.equals(".gradle") || n.equals(".idea")) continue;
                collect(f, out);
            } else if (f.isFile()) {
                out.add(f);
            }
        }
    }

    private byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); return out.toByteArray();
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

    private void writeFile(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
