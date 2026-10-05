package com.fahim.myide;

import java.io.File;
import java.io.FileOutputStream;

public class ProjectScaffold {

    public static File create(File projDir, String appName, String pkg) {
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
