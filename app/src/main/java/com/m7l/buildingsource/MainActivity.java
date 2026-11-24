package com.m7l.buildingsource;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Building source — turns Android Studio / Gradle projects into APKs on the phone.
 * Tabs: Build • Libraries • Settings • About.  The build itself runs in {@link BuildService}.
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------------ palette
    static final int BG = Color.parseColor("#03070A");
    static final int BAR = Color.parseColor("#07110E");
    static final int CARD = Color.parseColor("#0A1613");
    static final int CARD2 = Color.parseColor("#0F201B");
    static final int STROKE = Color.parseColor("#1D3A31");
    static final int TEXT = Color.parseColor("#D4FFEE");
    static final int SUB = Color.parseColor("#5FA88A");
    static final int GREEN = Color.parseColor("#00FF9C");
    static final int CYAN = Color.parseColor("#00E5FF");
    static final int AMBER = Color.parseColor("#FFB300");
    static final int RED = Color.parseColor("#FF2E4D");
    static final int PURPLE = Color.parseColor("#B46CFF");
    static final int BLUE = Color.parseColor("#4D9CFF");

    private static final int REQ_STORAGE = 8101, REQ_ZIP = 8102;

    /** name, url, colour, glyph */
    private static final String[][] SOCIAL = {
        {"YouTube", "https://youtube.com/@androidplus4494", "#FF0000", "▶"},
        {"Instagram", "https://www.instagram.com/m7l.misoz", "#DD2A7B", "📷"},
        {"Telegram", "https://t.me/m7lmisoz", "#229ED9", "📨"},
        {"X (Twitter)", "https://x.com/m7lmisoz", "#1D1D1D", "𝕏"},
        {"PayPal", "https://www.paypal.com/paypalme/hakrm7l", "#003087", "P"},
    };

    private boolean ar;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private LinearLayout nav;
    private int tab = 0;

    // build tab views
    private Ring ring;
    private TextView stageTv, timeTv, tasksTv, numTv, projName, projPath, logTv, statsTv, statusChip;
    private ScrollView logScroll;
    private String lastLog = "", lastRes = "", lastPerm = "";
    private LinearLayout resultBox, permBox;
    private TextView buildBtn, stopBtn;
    private boolean logOpen;
    private String lastState = "";

    private final Runnable refresher = this::refreshBuild;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (timeTv != null && BuildService.running()) timeTv.setText(clock(System.currentTimeMillis() - BuildService.startMs));
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ shared helpers (also used by the service)

    static boolean isArabic(Context c) {
        String l = c.getSharedPreferences(BuildService.PREFS, MODE_PRIVATE).getString("lang", "");
        if (l.isEmpty()) return false;   // English first; Arabic is chosen in Settings
        return "ar".equals(l);
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private String t(String en, String a) { return ar ? a : en; }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(BuildService.PREFS, MODE_PRIVATE);
        BuildService.outPath = prefs.getString("out_dir", "");
        prefs.edit().remove("recent").apply();   // no project history is kept
        // a fresh start always begins with no project chosen (unless a build of it is still running)
        if (b == null && !BuildService.running()) prefs.edit().remove("proj_path").remove("proj_name").apply();
        ar = isArabic(this);
        try {
            getWindow().setStatusBarColor(BAR);
            getWindow().setNavigationBarColor(BAR);
        } catch (Exception ignored) {}

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(BAR);
        nav.setPadding(dp(6), dp(6), dp(6), dp(8));
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(Fx.wrap(this, root));
        if (ar) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        tab = b != null ? b.getInt("tab", 0) : 0;
        renderNav();
        showTab(tab, false);

        handleIncoming(getIntent());
        if (prefs.getBoolean("first", true)) {
            prefs.edit().putBoolean("first", false).apply();
            ui.postDelayed(this::welcome, 900);
        }
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); o.putInt("tab", tab); }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIncoming(i); }

    @Override
    protected void onResume() {
        super.onResume();
        BuildService.listeners.add(refresher);
        ui.post(clockTick);
        if (tab == 0) refreshBuild();
        else if (tab == 1) showTab(1, false);
    }

    @Override
    protected void onPause() {
        BuildService.listeners.remove(refresher);
        ui.removeCallbacks(clockTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !BuildService.running()) prefs.edit().remove("proj_path").remove("proj_name").apply();
        // leaving the tool → free the space it used (built files are kept)
        if (isFinishing() && prefs.getBoolean("cleanup", true) && Env.exists(Env.rootfs(this))) {
            if (BuildService.running()) {
                if ("build".equals(BuildService.kind)) prefs.edit().putBoolean("cleanup_pending", true).apply();
            } else {
                try {
                    startSvc(new Intent(this, BuildService.class).setAction(BuildService.ACTION_LIBS)
                        .putExtra(BuildService.EXTRA_LIB_ACTION, "remove").putExtra(BuildService.EXTRA_LIBS, new String[]{"everything"})
                        .putExtra(BuildService.EXTRA_TITLE, t("Freeing space", "تفريغ المساحة")));
                } catch (Exception ignored) {}
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (tab != 0) { showTab(0, true); return; }
        super.onBackPressed();
    }

    /** A project .zip opened or shared from another app. */
    private void handleIncoming(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) { try { u = i.getParcelableExtra(Intent.EXTRA_STREAM); } catch (Exception ignored) {} }
        if (u != null) importZip(u);
    }

    // ------------------------------------------------------------------ navigation

    private static final String[][] TABS = {{"🏗", "Build", "تجميع"}, {"📦", "Libraries", "المكتبات"}, {"⚙", "Settings", "الإعدادات"}, {"👤", "About", "حول"}};

    private void renderNav() {
        nav.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            boolean on = i == tab;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(6), 0, dp(6));
            if (on) b.setBackground(round(CARD2, dp(14), GREEN));
            TextView ic = label(TABS[i][0], on ? 21 : 19, TEXT, false);
            ic.setGravity(Gravity.CENTER);
            b.addView(ic);
            TextView lb = label(ar ? TABS[i][2] : TABS[i][1], 11, on ? GREEN : SUB, true);
            lb.setGravity(Gravity.CENTER);
            b.addView(lb);
            b.setOnClickListener(v -> showTab(idx, true));
            press(b);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
            l.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(b, l);
        }
    }

    private void showTab(int i, boolean anim) {
        tab = i;
        renderNav();
        View page;
        switch (i) {
            case 1: page = libsPage(); break;
            case 2: page = settingsPage(); break;
            case 3: page = aboutPage(); break;
            default: page = buildPage(); break;
        }
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (anim) {
            page.setAlpha(0f);
            page.setTranslationY(dp(18));
            page.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (i == 0) refreshBuild();
    }

    /** id, label en, label ar */
    private static final String[][] TARGETS = {
        {"auto", "⚡ Auto", "⚡ تلقائي"}, {"android", "🤖 Android APK", "🤖 أندرويد APK"},
        {"ps5", "PS5 ELF", "PS5 ELF"}, {"ps4", "PS4 ELF/PKG", "PS4 ELF/PKG"}, {"ps3", "PS3 SELF/PKG", "PS3 SELF/PKG"},
        {"ps2", "PS2 ELF", "PS2 ELF"}, {"ps1", "PS1 EXE", "PS1 EXE"}, {"native", "🐧 C/C++ ELF", "🐧 C/C++ ELF"},
        {"flutter", "💙 Flutter APK", "💙 Flutter APK"}, {"reactnative", "⚛ React Native", "⚛ React Native"},
        {"rust", "🦀 Rust", "🦀 Rust"}, {"go", "🐹 Go", "🐹 Go"}, {"node", "🟩 Node / Web", "🟩 Node / ويب"},
        {"python", "🐍 Python", "🐍 بايثون"}, {"maven", "☕ Maven JAR", "☕ Maven JAR"}, {"gradlejava", "☕ Gradle JAR", "☕ Gradle JAR"},
        {"java", "☕ Java JAR", "☕ جافا JAR"}, {"dotnet", "🟪 .NET / C#", "🟪 .NET / C#"}, {"script", "📜 build.sh / سكربت", "📜 build.sh / سكربت"},
        {"zig", "⚡ Zig", "⚡ Zig"}, {"nim", "👑 Nim", "👑 Nim"}, {"dart", "🎯 Dart", "🎯 Dart"}, {"haskell", "λ Haskell", "λ Haskell"}, {"ant", "🐜 Ant", "🐜 Ant"},
    };

    // ------------------------------------------------------------------ BUILD tab

    private View buildPage() {
        lastLog = ""; lastRes = "";
        LinearLayout list = vlist();
        list.addView(header(t("Source code → APK / ELF / PKG", "سورس كود ← APK / ELF / PKG")));

        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(permBox);

        // project
        LinearLayout pc = card();
        LinearLayout row = hrow();
        TextView pi = label("📁", 26, TEXT, false);
        pi.setGravity(Gravity.CENTER);
        pi.setBackground(round(CARD2, dp(12), AMBER));
        row.addView(pi, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        projName = label("", 16, TEXT, true);
        projName.setSingleLine(true);
        projName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(projName);
        projPath = label("", 11.5f, SUB, false);
        projPath.setMaxLines(2);
        projPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tx.addView(projPath);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        pc.addView(row);
        LinearLayout pb = hrow();
        pb.setPadding(0, dp(10), 0, 0);
        TextView choose = button("📂  " + t("Choose project", "اختر المشروع"), AMBER, false);
        choose.setOnClickListener(v -> browse());
        pb.addView(choose, new LinearLayout.LayoutParams(0, -2, 1f));
        pc.addView(pb);
        TextView tl = label("🎯  " + t("Build for", "التجميع لـ"), 12.5f, SUB, true);
        tl.setPadding(0, dp(12), 0, dp(6));
        pc.addView(tl);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tg = hrow();
        String cur = prefs.getString("target", "auto");
        final List<TextView> tchips = new ArrayList<>();
        for (String[] tt : TARGETS) {
            TextView c = label(ar ? tt[2] : tt[1], 12.5f, tt[0].equals(cur) ? BG : TEXT, true);
            c.setPadding(dp(12), dp(7), dp(12), dp(7));
            c.setBackground(round(tt[0].equals(cur) ? PURPLE : CARD2, dp(14), tt[0].equals(cur) ? PURPLE : STROKE));
            c.setOnClickListener(v -> {
                prefs.edit().putString("target", tt[0]).apply();
                for (int k = 0; k < tchips.size(); k++) {
                    boolean on = TARGETS[k][0].equals(tt[0]);
                    tchips.get(k).setTextColor(on ? BG : TEXT);
                    tchips.get(k).setBackground(round(on ? PURPLE : CARD2, dp(14), on ? PURPLE : STROKE));
                }
                if (tt[0].matches("ps[123]")) toast(t("First time: the toolchain is built from source and can take hours", "أول مرة: أدوات التجميع تنبني من السورس وممكن تاخذ ساعات"));
            });
            press(c);
            tchips.add(c);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.setMargins(0, 0, dp(6), 0);
            tg.addView(c, l);
        }
        hs.addView(tg);
        pc.addView(hs);
        list.addView(pc);

        // progress: compact row — ring on one side, status / stage / counters on the other
        LinearLayout rc = card();
        rc.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout top = hrow();
        top.setGravity(Gravity.CENTER_VERTICAL);
        ring = new Ring(this);
        top.addView(ring, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(10), 0, 0, 0);
        statusChip = label("", 10, BG, true);
        statusChip.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-2, -2);
        side.addView(statusChip, scl);
        stageTv = label("", 11, SUB, false);
        stageTv.setMaxLines(2);
        stageTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stageTv.setPadding(0, dp(4), 0, dp(6));
        side.addView(stageTv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout counters = hrow();
        timeTv = counter(counters, "⏱", t("Time", "الوقت"), CYAN);
        tasksTv = counter(counters, "⚙", t("Tasks", "المهام"), PURPLE);
        numTv = counter(counters, "#", t("Build", "التجميع"), AMBER);
        side.addView(counters, new LinearLayout.LayoutParams(-1, -2));
        top.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        rc.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout br = hrow();
        br.setPadding(0, dp(8), 0, 0);
        buildBtn = button("▶  " + t("BUILD", "جمّع"), GREEN, true);
        buildBtn.setOnClickListener(v -> startBuild());
        buildBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        br.addView(buildBtn, new LinearLayout.LayoutParams(0, dp(42), 1f));
        stopBtn = button("■  " + t("Stop", "إيقاف"), RED, false);
        stopBtn.setOnClickListener(v -> confirmStop());
        stopBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sl.setMargins(dp(8), 0, 0, 0);
        br.addView(stopBtn, sl);
        rc.addView(br, new LinearLayout.LayoutParams(-1, -2));
        list.addView(rc);

        // results
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(resultBox);

        // log
        LinearLayout lc = card();
        LinearLayout lh = hrow();
        lh.addView(label("🖥  " + t("Build log", "سجل التجميع"), 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cp = chip("📋 " + t("Copy", "نسخ"), CYAN);
        cp.setOnClickListener(v -> copyLog());
        lh.addView(cp);
        TextView ex = chip(logOpen ? "▲" : "▼", GREEN);
        ex.setOnClickListener(v -> {
            logOpen = !logOpen;
            Icons.apply(ex, logOpen ? "▲" : "▼");
            ViewGroup.LayoutParams p = logScroll.getLayoutParams();
            p.height = dp(logOpen ? 520 : 220);
            logScroll.setLayoutParams(p);
        });
        LinearLayout.LayoutParams exl = new LinearLayout.LayoutParams(-2, -2);
        exl.setMargins(dp(6), 0, 0, 0);
        lh.addView(ex, exl);
        lc.addView(lh);
        logScroll = steadyScroll();
        final float[] lastY = {0};
        logScroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: lastY[0] = e.getY(); break;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = lastY[0] - e.getY();
                    lastY[0] = e.getY();
                    // the log keeps the finger only while it can still scroll that way; at its ends the page scrolls
                    v.getParent().requestDisallowInterceptTouchEvent(dy != 0 && v.canScrollVertically(dy > 0 ? 1 : -1));
                    break;
                }
                default: v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        logScroll.setBackground(round(Color.parseColor("#020504"), dp(10), STROKE));
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        logTv = label("", 10.5f, GREEN, false);
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setTextIsSelectable(true);
        logScroll.addView(logTv);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(logOpen ? 520 : 220));
        ll.topMargin = dp(8);
        lc.addView(logScroll, ll);
        list.addView(lc);

        // statistics
        LinearLayout st = card();
        st.addView(label("📊  " + t("Statistics", "الإحصائيات"), 14, TEXT, true));
        statsTv = label("", 12.5f, SUB, false);
        statsTv.setPadding(0, dp(6), 0, 0);
        st.addView(statsTv);
        list.addView(st);

        return scroll(list);
    }

    private TextView counter(LinearLayout row, String icon, String name, int color) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(4), dp(2), dp(4));
        b.setBackground(round(CARD2, dp(10), STROKE));
        TextView v = label("—", 12.5f, color, true);
        v.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        v.setGravity(Gravity.CENTER);
        b.addView(v);
        TextView n = label(icon + " " + name, 9, SUB, false);
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        b.addView(n);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.setMargins(dp(2), 0, dp(2), 0);
        row.addView(b, l);
        return v;
    }

    private void refreshBuild() {
        if (tab != 0 || ring == null) return;
        String st = BuildService.state;
        boolean run = BuildService.running();
        boolean isBuild = "build".equals(BuildService.kind);

        // project
        String path = prefs.getString("proj_path", "");
        projName.setText(path.isEmpty() ? t("No project chosen", "ما اخترت مشروع") : prefs.getString("proj_name", ""));
        projPath.setText(path.isEmpty() ? t("Choose a project folder or a .zip (Android, PlayStation, C/C++)", "اختر مجلد المشروع أو ملف zip (أندرويد، بلايستيشن، C/C++)") : path);

        // status
        int col; String s;
        switch (st) {
            case "running": col = CYAN; s = isBuild ? t("BUILDING", "جاري التجميع") : t("INSTALLING", "جاري التثبيت"); break;
            case "ok": col = GREEN; s = t("SUCCESS", "نجح"); break;
            case "fail": col = RED; s = t("FAILED", "فشل"); break;
            case "stopped": col = AMBER; s = t("STOPPED", "توقف"); break;
            default: col = SUB; s = t("READY", "جاهز"); break;
        }
        statusChip.setText(s);
        statusChip.setBackground(round(col, dp(12), col));
        ring.set("idle".equals(st) ? 0 : BuildService.percent, col, run);
        String stage = BuildService.stage;
        if ("fail".equals(st) && BuildService.error != null && !BuildService.error.isEmpty()) stage = BuildService.error;
        if ("idle".equals(st)) stage = !Env.supported() ? t("This phone is not 64-bit ARM (arm64)", "هذا الجوال مو 64-bit ARM")
            : Env.ready(this) ? t("Choose a project and press BUILD", "اختر مشروع واضغط جمّع")
            : t("Choose a project and press BUILD — the first build sets up Ubuntu (one time)", "اختر مشروع واضغط جمّع — أول تجميع يجهّز Ubuntu (مرة وحدة)");
        stageTv.setText(stage);
        stageTv.setTextColor("fail".equals(st) ? RED : SUB);
        long el = run ? System.currentTimeMillis() - BuildService.startMs : BuildService.endMs > 0 ? BuildService.endMs - BuildService.startMs : 0;
        timeTv.setText(clock(el));
        tasksTv.setText(String.valueOf(BuildService.tasks));
        numTv.setText(String.valueOf(prefs.getInt("builds_total", 0) + (run && isBuild ? 1 : 0)));
        buildBtn.setAlpha(run ? 0.4f : 1f);
        buildBtn.setEnabled(!run);
        stopBtn.setAlpha(run ? 1f : 0.35f);
        stopBtn.setEnabled(run);
        if (run && prefs.getBoolean("screen", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // log (last 400 lines)
        List<String> lines = BuildService.logLines();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 400); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        if (sb.length() == 0) sb.append(t("The build output appears here.", "مخرجات التجميع تظهر هنا."));
        String logNow = sb.toString();
        if (!logNow.equals(lastLog)) {
            // follow the end of the log only when it was already at the end (so you can read older lines)
            boolean atEnd = logScroll.getScrollY() + logScroll.getHeight() >= logTv.getHeight() - dp(24);
            lastLog = logNow;
            logTv.setText(logNow);
            if (run && atEnd) logScroll.post(() -> logScroll.scrollTo(0, Math.max(0, logTv.getHeight() - logScroll.getHeight())));
        }

        // results (rebuilt only when they change, so the page does not jump)
        List<String> out;
        synchronized (BuildService.class) { out = new ArrayList<>(BuildService.apks); }
        String resKey = (isBuild && !out.isEmpty() && !run) ? out.toString() : "";
        if (!resKey.equals(lastRes)) {
            lastRes = resKey;
            resultBox.removeAllViews();
            if (!resKey.isEmpty()) resultBox.addView(resultCard(out));
        }

        // stats
        long best = prefs.getLong("best_ms", 0), last = prefs.getLong("last_ms", 0);
        statsTv.setText(t("Builds: ", "عدد التجميعات: ") + prefs.getInt("builds_total", 0)
            + "   ✓ " + prefs.getInt("builds_ok", 0) + "   ✗ " + prefs.getInt("builds_fail", 0)
            + "\n" + t("Fastest: ", "الأسرع: ") + (best > 0 ? clock(best) : "—") + "   •   " + t("Last: ", "الأخير: ") + (last > 0 ? clock(last) : "—"));

        renderPermissions();

        if (!lastState.equals(st)) {
            if ("running".equals(lastState) && "ok".equals(st)) ring.celebrate();
            lastState = st;
        }
    }

    private View resultCard(List<String> out) {
        LinearLayout c = card();
        c.setBackground(round(CARD, dp(16), GREEN));
        boolean anyApk = false;
        for (String p : out) if (p.endsWith(".apk")) anyApk = true;
        String tgt = BuildService.target == null ? "" : BuildService.target.toUpperCase(Locale.ROOT);
        c.addView(label("🎉  " + (anyApk ? t("Your app is ready", "تطبيقك جاهز") : t("Your files are ready", "ملفاتك جاهزة") + (tgt.startsWith("PS") ? "  •  " + tgt : "")), 15, GREEN, true));
        TextView where = label(t("Saved in: ", "انحفظ في: ") + "Building source/APK/" + prefs.getString("proj_name", ""), 11.5f, SUB, false);
        where.setPadding(0, dp(2), 0, dp(6));
        c.addView(where);
        for (String p : out) {
            File f = new File(p);
            LinearLayout r = hrow();
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackground(round(CARD2, dp(12), STROKE));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView n = label("📦 " + f.getName(), 13, TEXT, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tx.addView(n);
            tx.addView(label(human(f.length()), 11, SUB, false));
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            if (p.endsWith(".apk")) {
                TextView in = chip(t("Install", "تثبيت"), GREEN);
                in.setOnClickListener(v -> install(f));
                r.addView(in);
            }
            TextView sh = chip(t("Share", "مشاركة"), CYAN);
            sh.setOnClickListener(v -> share(f));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.setMargins(dp(6), 0, 0, 0);
            r.addView(sh, l);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(6);
            c.addView(r, rl);
        }
        return c;
    }

    /** The Linux environment is prepared on the first build; the phone only has to be 64-bit ARM. */
    private boolean bootstrapped() { return Env.supported(); }

    private void startBuild() {
        if (BuildService.running()) return;
        if (!bootstrapped()) {
            toast(t("This phone is not 64-bit ARM — the build tools need arm64", "هذا الجوال مو 64-bit ARM، أدوات التجميع تحتاج arm64"));
            return;
        }
        String path = prefs.getString("proj_path", "");
        if (path.isEmpty() || !new File(path).exists()) { browse(); return; }
        if (!storageOk()) { askStorage(); return; }
        Intent i = new Intent(this, BuildService.class).setAction(BuildService.ACTION_BUILD)
            .putExtra(BuildService.EXTRA_SRC, path)
            .putExtra(BuildService.EXTRA_NAME, prefs.getString("proj_name", "project"));
        startSvc(i);
        ring.pulse();
        toast(t("Build started — it keeps running in the background", "بدأ التجميع، ويكمل حتى لو طلعت من التطبيق"));
        ui.postDelayed(this::refreshBuild, 400);
    }

    private void startSvc(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void confirmStop() {
        if (!BuildService.running()) return;
        dlg().setTitle(t("Stop?", "إيقاف؟"))
            .setMessage(t("The current work will be stopped.", "بيتوقف الشغل الحالي."))
            .setPositiveButton(t("Stop", "إيقاف"), (d, w) -> startService(new Intent(this, BuildService.class).setAction(BuildService.ACTION_STOP)))
            .setNegativeButton(t("Continue", "كمّل"), null).show();
    }

    private void copyLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : BuildService.logLines()) sb.append(l).append('\n');
        try {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", sb.toString()));
            toast(t("Log copied", "تم نسخ السجل"));
        } catch (Exception ignored) {}
    }

    private Uri uriFor(File f) { return FileProvider.getUriForFile(this, "com.m7l.buildingsource.files", f); }

    private void install(File f) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast(t("Allow “Install unknown apps” for Building source, then press Install again", "اسمح بـ «تثبيت تطبيقات غير معروفة» لـ Building source وبعدها اضغط تثبيت"));
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { toast(String.valueOf(e.getMessage())); }
    }

    private void share(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType(f.getName().endsWith(".apk") ? "application/vnd.android.package-archive" : "application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uriFor(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (Exception e) { toast(String.valueOf(e.getMessage())); }
    }

    // ------------------------------------------------------------------ choosing a project

    private static boolean isProject(File d) {
        return new File(d, "settings.gradle").exists() || new File(d, "settings.gradle.kts").exists()
            || new File(d, "build.gradle").exists() || new File(d, "build.gradle.kts").exists()
            || new File(d, "Makefile").exists() || new File(d, "makefile").exists() || new File(d, "CMakeLists.txt").exists()
            || new File(d, "meson.build").exists();
    }

    private void setProject(File f) {
        String n = f.getName();
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty() || n.replace("_", "").isEmpty()) n = "project";
        prefs.edit().putString("proj_path", f.getAbsolutePath()).putString("proj_name", n).apply();
        addRecent(f.getAbsolutePath(), n);
        if (tab != 0) showTab(0, true); else refreshBuild();
        toast("✔ " + n);
    }

    private void browse() {
        if (!storageOk()) { askStorage(); return; }
        String last = prefs.getString("browse_dir", "");
        File start = !last.isEmpty() && new File(last).isDirectory() ? new File(last) : Environment.getExternalStorageDirectory();
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        if (ar) col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout top = hrow();
        top.setBackgroundColor(BAR);
        top.setPadding(dp(8), dp(12), dp(8), dp(12));
        TextView close = label("✕", 20, TEXT, true);
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(label(t("Choose a project", "اختر المشروع"), 16, TEXT, true));
        TextView pathTv = label("", 11, SUB, false);
        pathTv.setSingleLine(true);
        pathTv.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tt.addView(pathTv);
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(top);
        ScrollView sv = new ScrollView(this);
        LinearLayout items = vlist();
        sv.addView(items);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bottom = hrow();
        bottom.setBackgroundColor(BAR);
        bottom.setPadding(dp(10), dp(10), dp(10), dp(12));
        TextView zip = button("🗜  " + t("Zip from other app", "zip من تطبيق ثاني"), CYAN, false);
        zip.setOnClickListener(v -> {
            d.dismiss();
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(i, "zip"), REQ_ZIP); }
            catch (Exception e) { toast(t("No file app found", "ما في تطبيق ملفات")); }
        });
        bottom.addView(zip, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView use = button("✔  " + t("Use this folder", "استخدم هذا المجلد"), GREEN, true);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(0, -2, 1f);
        ul.setMargins(dp(8), 0, 0, 0);
        bottom.addView(use, ul);
        col.addView(bottom);
        final File[] cur = {start};
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            File dir = cur[0];
            prefs.edit().putString("browse_dir", dir.getAbsolutePath()).apply();
            pathTv.setText(dir.getAbsolutePath());
            boolean proj = isProject(dir);
            use.setAlpha(proj ? 1f : 0.35f);
            use.setOnClickListener(v -> { if (isProject(cur[0])) { d.dismiss(); setProject(cur[0]); } else toast(t("This folder is not a project (no Gradle / Makefile / CMake)", "هذا المجلد مو مشروع (ما فيه Gradle أو Makefile أو CMake)")); });
            items.removeAllViews();
            File parent = dir.getParentFile();
            if (parent != null && parent.canRead()) {
                View up = fileRow("⬆", "..", t("Up", "للأعلى"), SUB);
                up.setOnClickListener(v -> { cur[0] = parent; render[0].run(); });
                items.addView(up);
            }
            if (proj) {
                TextView hint = label("🤖  " + t("This folder is a project — press “Use this folder”", "هذا المجلد مشروع، اضغط «استخدم هذا المجلد»"), 12.5f, GREEN, true);
                hint.setPadding(dp(6), dp(6), dp(6), dp(8));
                items.addView(hint);
            }
            File[] fs = dir.listFiles();
            if (fs == null) fs = new File[0];
            Arrays.sort(fs, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            int shown = 0;
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    boolean p = isProject(f);
                    View r = fileRow(p ? "🤖" : "📁", f.getName(), p ? t("Project", "مشروع") : "", p ? GREEN : TEXT);
                    r.setOnClickListener(v -> { cur[0] = f; render[0].run(); });
                    if (p) r.setOnLongClickListener(v -> { d.dismiss(); setProject(f); return true; });
                    items.addView(r);
                    shown++;
                } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    View r = fileRow("🗜", f.getName(), human(f.length()) + " • " + t("tap to choose", "اضغط للاختيار"), AMBER);
                    r.setOnClickListener(v -> { d.dismiss(); setProject(f); });
                    items.addView(r);
                    shown++;
                }
            }
            if (shown == 0) {
                TextView e = label(t("No folders or .zip files here", "ما في مجلدات أو ملفات zip هنا"), 13, SUB, false);
                e.setPadding(dp(8), dp(20), dp(8), dp(20));
                items.addView(e);
            }
            sv.scrollTo(0, 0);
        };
        render[0].run();
        d.setContentView(col);
        d.show();
    }

    private View fileRow(String icon, String name, String sub, int color) {
        LinearLayout r = hrow();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(CARD, dp(12), STROKE));
        TextView i = label(icon, 20, TEXT, false);
        i.setGravity(Gravity.CENTER);
        r.addView(i, new LinearLayout.LayoutParams(dp(34), -2));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(8), 0, 0, 0);
        TextView n = label(name, 14, color, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(n);
        if (sub != null && !sub.isEmpty()) tx.addView(label(sub, 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, dp(3), 0, dp(3));
        r.setLayoutParams(l);
        press(r);
        return r;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ZIP && res == RESULT_OK && data != null && data.getData() != null) importZip(data.getData());
    }

    /** Copies a .zip from another app into the private area and selects it. */
    private void importZip(Uri u) {
        toast(t("Importing…", "جاري الاستيراد…"));
        new Thread(() -> {
            try {
                String name = "project.zip";
                try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                } catch (Exception ignored) {}
                if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name + ".zip";
                File dir = new File(BuildService.baseDir(), "zips");
                if (!dir.isDirectory()) dir.mkdirs();
                File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream o = new FileOutputStream(out)) {
                    if (in == null) throw new Exception("open");
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                ui.post(() -> setProject(out));
            } catch (Exception e) {
                ui.post(() -> toast(t("Could not import: ", "ما قدرت أستورد: ") + e.getMessage()));
            }
        }).start();
    }

    private void addRecent(String path, String name) {
        if (path != null) return;   // history is off: a built project is not remembered
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]")), n = new JSONArray();
            n.put(new JSONObject().put("path", path).put("name", name));
            for (int i = 0; i < a.length() && n.length() < 10; i++) {
                JSONObject o = a.getJSONObject(i);
                if (!path.equals(o.optString("path"))) n.put(o);
            }
            prefs.edit().putString("recent", n.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void outDirDialog() {
        final String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] presets = {
            new File(BuildService.baseDir(), "APK").getAbsolutePath(),
            root + "/Download/Building source",
            root + "/Documents/Building source",
            root + "/Download",
        };
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(BuildService.outDir().getAbsolutePath());
        et.setTextColor(TEXT);
        et.setTextSize(13);
        et.setSingleLine(true);
        f.addView(et);
        for (String pth : presets) {
            TextView c = chip(pth.replace(root, "…"), SUB);
            c.setOnClickListener(v -> et.setText(pth));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = dp(6);
            f.addView(c, l);
        }
        dlg().setTitle(t("Output folder", "مجلد الملفات الناتجة")).setView(f)
            .setPositiveButton(t("Save", "حفظ"), (d, w) -> {
                String v = et.getText().toString().trim();
                while (v.endsWith("/") && v.length() > 1) v = v.substring(0, v.length() - 1);
                if (!v.startsWith("/storage/") && !v.startsWith("/sdcard")) { toast(t("Choose a folder in the phone storage (/storage/…)", "اختر مجلد في ذاكرة الجوال (/storage/…)")); return; }
                File dir = new File(v);
                if (!dir.isDirectory() && !dir.mkdirs()) { toast(t("Cannot create this folder", "ما قدرت أسوي هذا المجلد")); return; }
                String def = new File(BuildService.baseDir(), "APK").getAbsolutePath();
                prefs.edit().putString("out_dir", v.equals(def) ? "" : v).apply();
                BuildService.outPath = v.equals(def) ? "" : v;
                toast("✔ " + v);
                showTab(2, false);
            })
            .setNegativeButton(t("Cancel", "إلغاء"), null).show();
    }

    private void recentDialog() {
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            List<String> names = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!new File(o.optString("path")).exists()) continue;
                names.add(o.optString("name") + "\n     " + o.optString("path"));
                paths.add(o.optString("path"));
            }
            if (names.isEmpty()) { toast(t("No recent projects", "ما في مشاريع سابقة")); return; }
            dlg().setTitle(t("Recent projects", "المشاريع الأخيرة"))
                .setItems(names.toArray(new String[0]), (d, w) -> setProject(new File(paths.get(w)))).show();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ permissions

    private boolean storageOk() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try { return ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()); }
        catch (Exception e) { return true; }
    }

    private boolean allFilesOk() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try { return Environment.isExternalStorageManager(); } catch (Exception e) { return true; }
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    @SuppressLint("BatteryLife")
    private void askBattery() {
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {} }
    }

    private void askAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return;
        try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) {} }
    }

    private void askNotifications() {
        try {
            Intent i = Build.VERSION.SDK_INT >= 26
                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_STORAGE) {
            if (storageOk()) toast("✔ " + t("Storage allowed", "تم السماح بالتخزين"));
            if (tab == 0) refreshBuild(); else if (tab == 2) showTab(2, false);
        }
    }

    private void renderPermissions() {
        if (permBox == null) return;
        boolean s = storageOk(), b = batteryOk();
        String key = s + "/" + b + "/" + permBox.hashCode();
        if (key.equals(lastPerm)) return;
        lastPerm = key;
        permBox.removeAllViews();
        if (s && b) return;
        LinearLayout c = card();
        c.setBackground(round(CARD, dp(16), AMBER));
        c.addView(label("🔐  " + t("Permissions needed", "صلاحيات مطلوبة"), 14, AMBER, true));
        if (!s) c.addView(permRow("📁", t("Files & storage", "الملفات والتخزين"), t("to read your projects and save the APK", "عشان يقرأ مشاريعك ويحفظ التطبيق"), false, this::askStorage));
        if (!b) c.addView(permRow("🔋", t("Run in background", "التشغيل في الخلفية"), t("so long builds are not stopped by the system", "عشان النظام ما يوقف التجميع الطويل"), false, this::askBattery));
        permBox.addView(c);
    }

    private View permRow(String icon, String name, String sub, boolean ok, Runnable ask) {
        LinearLayout r = hrow();
        r.setPadding(0, dp(8), 0, dp(4));
        TextView i = label(icon, 18, TEXT, false);
        r.addView(i, new LinearLayout.LayoutParams(dp(30), -2));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(label(name, 13.5f, TEXT, true));
        tx.addView(label(sub, 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = chip(ok ? "✓" : t("Allow", "سماح"), ok ? GREEN : AMBER);
        if (!ok) b.setOnClickListener(v -> ask.run());
        r.addView(b);
        return r;
    }

    // ------------------------------------------------------------------ LIBRARIES tab

    private File in(String path) { return Env.inside(this, path); }
    private static final String SDK = "/root/android-sdk";

    /** id, title en, title ar, desc en, desc ar, size, required */
    private static final String[][] LIBS = {
        {"java17", "Java 17 (OpenJDK)", "جافا 17 (OpenJDK)", "Runs Gradle — needed for most projects", "يشغّل Gradle، تحتاجه أغلب المشاريع", "~200 MB", "1"},
        {"java8", "Java 8 (OpenJDK)", "جافا 8 (OpenJDK)", "Very old projects (AGP 3.x, Gradle 4)", "المشاريع القديمة جداً (AGP 3 و Gradle 4)", "~150 MB", "0"},
        {"java11", "Java 11 (OpenJDK)", "جافا 11 (OpenJDK)", "Older projects (AGP 4–7, Gradle 5–7.2)", "المشاريع القديمة (AGP 4 إلى 7 و Gradle 5 إلى 7.2)", "~180 MB", "0"},
        {"java21", "Java 21 (OpenJDK)", "جافا 21 (OpenJDK)", "For new projects that need Java 21", "للمشاريع الجديدة اللي تحتاج جافا 21", "~210 MB", "0"},
        {"aapt2", "AAPT2 for ARM", "AAPT2 للمعالجات ARM", "Packs the app resources", "يجمّع موارد التطبيق", "~10 MB", "1"},
        {"cmdline", "Android SDK tools", "أدوات Android SDK", "sdkmanager to download SDK parts", "sdkmanager لتنزيل أجزاء الـ SDK", "~150 MB", "1"},
        {"platform-34", "Android 14 platform (API 34)", "منصة أندرويد 14 (API 34)", "android.jar for compileSdk 34", "ملف android.jar لـ compileSdk 34", "~60 MB", "1"},
        {"platform-35", "Android 15 platform (API 35)", "منصة أندرويد 15 (API 35)", "android.jar for compileSdk 35", "ملف android.jar لـ compileSdk 35", "~60 MB", "1"},
        {"platform-36", "Android 16 platform (API 36)", "منصة أندرويد 16 (API 36)", "android.jar for compileSdk 36", "ملف android.jar لـ compileSdk 36", "~60 MB", "0"},
        {"platform-32", "Android 12L platform (API 32)", "منصة أندرويد 12L (API 32)", "android.jar for compileSdk 32", "ملف android.jar لـ compileSdk 32", "~60 MB", "0"},
        {"platform-31", "Android 12 platform (API 31)", "منصة أندرويد 12 (API 31)", "android.jar for compileSdk 31", "ملف android.jar لـ compileSdk 31", "~60 MB", "0"},
        {"platform-30", "Android 11 platform (API 30)", "منصة أندرويد 11 (API 30)", "android.jar for compileSdk 30", "ملف android.jar لـ compileSdk 30", "~60 MB", "0"},
        {"platform-29", "Android 10 platform (API 29)", "منصة أندرويد 10 (API 29)", "android.jar for compileSdk 29", "ملف android.jar لـ compileSdk 29", "~60 MB", "0"},
        {"platform-28", "Android 9 platform (API 28)", "منصة أندرويد 9 (API 28)", "android.jar for compileSdk 28", "ملف android.jar لـ compileSdk 28", "~60 MB", "0"},
        {"platform-old", "Android 5–8.1 platforms (API 21–27)", "منصات أندرويد 5 إلى 8.1 (API 21–27)", "For very old projects", "للمشاريع القديمة جداً", "~350 MB", "0"},
        {"buildtools-old", "Older build-tools (30, 33, 34)", "Build-tools قديمة (30 و 33 و 34)", "For projects that pin buildToolsVersion", "للمشاريع اللي تحدد buildToolsVersion", "~150 MB", "0"},
        {"gradle-old", "Older Gradle (4.10, 5.6, 6.9, 7.6)", "Gradle قديم (4.10 و 5.6 و 6.9 و 7.6)", "For old projects without gradlew", "للمشاريع القديمة اللي ما فيها gradlew", "~500 MB", "0"},
        {"platform-33", "Android 13 platform (API 33)", "منصة أندرويد 13 (API 33)", "android.jar for compileSdk 33", "ملف android.jar لـ compileSdk 33", "~60 MB", "0"},
        {"buildtools", "Build-tools 35", "Build-tools 35", "Build tools (linked to the ARM versions)", "أدوات البناء (مربوطة بنسخ ARM)", "~60 MB", "1"},
        {"ndk", "NDK — C/C++ (ARM64)", "NDK — سي/سي++ (ARM64)", "Only for projects with native C/C++ code", "بس للمشاريع اللي فيها كود C/C++", "~700 MB", "0"},
        {"gradle", "Gradle (system)", "Gradle (النظام)", "Only for projects without gradlew", "بس للمشاريع اللي ما فيها gradlew", "~150 MB", "0"},
        {"git", "Git", "Git", "Some projects fetch code with git", "بعض المشاريع تجيب كود بـ git", "~30 MB", "0"},
        {"ps5", "PS5 payload SDK", "PS5 payload SDK", "clang + lld • builds PS5 .elf payloads", "clang + lld • يجمّع ملفات ‎.elf للـ PS5", "~600 MB", "0"},
        {"ps4", "PS4 OpenOrbis toolchain", "أدوات PS4 OpenOrbis", "clang + lld • .elf / eboot.bin (.pkg needs .NET)", "clang + lld • ‎.elf و eboot.bin (الـ pkg يحتاج .NET)", "~800 MB", "0"},
        {"ps3", "PS3 toolchain + PSL1GHT", "أدوات PS3 + PSL1GHT", "Built from source — can take hours", "تنبني من السورس، ممكن تاخذ ساعات", "~2 GB", "0"},
        {"ps2", "PS2 toolchain (ps2dev)", "أدوات PS2 (ps2dev)", "Ready-made ARM64 build (minutes)", "نسخة ARM64 جاهزة (دقائق)", "~700 MB", "0"},
        {"buildsys", "All build systems", "كل أنظمة البناء", "make, CMake, Ninja, Meson, autotools, SCons, Ant, just, xmake, nasm …", "make و CMake و Ninja و Meson و autotools و SCons و Ant و just و xmake و nasm …", "~300 MB", "0"},
        {"devlibs", "Common dev libraries", "مكتبات التطوير الشائعة", "SDL2, OpenGL, Vulkan, GLFW, curl, SSL, PNG, JPEG, FreeType, audio …", "SDL2 و OpenGL و Vulkan و GLFW و curl و SSL و PNG و JPEG و FreeType وصوت …", "~600 MB", "0"},
        {"aptfile", "Missing-library finder", "مكتشف المكتبات الناقصة", "Finds the package for a missing header / library and installs it", "يلقى الحزمة لأي ملف أو مكتبة ناقصة ويثبتها", "~60 MB", "0"},
        {"zig", "Zig", "Zig", "build.zig projects", "مشاريع build.zig", "~50 MB", "0"},
        {"nim", "Nim", "Nim", ".nim / .nimble projects", "مشاريع nim", "~60 MB", "0"},
        {"dart", "Dart SDK", "Dart SDK", "Dart programs (not Flutter)", "برامج Dart (مو Flutter)", "~250 MB", "0"},
        {"haskell", "Haskell (GHC + cabal)", "Haskell (GHC + cabal)", ".cabal projects", "مشاريع cabal", "~900 MB", "0"},
        {"cc", "C / C++ (GCC, Clang, CMake, Meson)", "سي/سي++ (GCC و Clang و CMake و Meson)", "Native programs for Linux / Termux", "برامج لينكس وتيرمكس", "~350 MB", "0"},
        {"rust", "Rust (rustup + cargo)", "Rust (rustup + cargo)", "Cargo.toml projects", "مشاريع Cargo.toml", "~500 MB", "0"},
        {"go", "Go", "Go", "go.mod projects", "مشاريع go.mod", "~250 MB", "0"},
        {"node", "Node.js LTS (npm / yarn / pnpm)", "Node.js LTS (npm / yarn / pnpm)", "Web, React, Vue, React Native", "ويب و React و Vue و React Native", "~120 MB", "0"},
        {"python", "Python 3 + pip", "بايثون 3 + pip", "Wheels or one executable (PyInstaller)", "ملف wheel أو برنامج واحد (PyInstaller)", "~150 MB", "0"},
        {"maven", "Maven", "Maven", "pom.xml Java projects", "مشاريع جافا pom.xml", "~60 MB", "0"},
        {"dotnet", ".NET SDK 8", ".NET SDK 8", "C# / F# console & web apps", "تطبيقات C# و F# (كونسول وويب)", "~700 MB", "0"},
        {"flutter", "Flutter SDK (experimental)", "Flutter SDK (تجريبي)", "Flutter APKs — ARM support is limited", "تطبيقات Flutter، دعم ARM محدود", "~1.5 GB", "0"},
        {"ps1", "PS1 GCC + PSn00bSDK", "أدوات PS1 (GCC + PSn00bSDK)", "Built from source — can take hours", "تنبني من السورس، ممكن تاخذ ساعات", "~1.5 GB", "0"},
    };

    /** M7L project libraries — same columns as LIBS */
    private static final String[][] M7L_LIBS = {
        {"m7l-dosbox", "DOSBox 0.74-3", "DOSBox 0.74-3", "DOS emulator • GPL v2", "محاكي DOS • GPL v2", "~10 MB", "0"},
        {"m7l-pcsx_rearmed", "pcsx_rearmed", "pcsx_rearmed", "PS1 emulator • GPL v2", "محاكي PS1 • GPL v2", "~60 MB", "0"},
        {"m7l-pcsx2", "PCSX2", "PCSX2", "PS2 emulator • GPL v3", "محاكي PS2 • GPL v3", "~150 MB", "0"},
        {"m7l-daedalusx64", "DaedalusX64", "DaedalusX64", "N64 emulator (libretro)", "محاكي N64 (libretro)", "~30 MB", "0"},
        {"m7l-dr_libs", "dr_libs", "dr_libs", "MP3 / FLAC / WAV audio • public domain", "صوت MP3 و FLAC و WAV • ملك عام", "~3 MB", "0"},
        {"m7l-stb", "stb", "stb", "stb_vorbis + stb_image • public domain", "stb_vorbis و stb_image • ملك عام", "~9 MB", "0"},
        {"m7l-imgui", "Dear ImGui", "Dear ImGui", "UI for PS4SX2 • MIT", "واجهة PS4SX2 • MIT", "~15 MB", "0"},
        {"m7l-glslang", "glslang", "glslang", "Shaders", "شيدرات", "~60 MB", "0"},
        {"m7l-shaderc", "shaderc", "shaderc", "Shaders", "شيدرات", "~10 MB", "0"},
        {"m7l-rapidyaml", "rapidyaml v0.12.1", "rapidyaml v0.12.1", "YAML", "YAML", "~20 MB", "0"},
        {"m7l-sdl2", "SDL2", "SDL2", "Video / audio / input", "صورة وصوت ومدخلات", "~90 MB", "0"},
        {"m7l-miniz", "miniz", "miniz", "ZIP extraction • MIT", "فك ZIP • MIT", "~2 MB", "0"},
        {"m7l-libchdr", "libchdr", "libchdr", "CHD format (+LZMA)", "صيغة CHD (+LZMA)", "~15 MB", "0"},
        {"m7l-zstd", "zstd", "zstd", "Compression", "ضغط", "~15 MB", "0"},
        {"m7l-mesa", "Mesa (RADV)", "Mesa (RADV)", "Vulkan for PS4SX2 — big", "Vulkan لـ PS4SX2، حجمه كبير", "~400 MB", "0"},
        {"m7l-openorbis", "OpenOrbis (PS4) source", "سورس OpenOrbis (PS4)", "Toolchain source", "سورس أدوات البناء", "~50 MB", "0"},
        {"m7l-psl1ght", "PSL1GHT (PS3) source", "سورس PSL1GHT (PS3)", "Toolchain source", "سورس أدوات البناء", "~30 MB", "0"},
        {"m7l-ps2sdk", "PS2SDK (PS2) source", "سورس PS2SDK (PS2)", "Toolchain source", "سورس أدوات البناء", "~40 MB", "0"},
        {"m7l-termux", "Termux (base)", "Termux (الأساس)", "termux-app source", "سورس termux-app", "~40 MB", "0"},
        {"m7l-python", "Python: Pillow + Playwright", "بايثون: Pillow + Playwright", "pip install + playwright install chromium", "pip install مع playwright install chromium", "~400 MB", "0"},
        {"m7l-fonts", "Fonts", "الخطوط", "DejaVu Sans, Montserrat, Cairo, Almarai", "DejaVu Sans و Montserrat و Cairo و Almarai", "~3 MB", "0"},
    };
    private static final String TCD = "/root/.bsource/toolchains";

    private static String libGroup(String id) {
        if (id.startsWith("ps")) return "ps";
        if (id.startsWith("java") || id.startsWith("platform") || id.startsWith("buildtools") || id.startsWith("gradle")
            || id.equals("aapt2") || id.equals("cmdline") || id.equals("ndk") || id.equals("git")) return "android";
        if (id.equals("buildsys") || id.equals("cc") || id.equals("devlibs") || id.equals("aptfile")) return "build";
        return "lang";
    }

    private boolean libInstalled(String id) {
        if (!Env.ready(this)) return false;
        switch (id) {
            case "java17": return Env.exists(in("/usr/lib/jvm/java-17-openjdk-arm64/bin/java"));
            case "java8": return Env.exists(in("/usr/lib/jvm/java-8-openjdk-arm64/bin/java"));
            case "java11": return Env.exists(in("/usr/lib/jvm/java-11-openjdk-arm64/bin/java"));
            case "platform-old": return in(SDK + "/platforms/android-21/android.jar").exists();
            case "buildtools-old": return in(SDK + "/build-tools/30.0.3").isDirectory();
            case "gradle-old": return in("/root/.bsource/gradle/gradle-6.9.4").isDirectory();
            case "java21": return Env.exists(in("/usr/lib/jvm/java-21-openjdk-arm64/bin/java"));
            case "aapt2": return in("/opt/bsource/bin/aapt2").exists();
            case "cmdline": return in(SDK + "/cmdline-tools/latest/bin/sdkmanager").exists();
            case "buildtools": return in(SDK + "/build-tools/35.0.0").isDirectory();
            case "ndk": { File[] f = in(SDK + "/ndk").listFiles(); return f != null && f.length > 0; }
            case "gradle": return in("/root/.bsource/gradle").isDirectory();
            case "git": return in("/usr/bin/git").exists();
            case "ps5": return in(TCD + "/ps5-payload-sdk").isDirectory();
            case "ps4": return in(TCD + "/OpenOrbis/include").isDirectory();
            case "ps3": return in(TCD + "/ps3dev/ppu/bin/ppu-gcc").exists();
            case "ps2": return in(TCD + "/ps2dev/ps2sdk/ee").isDirectory();
            case "ps1": return in(TCD + "/psn00bsdk/lib/libpsn00b").isDirectory();
            case "buildsys": return Env.exists(in("/usr/bin/scons")) && Env.exists(in("/usr/bin/ninja"));
            case "devlibs": return in("/usr/include/SDL2/SDL.h").exists() && in("/usr/include/GLFW/glfw3.h").exists();
            case "aptfile": return in("/root/.bsource/.aptfile").exists();
            case "zig": return in("/opt/bsource/zig/zig").exists();
            case "nim": return Env.exists(in("/usr/bin/nim"));
            case "dart": return in("/opt/bsource/dart-sdk/bin/dart").exists();
            case "haskell": return Env.exists(in("/usr/bin/ghc"));
            case "cc": return Env.exists(in("/usr/bin/cmake")) && Env.exists(in("/usr/bin/clang"));
            case "rust": return in("/root/.cargo/bin/cargo").exists();
            case "go": return Env.exists(in("/usr/bin/go")) || in("/usr/lib/go/bin/go").exists();
            case "node": return in("/opt/bsource/node/bin/node").exists();
            case "python": return Env.exists(in("/usr/bin/pip3"));
            case "maven": return Env.exists(in("/usr/bin/mvn"));
            case "dotnet": return in("/opt/bsource/dotnet/dotnet").exists();
            case "flutter": return in("/root/.bsource/flutter/bin/flutter").exists();
        }
        if (id.equals("m7l-python")) return in("/root/.bsource/pyenv/bin/playwright").exists();
        if (id.equals("m7l-fonts")) return in("/usr/share/fonts/truetype/m7l").isDirectory();
        if (id.startsWith("m7l-")) { File[] f = in("/root/.bsource/libs/" + id.substring(4)).listFiles(); return f != null && f.length > 0; }
        if (id.startsWith("platform-")) return in(SDK + "/platforms/android-" + id.substring(9) + "/android.jar").exists();
        return false;
    }

    private View libsPage() {
        LinearLayout list = vlist();
        list.addView(header(t("Tools & libraries for building", "أدوات ومكتبات التجميع")));

        int req = 0, reqOk = 0;
        for (String[] l : LIBS) if ("1".equals(l[6])) { req++; if (libInstalled(l[0])) reqOk++; }
        LinearLayout top = card();
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean all = reqOk == req;
        TextView big = label(all ? "✅" : "📦", 38, TEXT, false);
        big.setGravity(Gravity.CENTER);
        top.addView(big);
        TextView s = label(all ? t("Everything needed is installed", "كل الأساسيات مثبتة") : reqOk + " / " + req + t(" required parts installed", " من الأجزاء الأساسية مثبتة"), 14, all ? GREEN : TEXT, true);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(4), 0, dp(4));
        top.addView(s);
        TextView note = label(t("One time, about 1 GB. Needs internet. Missing parts are also installed automatically when you build.",
            "مرة وحدة بس، تقريباً 1GB، ويحتاج إنترنت. الناقص ينثبت تلقائياً كمان لما تجمّع."), 11.5f, SUB, false);
        note.setGravity(Gravity.CENTER);
        top.addView(note);
        TextView ia = button("⬇  " + t("Install all required", "تثبيت كل الأساسيات"), GREEN, true);
        ia.setOnClickListener(v -> runLibs("install", new String[]{"required"}, t("required tools", "الأدوات الأساسية")));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, dp(50));
        il.topMargin = dp(12);
        top.addView(ia, il);
        list.addView(top);

        if (BuildService.running()) {
            TextView busy = label("⏳  " + t("Working: ", "جاري: ") + BuildService.title + " • " + BuildService.percent + "%  — " + t("see the Build tab", "شوف تبويب التجميع"), 12.5f, CYAN, true);
            busy.setPadding(dp(4), dp(4), dp(4), dp(8));
            busy.setOnClickListener(v -> showTab(0, true));
            list.addView(busy);
        }

        String[][] groups = {
            {"android", t("Android (Java, SDK, NDK, Gradle)", "أندرويد (جافا و SDK و NDK و Gradle)")},
            {"build", t("Build systems & C/C++", "أنظمة البناء و C/C++")},
            {"lang", t("Languages", "لغات البرمجة")},
            {"ps", t("PlayStation toolchains", "أدوات بلايستيشن")},
        };
        for (String[] g : groups) {
            list.addView(section(g[1]));
            for (String[] l : LIBS) if (libGroup(l[0]).equals(g[0])) list.addView(libRow(l));
        }

        list.addView(section(t("M7L project libraries", "مكتبات مشاريع M7L")));
        LinearLayout mc = card();
        mc.addView(label(t("Fetched automatically when a project includes them (dr_mp3.h, stb_*.h, imgui.h, miniz.h …). Android libraries (ZXing, AndroidX) are downloaded by Gradle.",
            "تنزل تلقائياً لما المشروع يستخدمها (dr_mp3.h و stb_*.h و imgui.h و miniz.h …). مكتبات أندرويد (ZXing و AndroidX) ينزلها Gradle بنفسه."), 11.5f, SUB, false));
        LinearLayout mr = hrow();
        mr.setPadding(0, dp(10), 0, 0);
        TextView mAll = button("⬇ " + t("Install all", "تثبيت الكل"), GREEN, true);
        mAll.setOnClickListener(v -> runLibs("install", new String[]{"m7l-all"}, t("M7L libraries", "مكتبات M7L")));
        mr.addView(mAll, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView mDel = button("🗑 " + t("Remove all", "حذف الكل"), RED, false);
        mDel.setOnClickListener(v -> confirm(t("Remove all M7L libraries?", "حذف كل مكتبات M7L؟"), null,
            () -> runLibs("remove", new String[]{"m7l-all"}, t("M7L libraries", "مكتبات M7L"))));
        LinearLayout.LayoutParams mdl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        mdl.setMargins(dp(8), 0, 0, 0);
        mr.addView(mDel, mdl);
        mc.addView(mr);
        list.addView(mc);
        for (String[] l : M7L_LIBS) list.addView(libRow(l));
        TextView lic = label(t("Licenses: DOSBox and pcsx_rearmed = GPL v2, PCSX2 = GPL v3 (you must share the source when you publish). stb / dr_libs / miniz = public domain or MIT.",
            "الرخص: DOSBox و pcsx_rearmed = GPL v2، و PCSX2 = GPL v3 (لازم توفر السورس لما تنشر). stb و dr_libs و miniz = ملك عام أو MIT."), 11, AMBER, false);
        lic.setPadding(dp(6), dp(6), dp(6), dp(10));
        list.addView(lic);

        list.addView(section(t("Storage", "التخزين")));
        LinearLayout sc = card();
        TextView usage = label(t("Calculating…", "جاري الحساب…"), 12.5f, SUB, false);
        sc.addView(usage);
        LinearLayout sr = hrow();
        sr.setPadding(0, dp(10), 0, 0);
        TextView cc = button("🧹 " + t("Gradle cache", "كاش Gradle"), AMBER, false);
        cc.setOnClickListener(v -> confirm(t("Delete the Gradle cache?", "حذف كاش Gradle؟"), t("Next build downloads the libraries again.", "التجميع الجاي بينزّل المكتبات من جديد."),
            () -> runLibs("remove", new String[]{"gradlecache"}, t("Gradle cache", "كاش Gradle"))));
        sr.addView(cc, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cp = button("🗑 " + t("Build copies", "نسخ التجميع"), RED, false);
        cp.setOnClickListener(v -> confirm(t("Delete build copies?", "حذف نسخ التجميع؟"), t("Your original projects are not touched.", "مشاريعك الأصلية ما تنمس."),
            () -> runLibs("remove", new String[]{"projects"}, t("build copies", "نسخ التجميع"))));
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(0, -2, 1f);
        cpl.setMargins(dp(8), 0, 0, 0);
        sr.addView(cp, cpl);
        sc.addView(sr);
        TextView freeAll = button("🧨  " + t("Free all space now", "تفريغ كل المساحة الحين"), RED, false);
        freeAll.setOnClickListener(v -> confirm(t("Delete all tools, SDKs and libraries?", "حذف كل الأدوات والـ SDK والمكتبات؟"),
            t("Your built files in “Building source” are kept. Next build downloads everything again.", "ملفاتك الجاهزة في «Building source» ما تنحذف. التجميع الجاي بينزّل كل شيء من جديد."),
            () -> runLibs("remove", new String[]{"everything"}, t("all tools", "كل الأدوات"))));
        LinearLayout.LayoutParams all2 = new LinearLayout.LayoutParams(-1, -2);
        all2.topMargin = dp(8);
        sc.addView(freeAll, all2);
        list.addView(sc);
        new Thread(() -> {
            long tc = Env.du(in(TCD));
            long sdk = Env.du(in(SDK)), gr = Env.du(in("/root/.gradle")),
                pj = Env.du(in("/root/bsource-projects")), env = Env.du(Env.rootfs(this));
            ui.post(() -> usage.setText("Android SDK: " + human(sdk) + "\nGradle: " + human(gr) + "\n" + t("Build copies: ", "نسخ التجميع: ") + human(pj)
                + "\n" + t("PlayStation toolchains: ", "أدوات بلايستيشن: ") + human(tc)
                + "\n" + t("Ubuntu (everything): ", "Ubuntu (كل شيء): ") + human(env)));
        }).start();
        return scroll(list);
    }

    private View libRow(String[] l) {
        boolean ok = libInstalled(l[0]);
        LinearLayout r = card();
        LinearLayout h = hrow();
        TextView dot = label(ok ? "●" : "○", 16, ok ? GREEN : SUB, true);
        h.addView(dot, new LinearLayout.LayoutParams(dp(24), -2));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView n = label((ar ? l[2] : l[1]) + ("1".equals(l[6]) ? "  ★" : ""), 14, TEXT, true);
        tx.addView(n);
        tx.addView(label((ar ? l[4] : l[3]) + "  •  " + l[5], 11, SUB, false));
        h.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = chip(ok ? t("Remove", "حذف") : t("Install", "تثبيت"), ok ? RED : GREEN);
        b.setOnClickListener(v -> {
            if (ok) confirm(t("Remove ", "حذف ") + (ar ? l[2] : l[1]) + "?", null, () -> runLibs("remove", new String[]{l[0]}, ar ? l[2] : l[1]));
            else runLibs("install", new String[]{l[0]}, ar ? l[2] : l[1]);
        });
        h.addView(b);
        r.addView(h);
        return r;
    }

    private void runLibs(String action, String[] ids, String title) {
        if (BuildService.running()) { toast(t("Wait for the current work to finish", "انتظر لين يخلص الشغل الحالي")); showTab(0, true); return; }
        if (!bootstrapped()) { toast(t("This phone is not 64-bit ARM — the build tools need arm64", "هذا الجوال مو 64-bit ARM، أدوات التجميع تحتاج arm64")); return; }
        startSvc(new Intent(this, BuildService.class).setAction(BuildService.ACTION_LIBS)
            .putExtra(BuildService.EXTRA_LIB_ACTION, action).putExtra(BuildService.EXTRA_LIBS, ids).putExtra(BuildService.EXTRA_TITLE, title));
        toast(("install".equals(action) ? t("Installing ", "جاري تثبيت ") : t("Removing ", "جاري حذف ")) + title);
        ui.postDelayed(() -> showTab(0, true), 300);
    }


    // ------------------------------------------------------------------ SETTINGS tab

    private View settingsPage() {
        LinearLayout list = vlist();
        list.addView(header(t("Settings", "الإعدادات")));

        list.addView(section(t("General", "عام")));
        list.addView(choiceRow("🌐", t("Language", "اللغة"), new String[]{"English", "العربية"}, ar ? 1 : 0, i -> {
            prefs.edit().putString("lang", i == 1 ? "ar" : "en").apply();
            recreate();
        }));
        list.addView(switchRow("💡", t("Keep screen on while building", "إبقاء الشاشة شغالة أثناء التجميع"), "screen", true));
        list.addView(switchRow("🧹", t("Delete tools & libraries when I leave (saves space; the next build downloads them again)",
            "حذف الأدوات والمكتبات لما أطلع من الأداة (يوفر المساحة، والتجميع الجاي ينزّلها من جديد)"), "cleanup", true));
        list.addView(switchRow("🎮", t("…but keep PlayStation toolchains (PS1/PS2/PS3 take hours to rebuild)",
            "…بس خلّ أدوات بلايستيشن (PS1 و PS2 و PS3 ياخذون ساعات لين ينبنون)"), "keep_ps", false));

        list.addView(section(t("Build", "التجميع")));
        String type = prefs.getString("type", "debug");
        list.addView(choiceRow("🏷", t("Build type", "نوع التجميع"), new String[]{"Debug", "Release"}, "release".equals(type) ? 1 : 0,
            i -> prefs.edit().putString("type", i == 1 ? "release" : "debug").apply()));
        String[] jvs = {"auto", "8", "11", "17", "21"};
        String jv = prefs.getString("java", "auto");
        int ji0 = 0;
        for (int i = 0; i < jvs.length; i++) if (jvs[i].equals(jv)) ji0 = i;
        list.addView(choiceRow("☕", t("Java version", "نسخة الجافا"), new String[]{t("Auto", "تلقائي"), "8", "11", "17", "21"}, ji0,
            i -> prefs.edit().putString("java", jvs[i]).apply()));
        String[] gvs = {"auto", "4.10.3", "5.6.4", "6.9.4", "7.6.4", "8.13", "8.14.3", "9.2.1"};
        String gv = prefs.getString("gradle", "auto");
        int gi = 0;
        for (int i = 0; i < gvs.length; i++) if (gvs[i].equals(gv)) gi = i;
        list.addView(choiceRow("🐘", t("Gradle version (when the project has no gradlew)", "نسخة Gradle (لما المشروع ما فيه gradlew)"),
            new String[]{t("Auto", "تلقائي"), "4.10", "5.6", "6.9", "7.6", "8.13", "8.14", "9.2"}, gi, i -> prefs.edit().putString("gradle", gvs[i]).apply()));
        int[] mems = {1024, 1536, 2048, 3072, 4096};
        int mem = prefs.getInt("mem", 2048), mi = 2;
        for (int i = 0; i < mems.length; i++) if (mems[i] == mem) mi = i;
        list.addView(choiceRow("🧠", t("Gradle memory", "ذاكرة Gradle"), new String[]{"1 GB", "1.5 GB", "2 GB", "3 GB", "4 GB"}, mi,
            i -> prefs.edit().putInt("mem", mems[i]).apply()));
        int[] jobs = {0, 1, 2, 4, 8};
        int jb = prefs.getInt("jobs", 0), ji = 0;
        for (int i = 0; i < jobs.length; i++) if (jobs[i] == jb) ji = i;
        list.addView(choiceRow("⚙", t("Parallel jobs (C/C++, Rust, PS toolchains)", "المهام المتوازية (C/C++ و Rust وأدوات PS)"),
            new String[]{t("Auto", "تلقائي"), "1", "2", "4", "8"}, ji, i -> prefs.edit().putInt("jobs", jobs[i]).apply()));
        list.addView(switchRow("🧹", t("Clean before building", "تنظيف قبل التجميع"), "clean", false));
        list.addView(switchRow("📋", t("Build a private copy (faster, keeps your folder clean)", "تجميع نسخة خاصة (أسرع ويحافظ على مجلدك نظيف)"), "copy", true));
        list.addView(switchRow("✈", t("Offline mode (use downloaded libraries only)", "بدون إنترنت (يستخدم المكتبات المنزّلة بس)"), "offline", false));
        LinearLayout ac = card();
        LinearLayout ah = hrow();
        ah.addView(label("⌨", 18, TEXT, false), new LinearLayout.LayoutParams(dp(30), -2));
        LinearLayout atx = new LinearLayout(this);
        atx.setOrientation(LinearLayout.VERTICAL);
        atx.addView(label(t("Extra Gradle arguments", "أوامر Gradle إضافية"), 14, TEXT, true));
        String args = prefs.getString("args", "");
        atx.addView(label(args.isEmpty() ? t("none", "لا يوجد") : args, 11.5f, SUB, false));
        ah.addView(atx, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ed = chip(t("Edit", "تعديل"), CYAN);
        ed.setOnClickListener(v -> argsDialog());
        ah.addView(ed);
        ac.addView(ah);
        list.addView(ac);
        LinearLayout oc = card();
        LinearLayout oh = hrow();
        LinearLayout otx = new LinearLayout(this);
        otx.setOrientation(LinearLayout.VERTICAL);
        otx.addView(label("📂  " + t("Output folder", "مجلد الملفات الناتجة"), 14, TEXT, true));
        TextView op = label(BuildService.outDir().getAbsolutePath(), 11.5f, SUB, false);
        op.setPadding(0, dp(2), 0, 0);
        otx.addView(op);
        oh.addView(otx, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView oe = chip(t("Change", "تغيير"), CYAN);
        oe.setOnClickListener(v -> outDirDialog());
        oh.addView(oe);
        oc.addView(oh);
        list.addView(oc);

        list.addView(section(t("Permissions", "الصلاحيات")));
        LinearLayout pc = card();
        pc.addView(permRow("📁", t("Files & storage", "الملفات والتخزين"), t("read projects, save APKs", "قراءة المشاريع وحفظ التطبيقات"), storageOk(), this::askStorage));
        if (Build.VERSION.SDK_INT >= 30)
            pc.addView(permRow("🗂", t("All files access (optional)", "الوصول لكل الملفات (اختياري)"), t("for projects in protected folders", "للمشاريع في المجلدات المحمية"), allFilesOk(), this::askAllFiles));
        pc.addView(permRow("🔋", t("Run in background", "التشغيل في الخلفية"), t("long builds keep going", "التجميع الطويل يكمل"), batteryOk(), this::askBattery));
        pc.addView(permRow("🔔", t("Notifications", "الإشعارات"), t("progress and “app ready”", "التقدم و«التطبيق جاهز»"), false, this::askNotifications));
        boolean canInstall = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        pc.addView(permRow("📲", t("Install apps", "تثبيت التطبيقات"), t("install the APK you built", "تثبيت التطبيق اللي جمّعته"), canInstall, () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
        }));
        list.addView(pc);

        list.addView(section(t("Advanced", "متقدم")));
        LinearLayout xc = card();
        TextView rs = button("↺  " + t("Reset statistics", "تصفير الإحصائيات"), AMBER, false);
        rs.setOnClickListener(v -> confirm(t("Reset statistics?", "تصفير الإحصائيات؟"), null, () -> {
            prefs.edit().remove("builds_total").remove("builds_ok").remove("builds_fail").remove("best_ms").remove("last_ms").apply();
            toast("✔");
        }));
        xc.addView(rs, new LinearLayout.LayoutParams(-1, -2));
        list.addView(xc);
        return scroll(list);
    }

    interface Pick { void on(int i); }

    private View choiceRow(String icon, String name, String[] options, int sel, Pick pick) {
        LinearLayout c = card();
        LinearLayout h = hrow();
        h.addView(label(icon, 18, TEXT, false), new LinearLayout.LayoutParams(dp(30), -2));
        h.addView(label(name, 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(h);
        LinearLayout opts = hrow();
        opts.setPadding(0, dp(8), 0, 0);
        final List<TextView> all = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView o = label(options[i], 12.5f, i == sel ? BG : TEXT, true);
            o.setGravity(Gravity.CENTER);
            o.setPadding(dp(6), dp(8), dp(6), dp(8));
            o.setBackground(round(i == sel ? GREEN : CARD2, dp(12), i == sel ? GREEN : STROKE));
            o.setOnClickListener(v -> {
                for (int k = 0; k < all.size(); k++) {
                    all.get(k).setTextColor(k == idx ? BG : TEXT);
                    all.get(k).setBackground(round(k == idx ? GREEN : CARD2, dp(12), k == idx ? GREEN : STROKE));
                }
                pick.on(idx);
            });
            press(o);
            all.add(o);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
            l.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            opts.addView(o, l);
        }
        c.addView(opts);
        return c;
    }

    private View switchRow(String icon, String name, String key, boolean def) {
        LinearLayout c = card();
        LinearLayout h = hrow();
        h.addView(label(icon, 18, TEXT, false), new LinearLayout.LayoutParams(dp(30), -2));
        h.addView(label(name, 13.5f, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Toggle sw = new Toggle(this, prefs.getBoolean(key, def));
        sw.cb = on -> prefs.edit().putBoolean(key, on).apply();
        h.addView(sw, new LinearLayout.LayoutParams(dp(50), dp(28)));
        c.addView(h);
        c.setOnClickListener(v -> sw.toggle());
        return c;
    }

    private void argsDialog() {
        EditText et = new EditText(this);
        et.setText(prefs.getString("args", ""));
        et.setHint("-x lint  --info");
        et.setTextColor(TEXT);
        et.setHintTextColor(SUB);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(et);
        dlg().setTitle(t("Extra Gradle arguments", "أوامر Gradle إضافية")).setView(f)
            .setPositiveButton(t("Save", "حفظ"), (d, w) -> { prefs.edit().putString("args", et.getText().toString().trim()).apply(); showTab(2, false); })
            .setNegativeButton(t("Cancel", "إلغاء"), null).show();
    }

    // ------------------------------------------------------------------ ABOUT tab

    private View aboutPage() {
        LinearLayout list = vlist();
        LinearLayout hero = card();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(16), dp(24), dp(16), dp(20));
        FrameLayout logo = new FrameLayout(this);
        Ring halo = new Ring(this);
        halo.set(100, GREEN, true);
        logo.addView(halo, new FrameLayout.LayoutParams(dp(150), dp(150)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_bs_fg);
        FrameLayout.LayoutParams ivl = new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER);
        logo.addView(iv, ivl);
        hero.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView name = new Shimmer(this, "Building source", 26);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        hero.addView(name);
        String ver = "";
        try { PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0); ver = pi.versionName; } catch (Exception ignored) {}
        TextView v = label(t("Version ", "الإصدار ") + ver, 12, SUB, false);
        v.setGravity(Gravity.CENTER);
        hero.addView(v);
        TextView d = label(t("Build Android Studio projects, PlayStation homebrew (PS1–PS5) and C/C++ — right on your phone.", "جمّع مشاريع أندرويد استوديو وهوم برو بلايستيشن (PS1 إلى PS5) و C/C++ من جوالك مباشرة."), 13, TEXT, false);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(10), 0, 0);
        hero.addView(d);
        list.addView(hero);

        list.addView(section(t("Developer", "المطور")));
        LinearLayout dev = card();
        LinearLayout dh = hrow();
        TextView badge = label("M7L", 18, BG, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{GREEN, CYAN, PURPLE});
        g.setCornerRadius(dp(14));
        badge.setBackground(g);
        dh.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout dtx = new LinearLayout(this);
        dtx.setOrientation(LinearLayout.VERTICAL);
        dtx.setPadding(dp(12), 0, 0, 0);
        dtx.addView(label("M7L TEAM", 17, TEXT, true));
        dtx.addView(label(t("Developed and designed by M7L TEAM", "تطوير وتصميم M7L TEAM"), 12, SUB, false));
        dh.addView(dtx, new LinearLayout.LayoutParams(0, -2, 1f));
        dev.addView(dh);
        list.addView(dev);

        list.addView(section(t("My channels & accounts", "قنواتي وحساباتي")));
        for (String[] s : SOCIAL) list.addView(socialRow(s));

        list.addView(section(t("Rights", "الحقوق")));
        LinearLayout rc = card();
        rc.addView(label("© 2026 M7L TEAM — Building source", 14, TEXT, true));
        rc.addView(label(t("Name, design and icon by M7L TEAM. All rights reserved.", "الاسم والتصميم والأيقونة من M7L TEAM. جميع الحقوق محفوظة."), 12, SUB, false));
        TextView lic = label(t("Uses PRoot (GPLv2) and Ubuntu. Android, Gradle, OpenJDK, the Android SDK and the PlayStation SDKs belong to their owners.",
            "يستخدم PRoot (GPLv2) و Ubuntu. وأندرويد و Gradle و OpenJDK و Android SDK وأدوات بلايستيشن ملك لأصحابها."), 11, SUB, false);
        lic.setPadding(0, dp(8), 0, 0);
        rc.addView(lic);
        list.addView(rc);
        return scroll(list);
    }

    private View socialRow(String[] s) {
        int c = Color.parseColor(s[2]);
        LinearLayout r = hrow();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(CARD, dp(14), STROKE));
        TextView g = label(s[3], 18, Color.WHITE, true);
        g.setGravity(Gravity.CENTER);
        g.setBackground(round(c, dp(12), c));
        r.addView(g, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, 0, 0);
        tx.addView(label(s[0], 14.5f, TEXT, true));
        TextView u = label(s[1].replace("https://", "").replace("www.", ""), 11, SUB, false);
        u.setSingleLine(true);
        u.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(u);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(label("↗", 18, SUB, true));
        r.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s[1]))); } catch (Exception e) { toast(t("No app can open this link", "ما في تطبيق يفتح الرابط")); }
        });
        press(r);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(l);
        return r;
    }

    // ------------------------------------------------------------------ welcome

    private void welcome() {
        if (isFinishing()) return;
        dlg().setTitle(t("Welcome to Building source", "أهلاً بك في Building source"))
            .setMessage(t("1. Allow storage and background running.\n2. Choose your project folder or .zip and press BUILD.\n\nThe first build sets up a small Ubuntu inside the app and downloads only what your project needs (needs internet).\nResults are saved in “Building source/APK”.",
                "1. اسمح بالتخزين والتشغيل في الخلفية.\n2. اختر مجلد مشروعك أو ملف zip واضغط جمّع.\n\nأول تجميع يجهّز Ubuntu صغير داخل التطبيق، وينزّل بس اللي يحتاجه مشروعك (يحتاج إنترنت).\nالملفات الناتجة تنحفظ في «Building source/APK»."))
            .setPositiveButton(t("Start", "ابدأ"), (d, w) -> { if (!storageOk()) askStorage(); })
            .show();
    }

    // ------------------------------------------------------------------ effects

    /** Animated progress ring: gradient arc, glow, running comet, celebration burst. */
    final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float shown, target, spin, burst;
        private int color = SUB;
        private boolean running;
        private final ValueAnimator loop = ValueAnimator.ofFloat(0, 360);

        Ring(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(Color.parseColor("#132520"));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(TEXT);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            small.setColor(SUB);
            small.setTextAlign(Paint.Align.CENTER);
            loop.setDuration(2400);
            loop.setRepeatCount(ValueAnimator.INFINITE);
            loop.setInterpolator(new LinearInterpolator());
            loop.addUpdateListener(a -> {
                spin = (float) a.getAnimatedValue();
                shown += (target - shown) * 0.08f;
                if (burst > 0) burst = Math.max(0, burst - 0.012f);
                if (!running && burst == 0 && Math.abs(target - shown) < 0.3f) { shown = target; loop.cancel(); }
                invalidate();
            });
        }

        /** The animation runs only while something moves (building, counting up, celebrating) — idle costs nothing. */
        private void wake() { if (isAttachedToWindow() && !loop.isRunning()) loop.start(); }

        void set(int pct, int col, boolean run) {
            boolean change = pct != target || col != color || run != running;
            target = pct; color = col; running = run;
            if (change) { wake(); invalidate(); }
        }

        void pulse() {
            animate().scaleX(1.07f).scaleY(1.07f).setDuration(140).withEndAction(() ->
                animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(3f)).start()).start();
        }

        void celebrate() { burst = 1f; pulse(); wake(); }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (running || burst > 0 || Math.abs(target - shown) > 0.3f) loop.start(); }

        @Override protected void onDetachedFromWindow() { loop.cancel(); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sw = Math.min(w, h) * 0.075f, pad = sw * 1.6f;
            box.set(pad, pad, w - pad, h - pad);
            float cx = w / 2, cy = h / 2;
            track.setStrokeWidth(sw);
            c.drawArc(box, 0, 360, false, track);
            SweepGradient sg = new SweepGradient(cx, cy, new int[]{GREEN, CYAN, PURPLE, AMBER, GREEN}, null);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(running ? spin - 90 : -90, cx, cy);
            sg.setLocalMatrix(m);
            float sweep = 360f * Math.max(0, Math.min(100, shown)) / 100f;
            glow.setShader(sg);
            glow.setStrokeWidth(sw * 1.9f);
            glow.setAlpha(running ? 70 + (int) (40 * Math.sin(Math.toRadians(spin * 2))) : 60);
            if (sweep > 0) c.drawArc(box, -90, sweep, false, glow);
            arc.setShader(sg);
            arc.setStrokeWidth(sw);
            if (sweep > 0) c.drawArc(box, -90, sweep, false, arc);
            if (running) {
                double a = Math.toRadians(spin - 90);
                float r = box.width() / 2;
                float dx = cx + (float) (r * Math.cos(a)), dy = cy + (float) (r * Math.sin(a));
                dot.setColor(CYAN); dot.setAlpha(90);
                c.drawCircle(dx, dy, sw * 0.9f, dot);
                dot.setColor(Color.WHITE); dot.setAlpha(255);
                c.drawCircle(dx, dy, sw * 0.42f, dot);
            }
            if (burst > 0) {
                for (int i = 0; i < 18; i++) {
                    double a = Math.toRadians(i * 20 + spin * 0.3);
                    float r = box.width() / 2 + (1 - burst) * sw * 6;
                    dot.setColor(i % 3 == 0 ? AMBER : i % 3 == 1 ? GREEN : CYAN);
                    dot.setAlpha((int) (255 * burst));
                    c.drawCircle(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), sw * 0.35f, dot);
                }
                dot.setAlpha(255);
            }
            txt.setTextSize(Math.min(w, h) * (Math.min(w, h) >= dp(110) ? 0.2f : 0.24f));
            txt.setShadowLayer(sw, 0, 0, color);
            c.drawText(Math.round(shown) + "%", cx, cy + txt.getTextSize() * 0.32f, txt);
            if (Math.min(w, h) >= dp(110)) {
                small.setTextSize(Math.min(w, h) * 0.085f);
                c.drawText("PROGRESS", cx, cy + txt.getTextSize() * 0.95f, small);
            }
        }
    }

    /** Title with a moving colour shine. */
    final class Shimmer extends TextView {
        private float off;
        private final ValueAnimator a = ValueAnimator.ofFloat(0, 1);

        Shimmer(Context c, String s, float size) {
            super(c);
            setText(s);
            setTextSize(size);
            setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            a.setDuration(3200);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(x -> { off = (float) x.getAnimatedValue(); invalidate(); });
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); a.start(); }

        @Override protected void onDetachedFromWindow() { a.cancel(); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float w = Math.max(1, getWidth());
            float x = (off * 2 - 0.5f) * w;
            getPaint().setShader(new LinearGradient(x - w * 0.6f, 0, x + w * 0.6f, 0,
                new int[]{GREEN, CYAN, Color.WHITE, PURPLE, GREEN}, null, Shader.TileMode.MIRROR));
            getPaint().setShadowLayer(dp(8), 0, 0, Color.argb(120, 0, 255, 156));
            super.onDraw(c);
        }
    }

    /** Small animated on/off switch. */
    interface ToggleCb { void on(boolean on); }

    final class Toggle extends View {
        ToggleCb cb;
        private boolean on;
        private float pos;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c, boolean v) { super(c); on = v; pos = v ? 1 : 0; setOnClickListener(x -> toggle()); }

        void toggle() {
            on = !on;
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1 : 0);
            a.setDuration(180);
            a.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
            if (cb != null) cb.on(on);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(blend(Color.parseColor("#1A2B26"), Color.parseColor("#0E6B49"), pos));
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            p.setColor(blend(SUB, GREEN, pos));
            p.setShadowLayer(on ? r * 0.6f : 0, 0, 0, GREEN);
            c.drawCircle(r + (w - 2 * r) * pos, r, r * 0.72f, p);
            p.clearShadowLayer();
        }
    }

    private static int blend(int a, int b, float f) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
            (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
            (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    /** Press animation for anything tappable. */
    @SuppressLint("ClickableViewAccessibility")
    private void press(View v) {
        v.setOnTouchListener((x, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                x.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new OvershootInterpolator(2.5f)).start();
            return false;
        });
    }

    // ------------------------------------------------------------------ UI helpers

    private View header(String sub) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(4), dp(10), dp(4), dp(10));
        LinearLayout row = hrow();
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_bs_fg);
        row.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(8), 0, 0, 0);
        tx.addView(new Shimmer(this, "Building source", 22));
        tx.addView(label(sub, 12, SUB, false));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = label("M7L TEAM", 11, BG, true);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{GREEN, CYAN});
        g.setCornerRadius(dp(8));
        badge.setBackground(g);
        row.addView(badge);
        h.addView(row);
        return h;
    }

    private TextView section(String s) {
        TextView v = label("▍" + s, 13, GREEN, true);
        v.setPadding(dp(4), dp(14), dp(4), dp(4));
        return v;
    }

    private LinearLayout vlist() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(8), dp(12), dp(20));
        return l;
    }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(Color.argb(225, 10, 22, 19), dp(16), STROKE));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, dp(5), 0, dp(5));
        c.setLayoutParams(l);
        return c;
    }

    /** A ScrollView that never jumps on its own (focus changes, text updates) — only the finger moves it. */
    private ScrollView steadyScroll() {
        return new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
        };
    }

    private ScrollView scroll(View v) {
        ScrollView s = steadyScroll();
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setVerticalScrollBarEnabled(false);
        s.setFillViewport(true);
        s.addView(v);
        return s;
    }

    private TextView button(String s, int color, boolean filled) {
        TextView b = label(s, 14, filled ? BG : color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (filled) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color, blend(color, CYAN, 0.55f)});
            g.setCornerRadius(dp(14));
            b.setBackground(g);
        } else {
            b.setBackground(round(CARD2, dp(14), color));
        }
        press(b);
        return b;
    }

    private TextView chip(String s, int color) {
        TextView v = label(s, 12, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setBackground(round(CARD2, dp(14), color));
        press(v);
        return v;
    }

    private TextView label(String s, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Icons.apply(v, s);
        return v;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Fx.Dlg dlg() { return new Fx.Dlg(this); }

    private void confirm(String title, String msg, Runnable yes) {
        AlertDialog.Builder b = dlg().setTitle(title);
        if (msg != null) b.setMessage(msg);
        b.setPositiveButton(t("Yes", "نعم"), (d, w) -> yes.run()).setNegativeButton(t("Cancel", "إلغاء"), null).show();
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
