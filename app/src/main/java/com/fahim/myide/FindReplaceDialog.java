package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FindReplaceDialog {

    public static Dialog create(final Context ctx, final EditText target) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final View root = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_find, null, false);

        final EditText findField    = root.findViewById(R.id.findField);
        final EditText replaceField = root.findViewById(R.id.replaceField);
        final CheckBox caseBox      = root.findViewById(R.id.findCase);
        final CheckBox regexBox     = root.findViewById(R.id.findRegex);
        final CheckBox wholeBox     = root.findViewById(R.id.findWhole);
        final TextView counter      = root.findViewById(R.id.findCounter);
        final TextView prevBtn      = root.findViewById(R.id.findPrev);
        final TextView nextBtn      = root.findViewById(R.id.findNext);
        final TextView closeBtn     = root.findViewById(R.id.findClose);
        final TextView replaceOne   = root.findViewById(R.id.replaceOne);
        final TextView replaceAll   = root.findViewById(R.id.replaceAll);

        final List<int[]> matches = new ArrayList<int[]>();
        final int[] current = { -1 };

        final Runnable rescan = new Runnable() {
            @Override public void run() {
                matches.clear();
                current[0] = -1;

                String needle = findField.getText().toString();
                if (needle.isEmpty()) { counter.setText(""); return; }

                String hay = target.getText().toString();
                Pattern p = compile(needle, caseBox.isChecked(), regexBox.isChecked(), wholeBox.isChecked());
                if (p == null) { counter.setText("bad regex"); return; }

                Matcher m = p.matcher(hay);
                while (m.find()) matches.add(new int[]{m.start(), m.end()});

                Spannable span = new SpannableString(hay);
                for (int[] r : matches) {
                    span.setSpan(new BackgroundColorSpan(0x5533AAFF), r[0], r[1],
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                int cs = target.getSelectionStart();
                target.setText(span);
                int safe = Math.max(0, Math.min(cs, span.length()));
                target.setSelection(safe);

                if (matches.isEmpty()) {
                    counter.setText("0");
                } else {
                    counter.setText("0/" + matches.size());
                    current[0] = 0;
                    focus(target, counter, matches, current);
                }
            }
        };

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rescan.run(); }
        };
        findField.addTextChangedListener(w);

        CompoundButton.OnCheckedChangeListener tog = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean b) { rescan.run(); }
        };
        caseBox.setOnCheckedChangeListener(tog);
        regexBox.setOnCheckedChangeListener(tog);
        wholeBox.setOnCheckedChangeListener(tog);

        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (matches.isEmpty()) return;
                current[0] = (current[0] - 1 + matches.size()) % matches.size();
                focus(target, counter, matches, current);
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (matches.isEmpty()) return;
                current[0] = (current[0] + 1) % matches.size();
                focus(target, counter, matches, current);
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        replaceOne.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (matches.isEmpty() || current[0] < 0) return;
                int[] r = matches.get(current[0]);
                Editable e = target.getText();
                e.replace(r[0], r[1], replaceField.getText().toString());
                rescan.run();
            }
        });
        replaceAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String needle = findField.getText().toString();
                if (needle.isEmpty()) return;
                Pattern p = compile(needle, caseBox.isChecked(), regexBox.isChecked(), wholeBox.isChecked());
                if (p == null) return;
                String rep = replaceField.getText().toString();
                String hay = target.getText().toString();
                String out = p.matcher(hay).replaceAll(Matcher.quoteReplacement(rep));
                target.setText(out);
                rescan.run();
            }
        });

        String sel = selected(target);
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);

        dlg.setContentView(root);
        dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setGravity(Gravity.BOTTOM);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        rescan.run();
        return dlg;
    }

    private static void focus(EditText target, TextView counter,
                              List<int[]> matches, int[] current) {
        int[] r = matches.get(current[0]);
        target.requestFocus();
        target.setSelection(r[0], r[1]);
        counter.setText((current[0] + 1) + "/" + matches.size());
    }

    private static String selected(EditText t) {
        int s = t.getSelectionStart(), e = t.getSelectionEnd();
        if (s < 0 || e < 0 || s == e) return null;
        return t.getText().subSequence(Math.min(s, e), Math.max(s, e)).toString();
    }

    private static Pattern compile(String needle, boolean caseSens,
                                   boolean regex, boolean whole) {
        try {
            int flags = caseSens ? 0 : Pattern.CASE_INSENSITIVE;
            String expr = regex ? needle : Pattern.quote(needle);
            if (whole) expr = "\\b" + expr + "\\b";
            return Pattern.compile(expr, flags);
        } catch (Exception e) { return null; }
    }
}
