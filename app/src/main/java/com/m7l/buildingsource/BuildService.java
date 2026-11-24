package com.m7l.buildingsource;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Building source — runs a build (or a library install) in the background as a foreground service:
 * live notification with progress, elapsed time and a Stop button; keeps the CPU awake; survives leaving the app.
 */
public class BuildService extends Service {

    public static final String ACTION_BUILD = "bs.BUILD", ACTION_LIBS = "bs.LIBS", ACTION_STOP = "bs.STOP";
    public static final String EXTRA_SRC = "src", EXTRA_NAME = "name", EXTRA_LIB_ACTION = "libAction", EXTRA_LIBS = "libs", EXTRA_TITLE = "title";
    private static final String CHANNEL = "bs_build", CHANNEL_DONE = "bs_done";
    private static final int NOTE_ID = 7301, NOTE_DONE = 7302;
    public static final String PREFS = "bs_prefs";

    // ---- shared state (read by the screen)
    public static volatile String state = "idle";   // idle | running | ok | fail | stopped
    public static volatile String kind = "";        // build | libs
    public static volatile String title = "", stage = "", error = "", target = "";
    public static volatile int percent, tasks;
    public static volatile long startMs, endMs;
    public static final List<String> apks = new ArrayList<>();
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final int LOG_MAX = 1500;
    public static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean notifyQueued;

    private PowerManager.WakeLock wake;
    private volatile Process proc;
    private volatile boolean stopRequested;
    private Thread worker;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!"running".equals(state)) return;
            updateNotification();
            MAIN.postDelayed(this, 1000);
        }
    };

    public static boolean running() { return "running".equals(state); }

    public static synchronized List<String> logLines() { return new ArrayList<>(LOG); }

    private static synchronized void log(String s) {
        LOG.add(s);
        while (LOG.size() > LOG_MAX) LOG.pollFirst();
    }

    private static synchronized void clearLog() { LOG.clear(); }

    /** Coalesces updates to ~6 per second. */
    static void changed() {
        if (notifyQueued) return;
        notifyQueued = true;
        MAIN.postDelayed(() -> {
            notifyQueued = false;
            for (Runnable r : listeners) { try { r.run(); } catch (Exception ignored) {} }
        }, 160);
    }

    /** Where the app keeps its own files (imported .zip projects …). */
    public static File baseDir() { return new File(Environment.getExternalStorageDirectory(), "Building source"); }
    /** Output folder: chosen in Settings, else "Building source/APK". */
    static volatile String outPath = "";
    public static File outDir() { return outPath != null && !outPath.isEmpty() ? new File(outPath) : new File(baseDir(), "APK"); }
    static volatile String lastSrc;

    private static File filesDir;
    public static File logFile() { return new File(filesDir, "last-build.log"); }

    // ------------------------------------------------------------------ service

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i != null ? i.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            stopRequested = true;
            killAll();
            return START_NOT_STICKY;
        }
        if (running()) return START_NOT_STICKY;
        outPath = getSharedPreferences(PREFS, MODE_PRIVATE).getString("out_dir", "");
        if (ACTION_BUILD.equals(a) || ACTION_LIBS.equals(a)) {
            startForegroundNow();
            final Intent in = i;
            worker = new Thread(() -> work(in), "bs-build");
            worker.start();
        } else if (!running()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(ticker);
        releaseWake();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ work

    private void work(Intent in) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean build = ACTION_BUILD.equals(in.getAction());
        String name = in.getStringExtra(EXTRA_NAME);
        synchronized (BuildService.class) {
            kind = build ? "build" : "libs";
            title = build ? name : in.getStringExtra(EXTRA_TITLE);
            stage = "";
            error = "";
            percent = 0;
            tasks = 0;
            startMs = System.currentTimeMillis();
            endMs = 0;
            apks.clear();
            target = "";
            clearLog();
            state = "running";
        }
        stopRequested = false;
        acquireWake();
        MAIN.post(ticker);
        changed();

        int expected = build ? p.getInt("tasks_" + name, 0) : 0;
        boolean okMarker = false;
        FileWriter lf = null;
        filesDir = getFilesDir();
        Env.Progress prog = new Env.Progress() {
            @Override public void stage(int pct, String text) { percent = pct; stage = text; changed(); }
            @Override public void log(String line) { BuildService.log(line); changed(); }
            @Override public boolean cancelled() { return stopRequested; }
        };
        try {
            lf = new FileWriter(logFile());
            String libAction = in.getStringExtra(EXTRA_LIB_ACTION);
            String[] libs = in.getStringArrayExtra(EXTRA_LIBS);

            // free all space: delete the whole Linux environment (built files in “Building source” stay)
            if (!build && "remove".equals(libAction) && libs != null && libs.length == 1 && "everything".equals(libs[0])) {
                freeSpace(p, prog);
                finish(stopRequested ? "stopped" : "ok", p, name, false);
                return;
            }

            // first use (or after clean-up): download and prepare Ubuntu
            if (!Env.ready(this)) {
                log("━━ Preparing the Linux environment (first time)");
                Env.setup(this, prog);
                percent = 0;
            }
            Env.prepareProot(this);

            File dir = Env.inside(this, "/opt/bsource");
            if (!dir.isDirectory()) dir.mkdirs();
            for (String f : new String[]{"common.sh", "build.sh", "libs.sh", "ps.sh", "langs.sh"}) copyAsset("bsource/" + f, new File(dir, f));
            Env.inside(this, "/root/.bsource").mkdirs();

            Map<String, String> env = new HashMap<>();
            List<String> cmd = new ArrayList<>();
            cmd.add("/bin/bash");
            if (build) {
                cmd.add("/opt/bsource/build.sh");
                lastSrc = in.getStringExtra(EXTRA_SRC);
                env.put("BS_SRC", toInside(lastSrc));
                env.put("BS_NAME", name);
                env.put("BS_TYPE", p.getString("type", "debug"));
                env.put("BS_CLEAN", p.getBoolean("clean", false) ? "1" : "0");
                env.put("BS_COPY", p.getBoolean("copy", true) ? "1" : "0");
                env.put("BS_MEM", String.valueOf(p.getInt("mem", 2048)));
                env.put("BS_JAVA", p.getString("java", "auto"));
                env.put("BS_GRADLE", p.getString("gradle", "auto"));
                env.put("BS_TARGET", p.getString("target", "auto"));
                env.put("BS_OFFLINE", p.getBoolean("offline", false) ? "1" : "0");
                env.put("BS_ARGS", p.getString("args", ""));
                env.put("BS_JOBS", String.valueOf(p.getInt("jobs", 0)));
                env.put("BS_OUT", outDir().getAbsolutePath());
            } else {
                cmd.add("/opt/bsource/libs.sh");
                cmd.add(libAction);
                if (libs != null) for (String l : libs) cmd.add(l);
            }
            env.put("BS_PIDFILE", "/root/.bsource/pid");
            env.put("BS_KEEP_PS", p.getBoolean("keep_ps", false) ? "1" : "0");

            proc = Env.builder(this, cmd, env).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String ln;
            while ((ln = r.readLine()) != null) {
                lf.write(ln);
                lf.write('\n');
                if (ln.startsWith("@@STAGE ")) {
                    String rest = ln.substring(8);
                    int sp = rest.indexOf(' ');
                    try { percent = Math.max(percent, Integer.parseInt(sp > 0 ? rest.substring(0, sp) : rest)); } catch (Exception ignored) {}
                    stage = sp > 0 ? rest.substring(sp + 1) : "";
                    log("━━ " + stage);
                } else if (ln.startsWith("@@APK ")) {
                    synchronized (BuildService.class) { apks.add(ln.substring(6).trim()); }
                    log("📦 " + ln.substring(6).trim());
                } else if (ln.startsWith("@@ERROR ")) {
                    error = ln.substring(8);
                    log("✖ " + error);
                } else if (ln.startsWith("@@OK ")) {
                    okMarker = true;
                    log("✔ " + ln.substring(5));
                } else if (ln.startsWith("@@TARGET ")) {
                    target = ln.substring(9).trim();
                } else if (ln.startsWith("@@GRADLE_EXIT ")) {
                    // shown through @@ERROR / @@OK
                } else {
                    if (ln.startsWith("> Task ")) {
                        tasks++;
                        if (build && percent >= 20 && percent < 98) {
                            double f = expected > 0 ? Math.min(1.0, tasks / (double) expected) : 1 - Math.exp(-tasks / 140.0);
                            percent = Math.max(percent, Math.min(97, 20 + (int) (77 * f)));
                        }
                        stage = ln.substring(7).trim();
                    }
                    log(ln);
                }
                changed();
            }
            int code = proc.waitFor();
            if (code != 0 && !stopRequested && error == null) {
                if (code >= 128 || code == 137 || code == 9) {
                    error = "The build was killed by Android (signal " + (code >= 128 ? code - 128 : code) + "): too many processes or not enough RAM. "
                          + "Lower the parallel jobs in Settings, or turn on Developer options → \"Disable child process restrictions\".";
                } else {
                    error = "The build stopped (exit code " + code + ") — see the log above";
                }
                log("✖ " + error);
            }
            boolean ok = code == 0 && (okMarker || !build);
            if (build && !stopRequested && p.getBoolean("cleanup_pending", false)) {
                p.edit().remove("cleanup_pending").apply();
                log("━━ Freeing space (you left the tool)");
                freeSpace(p, prog);
            }
            finish(stopRequested ? "stopped" : ok ? "ok" : "fail", p, name, build);
            if (build && ok && tasks > 0) p.edit().putInt("tasks_" + name, tasks).apply();
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            log("✖ " + error);
            finish(stopRequested ? "stopped" : "fail", p, name, build);
        } finally {
            if (lf != null) { try { lf.close(); } catch (Exception ignored) {} }
            proc = null;
        }
    }

    /** Deletes the Linux environment, or (when PlayStation toolchains are kept) everything else in it. */
    private void freeSpace(SharedPreferences p, Env.Progress prog) throws Exception {
        stage = "Freeing space";
        changed();
        if (p.getBoolean("keep_ps", false) && Env.ready(this)) {
            File dir = Env.inside(this, "/opt/bsource");
            for (String f : new String[]{"common.sh", "build.sh", "libs.sh", "ps.sh", "langs.sh"}) copyAsset("bsource/" + f, new File(dir, f));
            Map<String, String> env = new HashMap<>();
            env.put("BS_PIDFILE", "/root/.bsource/pid");
            List<String> cmd = new ArrayList<>();
            cmd.add("/bin/bash"); cmd.add("/opt/bsource/libs.sh"); cmd.add("remove"); cmd.add("caches");
            Env.run(this, cmd, env, prog);
        } else {
            long before = Env.du(Env.rootfs(this));
            Env.deleteTree(Env.rootfs(this));
            Env.deleteTree(Env.tmp(this));
            log("✔ freed " + (before >> 20) + " MB");
        }
    }

    /** Paths the app keeps in its private folder → the same file as seen inside Ubuntu. */
    private String toInside(String path) {
        if (path == null) return "";
        String rf = Env.rootfs(this).getAbsolutePath();
        return path.startsWith(rf) ? path.substring(rf.length()) : path;
    }

    private void finish(String st, SharedPreferences p, String name, boolean build) {
        endMs = System.currentTimeMillis();
        long took = endMs - startMs;
        if ("ok".equals(st)) percent = 100;
        state = st;
        if ("stopped".equals(st)) { error = ""; log("■ stopped"); }
        if (build) {
            SharedPreferences.Editor e = p.edit();
            e.putInt("builds_total", p.getInt("builds_total", 0) + 1);
            if ("ok".equals(st)) {
                e.putInt("builds_ok", p.getInt("builds_ok", 0) + 1);
                long best = p.getLong("best_ms", 0);
                if (best == 0 || took < best) e.putLong("best_ms", took);
                e.putLong("last_ms", took);
            } else if ("fail".equals(st)) {
                e.putInt("builds_fail", p.getInt("builds_fail", 0) + 1);
            }
            // the finished project is not kept: it leaves the screen, no history, and an imported .zip copy is deleted
            if (!"stopped".equals(st)) {
                e.remove("proj_path").remove("proj_name").remove("recent");
                try {
                    File z = lastSrc == null ? null : new File(lastSrc);
                    if (z != null && z.isFile() && z.getParentFile() != null && z.getParentFile().equals(new File(baseDir(), "zips"))) z.delete();
                } catch (Exception ignored) {}
            }
            e.apply();
        }
        MAIN.post(() -> {
            MAIN.removeCallbacks(ticker);
            doneNotification(st, took);
            releaseWake();
            stopForegroundCompat();
            stopSelf();
            changed();
        });
    }

    // ------------------------------------------------------------------ stop: kill the script and every process it started

    private void killAll() {
        try {
            File pf = Env.inside(this, "/root/.bsource/pid");
            int root = -1;
            if (pf.exists()) {
                InputStream in = new java.io.FileInputStream(pf);
                byte[] b = new byte[32];
                int n = in.read(b);
                in.close();
                if (n > 0) root = Integer.parseInt(new String(b, 0, n).trim());
            }
            List<Integer> tree = new ArrayList<>();
            if (root > 0) collect(root, tree);
            for (int i = tree.size() - 1; i >= 0; i--) { try { android.os.Process.sendSignal(tree.get(i), 9); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
        Process pr = proc;
        if (pr != null) { try { pr.destroy(); } catch (Exception ignored) {} }
    }

    /** pid and all its descendants, read from /proc. */
    private static void collect(int pid, List<Integer> out) {
        out.add(pid);
        File[] all = new File("/proc").listFiles();
        if (all == null) return;
        for (File f : all) {
            String n = f.getName();
            if (!n.matches("\\d+")) continue;
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(f, "stat")));
                String s = r.readLine();
                r.close();
                int close = s.lastIndexOf(')');
                String[] parts = s.substring(close + 2).split(" ");
                if (Integer.parseInt(parts[1]) == pid) collect(Integer.parseInt(n), out);
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ notifications

    private NotificationManager nm() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = nm();
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building", NotificationManager.IMPORTANCE_LOW));
        if (nm.getNotificationChannel(CHANNEL_DONE) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Build finished", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private int piFlags() { return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0); }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i, piFlags());
    }

    private Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    private Notification progressNote() {
        boolean ar = MainActivity.isArabic(this);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, BuildService.class).setAction(ACTION_STOP), piFlags());
        String head = ("build".equals(kind) ? (ar ? "تجميع " : "Building ") : (ar ? "تثبيت " : "Installing ")) + (title == null ? "" : title);
        return builder(CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(head + "  •  " + percent + "%")
            .setContentText("⏱ " + MainActivity.clock(System.currentTimeMillis() - startMs) + "   ⚙ " + tasks + "   " + (stage == null ? "" : stage))
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(new Notification.Action.Builder(0, ar ? "■ إيقاف" : "■ Stop", stop).build())
            .build();
    }

    private void startForegroundNow() {
        channels();
        startForeground(NOTE_ID, progressNote());
    }

    private void updateNotification() {
        try { nm().notify(NOTE_ID, progressNote()); } catch (Exception ignored) {}
    }

    private void doneNotification(String st, long took) {
        boolean ar = MainActivity.isArabic(this);
        String t;
        if ("ok".equals(st)) t = "build".equals(kind) ? (ar ? "✓ التطبيق جاهز" : "✓ App ready") : (ar ? "✓ تم التثبيت" : "✓ Installed");
        else if ("stopped".equals(st)) t = ar ? "■ تم الإيقاف" : "■ Stopped";
        else t = ar ? "✗ فشل" : "✗ Failed";
        String body = (title == null ? "" : title) + "  •  ⏱ " + MainActivity.clock(took)
            + ("ok".equals(st) && !apks.isEmpty() ? "  •  " + apks.size() + " APK" : "")
            + ("fail".equals(st) && error != null && !error.isEmpty() ? "\n" + error : "");
        try {
            nm().cancel(NOTE_ID);
            nm().notify(NOTE_DONE, builder(CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(t)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build());
        } catch (Exception ignored) {}
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BuildingSource:build");
            wake.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        wake = null;
    }

    private void copyAsset(String name, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = getAssets().open(name); OutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        to.setExecutable(true, true);
    }
}
