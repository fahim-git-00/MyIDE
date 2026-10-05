package com.fahim.myide;

import android.app.Dialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CommandPalette {

    public interface Handler {
        void onCommand(String id);
    }

    public static class Command {
        public final String id;
        public final String title;
        public final String category;
        public final String shortcut;

        public Command(String id, String title, String category, String shortcut) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.shortcut = shortcut;
        }
    }

    private CommandPalette() {}

    public static void show(final Context ctx, final Handler handler) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final View root = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_command_palette, null, false);

        final EditText input = root.findViewById(R.id.paletteInput);
        final ListView list = root.findViewById(R.id.paletteList);
        final TextView empty = root.findViewById(R.id.paletteEmpty);
        final TextView close = root.findViewById(R.id.paletteClose);

        final List<Command> all = buildAll(ctx);
        final List<Command> filtered = new ArrayList<Command>();

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int i) { return filtered.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View reuse, ViewGroup parent) {
                View row = reuse != null ? reuse
                        : android.view.LayoutInflater.from(ctx)
                                .inflate(R.layout.item_palette, parent, false);
                Command c = filtered.get(pos);

                TextView icon = row.findViewById(R.id.cmdIcon);
                icon.setText(iconFor(c.category));

                TextView title = row.findViewById(R.id.cmdTitle);
                title.setText(c.title);

                TextView cat = row.findViewById(R.id.cmdCategory);
                if (c.category == null || c.category.isEmpty()) {
                    cat.setVisibility(View.GONE);
                } else {
                    cat.setVisibility(View.VISIBLE);
                    cat.setText(c.category);
                }

                TextView sc = row.findViewById(R.id.cmdShortcut);
                if (c.shortcut == null || c.shortcut.isEmpty()) sc.setVisibility(View.GONE);
                else { sc.setVisibility(View.VISIBLE); sc.setText(c.shortcut); }

                return row;
            }
        };
        list.setAdapter(adapter);

        final Runnable filter = new Runnable() {
            @Override public void run() {
                String q = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                if (q.isEmpty()) {
                    filtered.addAll(all);
                } else {
                    for (Command c : all) {
                        String hay = (c.title + " " + c.category + " " + c.id).toLowerCase(Locale.ROOT);
                        if (hay.contains(q)) filtered.add(c);
                    }
                }
                adapter.notifyDataSetChanged();
                empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
            }
        };
        filter.run();

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Command c = filtered.get(pos);
                dlg.dismiss();
                if (handler != null) handler.onCommand(c.id);
            }
        });

        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = 60;
            w.setAttributes(lp);
        }
        dlg.show();
        input.requestFocus();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private static String iconFor(String cat) {
        if (cat == null) return "›";
        if (cat.equals("File"))   return "📄";
        if (cat.equals("Edit"))   return "✎";
        if (cat.equals("View"))   return "👁";
        if (cat.equals("Build"))  return "🔨";
        if (cat.equals("Tools"))  return "🛠";
        if (cat.equals("Help"))   return "?";
        return "›";
    }

    private static List<Command> buildAll(Context ctx) {
        List<Command> c = new ArrayList<Command>();
        // File
        c.add(new Command("file.new",     "New Project",          "File",  ""));
        c.add(new Command("file.open",    "Open Project",         "File",  ""));
        c.add(new Command("file.save",    "Save File",            "File",  "Ctrl+S"));
        c.add(new Command("file.saveas",  "Save As…",             "File",  ""));
        // Edit
        c.add(new Command("edit.find",    "Find & Replace",       "Edit",  "Ctrl+F"));
        c.add(new Command("edit.goto",    "Go to Line",           "Edit",  "Ctrl+G"));
        // View
        c.add(new Command("view.sidebar", "Toggle Sidebar",       "View",  ""));
        c.add(new Command("view.bottom",  "Toggle Bottom Panel",  "View",  ""));
        c.add(new Command("view.settings","Open Settings",        "View",  ""));
        // Build
        c.add(new Command("build.apk",    "Build APK",            "Build", "Ctrl+B"));
        c.add(new Command("build.gradle", "Build with Gradle",    "Build", ""));
        // Tools
        c.add(new Command("tools.logcat",    "Show Logcat",       "Tools", ""));
        c.add(new Command("tools.terminal",  "Show Terminal",     "Tools", ""));
        c.add(new Command("tools.problems",  "Show Problems",     "Tools", ""));
        c.add(new Command("tools.libs",      "Manage Libraries",  "Tools", ""));
        // Help
        c.add(new Command("about",        "About MyIDE",          "Help",  ""));
        return c;
    }
}
