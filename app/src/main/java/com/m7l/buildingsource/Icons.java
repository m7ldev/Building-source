package com.m7l.buildingsource;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/** Line icons drawn in code (24 × 24 grid, rounded strokes) — one design language for the whole app. */
final class Icons {

    /** emoji used in the texts → icon name */
    static final Map<String, String> EMOJI = new HashMap<>();
    static {
        String[][] m = {
            {"🏗", "hammer"}, {"📦", "box"}, {"⚙", "gear"}, {"👤", "user"}, {"📁", "folder"}, {"📂", "folder_open"}, {"🗂", "folders"},
            {"🕘", "history"}, {"⏱", "timer"}, {"⏳", "hourglass"}, {"🎯", "target"}, {"▶", "play"}, {"■", "stop"}, {"🖥", "terminal"},
            {"📋", "copy"}, {"▲", "chev_up"}, {"▼", "chev_down"}, {"📊", "chart"}, {"🎉", "badge"}, {"🗜", "zip"}, {"✔", "check"},
            {"✓", "check"}, {"✅", "check_circle"}, {"✕", "close"}, {"✗", "close"}, {"🤖", "android"}, {"🔐", "lock"}, {"🔋", "battery"},
            {"🗑", "trash"}, {"🧹", "broom"}, {"🧨", "alert"}, {"🌐", "globe"}, {"💡", "bulb"}, {"🎮", "gamepad"}, {"🏷", "tag"},
            {"☕", "cup"}, {"🐘", "layers"}, {"🧠", "chip"}, {"✈", "cloud_off"}, {"⌨", "keyboard"}, {"🔔", "bell"}, {"📲", "install"},
            {"⚡", "bolt"}, {"🐧", "terminal"}, {"💙", "flutter"}, {"⚛", "atom"}, {"🦀", "gear"}, {"🐹", "code"}, {"🟩", "hexagon"},
            {"🐍", "code"}, {"🟪", "hexagon"}, {"📜", "script"}, {"👑", "crown"}, {"🐜", "bug"}, {"#", "hash"}, {"●", "dot_on"},
            {"○", "dot_off"}, {"👋", "badge"}, {"🔗", "link"}, {"📤", "share"}, {"⬇", "download"}, {"📷", "camera"}, {"📨", "send"},
        };
        for (String[] e : m) EMOJI.put(e[0], e[1]);
    }

    /** Splits a leading emoji (and the spaces after it) from a text: returns {icon name or null, rest}. */
    static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[]{null, s};
        int cp = s.codePointAt(0);
        int len = Character.charCount(cp);
        if (len < s.length() && s.charAt(len) == '️') len++;        // variation selector
        String head = s.substring(0, len);
        String name = EMOJI.get(head);
        if (name == null) name = EMOJI.get(new String(Character.toChars(cp)));
        if (name == null) return new String[]{null, s};
        if ("#".equals(head) && s.length() > 1 && s.charAt(1) != ' ') return new String[]{null, s};
        int i = len;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ' ')) i++;
        return new String[]{name, s.substring(i)};
    }

    /** Removes leading emoji from dialog titles and toasts. */
    static String plain(String s) { String[] p = split(s); return p[1]; }

    /** Puts the leading emoji of a text on the view as a drawn icon (start of the text, or centred when there is no text). */
    static void apply(android.widget.TextView v, String s) {
        String[] sp = split(s);
        if (sp[0] == null) { v.setText(s); v.setCompoundDrawablesRelative(null, null, null, null); return; }
        float px = v.getTextSize();
        if (sp[1].isEmpty()) {
            v.setText("");
            Icon d = new Icon(sp[0], v.getCurrentTextColor()); d.host = v; d.fixed = Math.round(px * 1.15f);
            v.setForeground(d);
            v.setCompoundDrawablesRelative(null, null, null, null);
        } else {
            v.setText(sp[1]);
            Icon d = new Icon(sp[0], v.getCurrentTextColor()); d.host = v;
            int sz = Math.round(px * 1.25f);
            d.setBounds(0, 0, sz, sz);
            v.setCompoundDrawablesRelative(d, null, null, null);
            v.setCompoundDrawablePadding(Math.round(px * 0.5f));
            v.setForeground(null);
        }
    }

    static Drawable of(String name, int color, int sizePx) {
        Icon d = new Icon(name, color);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** An icon that draws itself centred in any bounds (for icon-only views, used as foreground). */
    static Drawable centered(String name, int color, int sizePx) { Icon d = new Icon(name, color); d.fixed = sizePx; return d; }

    static final class Icon extends Drawable {
        final String name;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), f = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF r = new RectF();
        int fixed;
        android.widget.TextView host;   // when set, the icon takes the text colour of this view (follows later colour changes)

        Icon(String name, int color) {
            this.name = name;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            f.setStyle(Paint.Style.FILL);
            f.setColor(color);
        }

        @Override public void draw(Canvas c) {
            if (host != null) { int col = host.getCurrentTextColor(); p.setColor(col); f.setColor(col); }
            Rect b = getBounds();
            float size = fixed > 0 ? Math.min(fixed, Math.min(b.width(), b.height())) : Math.min(b.width(), b.height());
            float ox = b.left + (b.width() - size) / 2f, oy = b.top + (b.height() - size) / 2f;
            c.save();
            c.translate(ox, oy);
            c.scale(size / 24f, size / 24f);
            p.setStrokeWidth(2f);
            paint(c);
            c.restore();
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, p); }
        private void rr(Canvas c, float l, float t, float rt, float bt, float rad) { r.set(l, t, rt, bt); c.drawRoundRect(r, rad, rad, p); }
        private void circle(Canvas c, float x, float y, float rad) { c.drawCircle(x, y, rad, p); }
        private void poly(Canvas c, boolean close, float... xy) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            if (close) path.close();
            c.drawPath(path, p);
        }
        private void fillPoly(Canvas c, float... xy) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            path.close();
            c.drawPath(path, f);
        }
        private void arc(Canvas c, float l, float t, float rt, float bt, float start, float sweep) { r.set(l, t, rt, bt); c.drawArc(r, start, sweep, false, p); }

        private void paint(Canvas c) {
            switch (name) {
                case "hammer":   // build: hammer over a brick
                    poly(c, false, 14, 4, 20, 10);
                    poly(c, true, 12, 6, 16, 2, 22, 8, 18, 12);
                    line(c, 13, 9, 4, 18);
                    line(c, 4, 18, 6, 20);
                    line(c, 6, 20, 15, 11);
                    break;
                case "box":
                    poly(c, true, 12, 3, 20, 7, 20, 17, 12, 21, 4, 17, 4, 7);
                    poly(c, false, 4, 7, 12, 11, 20, 7);
                    line(c, 12, 11, 12, 21);
                    line(c, 8, 5, 16, 9);
                    break;
                case "gear": {
                    circle(c, 12, 12, 3);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        line(c, 12 + (float) (6.2 * Math.cos(a)), 12 + (float) (6.2 * Math.sin(a)), 12 + (float) (9 * Math.cos(a)), 12 + (float) (9 * Math.sin(a)));
                    }
                    circle(c, 12, 12, 6.5f);
                    break;
                }
                case "user": circle(c, 12, 8, 4); arc(c, 4, 13, 20, 29, 180, 180); break;
                case "folder": case "folders":
                    poly(c, true, 3, 7, 3, 19, 21, 19, 21, 9, 12, 9, 10, 6, 3, 6);
                    if ("folders".equals(name)) line(c, 6, 4, 11, 4);
                    break;
                case "folder_open":
                    poly(c, false, 3, 18, 3, 6, 9, 6, 11, 8, 18, 8, 18, 11);
                    poly(c, true, 3, 18, 6, 11, 22, 11, 19, 18);
                    break;
                case "history":
                    arc(c, 4, 4, 20, 20, 160, 290);
                    poly(c, false, 3, 9, 4.5f, 12.5f, 8, 11);
                    poly(c, false, 12, 8, 12, 12, 15, 14);
                    break;
                case "timer": circle(c, 12, 13, 7.5f); line(c, 12, 13, 12, 9); line(c, 10, 3, 14, 3); line(c, 18, 6, 19.5f, 4.5f); break;
                case "hourglass": poly(c, true, 6, 3, 18, 3, 12, 12); poly(c, true, 12, 12, 18, 21, 6, 21); break;
                case "target": circle(c, 12, 12, 8.5f); circle(c, 12, 12, 4.5f); f.setAlpha(255); c.drawCircle(12, 12, 1.6f, f); break;
                case "play": fillPoly(c, 8, 5, 19, 12, 8, 19); break;
                case "stop": r.set(6, 6, 18, 18); c.drawRoundRect(r, 2.5f, 2.5f, f); break;
                case "terminal": rr(c, 3, 4, 21, 20, 2.5f); poly(c, false, 7, 9, 10, 12, 7, 15); line(c, 12, 15, 16, 15); break;
                case "copy": rr(c, 8, 8, 20, 20, 2); poly(c, false, 16, 8, 16, 4, 4, 4, 4, 16, 8, 16); break;
                case "chev_up": poly(c, false, 6, 15, 12, 9, 18, 15); break;
                case "chev_down": poly(c, false, 6, 9, 12, 15, 18, 9); break;
                case "chart": line(c, 4, 20, 20, 20); line(c, 7, 17, 7, 12); line(c, 12, 17, 12, 6); line(c, 17, 17, 17, 9); break;
                case "badge": poly(c, true, 12, 2, 14.5f, 5, 18.5f, 4.5f, 18.5f, 8.5f, 21.5f, 11, 19, 14, 20, 18, 16, 18.5f, 12, 21.5f, 8, 18.5f, 4, 18, 5, 14, 2.5f, 11, 5.5f, 8.5f, 5.5f, 4.5f, 9.5f, 5);
                    poly(c, false, 8.5f, 12, 11, 14.5f, 15.5f, 9.5f); break;
                case "zip": rr(c, 5, 3, 19, 21, 2); line(c, 12, 3, 12, 5); line(c, 12, 7, 12, 9); line(c, 12, 11, 12, 13); rr(c, 10, 14, 14, 18, 1); break;
                case "check": poly(c, false, 5, 12.5f, 10, 17.5f, 19, 7); break;
                case "check_circle": circle(c, 12, 12, 9); poly(c, false, 7.5f, 12.5f, 10.5f, 15.5f, 16.5f, 9); break;
                case "close": line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
                case "android":
                    arc(c, 5, 7, 19, 21, 180, 180); line(c, 5, 14, 19, 14);
                    line(c, 8, 7.5f, 6.5f, 5); line(c, 16, 7.5f, 17.5f, 5);
                    c.drawCircle(9.3f, 11, 1, f); c.drawCircle(14.7f, 11, 1, f);
                    rr(c, 5, 14, 19, 19, 1.5f);
                    break;
                case "lock": rr(c, 5, 11, 19, 21, 2.5f); arc(c, 8, 4, 16, 14, 180, 180); line(c, 8, 9, 8, 11); line(c, 16, 9, 16, 11); line(c, 12, 15, 12, 17); break;
                case "battery": rr(c, 3, 7, 19, 17, 2.5f); line(c, 22, 10, 22, 14); r.set(5.5f, 9.5f, 12, 14.5f); c.drawRect(r, f); break;
                case "trash": line(c, 4, 7, 20, 7); poly(c, false, 9, 7, 9, 4, 15, 4, 15, 7); poly(c, false, 6, 7, 7, 20, 17, 20, 18, 7); line(c, 10, 11, 10, 16); line(c, 14, 11, 14, 16); break;
                case "broom": line(c, 18, 3, 11, 12); poly(c, true, 9, 11, 14, 15, 11, 21, 3, 17, 5, 13); line(c, 7, 15, 6, 19); line(c, 10, 16, 9, 20); break;
                case "alert": poly(c, true, 12, 3, 22, 20, 2, 20); line(c, 12, 9, 12, 14); c.drawCircle(12, 17, 1.1f, f); break;
                case "globe": circle(c, 12, 12, 9); line(c, 3, 12, 21, 12); r.set(8, 3, 16, 21); c.drawOval(r, p); break;
                case "bulb": arc(c, 6, 3, 18, 15, 150, 240); poly(c, false, 7, 13.5f, 9, 17, 15, 17, 17, 13.5f); line(c, 9.5f, 20, 14.5f, 20); break;
                case "gamepad": rr(c, 2.5f, 7, 21.5f, 18, 5); line(c, 7, 10.5f, 7, 14.5f); line(c, 5, 12.5f, 9, 12.5f); c.drawCircle(15.5f, 11, 1.1f, f); c.drawCircle(17.5f, 14, 1.1f, f); break;
                case "tag": poly(c, true, 3, 3, 12, 3, 21, 12, 12, 21, 3, 12); c.drawCircle(8, 8, 1.5f, f); break;
                case "cup": poly(c, false, 4, 9, 5, 19, 15, 19, 16, 9, 4, 9); arc(c, 14, 10, 20, 16, -90, 180); line(c, 7, 3, 7, 6); line(c, 10.5f, 3, 10.5f, 6); line(c, 14, 3, 14, 6); break;
                case "layers": poly(c, true, 12, 3, 21, 8, 12, 13, 3, 8); poly(c, false, 3, 12.5f, 12, 17.5f, 21, 12.5f); poly(c, false, 3, 16.5f, 12, 21.5f, 21, 16.5f); break;
                case "chip": rr(c, 6, 6, 18, 18, 2); rr(c, 9.5f, 9.5f, 14.5f, 14.5f, 1);
                    for (int i = 0; i < 3; i++) { float k = 9 + i * 3; line(c, k, 3, k, 6); line(c, k, 18, k, 21); line(c, 3, k, 6, k); line(c, 18, k, 21, k); }
                    break;
                case "cloud_off": arc(c, 3, 9, 11, 17, 90, 180); arc(c, 7, 5, 17, 15, 180, 180); arc(c, 13, 9, 21, 17, -90, 180); line(c, 7, 17, 17, 17); line(c, 4, 4, 20, 20); break;
                case "keyboard": rr(c, 2.5f, 6, 21.5f, 18, 2.5f);
                    for (int i = 0; i < 5; i++) c.drawCircle(6 + i * 3, 10, 0.9f, f);
                    for (int i = 0; i < 4; i++) c.drawCircle(7.5f + i * 3, 13, 0.9f, f);
                    line(c, 8, 15.5f, 16, 15.5f); break;
                case "bell": poly(c, false, 6, 17, 6, 11); arc(c, 6, 5, 18, 17, 180, 180); poly(c, false, 18, 11, 18, 17); line(c, 4, 17, 20, 17); arc(c, 10, 17, 14, 21, 0, 180); line(c, 12, 3, 12, 5); break;
                case "install": case "download": line(c, 12, 3, 12, 15); poly(c, false, 7, 10, 12, 15, 17, 10); line(c, 4, 20, 20, 20); break;
                case "share": circle(c, 18, 5, 2.5f); circle(c, 6, 12, 2.5f); circle(c, 18, 19, 2.5f); line(c, 8.2f, 10.8f, 15.8f, 6.2f); line(c, 8.2f, 13.2f, 15.8f, 17.8f); break;
                case "link": arc(c, 3, 8, 12, 16, 90, 180); arc(c, 12, 8, 21, 16, -90, 180); line(c, 7.5f, 8, 11, 8); line(c, 7.5f, 16, 11, 16); line(c, 13, 8, 16.5f, 8); line(c, 13, 16, 16.5f, 16); line(c, 9, 12, 15, 12); break;
                case "bolt": fillPoly(c, 13, 2, 5, 13.5f, 11, 13.5f, 10, 22, 19, 10, 13, 10); break;
                case "flutter": poly(c, false, 14, 3, 5, 12, 8, 15, 20, 3); poly(c, false, 14, 12, 9, 17, 13, 21, 20, 21, 15, 16, 20, 12); break;
                case "atom": circle(c, 12, 12, 1.2f);
                    c.save(); r.set(2, 8, 22, 16); for (int i = 0; i < 3; i++) { c.drawOval(r, p); c.rotate(60, 12, 12); } c.restore(); break;
                case "code": poly(c, false, 8, 7, 3, 12, 8, 17); poly(c, false, 16, 7, 21, 12, 16, 17); line(c, 13.5f, 5, 10.5f, 19); break;
                case "hexagon": poly(c, true, 12, 2.5f, 20.5f, 7.25f, 20.5f, 16.75f, 12, 21.5f, 3.5f, 16.75f, 3.5f, 7.25f); poly(c, false, 9, 9, 9, 15); poly(c, false, 9, 12, 15, 12); poly(c, false, 15, 9, 15, 15); break;
                case "script": poly(c, true, 6, 3, 15, 3, 19, 7, 19, 21, 6, 21); poly(c, false, 15, 3, 15, 7, 19, 7); line(c, 9, 12, 16, 12); line(c, 9, 16, 14, 16); break;
                case "crown": poly(c, true, 3, 8, 7.5f, 12, 12, 5, 16.5f, 12, 21, 8, 19, 19, 5, 19); break;
                case "bug": r.set(7, 7, 17, 21); c.drawOval(r, p); circle(c, 12, 5.5f, 2.5f); line(c, 12, 11, 12, 21); line(c, 7, 13, 3, 12); line(c, 17, 13, 21, 12); line(c, 7.5f, 17, 4, 19); line(c, 16.5f, 17, 20, 19); break;
                case "hash": line(c, 9, 4, 7, 20); line(c, 17, 4, 15, 20); line(c, 4, 9, 20, 9); line(c, 3.5f, 15, 19.5f, 15); break;
                case "camera": rr(c, 3.5f, 3.5f, 20.5f, 20.5f, 5.5f); circle(c, 12, 12, 4); c.drawCircle(17, 7, 1.1f, f); break;
                case "send": poly(c, true, 21, 3, 3, 10.5f, 10, 13.5f, 13.5f, 21); line(c, 21, 3, 10, 13.5f); break;
                case "dot_on": c.drawCircle(12, 12, 5, f); break;
                case "dot_off": circle(c, 12, 12, 5); break;
                default: circle(c, 12, 12, 4); break;
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); f.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); f.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return fixed > 0 ? fixed : -1; }
        @Override public int getIntrinsicHeight() { return fixed > 0 ? fixed : -1; }
    }

    private Icons() {}
}
