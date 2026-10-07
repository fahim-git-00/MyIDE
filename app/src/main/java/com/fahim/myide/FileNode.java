package com.fahim.myide;

import java.io.File;

public class FileNode {

    public final String name;
    public final boolean isDirectory;
    public final int depth;
    public final File file;
    public boolean expanded;
    public boolean dirty;

    public FileNode(String name, boolean isDirectory, int depth, File file) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.depth = depth;
        this.file = file;
        this.expanded = false;
        this.dirty = false;
    }

    public String icon() {
        if (isDirectory) return expanded ? "\uD83D\uDCC2" : "\uD83D\uDCC1";

        String l = name.toLowerCase();
        if (l.endsWith(".java"))                            return "\u2615";
        if (l.endsWith(".kt") || l.endsWith(".kts"))        return "\uD83C\uDD6A";
        if (l.endsWith(".xml"))                             return "\uD83D\uDCD0";
        if (l.endsWith(".gradle") || l.endsWith(".gradle.kts")) return "\uD83D\uDC18";
        if (l.endsWith(".json"))                            return "{}";
        if (l.endsWith(".py") || l.endsWith(".pyw"))      return "\uD83D\uDC0D";
        if (l.endsWith(".md"))                              return "\uD83D\uDCDD";
        if (l.endsWith(".png") || l.endsWith(".jpg")
                || l.endsWith(".jpeg") || l.endsWith(".webp")) return "\uD83D\uDDBC";
        if (l.endsWith(".jar") || l.endsWith(".aar"))       return "\uD83D\uDCE6";
        if (l.endsWith(".apk"))                             return "\uD83D\uDCF1";
        if (l.endsWith(".properties"))                      return "\u2699";
        if (l.endsWith(".so"))                              return "\uD83D\uDD27";
        if (l.endsWith(".dex"))                             return "\u26A1";
        if (l.endsWith(".zip"))                             return "\uD83D\uDCE6";
        if (l.startsWith("."))                              return "\uD83D\uDD12";
        return "\uD83D\uDCC4";
    }
}
