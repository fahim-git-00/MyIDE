[1mdiff --git a/app/src/main/java/com/fahim/myide/ApkBuilder.java b/app/src/main/java/com/fahim/myide/ApkBuilder.java[m
[1mindex 68f615c..1c8e877 100644[m
[1m--- a/app/src/main/java/com/fahim/myide/ApkBuilder.java[m
[1m+++ b/app/src/main/java/com/fahim/myide/ApkBuilder.java[m
[36m@@ -53,7 +53,7 @@[m [mpublic class ApkBuilder {[m
     public Result build(File projectRoot, int minSdk, int targetSdk) {[m
         StringBuilder log = new StringBuilder();[m
         try {[m
[31m-            File workDir = new File(ctx.getFilesDir(), "build_area");[m
[32m+[m[32m            File workDir = new File(ctx.getExternalFilesDir(null), "build_area");[m
             deleteRecursive(workDir);[m
             workDir.mkdirs();[m
 [m
[36m@@ -120,7 +120,7 @@[m [mpublic class ApkBuilder {[m
             File keyPk8      = extractAsset("keys/mykey.pk8");[m
             File keyPem      = extractAsset("keys/mykey.x509.pem");[m
 [m
[31m-            File ecjResDir = new File(ctx.getFilesDir(), "ecj_res");[m
[32m+[m[32m            File ecjResDir = new File(ctx.getExternalFilesDir(null), "ecj_res");[m
             deleteRecursive(ecjResDir);[m
             ecjResDir.mkdirs();[m
             unzipTo(ecjResZip, ecjResDir);[m
[36m@@ -342,7 +342,7 @@[m [mpublic class ApkBuilder {[m
         try {[m
             String[] names = ctx.getAssets().list("aar");[m
             if (names == null) return out;[m
[31m-            File aarCache = new File(ctx.getFilesDir(), "bundled_aar");[m
[32m+[m[32m            File aarCache = new File(ctx.getExternalFilesDir(null), "bundled_aar");[m
             if (!aarCache.exists()) aarCache.mkdirs();[m
             for (String n : names) {[m
                 if (!n.endsWith(".aar") && !n.endsWith(".jar")) continue;[m
[36m@@ -431,7 +431,7 @@[m [mpublic class ApkBuilder {[m
     }[m
 [m
     private File extractAsset(String name) throws IOException {[m
[31m-        File out = new File(ctx.getFilesDir(), name);[m
[32m+[m[32m        File out = new File(ctx.getExternalFilesDir(null), name);[m
         File parent = out.getParentFile();[m
         if (parent != null && !parent.exists()) parent.mkdirs();[m
 [m
