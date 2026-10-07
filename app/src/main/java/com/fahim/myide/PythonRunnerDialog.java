package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

public final class PythonRunnerDialog {

    public interface Handler {
        void onRun(String version, String stdin);
    }

    private PythonRunnerDialog() {}

    public static void show(final Context ctx, final String scriptName,
                            final Handler handler) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = dp(ctx, 18);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF252526);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Execute");
        title.setTextColor(0xFF4FC3F7);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView lblIn = new TextView(ctx);
        lblIn.setText("Input (Optional)");
        lblIn.setTextColor(0xFFD4D4D4);
        lblIn.setTextSize(14f);
        lblIn.setPadding(0, dp(ctx, 18), 0, dp(ctx, 6));
        root.addView(lblIn);

        final EditText stdin = new EditText(ctx);
        stdin.setHint("stdin passed to the script");
        stdin.setTextColor(0xFFD4D4D4);
        stdin.setHintTextColor(0xFF6A6A6A);
        stdin.setBackgroundColor(0xFF3C3C3C);
        stdin.setTypeface(Typeface.MONOSPACE);
        stdin.setTextSize(13f);
        stdin.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        stdin.setMinLines(2);
        stdin.setGravity(Gravity.TOP | Gravity.START);
        stdin.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(stdin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View d1 = new View(ctx);
        d1.setBackgroundColor(0xFF3E3E42);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        dlp.topMargin = dp(ctx, 20);
        dlp.bottomMargin = dp(ctx, 16);
        root.addView(d1, dlp);

        TextView lblLang = new TextView(ctx);
        lblLang.setText("Programming Language");
        lblLang.setTextColor(0xFFD4D4D4);
        lblLang.setTextSize(14f);
        root.addView(lblLang);

        final Spinner langSp = new Spinner(ctx);
        ArrayAdapter<String> langAd = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_spinner_item,
                new String[]{"Python"});
        langAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSp.setAdapter(langAd);
        root.addView(langSp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lblCmp = new TextView(ctx);
        lblCmp.setText("Compiler");
        lblCmp.setTextColor(0xFFD4D4D4);
        lblCmp.setTextSize(14f);
        lblCmp.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
        root.addView(lblCmp);

        final Spinner verSp = new Spinner(ctx);
        ArrayAdapter<String> verAd = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_spinner_item,
                new String[]{"Python (3.13.2)", "Python (3.12)", "Python (3.11)", "Python (3.10)"});
        verAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        verSp.setAdapter(verAd);
        root.addView(verSp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(ctx, 22), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("CANCEL");
        cancel.setTextColor(0xFF4FC3F7);
        cancel.setTextSize(14f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView exec = new TextView(ctx);
        exec.setText("EXECUTE");
        exec.setTextColor(0xFF4FC3F7);
        exec.setTextSize(14f);
        exec.setTypeface(Typeface.DEFAULT_BOLD);
        exec.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        exec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ver = (String) verSp.getSelectedItem();
                String verShort = "3.13";
                if (ver != null) {
                    int a = ver.indexOf('(');
                    int b = ver.indexOf(')');
                    if (a >= 0 && b > a) verShort = ver.substring(a + 1, b).trim();
                }
                String in = stdin.getText().toString();
                dlg.dismiss();
                if (handler != null) handler.onRun(verShort, in);
            }
        });
        btns.addView(exec);
        root.addView(btns);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dlg.show();
    }

    private static int dp(Context c, int v) {
        return (int)(v * c.getResources().getDisplayMetrics().density);
    }
}
