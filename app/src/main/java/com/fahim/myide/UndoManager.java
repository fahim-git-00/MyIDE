package com.fahim.myide;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Simple text undo/redo with debounce. Marks programmatic edits so
 * other watchers (highlighter, enhancer) can suppress their own
 * reactions when we're the ones mutating the buffer.
 */
public class UndoManager {

    public interface ChangeListener {
        void onProgrammaticChange();
    }

    private static final int MAX_DEPTH = 200;
    private static final long DEBOUNCE_MS = 350;

    private final EditText edit;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Deque<Snapshot> undo = new ArrayDeque<Snapshot>();
    private final Deque<Snapshot> redo = new ArrayDeque<Snapshot>();

    private String lastCommitted;
    private boolean selfEdit = false;
    private Runnable pending;
    private int lastCaret;
    private ChangeListener listener;

    private static class Snapshot {
        final String text;
        final int caret;
        Snapshot(String t, int c) { text = t; caret = c; }
    }

    public UndoManager(EditText edit) {
        this.edit = edit;
        this.lastCommitted = edit.getText().toString();
        this.lastCaret = edit.getSelectionStart();
        if (this.lastCaret < 0) this.lastCaret = 0;

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (selfEdit) return;
                scheduleCommit(s.toString());
            }
        });
    }

    public void setChangeListener(ChangeListener l) { this.listener = l; }

    /** True when the last change was made by undo/redo. */
    public boolean isProgrammatic() { return selfEdit; }

    public void reset() {
        if (pending != null) {
            h.removeCallbacks(pending);
            pending = null;
        }
        undo.clear();
        redo.clear();
        lastCommitted = edit.getText().toString();
        lastCaret = Math.max(0, edit.getSelectionStart());
    }

    private void scheduleCommit(final String current) {
        if (pending != null) h.removeCallbacks(pending);
        pending = new Runnable() {
            @Override public void run() {
                commit(edit.getText().toString());
                pending = null;
            }
        };
        h.postDelayed(pending, DEBOUNCE_MS);
    }

    private void commit(String current) {
        if (current.equals(lastCommitted)) return;
        undo.push(new Snapshot(lastCommitted, lastCaret));
        while (undo.size() > MAX_DEPTH) undo.removeLast();
        redo.clear();
        lastCommitted = current;
        int sel = edit.getSelectionStart();
        lastCaret = sel < 0 ? 0 : sel;
    }

    public void forceCommit() {
        if (pending != null) {
            h.removeCallbacks(pending);
            pending = null;
        }
        commit(edit.getText().toString());
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        forceCommit();
        if (undo.isEmpty()) return;
        int sel = edit.getSelectionStart();
        redo.push(new Snapshot(lastCommitted, sel < 0 ? 0 : sel));
        Snapshot s = undo.pop();
        apply(s);
    }

    public void redo() {
        forceCommit();
        if (redo.isEmpty()) return;
        int sel = edit.getSelectionStart();
        undo.push(new Snapshot(lastCommitted, sel < 0 ? 0 : sel));
        Snapshot s = redo.pop();
        apply(s);
    }

    private void apply(Snapshot s) {
        selfEdit = true;
        try {
            edit.setText(s.text);
            int caret = Math.max(0, Math.min(s.caret, s.text.length()));
            edit.setSelection(caret);
        } finally {
            selfEdit = false;
        }
        lastCommitted = s.text;
        lastCaret = s.caret;
        if (listener != null) listener.onProgrammaticChange();
    }

    /** Called by EditorEnhancer when it performs an internal edit. */
    public void noteInternalEdit(String newText, int newCaret) {
        lastCommitted = newText;
        lastCaret = newCaret;
    }
}
