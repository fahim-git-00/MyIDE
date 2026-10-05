package com.fahim.myide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Deque;

public class LogcatPanel {

    private static final int MAX_LINES = 5000;

    private final Context ctx;
    private final View root;
    private final TextView output;
    private final ScrollView scroll;
    private final EditText filter;
    private final TextView count;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Deque<Line> buffer = new ArrayDeque<Line>();
    private Process proc;
    private Thread reader;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile boolean autoScroll = true;
    private String filterText = "";

    private boolean showV = true, showD = true, showI = true, showW = true, showE = true;

    private static class Line {
        final char level;
        final String text;
        Line(char level, String text) { this.level = level; this.text = text; }
    }

    public LogcatPanel(Context ctx, View root) {
        this.ctx = ctx;
        this.root = root;
        this.output = root.findViewById(R.id.logOutput);
        this.scroll = root.findViewById(R.id.logScroll);
        this.filter = root.findViewById(R.id.logFilter);
        this.count  = root.findViewById(R.id.logCount);

        output.setMovementMethod(new ScrollingMovementMethod());
        output.setTextIsSelectable(true);

        wire();
    }

    private void wire() {
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                filterText = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                rerender();
            }
        });

        root.findViewById(R.id.btnLogPause).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                paused = !paused;
                ((TextView) v).setText(paused ? "▶" : "⏸");
            }
        });

        root.findViewById(R.id.btnLogClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                buffer.clear();
                output.setText("");
                updateCount();
            }
        });

        root.findViewById(R.id.btnLogCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (Line l : buffer) if (matches(l)) sb.append(l.text).append('\n');
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("logcat", sb.toString()));
                toast("Copied " + buffer.size() + " lines");
            }
        });

        root.findViewById(R.id.btnLogSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveLog(); }
        });

        root.findViewById(R.id.btnLogAuto).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoScroll = !autoScroll;
                ((TextView) v).setText(autoScroll ? "⤓" : "⤒");
            }
        });

        CheckBox v = root.findViewById(R.id.lvlV);
        CheckBox d = root.findViewById(R.id.lvlD);
        CheckBox i = root.findViewById(R.id.lvlI);
        CheckBox w = root.findViewById(R.id.lvlW);
        CheckBox e = root.findViewById(R.id.lvlE);

        CompoundButton.OnCheckedChangeListener l = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                showV = v.isChecked();
                showD = d.isChecked();
                showI = i.isChecked();
                showW = w.isChecked();
                showE = e.isChecked();
                rerender();
            }
        };
        v.setOnCheckedChangeListener(l);
        d.setOnCheckedChangeListener(l);
        i.setOnCheckedChangeListener(l);
        w.setOnCheckedChangeListener(l);
        e.setOnCheckedChangeListener(l);
    }

    public void resume() {
        if (running) return;
        running = true;
        try {
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
            proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "*:V"});
            final BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            reader = new Thread(new Runnable() {
                @Override public void run() {
                    String line;
                    try {
                        while (running && (line = r.readLine()) != null) {
                            final String l = line;
                            ui.post(new Runnable() {
                                @Override public void run() { append(l); }
                            });
                        }
                    } catch (Exception ignored) {}
                }
            }, "logcat-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            append("logcat failed: " + e.getMessage());
            running = false;
        }
    }

    public void pause() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
        proc = null;
        reader = null;
    }

    public void stop() { pause(); }

    private void append(String raw) {
        if (paused) return;
        Line l = parse(raw);
        buffer.addLast(l);
        while (buffer.size() > MAX_LINES) buffer.removeFirst();

        if (matches(l)) {
            appendColored(l);
            if (autoScroll) {
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); }
                });
            }
        }
        updateCount();
    }

    private Line parse(String raw) {
        char lvl = ' ';
        if (raw != null && raw.length() > 19) {
            // Format: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message"
            int p = raw.indexOf(' ');
            if (p > 0) {
                int q = raw.indexOf(' ', p + 1);
                if (q > 0) {
                    int r = raw.indexOf(' ', q + 1);
                    if (r > 0) {
                        int s = raw.indexOf(' ', r + 1);
                        if (s > 0 && s + 1 < raw.length()) {
                            char c = raw.charAt(s + 1);
                            if (c == 'V' || c == 'D' || c == 'I' || c == 'W'
                                    || c == 'E' || c == 'F' || c == 'A') {
                                lvl = c;
                            }
                        }
                    }
                }
            }
        }
        return new Line(lvl, raw == null ? "" : raw);
    }

    private boolean matches(Line l) {
        switch (l.level) {
            case 'V': if (!showV) return false; break;
            case 'D': if (!showD) return false; break;
            case 'I': if (!showI) return false; break;
            case 'W': if (!showW) return false; break;
            case 'E': case 'F': if (!showE) return false; break;
        }
        if (filterText.isEmpty()) return true;
        return l.text.toLowerCase(java.util.Locale.ROOT).contains(filterText);
    }

    private void appendColored(Line l) {
        SpannableString ss = new SpannableString(l.text + "\n");
        ss.setSpan(new ForegroundColorSpan(colorFor(l.level)),
                0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.append(ss);
    }

    private int colorFor(char lvl) {
        switch (lvl) {
            case 'E': case 'F': return 0xFFFF6666;
            case 'W': return 0xFFFFCC66;
            case 'I': return 0xFF66FF66;
            case 'D': return 0xFF66CCFF;
            case 'V': return 0xFFAAAAAA;
            default:  return 0xFFD4D4D4;
        }
    }

    private void rerender() {
        output.setText("");
        for (Line l : buffer) if (matches(l)) appendColored(l);
        if (autoScroll) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); }
            });
        }
        updateCount();
    }

    private void updateCount() {
        int shown = 0;
        for (Line l : buffer) if (matches(l)) shown++;
        count.setText(shown + " / " + buffer.size());
    }

    private void saveLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "logcat_" + System.currentTimeMillis() + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            for (Line l : buffer) if (matches(l)) fos.write((l.text + "\n").getBytes("UTF-8"));
            fos.close();
            toast("Saved: " + f.getAbsolutePath());
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
    }

    private void toast(String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
