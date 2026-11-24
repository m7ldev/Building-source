package com.m7l.buildingsource;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The app's own Linux: Ubuntu 24.04 (ARM64) run with proot — no root, no Termux, own package name.
 *  files/ubuntu      the Ubuntu root file system
 *  files/tmp         proot's scratch folder
 *  files/lib         libtalloc.so.2 → the copy shipped in the APK
 *  files/fakeproc    stand-ins for /proc files Android hides from apps
 */
final class Env {

    static final String[] ROOTFS_URLS = {
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
    };
    static final String PATH_IN = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bsource/bin";

    interface Progress {
        void stage(int pct, String text);
        void log(String line);
        boolean cancelled();
    }

    private Env() {}

    static File base(Context c) { return c.getFilesDir(); }
    static File rootfs(Context c) { return new File(base(c), "ubuntu"); }
    static File tmp(Context c) { return new File(base(c), "tmp"); }
    static File inside(Context c, String path) { return new File(rootfs(c), path.startsWith("/") ? path.substring(1) : path); }
    static boolean ready(Context c) { return new File(rootfs(c), ".bs-ready").exists(); }

    static boolean supported() { return Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"); }

    static File proot(Context c) { return new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so"); }

    // ------------------------------------------------------------------ first-time setup

    static void setup(Context c, Progress p) throws Exception {
        if (ready(c)) return;
        if (!supported()) throw new Exception("This phone is not 64-bit ARM (arm64-v8a); the build tools need it.");
        if (!proot(c).exists()) throw new Exception("proot is missing from the app (rebuild the APK with build-app.bat).");
        File base = base(c), rf = rootfs(c);
        prepareProot(c);

        // 1. download Ubuntu
        File tgz = new File(base, "ubuntu-rootfs.tar.gz");
        Exception last = null;
        boolean ok = tgz.exists() && tgz.length() > 20_000_000;
        for (int i = 0; !ok && i < ROOTFS_URLS.length; i++) {
            try {
                p.log("› downloading Ubuntu 24.04 (ARM64): " + ROOTFS_URLS[i]);
                download(ROOTFS_URLS[i], tgz, p, 1, 20);
                ok = true;
            } catch (Exception e) { last = e; p.log("! " + e.getMessage()); }
        }
        if (!ok) throw new Exception("Could not download Ubuntu: " + (last != null ? last.getMessage() : "?"));

        // 2. unpack
        p.stage(21, "Unpacking Ubuntu");
        if (rf.exists()) deleteTree(rf);
        rf.mkdirs();
        untar(tgz, rf, p);
        tgz.delete();

        // 3. make it work under proot on Android
        configure(c);

        // 4. basic tools
        p.stage(35, "Installing basic tools");
        int code = run(c, Arrays.asList("/bin/bash", "-c",
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y ca-certificates curl wget unzip zip xz-utils tar git procps"),
            null, p);
        if (code != 0) throw new Exception("Ubuntu was unpacked but installing the basic tools failed (exit " + code + "). Check the internet.");
        new File(rf, ".bs-ready").createNewFile();
        p.log("✔ Linux environment ready");
    }

    /** libtalloc.so.2 must sit next to the name proot asks for. */
    static void prepareProot(Context c) throws Exception {
        File lib = new File(base(c), "lib");
        lib.mkdirs();
        File link = new File(lib, "libtalloc.so.2");
        File target = new File(c.getApplicationInfo().nativeLibraryDir, "libtalloc.so");
        if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete();
        try { Os.symlink(target.getAbsolutePath(), link.getAbsolutePath()); }
        catch (Exception e) { copy(target, link); }
        tmp(c).mkdirs();
    }

    private static void configure(Context c) throws IOException {
        File rf = rootfs(c);
        for (String d : new String[]{"sdcard", "storage", "system", "apex", "vendor", "linkerconfig", "tmp", "dev", "proc", "sys",
            "root/.bsource", "opt/bsource/bin", "etc/apt/apt.conf.d"}) new File(rf, d).mkdirs();
        try { Os.chmod(new File(rf, "tmp").getAbsolutePath(), 01777); } catch (Exception ignored) {}
        File resolv = new File(rf, "etc/resolv.conf");
        resolv.delete();
        write(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        write(new File(rf, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n");
        write(new File(rf, "etc/apt/apt.conf.d/99bsource"),
            "APT::Sandbox::User \"root\";\nAPT::Install-Recommends \"false\";\nAcquire::Retries \"3\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-confdef\"; };\n");
        File fake = new File(base(c), "fakeproc");
        fake.mkdirs();
        write(new File(fake, "loadavg"), "0.12 0.07 0.02 2/165 765\n");
        write(new File(fake, "uptime"), "124.08 932.80\n");
        write(new File(fake, "version"), "Linux version 6.1.0-bsource (proot) #1 SMP PREEMPT\n");
        write(new File(fake, "vmstat"), "nr_free_pages 1000000\nnr_inactive_anon 0\nnr_active_anon 0\n");
        StringBuilder st = new StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n");
        int n = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < n; i++) st.append("cpu").append(i).append(" 1957 0 2877 93280 262 342 254 87 0 0\n");
        st.append("intr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n");
        write(new File(fake, "stat"), st.toString());
    }

    // ------------------------------------------------------------------ running commands inside Ubuntu

    /** proot command line around the given command (run inside Ubuntu as root). */
    static List<String> command(Context c, List<String> inner, Map<String, String> env) {
        File rf = rootfs(c);
        List<String> a = new ArrayList<>();
        a.add(proot(c).getAbsolutePath());
        a.add("--kill-on-exit");
        a.add("--link2symlink");
        a.add("-0");
        a.add("-r"); a.add(rf.getAbsolutePath());
        for (String b : new String[]{"/dev", "/proc", "/sys", "/dev/urandom:/dev/random"}) { a.add("-b"); a.add(b); }
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        a.add("-b"); a.add(sd + ":/sdcard");
        a.add("-b"); a.add("/storage");
        if (new File(sd).exists()) { a.add("-b"); a.add(sd + ":" + sd); }
        for (String b : new String[]{"/system", "/apex", "/vendor", "/linkerconfig"}) if (new File(b).exists()) { a.add("-b"); a.add(b); }
        File fake = new File(base(c), "fakeproc");
        for (String f : new String[]{"loadavg", "stat", "uptime", "version", "vmstat"}) {
            if (!new File("/proc/" + f).canRead() && new File(fake, f).exists()) { a.add("-b"); a.add(new File(fake, f).getAbsolutePath() + ":/proc/" + f); }
        }
        a.add("-w"); a.add("/root");
        a.add("/usr/bin/env"); a.add("-i");
        Map<String, String> e = new LinkedHashMap<>();
        e.put("HOME", "/root");
        e.put("USER", "root");
        e.put("PATH", PATH_IN);
        e.put("TERM", "xterm-256color");
        e.put("LANG", "C.UTF-8");
        e.put("TMPDIR", "/tmp");
        e.put("DEBIAN_FRONTEND", "noninteractive");
        e.put("ANDROID_DATA", "/data");
        e.put("ANDROID_ROOT", "/system");
        if (env != null) e.putAll(env);
        for (Map.Entry<String, String> kv : e.entrySet()) a.add(kv.getKey() + "=" + (kv.getValue() == null ? "" : kv.getValue()));
        a.addAll(inner);
        return a;
    }

    static ProcessBuilder builder(Context c, List<String> inner, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(command(c, inner, env));
        pb.directory(base(c));
        pb.redirectErrorStream(true);
        Map<String, String> pe = pb.environment();
        pe.put("PROOT_TMP_DIR", tmp(c).getAbsolutePath());
        String nl = c.getApplicationInfo().nativeLibraryDir;
        pe.put("PROOT_LOADER", nl + "/libproot-loader.so");
        if (new File(nl, "libproot-loader32.so").exists()) pe.put("PROOT_LOADER_32", nl + "/libproot-loader32.so");
        pe.put("LD_LIBRARY_PATH", new File(base(c), "lib").getAbsolutePath() + ":" + nl);
        return pb;
    }

    /** Runs a command inside Ubuntu, sending every output line to the log. */
    static int run(Context c, List<String> inner, Map<String, String> env, Progress p) throws Exception {
        Process pr = builder(c, inner, env).start();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"));
        String ln;
        while ((ln = r.readLine()) != null) {
            if (p != null) p.log(ln);
            if (p != null && p.cancelled()) { pr.destroy(); break; }
        }
        return pr.waitFor();
    }

    // ------------------------------------------------------------------ download & unpack

    static void download(String url, File out, Progress p, int from, int to) throws Exception {
        String u = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 8; hop++) {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "BuildingSource/2.0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            break;
        }
        long total = c.getContentLengthLong(), got = 0;
        File part = new File(out.getPath() + ".part");
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream o = new FileOutputStream(part)) {
            byte[] b = new byte[65536];
            int n, lastPct = -1;
            while ((n = in.read(b)) > 0) {
                if (p.cancelled()) throw new Exception("stopped");
                o.write(b, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.stage(from + (to - from) * pct / 100, "Downloading Ubuntu  " + (got >> 20) + " / " + (total >> 20) + " MB");
                    }
                }
            }
        }
        c.disconnect();
        if (!part.renameTo(out)) throw new Exception("rename failed");
    }

    /** Minimal tar.gz reader: files, folders, symlinks, hard links, GNU long names, pax paths. */
    static void untar(File tgz, File dest, Progress p) throws Exception {
        long total = tgz.length();
        CountingInput cin = new CountingInput(new FileInputStream(tgz));
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(cin), 65536), 65536)) {
            byte[] h = new byte[512];
            String longName = null, longLink = null, paxPath = null, paxLink = null;
            List<String[]> hardLinks = new ArrayList<>();
            int files = 0, lastPct = -1;
            while (readFully(in, h)) {
                boolean empty = true;
                for (byte x : h) if (x != 0) { empty = false; break; }
                if (empty) break;
                String name = str(h, 0, 100);
                String prefix = str(h, 345, 155);
                if (!prefix.isEmpty() && "ustar".equals(str(h, 257, 5))) name = prefix + "/" + name;
                int mode = (int) octal(h, 100, 8);
                long size = octal(h, 124, 12);
                char type = (char) h[156];
                String link = str(h, 157, 100);
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) size];
                    readFully(in, data);
                    skip(in, pad(size));
                    String s = new String(data, "UTF-8");
                    if (type == 'L') longName = trimNul(s);
                    else if (type == 'K') longLink = trimNul(s);
                    else if (type == 'x') {
                        for (String rec : s.split("\n")) {
                            int sp = rec.indexOf(' '), eq = rec.indexOf('=');
                            if (sp < 0 || eq < 0) continue;
                            String k = rec.substring(sp + 1, eq), v = rec.substring(eq + 1);
                            if (k.equals("path")) paxPath = v;
                            else if (k.equals("linkpath")) paxLink = v;
                        }
                    }
                    continue;
                }
                if (paxPath != null) name = paxPath;
                if (longName != null) name = longName;
                if (paxLink != null) link = paxLink;
                if (longLink != null) link = longLink;
                longName = longLink = paxPath = paxLink = null;
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.equals(".") || name.contains("../")) { skip(in, size + pad(size)); continue; }
                File f = new File(dest, name);
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                switch (type) {
                    case '5':
                        f.mkdirs();
                        chmod(f, (mode & 07777) | 0700);
                        break;
                    case '2':
                        if (f.exists() || Files.isSymbolicLink(f.toPath())) f.delete();
                        Os.symlink(link, f.getAbsolutePath());
                        break;
                    case '1':
                        hardLinks.add(new String[]{name, link});
                        break;
                    case '3': case '4': case '6':
                        break; // device nodes / fifos: not possible (or needed) in an app
                    default:
                        if (Files.isSymbolicLink(f.toPath())) f.delete();
                        try (OutputStream o = new FileOutputStream(f)) {
                            long left = size;
                            byte[] b = new byte[65536];
                            while (left > 0) {
                                int n = in.read(b, 0, (int) Math.min(b.length, left));
                                if (n < 0) throw new IOException("truncated archive");
                                o.write(b, 0, n);
                                left -= n;
                            }
                        }
                        skip(in, pad(size));
                        chmod(f, (mode & 0777) | 0600);
                        files++;
                        break;
                }
                if (type == '5' || type == '2' || type == '1' || type == '3' || type == '4' || type == '6') skip(in, size + pad(size));
                int pct = (int) (cin.count * 100 / Math.max(1, total));
                if (pct != lastPct) { lastPct = pct; p.stage(21 + 12 * pct / 100, "Unpacking Ubuntu  " + pct + "%  (" + files + " files)"); }
                if (p.cancelled()) throw new Exception("stopped");
            }
            // hard links: real links are blocked for apps, so copy
            for (String[] hl : hardLinks) {
                File f = new File(dest, hl[0]), src = new File(dest, hl[1].startsWith("./") ? hl[1].substring(2) : hl[1]);
                try { if (f.exists()) f.delete(); copy(src, f); chmod(f, 0755); } catch (Exception ignored) {}
            }
            p.log("› unpacked " + files + " files");
        }
    }

    private static final class CountingInput extends InputStream {
        final InputStream in; long count;
        CountingInput(InputStream i) { in = i; }
        @Override public int read() throws IOException { int r = in.read(); if (r >= 0) count++; return r; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int r = in.read(b, o, l); if (r > 0) count += r; return r; }
        @Override public void close() throws IOException { in.close(); }
    }

    private static long pad(long size) { long r = size % 512; return r == 0 ? 0 : 512 - r; }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return off > 0 && off == b.length;
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[8192];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) break;
            n -= r;
        }
    }

    private static String str(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String trimNul(String s) { int i = s.indexOf('\0'); return i >= 0 ? s.substring(0, i) : s.trim(); }

    private static long octal(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {   // base-256 (big files)
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (h[i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') { if (v > 0) break; else continue; }
            if (b < '0' || b > '7') break;
            v = v * 8 + (b - '0');
        }
        return v;
    }

    private static void chmod(File f, int mode) { try { Os.chmod(f.getAbsolutePath(), mode); } catch (Exception ignored) {} }

    static void copy(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    static void write(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        try (FileWriter w = new FileWriter(f)) { w.write(s); }
    }

    // ------------------------------------------------------------------ remove everything

    /** Deletes a folder tree without ever following links (so bound / linked folders outside are safe). */
    static void deleteTree(File root) {
        if (root == null || !(root.exists() || Files.isSymbolicLink(root.toPath()))) return;
        try {
            Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    try { Os.chmod(d.toString(), 0700); } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    try { Files.delete(f); } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    try { Files.deleteIfExists(f); } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    try { Files.delete(d); } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
    }

    /** Size of a folder (links not followed). */
    static long du(File f) {
        if (f == null || !f.exists()) return 0;
        final long[] s = {0};
        try {
            Files.walkFileTree(f.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                    if (a.isRegularFile()) s[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path p, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (Exception ignored) {}
        return s[0];
    }

    static boolean isLink(File f) { return Files.isSymbolicLink(f.toPath()); }

    static boolean exists(File f) { return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS); }
}
