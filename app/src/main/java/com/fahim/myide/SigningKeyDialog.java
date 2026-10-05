package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class SigningKeyDialog {

    public static final String MODE_BUNDLED  = "bundled";
    public static final String MODE_PK8PEM   = "pk8pem";
    public static final String MODE_KEYSTORE = "keystore";

    private SigningKeyDialog() {}

    private static int dp(Context c, int v) {
        return (int)(v * c.getResources().getDisplayMetrics().density);
    }

    public static void show(final Context ctx) {
        final SharedPreferences prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = dp(ctx, 16);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF252526);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Signing Key");
        title.setTextColor(0xFFDCDCAA);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Which key signs the built APK?");
        sub.setTextColor(0xFF858585);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(ctx,4), 0, dp(ctx,10));
        root.addView(sub);

        final RadioGroup modes = new RadioGroup(ctx);
        modes.setOrientation(RadioGroup.VERTICAL);
        final RadioButton rbBundled = new RadioButton(ctx);
        rbBundled.setText("Bundled test key (debug only)");
        rbBundled.setTextColor(0xFFD4D4D4);
        final RadioButton rbPk8 = new RadioButton(ctx);
        rbPk8.setText("Custom PK8 + PEM");
        rbPk8.setTextColor(0xFFD4D4D4);
        final RadioButton rbKs = new RadioButton(ctx);
        rbKs.setText("Custom keystore (.jks / .keystore)");
        rbKs.setTextColor(0xFFD4D4D4);
        modes.addView(rbBundled);
        modes.addView(rbPk8);
        modes.addView(rbKs);
        root.addView(modes);

        // --- pk8+pem box ---
        final LinearLayout pk8Box = new LinearLayout(ctx);
        pk8Box.setOrientation(LinearLayout.VERTICAL);
        pk8Box.setPadding(0, dp(ctx,4), 0, dp(ctx,4));
        final EditText pk8Et = field(ctx, "path/to/key.pk8");
        addFieldRow(ctx, pk8Box, "PK8 private key", pk8Et);
        final EditText pemEt = field(ctx, "path/to/cert.pem");
        addFieldRow(ctx, pk8Box, "PEM certificate", pemEt);
        root.addView(pk8Box);

        // --- keystore box ---
        final LinearLayout ksBox = new LinearLayout(ctx);
        ksBox.setOrientation(LinearLayout.VERTICAL);
        ksBox.setPadding(0, dp(ctx,4), 0, dp(ctx,4));
        final EditText ksEt = field(ctx, "path/to/release.jks");
        addFieldRow(ctx, ksBox, "Keystore file", ksEt);
        final EditText aliasEt = field(ctx, "mykey");
        addFieldRow(ctx, ksBox, "Key alias", aliasEt);
        final EditText storePassEt = field(ctx, "password");
        storePassEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addFieldRow(ctx, ksBox, "Store password", storePassEt);
        final EditText keyPassEt = field(ctx, "same as store if empty");
        keyPassEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addFieldRow(ctx, ksBox, "Key password (optional)", keyPassEt);
        root.addView(ksBox);

        // ---- load saved ----
        String saved = prefs.getString("sign_key_mode", MODE_BUNDLED);
        if (MODE_PK8PEM.equals(saved)) modes.check(rbPk8.getId());
        else if (MODE_KEYSTORE.equals(saved)) modes.check(rbKs.getId());
        else modes.check(rbBundled.getId());

        pk8Et.setText(prefs.getString("sign_pk8_path", ""));
        pemEt.setText(prefs.getString("sign_pem_path", ""));
        ksEt.setText(prefs.getString("sign_ks_path", ""));
        aliasEt.setText(prefs.getString("sign_ks_alias", ""));
        storePassEt.setText(prefs.getString("sign_ks_store_pass", ""));
        keyPassEt.setText(prefs.getString("sign_ks_key_pass", ""));

        // ---- visibility ----
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                pk8Box.setVisibility(id == rbPk8.getId() ? View.VISIBLE : View.GONE);
                ksBox.setVisibility(id == rbKs.getId() ? View.VISIBLE : View.GONE);
            }
        });
        pk8Box.setVisibility(rbPk8.isChecked() ? View.VISIBLE : View.GONE);
        ksBox.setVisibility(rbKs.isChecked() ? View.VISIBLE : View.GONE);

        // ---- buttons ----
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(ctx,14), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFD4D4D4);
        cancel.setTextSize(14f);
        cancel.setPadding(dp(ctx,16), dp(ctx,10), dp(ctx,16), dp(ctx,10));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView save = new TextView(ctx);
        save.setText("Save");
        save.setTextColor(0xFFFFFFFF);
        save.setTextSize(14f);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setBackgroundColor(0xFF0E639C);
        save.setPadding(dp(ctx,20), dp(ctx,10), dp(ctx,20), dp(ctx,10));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int id = modes.getCheckedRadioButtonId();
                String mode = id == rbPk8.getId() ? MODE_PK8PEM
                            : id == rbKs.getId() ? MODE_KEYSTORE
                            : MODE_BUNDLED;

                if (MODE_PK8PEM.equals(mode)) {
                    if (!new File(pk8Et.getText().toString().trim()).isFile()) {
                        Toast.makeText(ctx, "PK8 file not found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!new File(pemEt.getText().toString().trim()).isFile()) {
                        Toast.makeText(ctx, "PEM file not found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                if (MODE_KEYSTORE.equals(mode)) {
                    if (!new File(ksEt.getText().toString().trim()).isFile()) {
                        Toast.makeText(ctx, "Keystore file not found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (aliasEt.getText().toString().trim().isEmpty()) {
                        Toast.makeText(ctx, "Alias is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                prefs.edit()
                    .putString("sign_key_mode", mode)
                    .putString("sign_pk8_path", pk8Et.getText().toString().trim())
                    .putString("sign_pem_path", pemEt.getText().toString().trim())
                    .putString("sign_ks_path", ksEt.getText().toString().trim())
                    .putString("sign_ks_alias", aliasEt.getText().toString().trim())
                    .putString("sign_ks_store_pass", storePassEt.getText().toString())
                    .putString("sign_ks_key_pass", keyPassEt.getText().toString())
                    .apply();

                Toast.makeText(ctx, "Signing key saved", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            }
        });
        btns.addView(save);
        root.addView(btns);

        dlg.setContentView(root);
        dlg.getWindow().setLayout(
            (int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.92f),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.show();
    }

    private static EditText field(Context ctx, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        et.setBackgroundColor(0xFF3C3C3C);
        et.setTextSize(12f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setPadding(dp(ctx,10), dp(ctx,10), dp(ctx,10), dp(ctx,10));
        et.setSingleLine(true);
        return et;
    }

    private static void addFieldRow(final Context ctx, LinearLayout parent,
                                    String labelText, final EditText et) {
        TextView lbl = new TextView(ctx);
        lbl.setText(labelText);
        lbl.setTextColor(0xFFDCDCAA);
        lbl.setTextSize(11f);
        lbl.setPadding(0, dp(ctx,8), 0, dp(ctx,2));
        parent.addView(lbl);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(et, etLp);

        TextView pick = new TextView(ctx);
        pick.setText("Pick");
        pick.setTextColor(0xFF4FC3F7);
        pick.setTextSize(12f);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setPadding(dp(ctx,10), dp(ctx,10), dp(ctx,10), dp(ctx,10));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File start = Environment.getExternalStorageDirectory();
                if (!start.isDirectory()) start = ctx.getFilesDir();
                pickFile(ctx, start, new PathCb() {
                    @Override public void onPath(String p) { et.setText(p); }
                });
            }
        });
        row.addView(pick);

        parent.addView(row);
    }

    private interface PathCb { void onPath(String path); }

    private static void pickFile(final Context ctx, File start, final PathCb cb) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF252526);
        root.setPadding(dp(ctx,8), dp(ctx,8), dp(ctx,8), dp(ctx,8));

        final TextView crumb = new TextView(ctx);
        crumb.setTextColor(0xFF4FC3F7);
        crumb.setTextSize(11f);
        crumb.setTypeface(Typeface.MONOSPACE);
        crumb.setPadding(dp(ctx,4), dp(ctx,4), dp(ctx,4), dp(ctx,8));
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(crumb);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView up = new TextView(ctx);
        up.setText("↑ Up");
        up.setTextColor(0xFFD4D4D4);
        up.setPadding(dp(ctx,10), dp(ctx,8), dp(ctx,10), dp(ctx,8));
        bar.addView(up);
        View sp = new View(ctx);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView cancel = new TextView(ctx);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFD4D4D4);
        cancel.setPadding(dp(ctx,10), dp(ctx,8), dp(ctx,10), dp(ctx,8));
        bar.addView(cancel);
        root.addView(bar);

        final ListView list = new ListView(ctx);
        list.setBackgroundColor(0xFF1E1E1E);
        list.setDivider(null);
        list.setDividerHeight(0);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 340)));

        final File[] cur = { start };
        final List<File> entries = new ArrayList<File>();
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View reuse, ViewGroup parent) {
                TextView tv;
                if (reuse instanceof TextView) tv = (TextView) reuse;
                else {
                    tv = new TextView(ctx);
                    tv.setTextSize(13f);
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setPadding(dp(ctx,12), dp(ctx,12), dp(ctx,12), dp(ctx,12));
                }
                File f = entries.get(pos);
                tv.setText((f.isDirectory() ? "\uD83D\uDCC1  " : "\uD83D\uDCC4  ") + f.getName());
                tv.setTextColor(f.isDirectory() ? 0xFFDCDCAA : 0xFFD4D4D4);
                return tv;
            }
        };
        list.setAdapter(ad);

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                crumb.setText(cur[0].getAbsolutePath());
                entries.clear();
                File[] kids = cur[0].listFiles();
                if (kids != null) {
                    Arrays.sort(kids, new Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    for (File f : kids) {
                        if (f.getName().startsWith(".")) continue;
                        entries.add(f);
                    }
                }
                ad.notifyDataSetChanged();
            }
        };

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                File f = entries.get(pos);
                if (f.isDirectory()) { cur[0] = f; refresh.run(); }
                else { dlg.dismiss(); cb.onPath(f.getAbsolutePath()); }
            }
        });
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File par = cur[0].getParentFile();
                if (par != null && par.canRead()) { cur[0] = par; refresh.run(); }
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        dlg.setContentView(root);
        dlg.getWindow().setLayout(
            (int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.92f),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        refresh.run();
        dlg.show();
    }
}
