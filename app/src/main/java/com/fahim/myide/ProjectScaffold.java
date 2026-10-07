package com.fahim.myide;

import java.io.File;
import java.io.FileOutputStream;

public class ProjectScaffold {

    public static File create(File projDir, String appName, String pkg) {
        return create(projDir, appName, pkg, false);
    }

    public static File create(File projDir, String appName, String pkg, boolean withCpp) {
        try {
            String pkgPath = pkg.replace('.', '/');
            File srcDir      = new File(projDir, "src/" + pkgPath);
            File resLayout   = new File(projDir, "res/layout");
            File resValues   = new File(projDir, "res/values");
            File resDrawable = new File(projDir, "res/drawable");
            File myideDir    = new File(projDir, ".myide");
            srcDir.mkdirs();
            resLayout.mkdirs();
            resValues.mkdirs();
            resDrawable.mkdirs();
            myideDir.mkdirs();

            write(new File(projDir, "AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    package=\"" + pkg + "\">\n\n" +
                "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"35\" />\n\n" +
                "    <application\n" +
                "        android:allowBackup=\"true\"\n" +
                "        android:label=\"@string/app_name\"\n" +
                "        android:icon=\"@drawable/ic_launcher\"\n" +
                "        android:theme=\"@android:style/Theme.Material.Light\">\n" +
                "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                "            <intent-filter>\n" +
                "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                "            </intent-filter>\n" +
                "        </activity>\n" +
                "    </application>\n" +
                "</manifest>\n");

            if (withCpp) {
                writeCppProject(projDir, pkg, appName);
            } else {
                writeJavaOnly(srcDir, pkg, appName);
            }

            write(new File(resValues, "strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<resources>\n" +
                "    <string name=\"app_name\">" + esc(appName) + "</string>\n" +
                "    <string name=\"hello\">Hello from " + esc(appName) + "!</string>\n" +
                "</resources>\n");

            write(new File(resLayout, "main.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"match_parent\"\n" +
                "    android:orientation=\"vertical\"\n" +
                "    android:gravity=\"center\">\n\n" +
                "    <TextView\n" +
                "        android:layout_width=\"wrap_content\"\n" +
                "        android:layout_height=\"wrap_content\"\n" +
                "        android:text=\"@string/hello\"\n" +
                "        android:textSize=\"20sp\" />\n\n" +
                "</LinearLayout>\n");

            write(new File(resDrawable, "ic_launcher.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    android:width=\"108dp\" android:height=\"108dp\"\n" +
                "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n" +
                "    <path android:fillColor=\"#3F51B5\" android:pathData=\"M0,0h108v108h-108z\" />\n" +
                "    <path android:fillColor=\"#FFFFFF\" android:pathData=\"M54,24 L84,84 L24,84 Z\" />\n" +
                "</vector>\n");

            write(new File(myideDir, "deps.txt"), "");

            return projDir;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJavaOnly(File srcDir, String pkg, String appName) throws Exception {
        write(new File(srcDir, "MainActivity.java"),
            "package " + pkg + ";\n\n" +
            "import android.app.Activity;\n" +
            "import android.os.Bundle;\n\n" +
            "public class MainActivity extends Activity {\n" +
            "    @Override\n" +
            "    protected void onCreate(Bundle savedInstanceState) {\n" +
            "        super.onCreate(savedInstanceState);\n" +
            "        setContentView(R.layout.main);\n" +
            "    }\n" +
            "}\n");
    }

    private static void writeCppProject(File projDir, String pkg, String appName) throws Exception {
        // Java sources
        File srcDir = new File(projDir, "src/" + pkg.replace('.', '/'));
        String javaPkg = pkg;

        write(new File(srcDir, "MainActivity.java"),
            "package " + javaPkg + ";\n\n" +
            "import android.app.Activity;\n" +
            "import android.os.Bundle;\n" +
            "import android.widget.TextView;\n\n" +
            "public class MainActivity extends Activity {\n" +
            "    static {\n" +
            "        System.loadLibrary(\"native\");\n" +
            "    }\n\n" +
            "    public native String helloFromC();\n" +
            "    public native String helloFromCpp();\n\n" +
            "    @Override\n" +
            "    protected void onCreate(Bundle savedInstanceState) {\n" +
            "        super.onCreate(savedInstanceState);\n" +
            "        String msg = helloFromC() + \"\\n\" + helloFromCpp();\n" +
            "        TextView tv = new TextView(this);\n" +
            "        tv.setText(msg);\n" +
            "        tv.setTextSize(18f);\n" +
            "        tv.setPadding(32, 32, 32, 32);\n" +
            "        setContentView(tv);\n" +
            "    }\n" +
            "}\n");

        // JNI folder
        File jniDir = new File(projDir, "jni");
        jniDir.mkdirs();

        String jniBase = javaPkg.replace('.', '_');

        write(new File(jniDir, "native.c"),
            "#include <jni.h>\n\n" +
            "JNIEXPORT jstring JNICALL\n" +
            "Java_" + jniBase + "_MainActivity_helloFromC(JNIEnv *env, jobject thiz) {\n" +
            "    return (*env)->NewStringUTF(env, \"Hello from C\");\n" +
            "}\n");

        write(new File(jniDir, "native.cpp"),
            "#include <jni.h>\n\n" +
            "extern \"C\" JNIEXPORT jstring JNICALL\n" +
            "Java_" + jniBase + "_MainActivity_helloFromCpp(JNIEnv *env, jobject thiz) {\n" +
            "    return env->NewStringUTF(\"Hello from C++\");\n" +
            "}\n");

        write(new File(jniDir, "Android.mk"),
            "LOCAL_PATH := $(call my-dir)\n\n" +
            "include $(CLEAR_VARS)\n" +
            "LOCAL_MODULE    := native\n" +
            "LOCAL_SRC_FILES := native.c native.cpp\n" +
            "LOCAL_LDLIBS    := -llog\n" +
            "include $(BUILD_SHARED_LIBRARY)\n");

        write(new File(jniDir, "Application.mk"),
            "APP_ABI := arm64-v8a\n" +
            "APP_PLATFORM := android-24\n" +
            "APP_STL := c++_static\n");
    }

    private static void write(File f, String content) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
