package com.fahim.myide;

import java.io.File;
import java.util.List;

public class EditorTab {

    public final File file;
    public String text;
    public int selStart;
    public int selEnd;
    public int scrollY;
    public boolean dirty;
    public boolean loaded;

    // Highlight cache
    public List<SyntaxHighlighter.Range> cachedSpans;
    public int cachedLangHash;

    // Line-start index
    public int[] lineStarts;

    public EditorTab(File f, String initial) {
        this.file = f;
        this.text = initial != null ? initial : "";
        this.selStart = 0;
        this.selEnd = 0;
        this.scrollY = 0;
        this.dirty = false;
        this.loaded = false;
        this.cachedSpans = null;
        this.cachedLangHash = 0;
        this.lineStarts = null;
    }

    public String title() {
        return file.getName() + (dirty ? " \u25CF" : "");
    }

    public String name() {
        return file.getName();
    }

    public int lang() {
        return SyntaxHighlighter.detectLang(file.getAbsolutePath());
    }

    public String langName() {
        switch (lang()) {
            case SyntaxHighlighter.LANG_XML:      return "XML";
            case SyntaxHighlighter.LANG_JSON:     return "JSON";
            case SyntaxHighlighter.LANG_GRADLE:   return "Gradle";
            case SyntaxHighlighter.LANG_MARKDOWN: return "Markdown";
            case SyntaxHighlighter.LANG_KOTLIN:   return "Kotlin";
            default:                              return "Java";
        }
    }

    public String icon() {
        switch (lang()) {
            case SyntaxHighlighter.LANG_XML:      return "\uD83D\uDCD0";
            case SyntaxHighlighter.LANG_JSON:     return "{}";
            case SyntaxHighlighter.LANG_GRADLE:   return "\uD83D\uDC18";
            case SyntaxHighlighter.LANG_MARKDOWN: return "\uD83D\uDCDD";
            case SyntaxHighlighter.LANG_KOTLIN:   return "K";
            default:                              return "\u2615";
        }
    }
}
