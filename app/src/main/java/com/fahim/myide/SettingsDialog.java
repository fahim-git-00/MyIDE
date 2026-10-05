package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsDialog {

    private SettingsDialog() {}

    public static void show(final Context ctx, final Runnable onApplied) {
        final SharedPreferences prefs = ThemeHelper.prefs(ctx);

        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final View root = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_settings, null, false);

        // Editor controls
        final SeekBar fontSeek     = root.findViewById(R.id.prefFontSize);
        final TextView fontValue   = root.findViewById(R.id.prefFontSizeValue);
        final RadioButton tab2     = root.findViewById(R.id.prefTab2);
        final RadioButton tab4     = root.findViewById(R.id.prefTab4);
        final RadioButton tab8     = root.findViewById(R.id.prefTab8);
        final CheckBox wrap        = root.findViewById(R.id.prefWordWrap);
        final CheckBox lineNums    = root.findViewById(R.id.prefLineNumbers);
        final CheckBox autoClose   = root.findViewById(R.id.prefAutoClose);
        final CheckBox autoIndent  = root.findViewById(R.id.prefAutoIndent);
        final CheckBox syntax      = root.findViewById(R.id.prefSyntaxHighlight);

        // Appearance
        final RadioButton themeDark   = root.findViewById(R.id.prefThemeDark);
        final RadioButton themeLight  = root.findViewById(R.id.prefThemeLight);
        final RadioButton themeSystem = root.findViewById(R.id.prefThemeSystem);

        // Build
        final EditText minSdk    = root.findViewById(R.id.prefMinSdk);
        final EditText targetSdk = root.findViewById(R.id.prefTargetSdk);
        final RadioButton ktAuto   = root.findViewById(R.id.prefKotlinAuto);
        final RadioButton ktLocal  = root.findViewById(R.id.prefKotlinLocal);
        final RadioButton ktRemote = root.findViewById(R.id.prefKotlinRemote);

        // Advanced
        final EditText token = root.findViewById(R.id.prefGithubToken);

        // ---- initial values ----
        int fs = ThemeHelper.getFontSize(ctx);
        fontSeek.setProgress(fs - 8);
        fontValue.setText(fs + "sp");
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean from) {
                fontValue.setText((p + 8) + "sp");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        int tab = ThemeHelper.getTabSize(ctx);
        if (tab == 2) tab2.setChecked(true);
        else if (tab == 8) tab8.setChecked(true);
        else tab4.setChecked(true);

        wrap.setChecked(ThemeHelper.isWordWrap(ctx));
        lineNums.setChecked(ThemeHelper.isLineNumbers(ctx));
        autoClose.setChecked(ThemeHelper.isAutoClose(ctx));
        autoIndent.setChecked(ThemeHelper.isAutoIndent(ctx));
        syntax.setChecked(ThemeHelper.isSyntaxHighlight(ctx));

        int theme = ThemeHelper.getThemeMode(ctx);
        if (theme == ThemeHelper.THEME_LIGHT) themeLight.setChecked(true);
        else if (theme == ThemeHelper.THEME_SYSTEM) themeSystem.setChecked(true);
        else themeDark.setChecked(true);

        minSdk.setText(String.valueOf(ThemeHelper.getMinSdk(ctx)));
        targetSdk.setText(String.valueOf(ThemeHelper.getTargetSdk(ctx)));

        String km = ThemeHelper.getKotlinMode(ctx);
        if ("local".equals(km)) ktLocal.setChecked(true);
        else if ("remote".equals(km)) ktRemote.setChecked(true);
        else ktAuto.setChecked(true);

        String gh = ctx.getSharedPreferences("github", Context.MODE_PRIVATE)
                .getString("token", "");
        if (gh != null) token.setText(gh);

        // ---- actions ----
        root.findViewById(R.id.btnSettingsClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        root.findViewById(R.id.btnSettingsReset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().clear().apply();
                Toast.makeText(ctx, "Reset to defaults", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                if (onApplied != null) onApplied.run();
            }
        });

        root.findViewById(R.id.btnClearRecent).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RecentProjects.clear(ctx);
                Toast.makeText(ctx, "Recent projects cleared", Toast.LENGTH_SHORT).show();
            }
        });

        root.findViewById(R.id.btnSettingsApply).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Editor
                ThemeHelper.setFontSize(ctx, fontSeek.getProgress() + 8);

                int ts = tab4.isChecked() ? 4 : (tab2.isChecked() ? 2 : 8);
                ThemeHelper.setTabSize(ctx, ts);

                prefs.edit()
                    .putBoolean("word_wrap", wrap.isChecked())
                    .putBoolean("line_numbers", lineNums.isChecked())
                    .putBoolean("auto_close", autoClose.isChecked())
                    .putBoolean("auto_indent", autoIndent.isChecked())
                    .putBoolean("syntax_highlight", syntax.isChecked())
                    .apply();

                // Appearance
                int mode = themeLight.isChecked() ? ThemeHelper.THEME_LIGHT
                        : themeSystem.isChecked() ? ThemeHelper.THEME_SYSTEM
                        : ThemeHelper.THEME_DARK;
                ThemeHelper.setThemeMode(ctx, mode);

                // Build
                try { ThemeHelper.setMinSdk(ctx, Integer.parseInt(minSdk.getText().toString().trim())); }
                catch (Exception ignored) {}
                try { ThemeHelper.setTargetSdk(ctx, Integer.parseInt(targetSdk.getText().toString().trim())); }
                catch (Exception ignored) {}

                String mode2 = ktLocal.isChecked() ? "local"
                        : ktRemote.isChecked() ? "remote" : "auto";
                ThemeHelper.setKotlinMode(ctx, mode2);

                // Advanced
                ctx.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                    .putString("token", token.getText().toString().trim()).apply();

                Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                if (onApplied != null) onApplied.run();
            }
        });

        dlg.setContentView(root);
        dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.show();
    }
}
