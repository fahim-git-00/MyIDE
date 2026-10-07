package com.fahim.myide;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements EditorEnhancer.Host {

    private static final int REQ_MANAGE_STORAGE = 2001;

    private static final int PANEL_BUILD    = 0;
    private static final int PANEL_PROBLEMS = 1;
    private static final int PANEL_LOGCAT   = 2;
    private static final int PANEL_TERMINAL = 3;

    private static volatile boolean LOADING_TAB = false;
    public static boolean isLoadingTab() { return LOADING_TAB; }

    // Widgets
    private EditText editor;
    private TextView lineNumbers;
    private HorizontalScrollView tabStrip;
    private LinearLayout tabRow;
    private LinearLayout sidebar;
    private View dimLayer;
    private ListView fileList;
    private TextView txtProjectPath;
    private TextView txtSidebarTitle;
    private LinearLayout emptyState;
    private FrameLayout panelContent;
    private LinearLayout bottomPanel;
    private TextView panelTabBuild, panelTabProblems, panelTabLogcat, panelTabTerminal;
    private TextView btnPanelClose, btnPanelMaximize;
    private TextView statusLeft, statusPos, statusSpaces, statusLang, statusEncoding, statusBranch, statusProblems, statusBuild, statusTheme;
    private TextView btnMenu, btnNewFile, btnSave, btnUndo, btnRedo, btnFind, btnBuild, btnMore;
    private TextView menuFile, menuEdit, menuView, menuBuild, menuTools, menuPalette;
    private HorizontalScrollView breadcrumbScroll;
    private LinearLayout breadcrumb;
    private TextView editorPlaceholder;
    private View sidebarResizer, panelResizer;
    private ScrollView gutterScroll;
    private FastScrollBar fastScrollBar;

    // State
    private final List<EditorTab> tabs = new ArrayList<EditorTab>();
    private int activeTab = -1;
    private EditorTab lastLoaded;

    private File projectRoot;
    private final List<FileNode> visibleNodes = new ArrayList<FileNode>();
    private FileTreeAdapter fileAdapter;
    private final java.util.Set<String> expandedPaths = new java.util.HashSet<String>();
    private long treeCacheTime = 0L;
    private static final long TREE_CACHE_TTL = 3000L;

    private UndoManager undoMgr;
    private TextWatcher editorWatcher;
    private EditorEnhancer enhancer;

    private boolean isHighlighting = false;
    private boolean sidebarOpen = false;
    private boolean bottomPanelOpen = false;
    private int currentPanel = PANEL_BUILD;
    private int currentLang = SyntaxHighlighter.LANG_JAVA;
    private int lastLineCount = -1;

    // Line-start index cache (fast line/col + goto)
    private int[] lineStarts = null;

    // Panels
    private View panelBuildView, panelProblemsView, panelLogcatView, panelTerminalView;
    private TextView buildOutput, buildStatus;
    private View buildProgress;
    private ListView probList;
    private TextView probEmpty, probCount;
    private ProblemAdapter probAdapter;
    private final List<Problem> problems = new ArrayList<Problem>();
    private LogcatPanel logcatPanel;
    private TerminalPanel terminalPanel;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable highlightDebounce;
    private final java.util.concurrent.ExecutorService highlightExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long highlightToken = 0L;

    // ================================================================
    // LIFECYCLE
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        bindWidgets();
        buildTabsBar();
        buildSidebar();
        buildPanels();
        wireToolbar();
        wireMenuBar();
        wireStatusBar();
        wireEditor();
        wireResizers();

        undoMgr = new UndoManager(editor);
        enhancer = new EditorEnhancer(editor, this);

        applySettingsToEditor();
        restoreSessionState();
        requestStoragePermissionIfNeeded();
        updateEditorPlaceholder();
        updateStatusBar();
        updateTitleBar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        flushActiveTab();
        if (logcatPanel != null) logcatPanel.pause();
        if (terminalPanel != null) terminalPanel.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (logcatPanel != null && bottomPanelOpen && currentPanel == PANEL_LOGCAT) {
            logcatPanel.resume();
        }
    }

    @Override
    public void onBackPressed() {
        if (sidebarOpen) { closeSidebar(); return; }
        if (bottomPanelOpen) { closePanel(); return; }
        super.onBackPressed();
    }

    // ================================================================
    // BINDING
    // ================================================================

    private void bindWidgets() {
        editor          = findViewById(R.id.editor);
        lineNumbers     = findViewById(R.id.lineNumbers);
        tabStrip        = findViewById(R.id.tabStrip);
        sidebar         = findViewById(R.id.sidebar);
        dimLayer        = findViewById(R.id.dimLayer);
        fileList        = findViewById(R.id.fileList);
        txtProjectPath  = findViewById(R.id.txtProjectPath);
        txtSidebarTitle = findViewById(R.id.txtSidebarTitle);
        emptyState      = findViewById(R.id.emptyState);
        panelContent    = findViewById(R.id.panelContent);
        bottomPanel     = findViewById(R.id.bottomPanel);
        panelTabBuild   = findViewById(R.id.panelTabBuild);
        panelTabProblems= findViewById(R.id.panelTabProblems);
        panelTabLogcat  = findViewById(R.id.panelTabLogcat);
        panelTabTerminal= findViewById(R.id.panelTabTerminal);
        btnPanelClose   = findViewById(R.id.btnPanelClose);
        btnPanelMaximize= findViewById(R.id.btnPanelMaximize);

        statusLeft      = findViewById(R.id.statusLeft);
        statusPos       = findViewById(R.id.statusPos);
        statusSpaces    = findViewById(R.id.statusSpaces);
        statusLang      = findViewById(R.id.statusLang);
        statusEncoding  = findViewById(R.id.statusEncoding);
        statusBranch    = findViewById(R.id.statusBranch);
        statusProblems  = findViewById(R.id.statusProblems);
        statusBuild     = findViewById(R.id.statusBuild);
        statusTheme     = findViewById(R.id.statusTheme);

        btnMenu    = findViewById(R.id.btnMenu);
        btnNewFile = findViewById(R.id.btnNewFile);
        btnSave    = findViewById(R.id.btnSave);
        btnUndo    = findViewById(R.id.btnUndo);
        btnRedo    = findViewById(R.id.btnRedo);
        btnFind    = findViewById(R.id.btnFind);
        btnBuild   = findViewById(R.id.btnBuild);
        btnMore    = findViewById(R.id.btnMore);

        menuFile    = findViewById(R.id.menuFile);
        menuEdit    = findViewById(R.id.menuEdit);
        menuView    = findViewById(R.id.menuView);
        menuBuild   = findViewById(R.id.menuBuild);
        menuTools   = findViewById(R.id.menuTools);
        menuPalette = findViewById(R.id.menuPalette);

        breadcrumbScroll   = findViewById(R.id.breadcrumbScroll);
        breadcrumb         = findViewById(R.id.breadcrumb);
        editorPlaceholder  = findViewById(R.id.editorPlaceholder);
        sidebarResizer     = findViewById(R.id.sidebarResizer);
        panelResizer       = findViewById(R.id.panelResizer);
        gutterScroll       = findViewById(R.id.gutterScroll);
        fastScrollBar      = findViewById(R.id.fastScrollBar);
    }

    private void buildTabsBar() {
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.removeAllViews();
        tabStrip.addView(tabRow);
        tabStrip.setHorizontalScrollBarEnabled(false);
    }

    private void buildSidebar() {
        fileAdapter = new FileTreeAdapter();
        fileList.setAdapter(fileAdapter);

        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                FileNode n = visibleNodes.get(pos);
                if (n.isDirectory) {
                    String key = n.file.getAbsolutePath();
                    if (expandedPaths.contains(key)) {
                        expandedPaths.remove(key);
                        n.expanded = false;
                    } else {
                        expandedPaths.add(key);
                        n.expanded = true;
                    }
                    rebuildVisibleNodes();
                } else if (n.file != null) {
                    openFile(n.file);
                    closeSidebar();
                }
            }
        });

        fileList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                FileNode n = visibleNodes.get(pos);
                if (n.file != null) showFileMenu(n.file);
                return true;
            }
        });

        findViewById(R.id.btnSidebarNew).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (projectRoot != null) showNewFileMenu(projectRoot);
            }
        });
        findViewById(R.id.btnSidebarRefresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { treeCacheTime = 0L; rebuildFileTree(); }
        });
        findViewById(R.id.btnSidebarCollapse).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collapseAll(); }
        });
        findViewById(R.id.btnSidebarClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSidebar(); }
        });
        findViewById(R.id.btnEmptyOpen).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickProjectFolder(); }
        });
    }

    private void buildPanels() {
        panelBuildView    = getLayoutInflater().inflate(R.layout.panel_build, null);
        panelProblemsView = getLayoutInflater().inflate(R.layout.panel_problems, null);
        panelLogcatView   = getLayoutInflater().inflate(R.layout.panel_logcat, null);
        panelTerminalView = getLayoutInflater().inflate(R.layout.panel_terminal, null);

        buildOutput  = panelBuildView.findViewById(R.id.buildOutput);
        buildStatus  = panelBuildView.findViewById(R.id.buildStatus);
        buildProgress= panelBuildView.findViewById(R.id.buildProgress);
        panelBuildView.findViewById(R.id.btnBuildClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { buildOutput.setText(""); buildStatus.setText("Idle"); }
        });
        panelBuildView.findViewById(R.id.btnBuildCopy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyToClipboard(buildOutput.getText().toString()); toast("Copied"); }
        });
        panelBuildView.findViewById(R.id.btnBuildSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveBuildLog(); }
        });

        probList     = panelProblemsView.findViewById(R.id.probList);
        probEmpty    = panelProblemsView.findViewById(R.id.probEmpty);
        probCount    = panelProblemsView.findViewById(R.id.probCount);
        probAdapter  = new ProblemAdapter();
        probList.setAdapter(probAdapter);
        panelProblemsView.findViewById(R.id.btnProbClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { problems.clear(); probAdapter.notifyDataSetChanged(); updateProblemsEmptyState(); }
        });
        probList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Problem pr = problems.get(pos);
                if (pr.file != null && pr.file.exists()) {
                    openFile(pr.file);
                    int line = Math.max(0, pr.line - 1);
                    gotoLine(line);
                }
            }
        });

        logcatPanel   = new LogcatPanel(this, panelLogcatView);
        terminalPanel = new TerminalPanel(this, panelTerminalView, projectRoot);

        panelTabBuild.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(PANEL_BUILD); }
        });
        panelTabProblems.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(PANEL_PROBLEMS); }
        });
        panelTabLogcat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(PANEL_LOGCAT); }
        });
        panelTabTerminal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(PANEL_TERMINAL); }
        });
        btnPanelClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        });
        btnPanelMaximize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanelMaximize(); }
        });

        updateProblemsEmptyState();
    }

    private void wireToolbar() {
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSidebar(); }
        });
        btnNewFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (projectRoot == null) toast(getString(R.string.toast_pick_project));
                else showNewFileMenu(projectRoot);
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCurrentFile(); }
        });
        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doUndo(); }
        });
        btnRedo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doRedo(); }
        });
        btnFind.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFindReplace(); }
        });
        btnBuild.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runBuild(); }
        });
        btnMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showOverflow(btnMore); }
        });
    }

    private void wireMenuBar() {
        menuFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(R.menu.menu_file, menuFile); }
        });
        menuEdit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(R.menu.menu_edit, menuEdit); }
        });
        menuView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(R.menu.menu_view, menuView); }
        });
        menuBuild.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(R.menu.menu_build, menuBuild); }
        });
        menuTools.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(R.menu.menu_tools, menuTools); }
        });
        menuPalette.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openCommandPalette(); }
        });
    }

    private void wireStatusBar() {
        View.OnClickListener openPanel = new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(PANEL_PROBLEMS); }
        };
        statusProblems.setOnClickListener(openPanel);

        statusBuild.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runBuild(); }
        });
        statusTheme.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTheme(); }
        });
        statusPos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGotoLine(); }
        });
        statusSpaces.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        statusLang.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openCommandPalette(); }
        });
    }

    private void wireEditor() {
        editorWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (isHighlighting || LOADING_TAB) return;
                markCurrentTabDirty();
                scheduleHighlight();
                updateLineNumbers(s.toString());
                updateStatusBarFast();
                updateBreadcrumb();
            }
        };
        editor.addTextChangedListener(editorWatcher);

        editor.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateStatusBarFast(); }
        });
        editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) updateStatusBarFast();
            }
        });

        editor.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                if (gutterScroll != null && gutterScroll.getScrollY() != sy) {
                    gutterScroll.scrollTo(0, sy);
                }
                if (fastScrollBar != null) fastScrollBar.refresh();
            }
        });

        fastScrollBar.setHost(new FastScrollBar.Host() {
            @Override public int getScrollY() { return editor.getScrollY(); }
            @Override public int getScrollRange() {
                android.text.Layout lay = editor.getLayout();
                int contentH = lay != null ? lay.getHeight() : 0;
                int viewH = editor.getHeight()
                        - editor.getCompoundPaddingTop()
                        - editor.getCompoundPaddingBottom();
                return Math.max(0, contentH - viewH);
            }
            @Override public int getViewHeight() {
                return editor.getHeight()
                        - editor.getCompoundPaddingTop()
                        - editor.getCompoundPaddingBottom();
            }
            @Override public void scrollToFraction(float frac) {
                int range = getScrollRange();
                int target = (int)(frac * range);
                editor.scrollTo(0, target);
                if (gutterScroll != null) gutterScroll.scrollTo(0, target);
                if (fastScrollBar != null) fastScrollBar.refresh();
            }
        });

    }

    private void wireResizers() {
        sidebarResizer.setOnTouchListener(new View.OnTouchListener() {
            float startX; int startW;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startW = sidebar.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int)(e.getRawX() - startX);
                        int w = Math.max(dp(200), Math.min(dp(520), startW + dx));
                        ViewGroup.LayoutParams lp = sidebar.getLayoutParams();
                        lp.width = w;
                        sidebar.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        ThemeHelper.setSidebarWidth(MainActivity.this, sidebar.getWidth());
                        return true;
                    }
                }
                return false;
            }
        });

        panelResizer.setOnTouchListener(new View.OnTouchListener() {
            float startY; int startH;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        startH = bottomPanel.getHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int)(startY - e.getRawY());
                        int h = Math.max(dp(120), Math.min(dp(800), startH + dy));
                        ViewGroup.LayoutParams lp = bottomPanel.getLayoutParams();
                        lp.height = h;
                        bottomPanel.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        ThemeHelper.setPanelHeight(MainActivity.this, bottomPanel.getHeight());
                        return true;
                    }
                }
                return false;
            }
        });
    }

    // ================================================================
    // SETTINGS
    // ================================================================

    private void applySettingsToEditor() {
        float size = ThemeHelper.getFontSize(this);
        editor.setTextSize(size);
        lineNumbers.setTextSize(size);

        boolean wrap = ThemeHelper.isWordWrap(this);
        editor.setHorizontallyScrolling(!wrap);
        if (wrap) {
            editor.setInputType(editor.getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }

        lineNumbers.setVisibility(ThemeHelper.isLineNumbers(this) ? View.VISIBLE : View.GONE);

        ViewGroup.LayoutParams slp = sidebar.getLayoutParams();
        slp.width = dp(ThemeHelper.getSidebarWidth(this));
        sidebar.setLayoutParams(slp);

        ViewGroup.LayoutParams plp = bottomPanel.getLayoutParams();
        plp.height = dp(ThemeHelper.getPanelHeight(this));
        bottomPanel.setLayoutParams(plp);
    }

    private void restoreSessionState() {
        if (ThemeHelper.isSidebarVisible(this)) {
            sidebar.post(new Runnable() {
                @Override public void run() { if (!sidebarOpen) openSidebar(); }
            });
        } else {
            sidebar.setVisibility(View.GONE);
        }
    }

    // ================================================================
    // LINE-START INDEX (fast line/col + goto)
    // ================================================================

    private void rebuildLineStarts(String text) {
        int n = text.length();
        int count = 1;
        for (int i = 0; i < n; i++) if (text.charAt(i) == '\n') count++;
        int[] arr = new int[count];
        arr[0] = 0;
        int k = 1;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                if (k < count) arr[k++] = i + 1;
            }
        }
        lineStarts = arr;
        lastLineCount = count;
    }

    private int lineOf(int pos) {
        if (lineStarts == null) return 1;
        int lo = 0, hi = lineStarts.length - 1, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= pos) { best = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return best + 1;
    }

    private int colOf(int pos) {
        if (lineStarts == null) return 1;
        int line = lineOf(pos) - 1;
        return pos - lineStarts[line] + 1;
    }

    // ================================================================
    // TAB MANAGEMENT  (fast)
    // ================================================================

    private void openFile(final File f) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).file.getAbsolutePath().equals(f.getAbsolutePath())) {
                selectTab(i);
                return;
            }
        }
        final String fileName = f.getName();
        toast("Opening " + fileName + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String text = "";
                try { text = readTextFile(f); }
                catch (Exception e) { text = ""; }
                final String fText = text;
                ui.post(new Runnable() {
                    @Override public void run() {
                        EditorTab t = new EditorTab(f, fText);
                        // Precompute line starts for this tab
                        rebuildLineStartsForTab(t);
                        tabs.add(t);
                        renderTabs();
                        selectTab(tabs.size() - 1);
                    }
                });
            }
        }).start();
    }

    private void rebuildLineStartsForTab(EditorTab t) {
        String text = t.text;
        int n = text.length();
        int count = 1;
        for (int i = 0; i < n; i++) if (text.charAt(i) == '\n') count++;
        int[] arr = new int[count];
        arr[0] = 0;
        int k = 1;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n' && k < count) arr[k++] = i + 1;
        }
        t.lineStarts = arr;
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        if (lastLoaded != null && lastLoaded != tabs.get(idx) && lastLoaded.loaded) flushTab(lastLoaded);

        activeTab = idx;
        EditorTab t = tabs.get(idx);
        currentLang = t.lang();

        LOADING_TAB = true;
        if (undoMgr != null) undoMgr.setSuspended(true);
        if (enhancer != null) enhancer.setSuspended(true);
        try {
            if (editorWatcher != null) editor.removeTextChangedListener(editorWatcher);
            editor.setText(t.text);
            if (editorWatcher != null) editor.addTextChangedListener(editorWatcher);
        } finally {
            LOADING_TAB = false;
            if (undoMgr != null) undoMgr.setSuspended(false);
            if (enhancer != null) enhancer.setSuspended(false);
        }

        // Line-start index either reuse tab's or rebuild
        if (t.lineStarts != null) {
            lineStarts = t.lineStarts;
            lastLineCount = t.lineStarts.length;
        } else {
            rebuildLineStartsForTab(t);
            lineStarts = t.lineStarts;
        }

        int s = Math.max(0, Math.min(t.selStart, t.text.length()));
        int e = Math.max(0, Math.min(t.selEnd, t.text.length()));
        editor.setSelection(s, e);
        final int scroll = t.scrollY;
        editor.post(new Runnable() {
            @Override public void run() { editor.scrollTo(0, scroll); }
        });

        t.loaded = true;
        lastLoaded = t;
        if (undoMgr != null) undoMgr.reset();

        renderTabs();
        updateTitleBar();
        updateEditorPlaceholder();

        // Apply cached spans synchronously — no thread hop
        if (t.cachedSpans != null && t.cachedLangHash == t.text.hashCode()) {
            isHighlighting = true;
            try {
                SyntaxHighlighter.applySpans(editor.getText(), t.cachedSpans);
            } finally {
                isHighlighting = false;
            }
        } else {
            // Defer highlight computation to after frame
            editor.post(new Runnable() {
                @Override public void run() { scheduleHighlight(); }
            });
        }

        // Gutter + status + breadcrumb — all fast now
        editor.post(new Runnable() {
            @Override public void run() {
                updateLineNumbers(t.text);
                updateStatusBarFast();
                updateBreadcrumb();
            }
        });
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        EditorTab t = tabs.get(idx);
        if (t.dirty) {
            askSaveBeforeClose(t, idx);
            return;
        }
        doCloseTab(idx);
    }

    private void doCloseTab(int idx) {
        EditorTab removed = tabs.remove(idx);
        if (lastLoaded == removed) lastLoaded = null;
        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
        renderTabs();
        if (tabs.isEmpty()) {
            currentLang = SyntaxHighlighter.LANG_JAVA;
            isHighlighting = true;
            editor.setText("");
            SyntaxHighlighter.clearSpans(editor.getText());
            isHighlighting = false;
            lastLineCount = -1;
            lineStarts = null;
            updateLineNumbers("");
            updateStatusBarFast();
            updateTitleBar();
            updateBreadcrumb();
            updateEditorPlaceholder();
            if (undoMgr != null) undoMgr.reset();
        } else {
            if (activeTab >= 0) selectTab(activeTab);
        }
    }

    private void askSaveBeforeClose(final EditorTab t, final int idx) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(t.name())
            .setMessage("Save changes before closing?")
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    if (t == lastLoaded) { flushTab(t); writeFile(t.file, t.text); t.dirty = false; }
                    doCloseTab(idx);
                }
            })
            .setNegativeButton("Discard", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { doCloseTab(idx); }
            })
            .setNeutralButton("Cancel", null)
            .show();
    }

    private void flushActiveTab() {
        if (lastLoaded != null) flushTab(lastLoaded);
    }

    private void flushTab(EditorTab t) {
        if (t == null || t != lastLoaded) return;
        CharSequence now = editor.getText();
        if (!now.toString().equals(t.text)) {
            t.text = now.toString();
            t.dirty = true;
            t.cachedSpans = null;
            t.cachedLangHash = 0;
            rebuildLineStartsForTab(t);
        }
        t.selStart = editor.getSelectionStart();
        t.selEnd   = editor.getSelectionEnd();
        t.scrollY  = editor.getScrollY();
    }

    private void renderTabs() {
        int count = tabs.size();
        if (tabRow.getChildCount() == count) {
            for (int i = 0; i < count; i++) {
                View cell = tabRow.getChildAt(i);
                EditorTab t = tabs.get(i);
                boolean active = (i == activeTab);
                TabHolder h = (TabHolder) cell.getTag();
                if (h == null) continue;
                if (h.active == active && t.title().equals(h.lastTitle)) continue;

                h.active = active;
                cell.setBackgroundColor(active ? 0xFF1E1E1E : 0xFF2D2D30);
                if (h.title != null) {
                    h.title.setText(t.title());
                    h.lastTitle = t.title().toString();
                    h.title.setTextColor(active ? 0xFFFFFFFF : 0xFF969696);
                    h.title.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
                }
                if (h.close != null) h.close.setTextColor(active ? 0xFFFFFFFF : 0xFF969696);
            }
            return;
        }
        tabRow.removeAllViews();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            EditorTab t = tabs.get(i);
            View cell = getLayoutInflater().inflate(R.layout.item_tab, tabRow, false);

            TabHolder h = new TabHolder();
            h.icon = cell.findViewById(R.id.tabIcon);
            h.title = cell.findViewById(R.id.tabTitle);
            h.close = cell.findViewById(R.id.tabClose);
            h.active = (i == activeTab);
            h.lastTitle = t.title().toString();
            cell.setTag(h);

            boolean active = h.active;
            cell.setBackgroundColor(active ? 0xFF1E1E1E : 0xFF2D2D30);
            h.icon.setText(t.icon());
            h.title.setText(h.lastTitle);
            h.title.setTextColor(active ? 0xFFFFFFFF : 0xFF969696);
            h.title.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
            h.close.setTextColor(active ? 0xFFFFFFFF : 0xFF969696);
            h.close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeTab(idx); }
            });

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });

            tabRow.addView(cell);
        }
    }

    private void updateTitleBar() {
        setTitle(tabs.isEmpty() ? getString(R.string.app_name)
                : getString(R.string.app_name) + " \u2014 " + tabs.get(activeTab).name());
    }

    private void updateBreadcrumb() {
        if (tabs.isEmpty() || activeTab < 0) return;
        File f = tabs.get(activeTab).file;
        if (projectRoot == null) return;

        String rel;
        try {
            rel = f.getCanonicalPath().substring(projectRoot.getCanonicalPath().length());
        } catch (Exception e) {
            rel = f.getName();
        }
        if (rel.startsWith("/")) rel = rel.substring(1);
        String[] parts = rel.split("/");

        // Reuse child views — no inflation
        int have = breadcrumb.getChildCount();
        int need = parts.length * 2 - 1;
        while (breadcrumb.getChildCount() > need) {
            breadcrumb.removeViewAt(breadcrumb.getChildCount() - 1);
        }
        while (breadcrumb.getChildCount() < need) {
            TextView tv = new TextView(this);
            tv.setTextSize(11f);
            tv.setTypeface(Typeface.MONOSPACE);
            breadcrumb.addView(tv);
        }
        for (int i = 0; i < parts.length; i++) {
            TextView seg = (TextView) breadcrumb.getChildAt(i * 2);
            seg.setText(parts[i]);
            seg.setTextColor(0xFF9CDCFE);
            if (i < parts.length - 1) {
                TextView sep = (TextView) breadcrumb.getChildAt(i * 2 + 1);
                sep.setText("  ›  ");
                sep.setTextColor(0xFF6A6A6A);
            }
        }
    }

    // ================================================================
    // SIDEBAR / FILE TREE
    // ================================================================

    private void toggleSidebar() {
        if (sidebarOpen) closeSidebar();
        else if (projectRoot == null) pickProjectFolder();
        else openSidebar();
    }

    private void openSidebar() {
        if (sidebarOpen && sidebar.getVisibility() == View.VISIBLE) return;
        sidebarOpen = true;
        ThemeHelper.setSidebarVisible(this, true);
        sidebar.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = sidebar.getLayoutParams();
        lp.width = dp(ThemeHelper.getSidebarWidth(this));
        sidebar.setLayoutParams(lp);
        if (visibleNodes.isEmpty() && projectRoot != null) {
            treeCacheTime = 0L;
            rebuildFileTree();
        }
    }

    private void closeSidebar() {
        if (!sidebarOpen) return;
        sidebarOpen = false;
        ThemeHelper.setSidebarVisible(this, false);
        sidebar.setVisibility(View.GONE);
    }

    private void rebuildFileTree() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        long now = System.currentTimeMillis();
        if (now - treeCacheTime < TREE_CACHE_TTL && !visibleNodes.isEmpty()) return;
        treeCacheTime = now;
        txtProjectPath.setText(projectRoot.getAbsolutePath());
        txtProjectPath.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        fileList.setVisibility(View.VISIBLE);
        rebuildVisibleNodes();
    }

    private void rebuildVisibleNodes() {
        final File root = projectRoot;
        if (root == null) {
            visibleNodes.clear();
            fileAdapter.notifyDataSetChanged();
            return;
        }
        visibleNodes.clear();
        walk(root, 0);
        fileAdapter.notifyDataSetChanged();
    }

    private void walk(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int n = kids.length;
        if (n == 0) return;

        String[] names = new String[n];
        boolean[] isDir = new boolean[n];
        for (int i = 0; i < n; i++) {
            File f = kids[i];
            names[i] = f.getName();
            isDir[i] = f.isDirectory();
        }

        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        sortIndices(idx, names, isDir, 0, n - 1);

        for (int k = 0; k < n; k++) {
            int i = idx[k];
            String name = names[i];
            if (name.startsWith(".") && !name.equals(".myide")) continue;
            if (name.equals("build") || name.equals("gradle")) continue;

            File f = kids[i];
            boolean d = isDir[i];
            FileNode node = new FileNode(name, d, depth, f);
            if (d) node.expanded = expandedPaths.contains(f.getAbsolutePath());
            visibleNodes.add(node);
            if (d && node.expanded) walk(f, depth + 1);
        }
    }

    private void sortIndices(int[] idx, String[] names, boolean[] isDir, int lo, int hi) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        sortIndices(idx, names, isDir, lo, mid);
        sortIndices(idx, names, isDir, mid + 1, hi);
        int[] tmp = new int[hi - lo + 1];
        int i = lo, j = mid + 1, t = 0;
        while (i <= mid && j <= hi) {
            int a = idx[i], b = idx[j];
            int cmp;
            if (isDir[a] != isDir[b]) cmp = isDir[a] ? -1 : 1;
            else cmp = names[a].compareToIgnoreCase(names[b]);
            if (cmp <= 0) tmp[t++] = idx[i++];
            else tmp[t++] = idx[j++];
        }
        while (i <= mid) tmp[t++] = idx[i++];
        while (j <= hi) tmp[t++] = idx[j++];
        System.arraycopy(tmp, 0, idx, lo, tmp.length);
    }

    private void collapseAll() {
        expandedPaths.clear();
        for (FileNode n : visibleNodes) n.expanded = false;
        rebuildVisibleNodes();
    }

    private void showFileMenu(final File f) {
        final String[] items = f.isDirectory()
                ? new String[]{"New File", "New Folder", "Rename", "Duplicate", "Delete"}
                : new String[]{"Open", "Rename", "Duplicate", "Delete"};

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(f.getName())
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String choice = items[w];
                    if ("Open".equals(choice)) openFile(f);
                    else if ("New File".equals(choice))   FileTreeOps.newFile(MainActivity.this, f, afterTree());
                    else if ("New Folder".equals(choice)) FileTreeOps.newFolder(MainActivity.this, f, afterTree());
                    else if ("Rename".equals(choice))     FileTreeOps.rename(MainActivity.this, f, afterTree());
                    else if ("Duplicate".equals(choice))  FileTreeOps.duplicate(f, afterTree());
                    else if ("Delete".equals(choice))     FileTreeOps.delete(MainActivity.this, f, afterTree());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private FileTreeOps.After afterTree() {
        return new FileTreeOps.After() {
            @Override public void done(boolean changed) {
                if (changed) { treeCacheTime = 0L; rebuildFileTree(); }
            }
        };
    }

    private void showNewFileMenu(final File dir) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(dir.getName())
            .setItems(new String[]{"New File", "New Folder"}, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    if (w == 0) FileTreeOps.newFile(MainActivity.this, dir, afterTree());
                    else        FileTreeOps.newFolder(MainActivity.this, dir, afterTree());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ================================================================
    // EDITOR
    // ================================================================

    private void scheduleHighlight() {
        if (highlightDebounce != null) ui.removeCallbacks(highlightDebounce);
        highlightDebounce = new Runnable() {
            @Override public void run() {
                if (ThemeHelper.isSyntaxHighlight(MainActivity.this)) {
                    applyHighlight(currentLang);
                }
            }
        };
        ui.postDelayed(highlightDebounce, 250);
    }

    private void applyHighlight(final int lang) {
        final long myToken = ++highlightToken;
        final Editable editable = editor.getText();
        final String snapshot = editable.toString();
        final int snapHash = snapshot.hashCode();

        highlightExec.submit(new Runnable() {
            @Override public void run() {
                final List<SyntaxHighlighter.Range> spans =
                        SyntaxHighlighter.computeSpans(snapshot, lang);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (myToken != highlightToken) return;
                        isHighlighting = true;
                        try {
                            SyntaxHighlighter.applySpans(editor.getText(), spans);
                        } finally {
                            isHighlighting = false;
                        }
                        if (activeTab >= 0 && activeTab < tabs.size()) {
                            EditorTab t = tabs.get(activeTab);
                            if (t.text.hashCode() == snapHash) {
                                t.cachedSpans = spans;
                                t.cachedLangHash = snapHash;
                            }
                        }
                    }
                });
            }
        });
    }

    private void updateLineNumbers(String text) {
        if (!ThemeHelper.isLineNumbers(this)) return;
        int lines = lastLineCount;
        if (lines <= 0) {
            lines = 1;
            for (int i = 0, n = text.length(); i < n; i++) {
                if (text.charAt(i) == '\n') lines++;
            }
            lastLineCount = lines;
        }
        StringBuilder sb = new StringBuilder(lines * 3);
        for (int i = 1; i <= lines; i++) {
            sb.append(i);
            if (i < lines) sb.append('\n');
        }
        lineNumbers.setText(sb.toString());
    }

    private void updateStatusBarFast() {
        int sel = editor.getSelectionStart();
        if (sel < 0) sel = 0;
        int len = editor.getText().length();
        if (sel > len) sel = len;

        int line = lineOf(sel);
        int col = colOf(sel);

        int selStart = editor.getSelectionStart();
        int selEnd = editor.getSelectionEnd();
        int selLen = Math.abs(selEnd - selStart);

        statusPos.setText("Ln " + line + ", Col " + col);
        statusLang.setText(langName(currentLang));
        statusSpaces.setText("Spaces: " + ThemeHelper.getTabSize(this));
        statusEncoding.setText("UTF-8");
        statusBranch.setText(projectRoot == null ? "—" : projectRoot.getName());
        if (tabs.isEmpty()) statusLeft.setText(getString(R.string.status_ready));
        else if (selLen > 0) statusLeft.setText(selLen + " selected");
        else statusLeft.setText(tabs.get(activeTab).name());

        statusTheme.setText(ThemeHelper.isNight(this) ? "🌙" : "☀");

        int pcount = problems.size();
        statusProblems.setText(pcount == 0 ? "0  ⚠" : pcount + "  ⚠");
    }

    private void updateStatusBar() { updateStatusBarFast(); }

    private String langName(int lang) {
        switch (lang) {
            case SyntaxHighlighter.LANG_XML:      return "XML";
            case SyntaxHighlighter.LANG_JSON:     return "JSON";
            case SyntaxHighlighter.LANG_GRADLE:   return "Gradle";
            case SyntaxHighlighter.LANG_MARKDOWN: return "Markdown";
            case SyntaxHighlighter.LANG_KOTLIN:   return "Kotlin";
            default:                              return "Java";
        }
    }

    private void markCurrentTabDirty() {
        if (lastLoaded != null && !lastLoaded.dirty) {
            lastLoaded.dirty = true;
            renderTabs();
        }
    }

    private void updateEditorPlaceholder() {
        boolean empty = tabs.isEmpty();
        editorPlaceholder.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) editorPlaceholder.setText(getString(R.string.sidebar_open_project_hint));
    }

    // ================================================================
    // FILE I/O
    // ================================================================

    private void saveCurrentFile() {
        if (tabs.isEmpty() || activeTab < 0) { saveAs(); return; }
        flushActiveTab();
        EditorTab t = tabs.get(activeTab);
        if (writeFile(t.file, t.text)) {
            t.dirty = false;
            renderTabs();
            toast(getString(R.string.toast_saved, t.name()));
        } else {
            toast(getString(R.string.toast_save_failed, t.name()));
        }
        updateStatusBarFast();
    }

    private boolean writeFile(File f, String text) {
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Exception e) { return false; }
    }

    private String readTextFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private void saveAs() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        final EditText et = new EditText(this);
        et.setHint("MainActivity.java");
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        et.setSingleLine(true);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Save As")
            .setView(et)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    File dir = new File(projectRoot, "src");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    if (writeFile(f, editor.getText().toString())) {
                        openFile(f);
                        treeCacheTime = 0L;
                        rebuildFileTree();
                        toast(getString(R.string.toast_saved, name));
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ================================================================
    // FIND / GOTO
    // ================================================================

    private void openFindReplace() {
        Dialog d = FindReplaceDialog.create(this, editor);
        d.show();
    }

    private void showGotoLine() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("Line number");
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.dlg_goto_line)
            .setView(et)
            .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    try {
                        int line = Integer.parseInt(et.getText().toString().trim());
                        gotoLine(Math.max(0, line - 1));
                    } catch (Exception ignored) {}
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void gotoLine(int lineIndex) {
        if (lineStarts == null) rebuildLineStarts(editor.getText().toString());
        int pos = (lineIndex >= 0 && lineIndex < lineStarts.length)
                ? lineStarts[lineIndex]
                : editor.getText().length();
        editor.requestFocus();
        editor.setSelection(Math.min(pos, editor.getText().length()));
        updateStatusBarFast();
    }

    // ================================================================
    // EDITOR ENHANCER HOST CALLBACKS
    // ================================================================

    @Override public boolean wantsAutoClose() { return ThemeHelper.isAutoClose(this); }
    @Override public boolean wantsAutoIndent() { return ThemeHelper.isAutoIndent(this); }
    @Override public int tabSize() { return ThemeHelper.getTabSize(this); }

    @Override public void onUndo() { doUndo(); }
    @Override public void onRedo() { doRedo(); }
    @Override public void onSave() { saveCurrentFile(); }
    @Override public void onFind() { openFindReplace(); }
    @Override public void onGotoLine() { showGotoLine(); }
    @Override public void onToggleComment() {
        if (enhancer != null) enhancer.toggleLineComment();
    }
    @Override public void onDuplicateLine() {
        if (enhancer != null) enhancer.duplicateLine();
    }
    @Override public void onDeleteLine() {
        if (enhancer != null) enhancer.deleteLine();
    }

    @Override
    public void onProgrammaticEdit(String newText, int newCaret) {
        undoMgr.noteInternalEdit(newText, newCaret);
    }

    private void doUndo() {
        flushActiveTab();
        undoMgr.undo();
        if (lastLoaded != null) lastLoaded.dirty = true;
        renderTabs();
        if (lastLoaded != null) rebuildLineStartsForTab(lastLoaded);
        lineStarts = lastLoaded != null ? lastLoaded.lineStarts : null;
        updateLineNumbers(editor.getText().toString());
        updateStatusBarFast();
    }

    private void doRedo() {
        flushActiveTab();
        undoMgr.redo();
        if (lastLoaded != null) lastLoaded.dirty = true;
        renderTabs();
        if (lastLoaded != null) rebuildLineStartsForTab(lastLoaded);
        lineStarts = lastLoaded != null ? lastLoaded.lineStarts : null;
        updateLineNumbers(editor.getText().toString());
        updateStatusBarFast();
    }

    // ================================================================
    // PROJECT PICKER / NEW PROJECT
    // ================================================================

    private void pickProjectFolder() {
        File start = Environment.getExternalStorageDirectory();
        if (start == null || !start.canRead()) start = getFilesDir();
        FolderPicker.pick(MainActivity.this, start, "Open Project Folder", false,
            new FolderPicker.Callback() {
            @Override public void onChosen(File folder) {
                File root = findProjectRoot(folder);
                if (root == null) { toast(getString(R.string.toast_no_manifest)); return; }
                projectRoot = root;
                RecentProjects.add(MainActivity.this, root);
                treeCacheTime = 0L;
                rebuildFileTree();
                openSidebar();
                toast(getString(R.string.toast_project_opened, root.getName()));
            }
        });
    }

    private File findProjectRoot(File picked) {
        File direct = matchRoot(picked);
        if (direct != null) return direct;
        File[] kids = picked.listFiles();
        if (kids != null) for (File k : kids) {
            if (!k.isDirectory()) continue;
            File r = matchRoot(k);
            if (r != null) return r;
            File k1 = new File(k, "app/src/main");
            if (matchRoot(k1) != null) return k1;
            File k2 = new File(k, "src/main");
            if (matchRoot(k2) != null) return k2;
            File k3 = new File(k, "src");
            if (matchRoot(k3) != null) return k3;
        }
        return null;
    }

    private File matchRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        boolean hasManifest = new File(dir, "AndroidManifest.xml").isFile();
        boolean hasRes = new File(dir, "res").isDirectory();
        boolean hasSrc = new File(dir, "src").isDirectory() || new File(dir, "java").isDirectory();
        if (hasManifest && hasRes && hasSrc) return dir;
        return null;
    }

    private void refreshFolder(File dir, List<File> out, BaseAdapter ad, TextView crumb) {
        out.clear();
        crumb.setText(dir.getAbsolutePath());
        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : kids) {
                if (f.getName().startsWith(".")) continue;
                if (!f.isDirectory()) continue;
                out.add(f);
            }
        }
        ad.notifyDataSetChanged();
    }

    private TextView barBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFD4D4D4);
        tv.setTextSize(13f);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView accentBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(0xFF0E639C);
        tv.setOnClickListener(l);
        return tv;
    }

    private void promptNewFolder(final File parent, final Runnable after) {
        final EditText et = new EditText(this);
        et.setHint("folder name");
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("New folder in " + parent.getName())
            .setView(et)
            .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) new File(parent, n).mkdirs();
                    if (after != null) after.run();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ================================================================
    // BUILD
    // ================================================================

    private void runBuild() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        flushActiveTab();
        final int defMin = ThemeHelper.getMinSdk(this);
        final int defTarget = ThemeHelper.getTargetSdk(this);
        BuildDialog.show(this, defMin, defTarget, new BuildDialog.Handler() {
            @Override public void onBuild(int minSdk, int targetSdk, boolean release) {
                ThemeHelper.setMinSdk(MainActivity.this, minSdk);
                ThemeHelper.setTargetSdk(MainActivity.this, targetSdk);
                showPanel(PANEL_BUILD);
                doBuild(minSdk, targetSdk);
            }
        });
    }

    private void doBuild(final int minSdk, final int targetSdk) {
        buildOutput.setText("");
        buildStatus.setText("Starting…");
        buildProgress.setVisibility(View.VISIBLE);
        statusBuild.setText("⏳");
        statusLeft.setText(getString(R.string.status_building));

        new Thread(new Runnable() {
            @Override public void run() {
                ApkBuilder builder = new ApkBuilder(MainActivity.this, new ApkBuilder.Progress() {
                    @Override public void onProgress(final String msg) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                appendBuildLine(msg);
                                buildStatus.setText(shortStatus(msg));
                            }
                        });
                    }
                });
                final ApkBuilder.Result r = builder.build(projectRoot, minSdk, targetSdk);

                ui.post(new Runnable() {
                    @Override public void run() {
                        buildProgress.setVisibility(View.GONE);
                        statusBuild.setText("🔨");
                        if (r.success) {
                            buildStatus.setText(getString(R.string.build_succeeded));
                            appendBuildLine("");
                            appendBuildLine("=== " + getString(R.string.build_succeeded) + " ===");
                            appendBuildLine(getString(R.string.build_output_path, r.apk.getAbsolutePath()));
                            statusLeft.setText(getString(R.string.status_build_ok));
                            askInstallOrClose(r.apk);
                        } else {
                            buildStatus.setText(getString(R.string.build_failed));
                            appendBuildLine("");
                            appendBuildLine("=== " + getString(R.string.build_failed) + " ===");
                            appendBuildLine(r.log);
                            statusLeft.setText(getString(R.string.status_build_fail));
                            parseProblemsFromLog(r.log);
                            showPanel(PANEL_PROBLEMS);
                        }
                    }
                });
            }
        }).start();
    }

    private String shortStatus(String msg) {
        if (msg == null) return "";
        int nl = msg.indexOf('\n');
        if (nl >= 0) msg = msg.substring(0, nl);
        return msg.length() > 60 ? msg.substring(0, 60) + "…" : msg;
    }

    private void appendBuildLine(String s) {
        buildOutput.append(s == null ? "\n" : s + "\n");
        final ScrollView sc = panelBuildView.findViewById(R.id.buildScroll);
        sc.post(new Runnable() {
            @Override public void run() { sc.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    private void saveBuildLog() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "MyIDE");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "build-" + System.currentTimeMillis() + ".log");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(buildOutput.getText().toString().getBytes("UTF-8"));
            fos.close();
            toast("Saved: " + f.getAbsolutePath());
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
    }

    private void askInstallOrClose(final File apk) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("\u2705 " + getString(R.string.build_succeeded))
            .setMessage(apk.getAbsolutePath())
            .setPositiveButton(getString(R.string.dlg_install), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { installApk(apk); }
            })
            .setNeutralButton("Save As\u2026", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { saveApkAs(apk); }
            })
            .setNegativeButton(getString(R.string.dlg_close), null)
            .show();
    }

    private void saveApkAs(final File apk) {
        File start = Environment.getExternalStorageDirectory();
        FolderPicker.pick(this, start, "Save APK to\u2026", true, new FolderPicker.Callback() {
            @Override public void onChosen(File folder) {
                try {
                    String base = "app.apk";
                    if (projectRoot != null) {
                        File mf = new File(projectRoot, "AndroidManifest.xml");
                        if (mf.exists()) {
                            String xml = readTextFile(mf);
                            int i = xml.indexOf("package=\"");
                            if (i >= 0) {
                                int s = i + 9;
                                int e = xml.indexOf('"', s);
                                if (e > s) base = xml.substring(s, e) + ".apk";
                            }
                        }
                    }
                    File dst = new File(folder, base);
                    int n = 1;
                    while (dst.exists()) {
                        dst = new File(folder, base.replace(".apk", "_" + n + ".apk"));
                        n++;
                    }
                    FileInputStream in = new FileInputStream(apk);
                    FileOutputStream out = new FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    in.close(); out.close();
                    toast("Saved: " + dst.getAbsolutePath());
                } catch (Exception e) {
                    toast("Save failed: " + e.getMessage());
                }
            }
        });
    }

    private void installApk(File apk) {
        try {
            File dst = new File(Environment.getExternalStorageDirectory(),
                    "Download/MyIDE-install.apk");
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileInputStream in = new FileInputStream(apk);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();

            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", dst);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.toast_install_failed, t.getMessage()));
        }
    }

    private void parseProblemsFromLog(String log) {
        problems.clear();
        if (log != null) {
            String[] lines = log.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("ERROR") || trimmed.contains("error:")
                        || trimmed.contains("ERROR:")) {
                    Problem p = new Problem();
                    p.msg = trimmed;
                    p.severity = Problem.ERROR;
                    problems.add(p);
                } else if (trimmed.startsWith("warning:") || trimmed.contains("warning:")) {
                    Problem p = new Problem();
                    p.msg = trimmed;
                    p.severity = Problem.WARNING;
                    problems.add(p);
                }
            }
        }
        probAdapter.notifyDataSetChanged();
        updateProblemsEmptyState();
        updateStatusBarFast();
    }

    private void updateProblemsEmptyState() {
        boolean empty = problems.isEmpty();
        probEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        probList.setVisibility(empty ? View.GONE : View.VISIBLE);
        probCount.setText(empty
                ? getString(R.string.problems_none)
                : getString(R.string.problems_count, problems.size()));
    }

    // ================================================================
    // PANELS
    // ================================================================

    private void showPanel(int which) {
        currentPanel = which;
        ThemeHelper.setLastPanel(this, which);
        bottomPanel.setVisibility(View.VISIBLE);
        bottomPanelOpen = true;
        panelContent.removeAllViews();
        switch (which) {
            case PANEL_BUILD:    panelContent.addView(panelBuildView);    break;
            case PANEL_PROBLEMS: panelContent.addView(panelProblemsView); break;
            case PANEL_LOGCAT:
                panelContent.addView(panelLogcatView);
                logcatPanel.resume();
                break;
            case PANEL_TERMINAL:
                panelContent.addView(panelTerminalView);
                terminalPanel.setCwd(projectRoot);
                break;
        }
        updatePanelTabStyles();
    }

    private void updatePanelTabStyles() {
        stylePanelTab(panelTabBuild, currentPanel == PANEL_BUILD);
        stylePanelTab(panelTabProblems, currentPanel == PANEL_PROBLEMS);
        stylePanelTab(panelTabLogcat, currentPanel == PANEL_LOGCAT);
        stylePanelTab(panelTabTerminal, currentPanel == PANEL_TERMINAL);
    }

    private void stylePanelTab(TextView tv, boolean active) {
        tv.setTextColor(active ? 0xFFFFFFFF : 0xFF969696);
        tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void closePanel() {
        bottomPanelOpen = false;
        bottomPanel.setVisibility(View.GONE);
        if (logcatPanel != null) logcatPanel.pause();
        if (terminalPanel != null) terminalPanel.stop();
    }

    private void togglePanelMaximize() {
        ViewGroup.LayoutParams lp = bottomPanel.getLayoutParams();
        int max = getResources().getDisplayMetrics().heightPixels - dp(120);
        if (lp.height < max / 2) lp.height = max;
        else lp.height = dp(ThemeHelper.getPanelHeight(this));
        bottomPanel.setLayoutParams(lp);
    }

    // ================================================================
    // MENUS & DIALOGS
    // ================================================================

    private void showMenu(int resId, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenuInflater().inflate(resId, pm.getMenu());
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                return handleMenu(item.getItemId());
            }
        });
        pm.show();
    }

    private void showOverflow(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenuInflater().inflate(R.menu.main_menu, pm.getMenu());
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                return handleMenu(item.getItemId());
            }
        });
        pm.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return handleMenu(item.getItemId()) || super.onOptionsItemSelected(item);
    }

    private boolean handleMenu(int id) {
        // FILE
        if (id == R.id.menu_new_project)  { showNewProjectDialog(); return true; }
        if (id == R.id.menu_open_project) { pickProjectFolder(); return true; }
        if (id == R.id.menu_open_recent)  { showRecentProjects(); return true; }
        if (id == R.id.menu_new_file)     { if (projectRoot != null) showNewFileMenu(projectRoot); return true; }
        if (id == R.id.menu_new_folder)   { if (projectRoot != null) FileTreeOps.newFolder(this, projectRoot, afterTree()); return true; }
        if (id == R.id.menu_save)         { saveCurrentFile(); return true; }
        if (id == R.id.menu_save_as)      { saveAs(); return true; }
        if (id == R.id.menu_save_all)     { saveAll(); return true; }
        if (id == R.id.menu_close_tab)    { if (activeTab >= 0) closeTab(activeTab); return true; }
        if (id == R.id.menu_close_all)    { closeAllTabs(); return true; }
        if (id == R.id.menu_refresh)      { treeCacheTime = 0L; rebuildFileTree(); return true; }
        if (id == R.id.menu_settings)     { showSettings(); return true; }
        if (id == R.id.menu_exit)         { finish(); return true; }

        // EDIT
        if (id == R.id.menu_undo)          { doUndo(); return true; }
        if (id == R.id.menu_redo)          { doRedo(); return true; }
        if (id == R.id.menu_cut)           { editor.onTextContextMenuItem(android.R.id.cut); return true; }
        if (id == R.id.menu_copy)          { editor.onTextContextMenuItem(android.R.id.copy); return true; }
        if (id == R.id.menu_paste)         { editor.onTextContextMenuItem(android.R.id.paste); return true; }
        if (id == R.id.menu_select_all)    { editor.selectAll(); return true; }
        if (id == R.id.menu_find)          { openFindReplace(); return true; }
        if (id == R.id.menu_replace)       { openFindReplace(); return true; }
        if (id == R.id.menu_find_in_files) { showFindInFiles(); return true; }
        if (id == R.id.menu_goto_line)     { showGotoLine(); return true; }
        if (id == R.id.menu_duplicate_line){ enhancer.duplicateLine(); return true; }
        if (id == R.id.menu_delete_line)   { enhancer.deleteLine(); return true; }
        if (id == R.id.menu_toggle_comment){ enhancer.toggleLineComment(); return true; }

        // VIEW
        if (id == R.id.menu_toggle_sidebar) { toggleSidebar(); return true; }
        if (id == R.id.menu_toggle_bottom)  { if (bottomPanelOpen) closePanel(); else showPanel(PANEL_BUILD); return true; }
        if (id == R.id.menu_word_wrap)      { toggleWordWrap(); return true; }
        if (id == R.id.menu_line_numbers)   { toggleLineNumbers(); return true; }
        if (id == R.id.menu_zoom_in)        { zoomIn(); return true; }
        if (id == R.id.menu_zoom_out)       { zoomOut(); return true; }
        if (id == R.id.menu_zoom_reset)     { zoomReset(); return true; }
        if (id == R.id.menu_theme_dark)     { applyThemeMode(ThemeHelper.THEME_DARK); return true; }
        if (id == R.id.menu_theme_light)    { applyThemeMode(ThemeHelper.THEME_LIGHT); return true; }
        if (id == R.id.menu_theme_system)   { applyThemeMode(ThemeHelper.THEME_SYSTEM); return true; }
        if (id == R.id.menu_fullscreen)     { toggleFullscreen(); return true; }

        // BUILD
        if (id == R.id.menu_build_apk)     { runBuild(); return true; }
        if (id == R.id.menu_gradle_build)  { runGradleBuild(); return true; }
        if (id == R.id.menu_clean)         { cleanProject(); return true; }
        if (id == R.id.menu_rebuild)       { runBuild(); return true; }
        if (id == R.id.menu_libraries)     { showLibraries(); return true; }
        if (id == R.id.menu_kotlin_mode)   { showKotlinMode(); return true; }
        if (id == R.id.menu_build_settings){ showBuildSettings(); return true; }
        if (id == R.id.menu_signing_key)   { SigningKeyDialog.show(this); return true; }

        // TOOLS
        if (id == R.id.menu_logcat)       { showPanel(PANEL_LOGCAT); return true; }
        if (id == R.id.menu_terminal)     { showPanel(PANEL_TERMINAL); return true; }
        if (id == R.id.menu_problems)     { showPanel(PANEL_PROBLEMS); return true; }
        if (id == R.id.menu_build_output) { showPanel(PANEL_BUILD); return true; }
        if (id == R.id.menu_palette)      { openCommandPalette(); return true; }
        if (id == R.id.menu_github_token) { showGithubTokenDialog(); return true; }
        if (id == R.id.menu_about)        { showAbout(); return true; }

        return false;
    }

    // ================================================================
    // NEW PROJECT
    // ================================================================

    private void showNewProjectDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xFF252526);

        root.addView(label("App name"));
        final EditText nameEt = new EditText(this);
        nameEt.setHint("MyApp");
        nameEt.setTextColor(0xFFD4D4D4);
        nameEt.setHintTextColor(0xFF6A6A6A);
        root.addView(nameEt);

        root.addView(label("Package"));
        final EditText pkgEt = new EditText(this);
        pkgEt.setText("com.example.myapp");
        pkgEt.setTextColor(0xFFD4D4D4);
        root.addView(pkgEt);

        final android.widget.CheckBox cppBox = new android.widget.CheckBox(this);
        cppBox.setText("Include C / C++ (JNI) support");
        cppBox.setTextColor(0xFFD4D4D4);
        cppBox.setTextSize(13f);
        cppBox.setChecked(false);
        cppBox.setPadding(0, dp(10), 0, dp(4));
        root.addView(cppBox);

        root.addView(label("Location"));
        final TextView locTv = new TextView(this);
        locTv.setText("(not selected)");
        locTv.setTextColor(0xFFF44747);
        locTv.setPadding(0, dp(4), 0, dp(4));
        root.addView(locTv);

        final File[] chosen = { null };
        TextView pick = accentBtn("Choose folder", new View.OnClickListener() {
            @Override public void onClick(View v) {
                File start = Environment.getExternalStorageDirectory();
                FolderPicker.pick(MainActivity.this, start, "Choose project location", true,
                    new FolderPicker.Callback() {
                    @Override public void onChosen(File folder) {
                        chosen[0] = folder;
                        locTv.setText(folder.getAbsolutePath());
                        locTv.setTextColor(0xFF4EC9B0);
                    }
                });
            }
        });
        root.addView(pick);

        final AlertDialog dlg = new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.dlg_new_project)
            .setView(root)
            .setPositiveButton(getString(R.string.dlg_create), null)
            .setNegativeButton(getString(R.string.dlg_cancel), null)
            .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String name = nameEt.getText().toString().trim();
                        String pkg = pkgEt.getText().toString().trim();
                        if (name.isEmpty()) { toast("App name required"); return; }
                        if (!pkg.contains(".") || pkg.contains(" ")) { toast("Invalid package"); return; }
                        if (chosen[0] == null) { toast("Pick a location"); return; }
                        File proj = new File(chosen[0], name);
                        if (proj.exists()) { toast("Already exists"); return; }
                        File created = ProjectScaffold.create(proj, name, pkg, cppBox.isChecked());
                        if (created == null) { toast("Create failed"); return; }
                        dlg.dismiss();
                        projectRoot = created;
                        RecentProjects.add(MainActivity.this, created);
                        treeCacheTime = 0L;
                        rebuildFileTree();
                        openSidebar();
                        File main = new File(created, "src/" + pkg.replace('.', '/') + "/MainActivity.java");
                        if (main.exists()) openFile(main);
                        toast(getString(R.string.toast_project_opened, name));
                    }
                });
            }
        });
        dlg.show();
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFFDCDCAA);
        tv.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ================================================================
    // SETTINGS / MISC DIALOGS
    // ================================================================

    private void showSettings() {
        SettingsDialog.show(this, new Runnable() {
            @Override public void run() {
                applySettingsToEditor();
                if (lastLoaded != null) {
                    lastLoaded.cachedSpans = null;
                    lastLoaded.cachedLangHash = 0;
                    applyHighlight(lastLoaded.lang());
                }
                updateStatusBarFast();
            }
        });
    }

    private void showLibraries() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        new LibrariesDialog(this, projectRoot, new Runnable() {
            @Override public void run() { toast(getString(R.string.toast_deps_updated)); }
        }).show();
    }

    private void showKotlinMode() {
        final SharedPreferences prefs = ThemeHelper.prefs(this);
        final String cur = prefs.getString("kotlin_mode", "auto");
        final String[] labels = {"Auto", "Local only", "Remote only"};
        final String[] values = {"auto", "local", "remote"};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.action_kotlin_mode)
            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    ThemeHelper.setKotlinMode(MainActivity.this, values[w]);
                    toast(getString(R.string.toast_kotlin_mode, values[w]));
                    d.dismiss();
                }
            })
            .show();
    }

    private void showBuildSettings() { showSettings(); }

    private void showGithubTokenDialog() {
        final EditText et = new EditText(this);
        et.setHint(R.string.pref_github_token_hint);
        et.setSingleLine(true);
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        String cur = getSharedPreferences("github", MODE_PRIVATE).getString("token", "");
        if (cur != null) et.setText(cur);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.action_github_token)
            .setMessage("Needs 'repo' and 'workflow' scopes.")
            .setView(et)
            .setPositiveButton(getString(R.string.dlg_save), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    getSharedPreferences("github", MODE_PRIVATE).edit()
                        .putString("token", et.getText().toString().trim()).apply();
                    toast(getString(R.string.toast_token_saved));
                }
            })
            .setNegativeButton(getString(R.string.dlg_cancel), null)
            .show();
    }

    private void showAbout() {
        String version = "1.0";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.dlg_about)
            .setMessage(getString(R.string.dlg_about_body, version))
            .setPositiveButton(getString(R.string.dlg_ok), null)
            .show();
    }

    private void showRecentProjects() {
        final List<String> recents = RecentProjects.list(this);
        if (recents.isEmpty()) { toast(getString(R.string.recent_empty)); return; }
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.recent_title)
            .setItems(recents.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    File f = new File(recents.get(w));
                    if (f.isDirectory()) {
                        projectRoot = f;
                        treeCacheTime = 0L;
                        rebuildFileTree();
                        openSidebar();
                    } else toast("Not found");
                }
            })
            .setNeutralButton(R.string.recent_clear, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    RecentProjects.clear(MainActivity.this);
                    toast("Cleared");
                }
            })
            .show();
    }

    private void showFindInFiles() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        final EditText et = new EditText(this);
        et.setHint("Search text…");
        et.setTextColor(0xFFD4D4D4);
        et.setHintTextColor(0xFF6A6A6A);
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.action_find_in_files)
            .setView(et)
            .setPositiveButton(getString(R.string.dlg_find), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String q = et.getText().toString().trim();
                    if (q.isEmpty()) return;
                    searchInFiles(q);
                }
            })
            .setNegativeButton(getString(R.string.dlg_cancel), null)
            .show();
    }

    private void searchInFiles(final String query) {
        final List<File> hits = new ArrayList<File>();
        new Thread(new Runnable() {
            @Override public void run() {
                search(projectRoot, query, hits);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (hits.isEmpty()) { toast("No matches"); return; }
                        String[] names = new String[hits.size()];
                        for (int i = 0; i < hits.size(); i++) names[i] = hits.get(i).getName();
                        new AlertDialog.Builder(MainActivity.this, R.style.AppDialogTheme)
                            .setTitle("Matches: " + hits.size())
                            .setItems(names, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    openFile(hits.get(w));
                                }
                            })
                            .setNegativeButton(getString(R.string.dlg_cancel), null)
                            .show();
                    }
                });
            }
        }).start();
    }

    private void search(File dir, String q, List<File> out) {
        if (out.size() >= 200) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) search(f, q, out);
            else {
                try {
                    String text = readTextFile(f);
                    if (text.contains(q)) out.add(f);
                } catch (Exception ignored) {}
            }
        }
    }

    // ================================================================
    // COMMAND PALETTE
    // ================================================================

    private void openCommandPalette() {
        CommandPalette.show(this, new CommandPalette.Handler() {
            @Override public void onCommand(String id) {
                handleCommandId(id);
            }
        });
    }

    private void handleCommandId(String id) {
        if (id == null) return;
        if (id.equals("file.new"))         showNewProjectDialog();
        else if (id.equals("file.open"))   pickProjectFolder();
        else if (id.equals("file.save"))   saveCurrentFile();
        else if (id.equals("file.saveas")) saveAs();
        else if (id.equals("edit.find"))   openFindReplace();
        else if (id.equals("edit.goto"))   showGotoLine();
        else if (id.equals("view.sidebar")) toggleSidebar();
        else if (id.equals("view.bottom")) { if (bottomPanelOpen) closePanel(); else showPanel(PANEL_BUILD); }
        else if (id.equals("view.settings")) showSettings();
        else if (id.equals("build.apk"))   runBuild();
        else if (id.equals("build.gradle")) runGradleBuild();
        else if (id.equals("tools.logcat")) showPanel(PANEL_LOGCAT);
        else if (id.equals("tools.terminal")) showPanel(PANEL_TERMINAL);
        else if (id.equals("tools.problems")) showPanel(PANEL_PROBLEMS);
        else if (id.equals("tools.libs"))  showLibraries();
        else if (id.equals("about"))       showAbout();
    }

    // ================================================================
    // TOGGLES / THEME
    // ================================================================

    private void toggleWordWrap() {
        boolean v = !ThemeHelper.isWordWrap(this);
        ThemeHelper.prefs(this).edit().putBoolean("word_wrap", v).apply();
        applySettingsToEditor();
        toast("Word wrap: " + v);
    }

    private void toggleLineNumbers() {
        boolean v = !ThemeHelper.isLineNumbers(this);
        ThemeHelper.prefs(this).edit().putBoolean("line_numbers", v).apply();
        lineNumbers.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    private void zoomIn() {
        int s = ThemeHelper.getFontSize(this) + 1;
        ThemeHelper.setFontSize(this, s);
        applySettingsToEditor();
    }

    private void zoomOut() {
        int s = ThemeHelper.getFontSize(this) - 1;
        ThemeHelper.setFontSize(this, s);
        applySettingsToEditor();
    }

    private void zoomReset() {
        ThemeHelper.setFontSize(this, 13);
        applySettingsToEditor();
    }

    private void applyThemeMode(int mode) {
        ThemeHelper.setThemeMode(this, mode);
        recreate();
    }

    private void cycleTheme() {
        int cur = ThemeHelper.getThemeMode(this);
        int next = (cur + 1) % 3;
        applyThemeMode(next);
    }

    private void toggleFullscreen() {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (lp.flags == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    // ================================================================
    // GRADLE / CLEAN
    // ================================================================

    private void runGradleBuild() {
        if (projectRoot == null) { toast(getString(R.string.toast_pick_project)); return; }
        flushActiveTab();
        showPanel(PANEL_BUILD);
        buildOutput.setText("");
        buildStatus.setText("Gradle…");
        buildProgress.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override public void run() {
                GradleBuilder gb = new GradleBuilder(MainActivity.this, new GradleBuilder.Progress() {
                    @Override public void onProgress(final String msg) {
                        ui.post(new Runnable() {
                            @Override public void run() { appendBuildLine(msg); }
                        });
                    }
                });
                final GradleBuilder.Result r = gb.build(projectRoot,
                        ThemeHelper.getMinSdk(MainActivity.this),
                        ThemeHelper.getTargetSdk(MainActivity.this));
                ui.post(new Runnable() {
                    @Override public void run() {
                        buildProgress.setVisibility(View.GONE);
                        if (r.success) {
                            buildStatus.setText(getString(R.string.build_succeeded));
                            askInstallOrClose(r.apk);
                        } else {
                            buildStatus.setText(getString(R.string.build_failed));
                            appendBuildLine(r.log);
                        }
                    }
                });
            }
        }).start();
    }

    private void cleanProject() {
        if (projectRoot == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                deleteDir(new File(projectRoot, ".myide/build_area"));
                deleteDir(new File(projectRoot, "build"));
                deleteDir(new File(projectRoot, "app/build"));
                ui.post(new Runnable() {
                    @Override public void run() { toast("Cleaned"); }
                });
            }
        }).start();
    }

    private void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteDir(k);
        }
        f.delete();
    }

    // ================================================================
    // STORAGE PERMISSIONS
    // ================================================================

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_MANAGE_STORAGE);
                } catch (Throwable t) {
                    try {
                        startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQ_MANAGE_STORAGE);
                    } catch (Throwable ignored) {}
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_MANAGE_STORAGE);
            }
        }
    }

    // ================================================================
    // UTIL
    // ================================================================

    private void saveAll() {
        for (EditorTab t : tabs) {
            if (t.dirty) {
                writeFile(t.file, t.text);
                t.dirty = false;
            }
        }
        renderTabs();
        toast("All saved");
    }

    private void closeAllTabs() {
        for (int i = tabs.size() - 1; i >= 0; i--) {
            if (!tabs.get(i).dirty) doCloseTab(i);
        }
        renderTabs();
    }

    private void copyToClipboard(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("text", s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ================================================================
    // ADAPTERS
    // ================================================================

    private class FileTreeAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleNodes.size(); }
        @Override public Object getItem(int i) { return visibleNodes.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View reuse, ViewGroup parent) {
            View row = reuse != null ? reuse
                    : getLayoutInflater().inflate(R.layout.item_file, parent, false);
            FileNode n = visibleNodes.get(pos);

            View indent = row.findViewById(R.id.indent);
            LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) indent.getLayoutParams();
            ilp.width = dp(n.depth * 14);
            indent.setLayoutParams(ilp);

            TextView chevron = row.findViewById(R.id.chevron);
            if (n.isDirectory) {
                chevron.setVisibility(View.VISIBLE);
                chevron.setText(n.expanded ? "▾" : "▸");
            } else {
                chevron.setVisibility(View.INVISIBLE);
            }

            TextView icon = row.findViewById(R.id.icon);
            icon.setText(n.icon());

            TextView name = row.findViewById(R.id.name);
            name.setText(n.name);
            name.setTextColor(n.isDirectory ? 0xFFDCDCAA : 0xFFD4D4D4);
            name.setTypeface(Typeface.MONOSPACE);

            return row;
        }
    }

    private class ProblemAdapter extends BaseAdapter {
        @Override public int getCount() { return problems.size(); }
        @Override public Object getItem(int i) { return problems.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View reuse, ViewGroup parent) {
            View row = reuse != null ? reuse
                    : getLayoutInflater().inflate(R.layout.item_problem, parent, false);
            Problem p = problems.get(pos);

            TextView icon = row.findViewById(R.id.probIcon);
            icon.setText(p.severity == Problem.ERROR ? "✖" : "⚠");
            icon.setTextColor(p.severity == Problem.ERROR ? 0xFFF44747 : 0xFFDCDCAA);

            TextView msg = row.findViewById(R.id.probMsg);
            msg.setText(p.msg);

            TextView loc = row.findViewById(R.id.probLoc);
            if (p.file != null) {
                loc.setText(p.file.getName() + (p.line > 0 ? ":" + p.line : ""));
                loc.setVisibility(View.VISIBLE);
            } else {
                loc.setVisibility(View.GONE);
            }
            return row;
        }
    }

    private static class Problem {
        static final int ERROR = 0;
        static final int WARNING = 1;
        String msg;
        int severity = ERROR;
        File file;
        int line;
    }

    }
