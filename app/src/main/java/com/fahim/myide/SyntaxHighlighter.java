package com.fahim.myide;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyntaxHighlighter {

    public static final int LANG_JAVA = 0;
    public static final int LANG_XML = 1;
    public static final int LANG_JSON = 2;
    public static final int LANG_GRADLE = 3;
    public static final int LANG_MARKDOWN = 4;
    public static final int LANG_KOTLIN = 5;

    // Hard caps — beyond this, skip highlighting to avoid UI freeze
    private static final int MAX_CHARS = 120_000;   // ~3000 lines
    private static final int MAX_SPANS = 4000;

    private static final int C_KEYWORD    = 0xFF569CD6;
    private static final int C_STRING     = 0xFFCE9178;
    private static final int C_COMMENT    = 0xFF6A9955;
    private static final int C_NUMBER     = 0xFFB5CEA8;
    private static final int C_ANNOTATION = 0xFFDCDCAA;
    private static final int C_TYPE       = 0xFF4EC9B0;
    private static final int C_XML_TAG    = 0xFF569CD6;
    private static final int C_XML_ATTR   = 0xFF9CDCFE;
    private static final int C_XML_STR    = 0xFFCE9178;
    private static final int C_FUNC       = 0xFFDCDCAA;

    private SyntaxHighlighter() {}

    public static int detectLang(String path) {
        if (path == null) return LANG_JAVA;
        String p = path.toLowerCase();
        if (p.endsWith(".xml") || p.endsWith(".html") || p.endsWith(".htm")) return LANG_XML;
        if (p.endsWith(".json"))         return LANG_JSON;
        if (p.endsWith(".gradle") || p.endsWith(".gradle.kts")) return LANG_GRADLE;
        if (p.endsWith(".md") || p.endsWith(".markdown")) return LANG_MARKDOWN;
        if (p.endsWith(".kt") || p.endsWith(".kts")) return LANG_KOTLIN;
        return LANG_JAVA;
    }

    public static class Range {
        public final int start, end, color;
        public Range(int s, int e, int c) { start = s; end = e; color = c; }
    }

    // ============ KEYWORD TABLES ============

    private static final String[] JAVA_KW = {
        "abstract","assert","boolean","break","byte","case","catch","char",
        "class","const","continue","default","do","double","else","enum",
        "extends","final","finally","float","for","goto","if","implements",
        "import","instanceof","int","interface","long","native","new",
        "package","private","protected","public","return","short","static",
        "strictfp","super","switch","synchronized","this","throw","throws",
        "transient","try","void","volatile","while",
        "true","false","null","var","record","sealed","permits","yield"
    };

    private static final String[] KOTLIN_KW = {
        "as","break","class","continue","do","else","false","for","fun","if",
        "in","interface","is","null","object","package","return","super","this",
        "throw","true","try","typealias","typeof","val","var","when","while",
        "by","catch","constructor","delegate","dynamic","field","file","finally",
        "get","import","init","param","property","receiver","set","setparam",
        "where","actual","abstract","annotation","companion","const","crossinline",
        "data","enum","expect","external","final","infix","inline","inner",
        "internal","lateinit","noinline","open","operator","out","override",
        "private","protected","public","reified","sealed","suspend","tailrec",
        "vararg","it","until","step","downTo"
    };

    private static final String[] GRADLE_KW = {
        "apply","plugin","plugins","android","dependencies","repositories",
        "buildscript","allprojects","task","def","ext","implementation",
        "compile","compileOnly","runtimeOnly","testImplementation",
        "androidTestImplementation","api","classpath",
        "compileSdk","compileSdkVersion","minSdk","minSdkVersion",
        "targetSdk","targetSdkVersion","buildToolsVersion",
        "defaultConfig","buildTypes","release","debug","proguardFiles",
        "minifyEnabled","shrinkResources","mavenCentral","google","jcenter",
        "namespace","viewBinding","buildFeatures","packaging","packagingOptions",
        "sourceCompatibility","targetCompatibility","compileOptions",
        "applicationId","versionCode","versionName"
    };

    // ============ PATTERNS ============

    private static final Pattern P_COMMENT =
        Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern P_STRING =
        Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern P_NUMBER =
        Pattern.compile("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b|\\b0[xX][0-9a-fA-F]+\\b|\\b0[bB][01]+\\b");
    private static final Pattern P_ANNOT =
        Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern P_TYPE =
        Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
    private static final Pattern P_FUNC =
        Pattern.compile("\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\()");

    private static final Pattern P_XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern P_XML_DECL    = Pattern.compile("<\\?[\\s\\S]*?\\?>");
    private static final Pattern P_XML_TAG     = Pattern.compile("</?[A-Za-z_][A-Za-z0-9_.:-]*");
    private static final Pattern P_XML_ATTR    = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_.:-]*(?=\\s*=)");
    private static final Pattern P_XML_STRING  = Pattern.compile("\"[^\"]*\"");

    private static final Pattern P_JSON_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern P_JSON_NUMBER = Pattern.compile("\\b-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\b");
    private static final Pattern P_JSON_KW     = Pattern.compile("\\b(?:true|false|null)\\b");

    private static final Pattern P_MD_HEADER = Pattern.compile("^#{1,6} .*$", Pattern.MULTILINE);
    private static final Pattern P_MD_CODE   = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`");
    private static final Pattern P_MD_LINK   = Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");

    private static final Pattern P_JAVA_KW_RE;
    private static final Pattern P_KOTLIN_KW_RE;
    private static final Pattern P_GRADLE_KW_RE;

    static {
        P_JAVA_KW_RE   = buildKw(JAVA_KW);
        P_KOTLIN_KW_RE = buildKw(KOTLIN_KW);
        P_GRADLE_KW_RE = buildKw(GRADLE_KW);
    }

    private static Pattern buildKw(String[] kws) {
        StringBuilder sb = new StringBuilder("\\b(?:");
        for (int i = 0; i < kws.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(kws[i]));
        }
        sb.append(")\\b");
        return Pattern.compile(sb.toString());
    }

    // ============ PUBLIC API ============

    public static List<Range> computeSpans(String text, int lang) {
        List<Range> out = new ArrayList<Range>();
        if (text == null || text.isEmpty()) return out;

        // Cap: skip highlighting for very large files
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }

        switch (lang) {
            case LANG_XML:      xml(out, text); break;
            case LANG_JSON:     json(out, text); break;
            case LANG_GRADLE:   gradle(out, text); break;
            case LANG_MARKDOWN: markdown(out, text); break;
            case LANG_KOTLIN:   kotlin(out, text); break;
            default:            java(out, text); break;
        }

        // Cap span count
        if (out.size() > MAX_SPANS) {
            return new ArrayList<Range>(out.subList(0, MAX_SPANS));
        }
        return out;
    }

    public static void applySpans(Spannable text, List<Range> spans) {
        clearSpans(text);
        int len = text.length();
        for (Range r : spans) {
            if (r.start < 0 || r.end > len || r.start >= r.end) continue;
            text.setSpan(new ForegroundColorSpan(r.color), r.start, r.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static void highlight(Spannable text, int lang) {
        applySpans(text, computeSpans(text.toString(), lang));
    }

    public static void clearSpans(Spannable text) {
        ForegroundColorSpan[] old = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan sp : old) text.removeSpan(sp);
    }

    // ============ PER-LANGUAGE ============

    private static void java(List<Range> out, String s) {
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
        add(out, s, P_ANNOT,   C_ANNOTATION);
        add(out, s, P_NUMBER,  C_NUMBER);
        add(out, s, P_TYPE,    C_TYPE);
        add(out, s, P_JAVA_KW_RE, C_KEYWORD);
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
    }

    private static void kotlin(List<Range> out, String s) {
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
        add(out, s, P_ANNOT,   C_ANNOTATION);
        add(out, s, P_NUMBER,  C_NUMBER);
        add(out, s, P_TYPE,    C_TYPE);
        add(out, s, P_KOTLIN_KW_RE, C_KEYWORD);
        add(out, s, P_FUNC,    C_FUNC);
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
    }

    private static void xml(List<Range> out, String s) {
        add(out, s, P_XML_COMMENT, C_COMMENT);
        add(out, s, P_XML_DECL,    C_KEYWORD);
        add(out, s, P_XML_TAG,     C_XML_TAG);
        add(out, s, P_XML_ATTR,    C_XML_ATTR);
        add(out, s, P_XML_STRING,  C_XML_STR);
        add(out, s, P_XML_COMMENT, C_COMMENT);
    }

    private static void json(List<Range> out, String s) {
        add(out, s, P_JSON_STRING, C_STRING);
        add(out, s, P_JSON_NUMBER, C_NUMBER);
        add(out, s, P_JSON_KW,     C_KEYWORD);
        add(out, s, P_JSON_STRING, C_STRING);
    }

    private static void gradle(List<Range> out, String s) {
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
        add(out, s, P_NUMBER,  C_NUMBER);
        add(out, s, P_GRADLE_KW_RE, C_KEYWORD);
        add(out, s, P_FUNC,    C_FUNC);
        add(out, s, P_COMMENT, C_COMMENT);
        add(out, s, P_STRING,  C_STRING);
    }

    private static void markdown(List<Range> out, String s) {
        add(out, s, P_MD_HEADER, C_KEYWORD);
        add(out, s, P_MD_CODE,   C_STRING);
        add(out, s, P_MD_LINK,   C_XML_ATTR);
    }

    private static void add(List<Range> out, String source, Pattern p, int color) {
        Matcher m = p.matcher(source);
        while (m.find()) {
            if (m.start() == m.end()) continue;
            out.add(new Range(m.start(), m.end(), color));
            if (out.size() >= MAX_SPANS) return;
        }
    }
}
