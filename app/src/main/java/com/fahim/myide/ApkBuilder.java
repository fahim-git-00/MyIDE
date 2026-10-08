package com.fahim.myide;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dalvik.system.DexClassLoader;

public class ApkBuilder {

    public interface Progress { void onProgress(String message); }

    public static class Result {
        public boolean success;
        public File apk;
        public String log;
        public Result(boolean s, File a, String l) { success = s; apk = a; log = l; }
    }

    private final Context ctx;
    private final Progress progress;
    private final StringBuilder fullLog = new StringBuilder();

    public ApkBuilder(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    private void say(String s) {
        fullLog.append(s).append('\n');
        if (progress != null) progress.onProgress(s);
    }

    public Result build(File projectRoot, int minSdk, int targetSdk) {
        StringBuilder log = new StringBuilder();
        try {
            File workDir = new File(ctx.getFilesDir(), "build_area");
            deleteRecursive(workDir);
            workDir.mkdirs();

            say("Locating project layout...");
            File appManifest = findManifest(projectRoot);
            File appRes      = findRes(projectRoot);
            File appSrc      = findSrc(projectRoot);

            if (appManifest == null) return fail(log, "AndroidManifest.xml not found");
            if (appRes == null)      return fail(log, "res/ not found");
            if (appSrc == null)      return fail(log, "src/ not found");

            List<File> sourceRoots = new ArrayList<File>();
            List<File> resRoots = new ArrayList<File>();
            List<File> jarDeps = new ArrayList<File>();
            List<File> aarDeps = new ArrayList<File>();

            sourceRoots.add(appSrc);
            resRoots.add(appRes);

            File parent = appManifest.getParentFile();
            if (parent != null) {
                File sibSrc = new File(parent, "src");
                if (!sibSrc.exists()) sibSrc = new File(parent, "java");
                if (sibSrc.exists() && !sibSrc.equals(appSrc)) sourceRoots.add(sibSrc);

                File sibRes = new File(parent, "res");
                if (sibRes.exists() && !sibRes.equals(appRes)) resRoots.add(sibRes);
            }

            File depsFile = new File(projectRoot, ".myide/deps.txt");
            if (depsFile.exists()) {
                File mavenDir = new File(workDir, "maven_libs");
                mavenDir.mkdirs();
                int n = 0;
                try { n = MavenResolver.copyResolvedToDir(ctx, depsFile, mavenDir); }
                catch (Exception e) { say("Maven copy failed: " + e.getMessage()); }
                if (n > 0) collectDeps(mavenDir, jarDeps, aarDeps);
            }

            collectDeps(new File(projectRoot, "libs"), jarDeps, aarDeps);
            collectDeps(new File(projectRoot, "app/libs"), jarDeps, aarDeps);

            File[] rootFiles = projectRoot.listFiles();
            if (rootFiles != null) {
                for (File f : rootFiles) {
                    if (!f.isFile()) continue;
                    if (f.getName().endsWith(".jar")) jarDeps.add(f);
                    else if (f.getName().endsWith(".aar")) aarDeps.add(f);
                }
            }

            say("Loading bundled AARs...");
            aarDeps.addAll(extractBundledAars());

            say("Extracting build tools from assets...");
            File androidJar  = extractAsset("android.jar");
            File lambdaStubs = extractAsset("core-lambda-stubs.jar");
            File ecjFull     = extractAsset("ecj_full.jar");
            File ecjResZip   = extractAsset("ecj_res.zip");
            File d8Zip       = extractAsset("d8.zip");
            File apksigner   = extractAsset("apksigner-full.jar");
            File keyPk8      = extractAsset("keys/mykey.pk8");
            File keyPem      = extractAsset("keys/mykey.x509.pem");

            File ecjResDir = new File(ctx.getFilesDir(), "ecj_res");
            deleteRecursive(ecjResDir);
            ecjResDir.mkdirs();
            unzipTo(ecjResZip, ecjResDir);

            say("Compiling resources (aapt2 compile)...");
            File appResFlat = new File(workDir, "app_res_flat");
            appResFlat.mkdirs();
            for (File r : resRoots) {
                if (!r.exists()) continue;
                File flatOut = new File(appResFlat,
                        r.getName() + "_" + Math.abs(r.hashCode()));
                flatOut.mkdirs();
                runAapt2("compile", "--dir", r.getAbsolutePath(),
                        "-o", flatOut.getAbsolutePath());
            }

            say("Extracting AAR libraries...");
            File aarClassesDir = new File(workDir, "aar_classes");
            aarClassesDir.mkdirs();
            List<File> aarFlatFiles = new ArrayList<File>();

            int aarIdx = 0;
            for (File aar : aarDeps) {
                if (aar.getName().endsWith(".jar")) { jarDeps.add(aar); continue; }

                aarIdx++;
                say("  AAR " + aarIdx + ": " + aar.getName());
                File extractDir = new File(workDir,
                        "aar_extract/" + aar.getName().replace(".", "_"));
                extractDir.mkdirs();
                unzipTo(aar, extractDir);

                File aarClasses = new File(extractDir, "classes.jar");
                if (aarClasses.exists()) {
                    File dest = new File(aarClassesDir,
                            aar.getName().replace(".aar", "_classes.jar"));
                    copyFile(aarClasses, dest);
                    jarDeps.add(dest);
                }

                File aarLibs = new File(extractDir, "libs");
                if (aarLibs.exists()) {
                    File[] libJars = aarLibs.listFiles();
                    if (libJars != null) for (File lj : libJars) {
                        if (lj.isFile() && lj.getName().endsWith(".jar")) {
                            File dest = new File(aarClassesDir,
                                    aar.getName().replace(".aar", "_") + lj.getName());
                            copyFile(lj, dest);
                            jarDeps.add(dest);
                        }
                    }
                }

                File aarRes = new File(extractDir, "res");
                if (!aarRes.exists()) continue;

                File aarResFlat = new File(workDir, "aar_res_flat/" + aarIdx);
                aarResFlat.mkdirs();
                runAapt2("compile", "--dir", aarRes.getAbsolutePath(),
                        "-o", aarResFlat.getAbsolutePath());
                File[] aarFlat = aarResFlat.listFiles();
                if (aarFlat != null) for (File f : aarFlat) {
                    if (f.getName().endsWith(".flat")) aarFlatFiles.add(f);
                }
            }

            // ---- Remote C/C++ compile (GitHub Actions) ----
            try {
                if (RemoteCCompiler.hasNativeSources(projectRoot)) {
                    say("C/C++ sources detected — remote compile");
                    new RemoteCCompiler(ctx, new RemoteCCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    }).compile(projectRoot);
                }
            } catch (Throwable ce) {
                say("Remote C compile failed: " + causeChain(ce));
                throw new RuntimeException("C compile failed", ce);
            }

            // ---- Remote Rust compile (GitHub Actions) ----
            try {
                if (RemoteRustCompiler.hasRustSources(projectRoot)) {
                    say("Rust sources detected \u2014 remote compile");
                    new RemoteRustCompiler(ctx, new RemoteRustCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    }).compile(projectRoot);
                }
            } catch (Throwable re) {
                say("Remote Rust compile failed: " + causeChain(re));
                throw new RuntimeException("Rust compile failed", re);
            }

            // ---- Remote Go compile (GitHub Actions) ----
            try {
                if (RemoteGoCompiler.hasGoSources(projectRoot)) {
                    say("Go sources detected \u2014 remote compile");
                    new RemoteGoCompiler(ctx, new RemoteGoCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    }).compile(projectRoot);
                }
            } catch (Throwable ge) {
                say("Remote Go compile failed: " + causeChain(ge));
                throw new RuntimeException("Go compile failed", ge);
            }

            File patchedManifest = new File(workDir, "AndroidManifest.xml");
            patchManifest(appManifest, patchedManifest, minSdk, targetSdk);

            say("Linking resources (aapt2 link)...");
            File genDir = new File(workDir, "gen");
            genDir.mkdirs();
            File unsignedApk = new File(workDir, "app-unsigned.apk");

            List<String> linkArgs = new ArrayList<String>();
            linkArgs.add("link");
            linkArgs.add("-I"); linkArgs.add(androidJar.getAbsolutePath());
            linkArgs.add("--manifest"); linkArgs.add(patchedManifest.getAbsolutePath());
            linkArgs.add("--java"); linkArgs.add(genDir.getAbsolutePath());
            linkArgs.add("--min-sdk-version"); linkArgs.add(String.valueOf(minSdk));
            linkArgs.add("--target-sdk-version"); linkArgs.add(String.valueOf(targetSdk));
            linkArgs.add("--auto-add-overlay");
            linkArgs.add("--no-version-vectors");
            linkArgs.add("-o"); linkArgs.add(unsignedApk.getAbsolutePath());

            File[] flatDirs = appResFlat.listFiles();
            if (flatDirs != null) {
                for (File d : flatDirs) {
                    File[] inner = d.listFiles();
                    if (inner != null) for (File f : inner) {
                        if (f.getName().endsWith(".flat")) linkArgs.add(f.getAbsolutePath());
                    }
                }
            }
            for (File f : aarFlatFiles) {
                linkArgs.add("-R");
                linkArgs.add(f.getAbsolutePath());
            }
            runAapt2(linkArgs.toArray(new String[0]));

            File classesDir = new File(workDir, "classes");
            classesDir.mkdirs();

            boolean hasKt = false;
            for (File src : sourceRoots) if (hasKtFiles(src)) { hasKt = true; break; }
            if (hasKt) {
                compileKotlin(sourceRoots, classesDir, androidJar);
            }

            boolean hasScala = false;
            for (File src : sourceRoots) if (hasScalaFiles(src)) { hasScala = true; break; }
            if (hasScala) {
                say("Scala mode: remote (GitHub Actions)");
                try {
                    new RemoteScalaCompiler(ctx, new RemoteScalaCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    }).compile(sourceRoots, classesDir);
                } catch (Throwable se) {
                    say("Scala compile failed: " + causeChain(se));
                    throw new RuntimeException("Scala compile failed", se);
                }
            }

            boolean hasGroovy = false;
            for (File src : sourceRoots) if (hasGroovyFiles(src)) { hasGroovy = true; break; }
            if (hasGroovy) {
                say("Groovy mode: remote (GitHub Actions)");
                try {
                    new RemoteGroovyCompiler(ctx, new RemoteGroovyCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    }).compile(sourceRoots, classesDir);
                } catch (Throwable gre) {
                    say("Groovy compile failed: " + causeChain(gre));
                    throw new RuntimeException("Groovy compile failed", gre);
                }
            }

            jarDeps.add(lambdaStubs);
            say("Compiling Java (ECJ)...");
            compileJava(androidJar, ecjFull, ecjResDir, sourceRoots, genDir,
                    classesDir, jarDeps);

            say("Dexing (D8)...");
            File dexDir = new File(workDir, "dex");
            dexDir.mkdirs();
            compileDex(androidJar, d8Zip, classesDir, dexDir, jarDeps, minSdk);

            say("Packaging APK...");
            File withDex = new File(workDir, "app-withdex.apk");
            addDexToApk(unsignedApk, dexDir, withDex);

            say("Packing native libs...");
            File withSo = new File(workDir, "app-withso.apk");
            SoPacker.pack(projectRoot, withDex, withSo);

            say("Signing APK...");
            File signedApk = new File(workDir, "app-signed.apk");
            signApk(apksigner, keyPk8, keyPem, withSo, signedApk);

            say("Build complete.");
            writeBuildLog();
            return new Result(true, signedApk, fullLog.toString());

        } catch (Throwable t) {
            String err = "ERROR: " + causeChain(t);
            log.append(err).append('\n');
            say(err);
            writeBuildLog();
            return new Result(false, null, fullLog.toString());
        }
    }

    private void compileKotlin(List<File> sourceRoots, File classesDir, File androidJar) {
        SharedPreferences prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String mode = prefs.getString("kotlin_mode", "auto");
        String token = ctx.getSharedPreferences("github", Context.MODE_PRIVATE)
                .getString("token", "");
        boolean hasToken = token != null && token.trim().length() > 0;

        if ("remote".equals(mode) && !hasToken) {
            say("Kotlin remote requested but no GitHub token — using local instead");
            mode = "local";
        }

        if ("remote".equals(mode)) {
            say("Kotlin mode: remote (GitHub Actions)");
            try {
                RemoteKotlinCompiler rkc = new RemoteKotlinCompiler(ctx,
                        new RemoteKotlinCompiler.Progress() {
                            @Override public void onProgress(String m) { say(m); }
                        });
                rkc.compile(sourceRoots, classesDir);
                return;
            } catch (Throwable t) {
                say("Remote Kotlin compile failed: " + causeChain(t));
                throw new RuntimeException("Kotlin compile failed", t);
            }
        }

        say("Kotlin mode: local (embedded Alpine)");
        try {
            KotlinCompiler kc = new KotlinCompiler(ctx,
                    new KotlinCompiler.Progress() {
                        @Override public void onProgress(String m) { say(m); }
                    });
            kc.compile(sourceRoots, classesDir, androidJar);
        } catch (Throwable t) {
            say("Local Kotlin compile failed: " + causeChain(t));
            if ("auto".equals(mode) && hasToken) {
                say("Falling back to remote…");
                try {
                    RemoteKotlinCompiler rkc = new RemoteKotlinCompiler(ctx,
                            new RemoteKotlinCompiler.Progress() {
                                @Override public void onProgress(String m) { say(m); }
                            });
                    rkc.compile(sourceRoots, classesDir);
                    return;
                } catch (Throwable t2) {
                    say("Remote fallback also failed: " + causeChain(t2));
                    throw new RuntimeException("Kotlin compile failed", t2);
                }
            }
            throw new RuntimeException("Kotlin compile failed", t);
        }
    }

    private void writeBuildLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "myide_build.log");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(fullLog.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private List<File> extractBundledAars() {
        List<File> out = new ArrayList<File>();
        try {
            String[] names = ctx.getAssets().list("aar");
            if (names == null) return out;
            File aarCache = new File(ctx.getFilesDir(), "bundled_aar");
            if (!aarCache.exists()) aarCache.mkdirs();
            for (String n : names) {
                if (!n.endsWith(".aar") && !n.endsWith(".jar")) continue;
                File dest = new File(aarCache, n);
                if (!dest.exists() || dest.length() == 0) {
                    InputStream in = ctx.getAssets().open("aar/" + n);
                    FileOutputStream fos = new FileOutputStream(dest);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                    fos.close();
                    in.close();
                }
                out.add(dest);
            }
        } catch (Exception e) { say("Bundled AAR load failed: " + e.getMessage()); }
        return out;
    }

    private File findManifest(File root) {
        if (root == null) return null;
        File m = new File(root, "AndroidManifest.xml");
        if (m.isFile()) return m;
        File m1 = new File(root, "app/src/main/AndroidManifest.xml");
        if (m1.isFile()) return m1;
        File m2 = new File(root, "src/main/AndroidManifest.xml");
        if (m2.isFile()) return m2;
        File m3 = new File(root, "src/AndroidManifest.xml");
        if (m3.isFile()) return m3;
        return null;
    }

    private File findRes(File root) {
        if (root == null) return null;
        File r = new File(root, "res");
        if (r.isDirectory()) return r;
        File r1 = new File(root, "app/src/main/res");
        if (r1.isDirectory()) return r1;
        File r2 = new File(root, "src/main/res");
        if (r2.isDirectory()) return r2;
        File r3 = new File(root, "src/res");
        if (r3.isDirectory()) return r3;
        return null;
    }

    private File findSrc(File root) {
        if (root == null) return null;
        File s = new File(root, "src");
        if (s.isDirectory()) return s;
        File s1 = new File(root, "java");
        if (s1.isDirectory()) return s1;
        File s2 = new File(root, "app/src/main/java");
        if (s2.isDirectory()) return s2;
        File s3 = new File(root, "src/main/java");
        if (s3.isDirectory()) return s3;
        return null;
    }

    private boolean hasScalaFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) { if (hasScalaFiles(f)) return true; }
            else if (f.getName().endsWith(".scala")) return true;
        }
        return false;
    }

    private boolean hasGroovyFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) { if (hasGroovyFiles(f)) return true; }
            else if (f.getName().endsWith(".groovy")) return true;
        }
        return false;
    }

    private boolean hasKtFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) { if (hasKtFiles(f)) return true; }
            else if (f.getName().endsWith(".kt")) return true;
        }
        return false;
    }

    private Result fail(StringBuilder log, String msg) {
        log.append(msg).append('\n');
        say(msg);
        writeBuildLog();
        return new Result(false, null, fullLog.toString());
    }

    private void collectDeps(File libsDir, List<File> jarOut, List<File> aarOut) {
        if (libsDir == null || !libsDir.exists() || !libsDir.isDirectory()) return;
        File[] files = libsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".jar")) jarOut.add(f);
            else if (f.getName().endsWith(".aar")) aarOut.add(f);
        }
    }

    private File extractAsset(String name) throws IOException {
        File out = new File(ctx.getFilesDir(), name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        if (out.exists()) out.delete();

        InputStream in = ctx.getAssets().open(name);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();

        say("  extracted " + name + " (" + out.length() + " bytes)");
        return out;
    }

    private void runAapt2(String... args) throws Exception {
        String path = ctx.getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
        List<String> cmd = new ArrayList<String>();
        cmd.add(path);
        for (String a : args) cmd.add(a);

        StringBuilder line = new StringBuilder("$ aapt2");
        for (String a : args) line.append(' ').append(a);
        say(line.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        int code = p.waitFor();

        if (code != 0) {
            String out = baos.toString();
            say("aapt2 failed (" + code + "):\n" + out);
            throw new RuntimeException("aapt2 failed (" + code + "):\n" + out);
        }
    }

    private void compileJava(File androidJar, File ecjFull, File ecjResDir,
                             List<File> sourceRoots, File genDir, File classesDir,
                             List<File> extraJars) throws Exception {
        List<File> javaFiles = new ArrayList<File>();
        for (File src : sourceRoots) findJavaFiles(src, javaFiles);
        findJavaFiles(genDir, javaFiles);
        if (javaFiles.isEmpty()) { say("No .java files to compile"); return; }

        ResourceAwareLoader loader = new ResourceAwareLoader(
                ecjFull.getAbsolutePath(), ctx.getCacheDir(),
                ctx.getClassLoader(), ecjResDir);

        Class<?> mainClass = loader.loadClass(
                "org.eclipse.jdt.internal.compiler.batch.Main");

        StringBuilder cp = new StringBuilder();
        cp.append(androidJar.getAbsolutePath());
        if (classesDir != null && classesDir.exists()) {
            cp.append(File.pathSeparator).append(classesDir.getAbsolutePath());
        }
        for (File j : extraJars) {
            if (j != null && j.exists())
                cp.append(File.pathSeparator).append(j.getAbsolutePath());
        }

        List<String> args = new ArrayList<String>();
        args.add("-1.8");
        args.add("-proc:none");
        args.add("-nowarn");
        args.add("-classpath"); args.add(cp.toString());
        args.add("-d"); args.add(classesDir.getAbsolutePath());
        for (File f : javaFiles) args.add(f.getAbsolutePath());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(baos);

        Object instance = mainClass
                .getConstructor(PrintWriter.class, PrintWriter.class, boolean.class)
                .newInstance(writer, writer, false);
        Method compile = mainClass.getMethod("compile", String[].class);

        Boolean ok;
        try {
            ok = (Boolean) compile.invoke(instance, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException ite) {
            String msg = "ECJ error: " + causeChain(ite);
            say(msg);
            throw new RuntimeException(msg);
        }
        writer.flush();
        if (ok == null || !ok) {
            String msg = "Java compile failed:\n" + baos.toString();
            say(msg);
            throw new RuntimeException(msg);
        }
    }

    private static class ResourceAwareLoader extends DexClassLoader {
        private final File resDir;
        ResourceAwareLoader(String dexPath, File optDir, ClassLoader parent, File resDir) {
            super(dexPath, optDir.getAbsolutePath(), null, parent);
            this.resDir = resDir;
        }
        @Override public InputStream getResourceAsStream(String name) {
            File f = new File(resDir, name);
            if (f.exists() && f.isFile()) {
                try { return new FileInputStream(f); } catch (Exception ignored) {}
            }
            return super.getResourceAsStream(name);
        }
        @Override public URL getResource(String name) {
            File f = new File(resDir, name);
            if (f.exists() && f.isFile()) {
                try { return f.toURI().toURL(); } catch (Exception ignored) {}
            }
            return super.getResource(name);
        }
    }

    private void compileDex(File androidJar, File d8Zip, File classesDir,
                            File outputDir, List<File> extraJars, int minSdk) throws Exception {
        List<File> classFiles = new ArrayList<File>();
        findClassFiles(classesDir, classFiles);
        if (classFiles.isEmpty()) throw new RuntimeException("No .class files to dex");
        if (!outputDir.exists()) outputDir.mkdirs();

        File allClassesJar = new File(outputDir.getParentFile(), "all-classes.jar");
        ZipOutputStream jarOut = new ZipOutputStream(new FileOutputStream(allClassesJar));
        byte[] copyBuf = new byte[8192];
        File classesRoot = classesDir;
        for (File cf : classFiles) {
            String rel = cf.getAbsolutePath().substring(
                    classesRoot.getAbsolutePath().length() + 1).replace('\\', '/');
            jarOut.putNextEntry(new ZipEntry(rel));
            FileInputStream fin = new FileInputStream(cf);
            int n;
            while ((n = fin.read(copyBuf)) > 0) jarOut.write(copyBuf, 0, n);
            fin.close();
            jarOut.closeEntry();
        }
        jarOut.close();

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream d8Out = new ByteArrayOutputStream();
        ByteArrayOutputStream d8Err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(d8Out, true));
        System.setErr(new PrintStream(d8Err, true));

        try {
            DexClassLoader loader = new DexClassLoader(
                    d8Zip.getAbsolutePath(),
                    ctx.getCacheDir().getAbsolutePath(),
                    null, ctx.getClassLoader());
            Class<?> d8Class = loader.loadClass("com.android.tools.r8.D8");
            Method main = d8Class.getMethod("main", String[].class);

            List<String> args = new ArrayList<String>();
            args.add("--output"); args.add(outputDir.getAbsolutePath());
            args.add("--min-api"); args.add(String.valueOf(minSdk));
            args.add("--lib"); args.add(androidJar.getAbsolutePath());
            args.add("--release");
            args.add("--thread-count");
            args.add(String.valueOf(Runtime.getRuntime().availableProcessors()));
            for (File j : extraJars) {
                if (j != null && j.exists() && j.getName().endsWith(".jar")) {
                    args.add(j.getAbsolutePath());
                }
            }
            args.add(allClassesJar.getAbsolutePath());

            try {
                main.invoke(null, (Object) args.toArray(new String[0]));
            } catch (InvocationTargetException ite) {
                String msg = "D8 error: " + causeChain(ite)
                        + "\n--- stdout ---\n" + d8Out.toString()
                        + "\n--- stderr ---\n" + d8Err.toString();
                say(msg);
                throw new RuntimeException(msg);
            }

            File[] kids = outputDir.listFiles();
            boolean anyDex = false;
            if (kids != null) for (File f : kids) {
                if (f.getName().endsWith(".dex") && f.length() > 0) { anyDex = true; break; }
            }
            if (!anyDex) {
                String msg = "D8 produced no dex.\n"
                        + "--- stdout ---\n" + d8Out.toString()
                        + "\n--- stderr ---\n" + d8Err.toString();
                say(msg);
                throw new RuntimeException(msg);
            }
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private void signApk(File apksigner, File pk8, File pem,
                         File inApk, File outApk) throws Exception {
        DexClassLoader loader = new DexClassLoader(
                apksigner.getAbsolutePath(),
                ctx.getCacheDir().getAbsolutePath(),
                null, ctx.getClassLoader());
        Class<?> tool = loader.loadClass("com.android.apksigner.ApkSignerTool");
        Method main = tool.getMethod("main", String[].class);

        List<String> args = new ArrayList<String>();
        args.add("sign");

        SharedPreferences _prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String _mode = _prefs.getString("sign_key_mode", "bundled");
        boolean _custom = false;
        if ("pk8pem".equals(_mode)) {
            File _pk8 = new File(_prefs.getString("sign_pk8_path", ""));
            File _pem = new File(_prefs.getString("sign_pem_path", ""));
            if (_pk8.isFile() && _pem.isFile()) {
                args.add("--key"); args.add(_pk8.getAbsolutePath());
                args.add("--cert"); args.add(_pem.getAbsolutePath());
                _custom = true;
                say("Signing with custom pk8/pem: " + _pk8.getName());
            } else {
                say("Custom pk8/pem missing \u2014 falling back to bundled key");
            }
        } else if ("keystore".equals(_mode)) {
            File _ks = new File(_prefs.getString("sign_ks_path", ""));
            String _alias = _prefs.getString("sign_ks_alias", "");
            String _sp = _prefs.getString("sign_ks_store_pass", "");
            String _kp = _prefs.getString("sign_ks_key_pass", "");
            if (_ks.isFile() && _alias != null && !_alias.isEmpty()) {
                args.add("--ks"); args.add(_ks.getAbsolutePath());
                args.add("--ks-key-alias"); args.add(_alias);
                args.add("--ks-pass"); args.add("pass:" + _sp);
                if (_kp != null && !_kp.isEmpty()) {
                    args.add("--key-pass"); args.add("pass:" + _kp);
                } else {
                    args.add("--key-pass"); args.add("pass:" + _sp);
                }
                _custom = true;
                say("Signing with keystore: " + _ks.getName() + " (alias=" + _alias + ")");
            } else {
                say("Custom keystore missing or alias empty \u2014 falling back to bundled key");
            }
        }

        if (!_custom) {
            args.add("--key"); args.add(pk8.getAbsolutePath());
            args.add("--cert"); args.add(pem.getAbsolutePath());
            say("Signing with bundled test key");
        }

        args.add("--out"); args.add(outApk.getAbsolutePath());
        args.add(inApk.getAbsolutePath());

        try {
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException ite) {
            String msg = "Sign error: " + causeChain(ite);
            say(msg);
            throw new RuntimeException(msg);
        }
        if (!outApk.exists() || outApk.length() == 0)
            throw new RuntimeException("Signing produced no output");
    }

    private void patchManifest(File in, File out, int minSdk, int targetSdk) throws Exception {
        String xml = readFile(in);

        String pkg = "";
        Matcher pm = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
        if (pm.find()) pkg = pm.group(1);

        xml = xml.replaceAll("<uses-sdk[^>]*/>", "");
        xml = xml.replaceAll("<uses-sdk.*?</uses-sdk>", "");
        xml = xml.replaceAll("android:applicationId\\s*=\\s*\"[^\"]*\"", "");

        String usesSdk = "<uses-sdk android:minSdkVersion=\"" + minSdk
                + "\" android:targetSdkVersion=\"" + targetSdk + "\" />\n    ";

        int mStart = xml.indexOf("<manifest");
        int mEnd = xml.indexOf('>', mStart);
        if (mStart >= 0 && mEnd > 0) {
            String head = xml.substring(0, mEnd + 1);
            String tail = xml.substring(mEnd + 1);
            if (pkg != null && pkg.length() > 0 && !head.contains("package=")) {
                head = head.replaceFirst("<manifest",
                        "<manifest package=\"" + pkg + "\"");
            }
            xml = head + "\n    " + usesSdk + tail;
        }

        FileOutputStream fos = new FileOutputStream(out);
        fos.write(xml.getBytes("UTF-8"));
        fos.close();
    }

    private void addDexToApk(File inApk, File dexDir, File outApk) throws Exception {
        if (outApk.exists()) outApk.delete();

        List<File> dexFiles = new ArrayList<File>();
        File[] kids = dexDir.listFiles();
        if (kids != null) for (File f : kids) {
            if (f.getName().endsWith(".dex")) dexFiles.add(f);
        }
        if (dexFiles.isEmpty()) throw new RuntimeException("No dex output");

        ZipInputStream zin = new ZipInputStream(new FileInputStream(inApk));
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outApk));
        byte[] buf = new byte[8192];
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("classes") && name.endsWith(".dex")) continue;
            zout.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
            zout.closeEntry();
        }
        zin.close();

        for (File dex : dexFiles) {
            zout.putNextEntry(new ZipEntry(dex.getName()));
            FileInputStream fin = new FileInputStream(dex);
            int n;
            while ((n = fin.read(buf)) > 0) zout.write(buf, 0, n);
            fin.close();
            zout.closeEntry();
        }
        zout.close();
    }

    private void unzipTo(File zip, File destDir) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            File out = new File(destDir, e.getName());
            if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath()))
                throw new SecurityException("Zip entry escapes target");
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

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private void findJavaFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findJavaFiles(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private void findClassFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) findClassFiles(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        while (t != null && d < 10) {
            sb.append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage()).append('\n');
            Throwable next = (t instanceof InvocationTargetException)
                    ? ((InvocationTargetException) t).getTargetException()
                    : t.getCause();
            if (next == t) break;
            t = next;
            d++;
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    public void compileRemoteRust(File projectRoot) throws Exception {
        new RemoteRustCompiler(ctx, new RemoteRustCompiler.Progress() {
            @Override public void onProgress(String m) { say(m); }
        }).compile(projectRoot);
    }

    public void compileRemoteGo(File projectRoot) throws Exception {
        new RemoteGoCompiler(ctx, new RemoteGoCompiler.Progress() {
            @Override public void onProgress(String m) { say(m); }
        }).compile(projectRoot);
    }

    public void compileRemoteScala(File projectRoot) throws Exception {
        File srcDir = new File(projectRoot, "src");
        File classesDir = new File(projectRoot, "build/classes");
        if (!classesDir.exists()) classesDir.mkdirs();
        List<File> roots = new ArrayList<File>();
        roots.add(srcDir);
        new RemoteScalaCompiler(ctx, new RemoteScalaCompiler.Progress() {
            @Override public void onProgress(String m) { say(m); }
        }).compile(roots, classesDir);
    }

    public void compileRemoteGroovy(File projectRoot) throws Exception {
        File srcDir = new File(projectRoot, "src");
        File classesDir = new File(projectRoot, "build/classes");
        if (!classesDir.exists()) classesDir.mkdirs();
        List<File> roots = new ArrayList<File>();
        roots.add(srcDir);
        new RemoteGroovyCompiler(ctx, new RemoteGroovyCompiler.Progress() {
            @Override public void onProgress(String m) { say(m); }
        }).compile(roots, classesDir);
    }

}
