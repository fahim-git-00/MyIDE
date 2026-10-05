package com.fahim.myide;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

public class EditorEnhancer {

    public interface Host {
        boolean wantsAutoClose();
        boolean wantsAutoIndent();
        int tabSize();
        void onUndo();
        void onRedo();
        void onSave();
        void onFind();
        void onGotoLine();
        void onToggleComment();
        void onDuplicateLine();
        void onDeleteLine();
        void onProgrammaticEdit(String newText, int newCaret);
    }

    private static final String OPENERS = "([{\"'`";
    private static final String CLOSERS = ")]}\"'`";

    private final EditText editor;
    private final Host host;
    private volatile boolean internalEdit = false;
    private volatile boolean suspend = false;

    public EditorEnhancer(EditText editor, Host host) {
        this.editor = editor;
        this.host = host;
        install();
    }

    public boolean isInternalEdit() { return internalEdit; }
    public void setSuspended(boolean s) { this.suspend = s; }

    private void install() {
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (internalEdit || suspend) return;
                if (!host.wantsAutoClose()) return;
                if (c == 1 && b == 0 && st >= 0 && st < s.length()) {
                    handleAutoClose(s.charAt(st), st);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        editor.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                boolean ctrl = event.isCtrlPressed();
                boolean shift = event.isShiftPressed();

                if (ctrl) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_S: host.onSave();           return true;
                        case KeyEvent.KEYCODE_F: host.onFind();           return true;
                        case KeyEvent.KEYCODE_Z:
                            if (shift) host.onRedo(); else host.onUndo();
                            return true;
                        case KeyEvent.KEYCODE_Y: host.onRedo();           return true;
                        case KeyEvent.KEYCODE_G: host.onGotoLine();       return true;
                        case KeyEvent.KEYCODE_SLASH:
                            host.onToggleComment(); return true;
                        case KeyEvent.KEYCODE_D:
                            if (shift) host.onDuplicateLine();
                            else host.onDeleteLine();
                            return true;
                    }
                }

                if (keyCode == KeyEvent.KEYCODE_TAB) {
                    insertTab();
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    return handleBackspaceDelete();
                }

                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (host.wantsAutoIndent()) {
                        insertNewlineWithIndent();
                        return true;
                    }
                }

                return false;
            }
        });
    }

    private void handleAutoClose(char typed, int pos) {
        int idx = OPENERS.indexOf(typed);
        if (idx < 0) return;

        Editable e = editor.getText();
        if (pos + 1 < e.length()) {
            char next = e.charAt(pos + 1);
            if (Character.isLetterOrDigit(next) || next == '_') return;
        }

        if (typed == '"' || typed == '\'' || typed == '`') {
            if (pos > 0 && e.charAt(pos - 1) == '\\') return;
        }

        char closer = CLOSERS.charAt(idx);
        doInternal(new Runnable() {
            @Override public void run() {
                e.insert(pos + 1, String.valueOf(closer));
                editor.setSelection(pos + 1);
            }
        });
    }

    private boolean handleBackspaceDelete() {
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        if (s != e || s <= 0) return false;

        final Editable text = editor.getText();
        final int start = s;
        char before = text.charAt(s - 1);
        if (s < text.length()) {
            char after = text.charAt(s);
            int oi = OPENERS.indexOf(before);
            if (oi >= 0 && CLOSERS.charAt(oi) == after) {
                doInternal(new Runnable() {
                    @Override public void run() { text.delete(start - 1, start + 1); }
                });
                return true;
            }
        }
        return false;
    }

    private void insertNewlineWithIndent() {
        int selStart = editor.getSelectionStart();
        int selEnd = editor.getSelectionEnd();
        if (selStart != selEnd) {
            final int s = selStart, e = selEnd;
            doInternal(new Runnable() {
                @Override public void run() { editor.getText().replace(s, e, "\n"); }
            });
            return;
        }

        final Editable text = editor.getText();
        int lineStart = selStart;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;

        StringBuilder indent = new StringBuilder();
        int i = lineStart;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') { indent.append(c); i++; }
            else break;
        }

        int j = selStart - 1;
        while (j >= lineStart && Character.isWhitespace(text.charAt(j))) j--;
        boolean openBrace = j >= lineStart && text.charAt(j) == '{';

        int k = selStart;
        while (k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) k++;
        boolean nextIsCloser = k < text.length() &&
                (text.charAt(k) == '}' || text.charAt(k) == ')' || text.charAt(k) == ']');

        String baseIndent = indent.toString();
        String newIndent = baseIndent + (openBrace ? spaces(host.tabSize()) : "");
        String insertion;

        if (openBrace && nextIsCloser && k == selStart) {
            insertion = "\n" + newIndent + "\n" + baseIndent;
        } else {
            insertion = "\n" + newIndent;
        }

        final String ins = insertion;
        final int at = selStart;
        doInternal(new Runnable() {
            @Override public void run() {
                text.insert(at, ins);
                editor.setSelection(at + ins.length());
            }
        });
    }

    private void insertTab() {
        final int s = editor.getSelectionStart();
        final int e = editor.getSelectionEnd();
        if (s < 0 || e < 0) return;
        final int a = Math.min(s, e), b = Math.max(s, e);

        if (a == b) {
            final Editable text = editor.getText();
            int lineStart = a;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
            final int ls = lineStart;
            final String sp = spaces(host.tabSize());
            doInternal(new Runnable() {
                @Override public void run() {
                    text.insert(ls, sp);
                    editor.setSelection(a + sp.length());
                }
            });
        } else {
            final Editable text = editor.getText();
            doInternal(new Runnable() {
                @Override public void run() {
                    text.replace(a, b, spaces(host.tabSize()));
                }
            });
        }
    }

    public void toggleLineComment() {
        final Editable text = editor.getText();
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        if (s > e) { int t = s; s = e; e = t; }

        int lineStart = s;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;

        int lineEnd = e;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;

        final int ls = lineStart;
        final int le = lineEnd;
        final String comment = "// ";
        final boolean allCommented = isAllCommented(text, ls, le, comment);

        doInternal(new Runnable() {
            @Override public void run() {
                if (allCommented) uncommentRange(text, ls, le, comment);
                else commentRange(text, ls, le, comment);
            }
        });
    }

    private boolean isAllCommented(Editable text, int start, int end, String comment) {
        int i = start;
        boolean anyLine = false;
        while (i < end) {
            int nl = indexOf(text, '\n', i, end);
            if (nl < 0) nl = end;
            int firstNonWs = i;
            while (firstNonWs < nl && (text.charAt(firstNonWs) == ' ' || text.charAt(firstNonWs) == '\t')) firstNonWs++;
            if (firstNonWs < nl) {
                anyLine = true;
                if (!startsWith(text, firstNonWs, nl, comment)) return false;
            }
            i = nl + 1;
        }
        return anyLine;
    }

    private boolean startsWith(CharSequence s, int from, int to, String needle) {
        if (from + needle.length() > to) return false;
        for (int k = 0; k < needle.length(); k++) {
            if (s.charAt(from + k) != needle.charAt(k)) return false;
        }
        return true;
    }

    private void commentRange(Editable text, int start, int end, String comment) {
        int i = end;
        while (i >= start) {
            int nl = lastIndexOf(text, '\n', start, i);
            int lineStart = nl < 0 ? start : nl + 1;
            if (lineStart <= i) {
                int firstNonWs = lineStart;
                while (firstNonWs < i && (text.charAt(firstNonWs) == ' ' || text.charAt(firstNonWs) == '\t')) firstNonWs++;
                text.insert(firstNonWs, comment);
            }
            if (nl < 0) break;
            i = nl - 1;
        }
    }

    private void uncommentRange(Editable text, int start, int end, String comment) {
        int i = end;
        while (i >= start) {
            int nl = lastIndexOf(text, '\n', start, i);
            int lineStart = nl < 0 ? start : nl + 1;
            if (lineStart <= i) {
                int firstNonWs = lineStart;
                while (firstNonWs < i && (text.charAt(firstNonWs) == ' ' || text.charAt(firstNonWs) == '\t')) firstNonWs++;
                if (startsWith(text, firstNonWs, i, comment)) {
                    text.delete(firstNonWs, firstNonWs + comment.length());
                }
            }
            if (nl < 0) break;
            i = nl - 1;
        }
    }

    public void duplicateLine() {
        final Editable text = editor.getText();
        int s = editor.getSelectionStart();
        int lineStart = s;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = s;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;

        final int ls = lineStart;
        final int le = lineEnd;
        final String line = text.subSequence(ls, le).toString();
        doInternal(new Runnable() {
            @Override public void run() {
                text.insert(le, "\n" + line);
                editor.setSelection(le + 1 + line.length());
            }
        });
    }

    public void deleteLine() {
        final Editable text = editor.getText();
        int s = editor.getSelectionStart();
        int lineStart = s;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = s;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;
        final int ls = lineStart;
        int end = lineEnd;
        if (end < text.length()) end++;
        final int le = end;
        doInternal(new Runnable() {
            @Override public void run() {
                text.delete(ls, le);
                int caret = Math.min(ls, text.length());
                editor.setSelection(caret);
            }
        });
    }

    private void doInternal(Runnable r) {
        internalEdit = true;
        try {
            r.run();
        } finally {
            internalEdit = false;
            if (host != null) {
                host.onProgrammaticEdit(editor.getText().toString(),
                        Math.max(0, editor.getSelectionStart()));
            }
        }
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    private static int indexOf(CharSequence s, char c, int from, int to) {
        for (int i = from; i < to; i++) if (s.charAt(i) == c) return i;
        return -1;
    }

    private static int lastIndexOf(CharSequence s, char c, int from, int to) {
        for (int i = Math.min(to, s.length() - 1); i >= from; i--) if (s.charAt(i) == c) return i;
        return -1;
    }
}
