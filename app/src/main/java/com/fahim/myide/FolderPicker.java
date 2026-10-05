package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class FolderPicker {

    public interface Callback {
        void onChosen(File folder);
    }

    private FolderPicker() {}

    public static void pick(Context ctx, File startDir, String title,
                            boolean allowCreate, final Callback cb) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int dp2 = dp(ctx, 2);
        final int dp4 = dp(ctx, 4);
        final int dp6 = dp(ctx, 6);
        final int dp8 = dp(ctx, 8);
        final int dp12 = dp(ctx, 12);
        final int dp14 = dp(ctx, 14);
        final int dp16 = dp(ctx, 16);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        // ===== HEADER =====
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF252526);
        header.setPadding(dp16, dp14, dp16, dp12);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(title == null ? "Select Folder" : title);
        titleTv.setTextColor(0xFFD4D4D4);
        titleTv.setTextSize(16f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleTv);

        // breadcrumb (horizontal scroll)
        android.widget.HorizontalScrollView bcScroll =
                new android.widget.HorizontalScrollView(ctx);
        bcScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout bcRow = new LinearLayout(ctx);
        bcRow.setOrientation(LinearLayout.HORIZONTAL);
        bcRow.setGravity(Gravity.CENTER_VERTICAL);
        bcRow.setPadding(0, dp6, 0, 0);
        bcScroll.addView(bcRow);
        header.addView(bcScroll);

        root.addView(header);

        View sep = new View(ctx);
        sep.setBackgroundColor(0xFF3E3E42);
        root.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));

        // ===== LIST =====
        final ListView list = new ListView(ctx);
        list.setBackgroundColor(0xFF1E1E1E);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(0, dp4, 0, dp4);
        list.setClipToPadding(false);

        final List<File> entries = new ArrayList<File>();
        final List<Boolean> isDirList = new ArrayList<Boolean>();
        final File[] currentDir = { startDir };
        final File[] selected = { startDir };

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View reuse, ViewGroup parent) {
                LinearLayout row;
                if (reuse instanceof LinearLayout) {
                    row = (LinearLayout) reuse;
                    row.removeAllViews();
                } else {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp16, dp12, dp16, dp12);
                }

                boolean isSelected = entries.get(pos).getAbsolutePath()
                        .equals(selected[0].getAbsolutePath());
                if (isSelected) row.setBackgroundColor(0xFF094771);
                else row.setBackgroundColor(0x00000000);

                TextView icon = new TextView(ctx);
                if (isDirList.get(pos)) icon.setText("📁");
                else icon.setText("📄");
                icon.setTextSize(16f);
                icon.setPadding(0, 0, dp12, 0);
                row.addView(icon);

                TextView name = new TextView(ctx);
                name.setText(entries.get(pos).getName());
                name.setTextColor(isDirList.get(pos) ? 0xFFDCDCAA : 0xFF858585);
                name.setTextSize(14f);
                name.setTypeface(Typeface.MONOSPACE);
                name.setSingleLine(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(name, lp);

                if (isDirList.get(pos)) {
                    TextView arrow = new TextView(ctx);
                    arrow.setText("›");
                    arrow.setTextColor(0xFF858585);
                    arrow.setTextSize(18f);
                    row.addView(arrow);
                }

                return row;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                File picked = entries.get(pos);
                if (picked.isDirectory()) {
                    selected[0] = picked;
                    currentDir[0] = picked;
                    refresh(ctx, currentDir[0], entries, isDirList, adapter, bcRow);
                }
            }
        });

        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== BOTTOM BAR =====
        View sep2 = new View(ctx);
        sep2.setBackgroundColor(0xFF3E3E42);
        root.addView(sep2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2D2D30);
        bar.setPadding(dp8, dp8, dp8, dp8);

        bar.addView(barBtn(ctx, "↑ Up", new View.OnClickListener() {
            @Override public void onClick(View v) {
                File parent = currentDir[0].getParentFile();
                if (parent != null) {
                    currentDir[0] = parent;
                    selected[0] = parent;
                    refresh(ctx, currentDir[0], entries, isDirList, adapter, bcRow);
                }
            }
        }));

        bar.addView(barBtn(ctx, "⌂ Home", new View.OnClickListener() {
            @Override public void onClick(View v) {
                File home = new File("/storage/emulated/0");
                if (home.isDirectory()) {
                    currentDir[0] = home;
                    selected[0] = home;
                    refresh(ctx, currentDir[0], entries, isDirList, adapter, bcRow);
                }
            }
        }));

        if (allowCreate) {
            bar.addView(barBtn(ctx, "＋ New", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    promptNew(ctx, currentDir[0], new Runnable() {
                        @Override public void run() {
                            refresh(ctx, currentDir[0], entries, isDirList, adapter, bcRow);
                        }
                    });
                }
            }));
        }

        View spacer = new View(ctx);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(barBtn(ctx, "Cancel", new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        }));

        TextView useTv = new TextView(ctx);
        useTv.setText("Select");
        useTv.setTextColor(0xFFFFFFFF);
        useTv.setTextSize(13f);
        useTv.setTypeface(Typeface.DEFAULT_BOLD);
        useTv.setBackgroundColor(0xFF0E639C);
        useTv.setPadding(dp16, dp10, dp16, dp10);
        useTv.setGravity(Gravity.CENTER);
        useTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                if (cb != null) cb.onChosen(selected[0]);
            }
        });
        bar.addView(useTv);

        root.addView(bar);

        dlg.setContentView(root);
        dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // initial
        selected[0] = startDir;
        refresh(ctx, currentDir[0], entries, isDirList, adapter, bcRow);

        dlg.show();
    }

    private static void refresh(Context ctx, File dir,
                                List<File> out, List<Boolean> isDirOut,
                                BaseAdapter adapter, LinearLayout breadcrumb) {
        out.clear();
        isDirOut.clear();

        // update breadcrumb
        breadcrumb.removeAllViews();
        List<File> chain = new ArrayList<File>();
        File cur = dir;
        while (cur != null) {
            chain.add(0, cur);
            cur = cur.getParentFile();
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            TextView seg = new TextView(ctx);
            seg.setText(i == 0 ? "root" : f.getName());
            seg.setTextColor(0xFF4FC3F7);
            seg.setTextSize(11f);
            seg.setTypeface(Typeface.MONOSPACE);
            seg.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2));
            seg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { /* jump not required */ }
            });
            breadcrumb.addView(seg);
            if (i < chain.size() - 1) {
                TextView sep = new TextView(ctx);
                sep.setText("›");
                sep.setTextColor(0xFF6A6A6A);
                sep.setTextSize(11f);
                breadcrumb.addView(sep);
            }
        }

        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    boolean ad = a.isDirectory();
                    boolean bd = b.isDirectory();
                    if (ad != bd) return ad ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : kids) {
                if (f.getName().startsWith(".")) continue;
                out.add(f);
                isDirOut.add(f.isDirectory());
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static void promptNew(final Context ctx, final File parent, final Runnable after) {
        final EditText et = new EditText(ctx);
        et.setHint("New folder name");
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        et.setBackgroundColor(0xFF3C3C3C);
        et.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));

        new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("New folder")
            .setView(et)
            .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    File nf = new File(parent, name);
                    if (nf.mkdirs()) {
                        Toast.makeText(ctx, "Created: " + name, Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    } else {
                        Toast.makeText(ctx, "Failed to create", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static TextView barBtn(Context ctx, String label, View.OnClickListener l) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFD4D4D4);
        tv.setTextSize(13f);
        tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        return tv;
    }

    private static int dp(Context ctx, int v) {
        return (int)(v * ctx.getResources().getDisplayMetrics().density);
    }
}
