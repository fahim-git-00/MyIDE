package com.fahim.myide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class TerminalPanel {

    private final Context ctx;
    private final View root;
    private final TextView output;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView cwdLabel;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private File cwd;
    private Process proc;
    private Thread reader;
    private volatile boolean running = false;

    public TerminalPanel(Context ctx, View root, File projectRoot) {
        this.ctx = ctx;
        this.root = root;
        this.output = root.findViewById(R.id.termOutput);
        this.scroll = root.findViewById(R.id.termScroll);
        this.input  = root.findViewById(R.id.termInput);
        this.cwdLabel = root.findViewById(R.id.termCwd);
        this.cwd = projectRoot != null ? projectRoot : new File("/sdcard");

        output.setMovementMethod(new ScrollingMovementMethod());
        output.setTextIsSelectable(true);

        wire();
        updateCwdLabel();
        append("[terminal ready] " + cwd.getAbsolutePath(), 0xFF6A6A6A);
    }

    private void wire() {
        root.findViewById(R.id.btnTermRun).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runInput(); }
        });
        root.findViewById(R.id.btnTermSend).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runInput(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO) {
                    runInput();
                    return true;
                }
                return false;
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_ENTER) {
                    runInput();
                    return true;
                }
                return false;
            }
        });

        root.findViewById(R.id.btnTermClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { output.setText(""); }
        });
        root.findViewById(R.id.btnTermCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("terminal",
                        output.getText().toString()));
                toast("Copied");
            }
        });
    }

    public void setCwd(File f) {
        if (f != null && f.isDirectory()) {
            cwd = f;
            updateCwdLabel();
        }
    }

    private void updateCwdLabel() {
        cwdLabel.setText(cwd.getAbsolutePath());
    }

    private void runInput() {
        String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) return;
        input.setText("");

        if (cmd.equals("clear") || cmd.equals("cls")) { output.setText(""); return; }

        // Built-in: cd
        if (cmd.equals("cd") || cmd.startsWith("cd ")) {
            String arg = cmd.equals("cd") ? "" : cmd.substring(3).trim();
            File next;
            if (arg.isEmpty()) next = new File("/sdcard");
            else if (arg.equals("~")) next = new File("/sdcard");
            else if (arg.equals("..")) next = cwd.getParentFile();
            else if (arg.startsWith("/")) next = new File(arg);
            else next = new File(cwd, arg);
            if (next != null && next.isDirectory()) {
                cwd = next;
                updateCwdLabel();
                append("$ " + cmd, 0xFF4EC9B0);
            } else {
                append("$ " + cmd, 0xFF4EC9B0);
                append("cd: no such directory: " + arg, 0xFFF44747);
            }
            return;
        }

        append("$ " + cmd, 0xFF4EC9B0);
        execShell(cmd);
    }

    private void execShell(final String cmd) {
        stop();
        running = true;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c", cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", "/data/data/com.termux/files/home");
            proc = pb.start();

            final BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            reader = new Thread(new Runnable() {
                @Override public void run() {
                    String line;
                    try {
                        while (running && (line = r.readLine()) != null) {
                            final String l = line;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    int color = l.toLowerCase().contains("error")
                                            ? 0xFFF44747
                                            : 0xFFCCCCCC;
                                    append(l, color);
                                }
                            });
                        }
                    } catch (Exception ignored) {}
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try { proc.waitFor(); } catch (Exception ignored) {}
                            int code = 0;
                            try { code = proc.exitValue(); } catch (Exception ignored) {}
                            append("[exit " + code + "]", code == 0 ? 0xFF4EC9B0 : 0xFFF44747);
                            running = false;
                        }
                    });
                }
            }, "terminal-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            append("exec failed: " + e.getMessage(), 0xFFF44747);
            running = false;
        }
    }

    public void stop() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
        proc = null;
        reader = null;
    }

    private void append(String s, int color) {
        SpannableString ss = new SpannableString(s + "\n");
        ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.append(ss);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    private void toast(String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
