package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

public final class BuildDialog {

    public interface Handler {
        void onBuild(int minSdk, int targetSdk, boolean release);
    }

    private BuildDialog() {}

    public static void show(final Context ctx, int defaultMin, int defaultTarget,
                            final Handler handler) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = dp(ctx, 16);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF252526);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Build APK");
        title.setTextColor(0xFFDCDCAA);
        title.setTextSize(16f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Configure build settings");
        sub.setTextColor(0xFF858585);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));
        root.addView(sub);

        // Min SDK
        TextView lblMin = new TextView(ctx);
        lblMin.setText("Minimum SDK");
        lblMin.setTextColor(0xFFDCDCAA);
        lblMin.setTextSize(12f);
        root.addView(lblMin);

        final EditText minEt = new EditText(ctx);
        minEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minEt.setText(String.valueOf(defaultMin));
        minEt.setTextColor(0xFFD4D4D4);
        minEt.setBackgroundColor(0xFF3C3C3C);
        minEt.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        root.addView(minEt, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hintMin = new TextView(ctx);
        hintMin.setText("Min supported: 21. Common: 24");
        hintMin.setTextColor(0xFF6A6A6A);
        hintMin.setTextSize(10f);
        hintMin.setPadding(0, dp(ctx, 2), 0, dp(ctx, 10));
        root.addView(hintMin);

        // Target SDK
        TextView lblTgt = new TextView(ctx);
        lblTgt.setText("Target SDK");
        lblTgt.setTextColor(0xFFDCDCAA);
        lblTgt.setTextSize(12f);
        root.addView(lblTgt);

        final EditText targetEt = new EditText(ctx);
        targetEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        targetEt.setText(String.valueOf(defaultTarget));
        targetEt.setTextColor(0xFFD4D4D4);
        targetEt.setBackgroundColor(0xFF3C3C3C);
        targetEt.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        root.addView(targetEt, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hintTgt = new TextView(ctx);
        hintTgt.setText("Common: 34 or 35");
        hintTgt.setTextColor(0xFF6A6A6A);
        hintTgt.setTextSize(10f);
        hintTgt.setPadding(0, dp(ctx, 2), 0, dp(ctx, 12));
        root.addView(hintTgt);

        // Build type
        TextView lblType = new TextView(ctx);
        lblType.setText("Build type");
        lblType.setTextColor(0xFFDCDCAA);
        lblType.setTextSize(12f);
        root.addView(lblType);

        final RadioButton dbg = new RadioButton(ctx);
        dbg.setText("Debug (unsigned, installable)");
        dbg.setTextColor(0xFFD4D4D4);
        dbg.setChecked(true);
        root.addView(dbg);

        final RadioButton rel = new RadioButton(ctx);
        rel.setText("Release (signed, optimized)");
        rel.setTextColor(0xFFD4D4D4);
        root.addView(rel);

        // Buttons
        android.widget.LinearLayout btns = new android.widget.LinearLayout(ctx);
        btns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.END);
        btns.setPadding(0, dp(ctx, 12), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFD4D4D4);
        cancel.setTextSize(14f);
        cancel.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView build = new TextView(ctx);
        build.setText("Build");
        build.setTextColor(0xFFFFFFFF);
        build.setTextSize(14f);
        build.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        build.setBackgroundColor(0xFF0E639C);
        build.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10));
        build.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int min, target;
                try { min = Integer.parseInt(minEt.getText().toString().trim()); }
                catch (Exception e) { min = 21; }
                try { target = Integer.parseInt(targetEt.getText().toString().trim()); }
                catch (Exception e) { target = 34; }
                if (min < 1) min = 21;
                if (target < min) target = min;
                final int fMin = min, fTarget = target;
                final boolean isRelease = rel.isChecked();
                dlg.dismiss();
                if (handler != null) handler.onBuild(fMin, fTarget, isRelease);
            }
        });
        btns.addView(build);
        root.addView(btns);

        dlg.setContentView(root);
        dlg.getWindow().setLayout(
                (int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.show();
    }

    private static int dp(Context ctx, int v) {
        return (int)(v * ctx.getResources().getDisplayMetrics().density);
    }
}
