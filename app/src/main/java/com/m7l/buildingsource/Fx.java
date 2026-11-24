package com.m7l.buildingsource;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.util.Random;

/** The "NET DEFENSE" look shared by every screen: falling code behind the page and soft scan lines over it. */
public final class Fx {
    public static final int BG = Color.parseColor("#03070A");

    private Fx() {}

    /** Puts the page between the falling-code layer and the scan-line layer. */
    public static FrameLayout wrap(Activity a, View content) {
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(BG);
        View rain = new Rain(a);
        rain.setAlpha(0.55f);
        f.addView(rain, new FrameLayout.LayoutParams(-1, -1));
        f.addView(content, new FrameLayout.LayoutParams(-1, -1));
        f.addView(new Scan(a), new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static class Rain extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random r = new Random();
        float[] y; int cols;
        final String chars = "0123456789ABCDEF<>/#$%&*ｱｲｳｴｵｶｷｸｹｺ";
        final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

        Rain(Context c) {
            super(c);
            p.setTypeface(Typeface.MONOSPACE); p.setTextSize(dp(c, 13));
        }
        // ~20 frames a second is enough for falling code and leaves the GPU free for smooth scrolling
        final Runnable tick = new Runnable() { @Override public void run() { invalidate(); postDelayed(this, 50); } };
        final String[] glyph = new String[64];
        { for (int i = 0; i < glyph.length; i++) glyph[i] = String.valueOf(chars.charAt(i % chars.length())); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            int step = dp(getContext(), 18), line = dp(getContext(), 15);
            if (y == null || cols != getWidth() / step) { cols = Math.max(1, getWidth() / step); y = new float[cols]; for (int i = 0; i < cols; i++) y[i] = r.nextInt(Math.max(1, getHeight())); }
            for (int i = 0; i < cols; i++) {
                for (int k = 0; k < 7; k++) {
                    p.setColor(Color.argb(k == 0 ? 60 : Math.max(0, 34 - k * 5), 0, 255, 156));
                    c.drawText(glyph[r.nextInt(glyph.length)], i * step, y[i] - k * line, p);
                }
                y[i] += dp(getContext(), 9f);
                if (y[i] > getHeight() + dp(getContext(), 120) && r.nextInt(20) == 0) y[i] = 0;
            }
        }
    }

    static class Scan extends View {
        final Paint p = new Paint(), lines = new Paint();
        float band = 0;
        long t0 = System.currentTimeMillis();
        // 30 frames a second; the scan lines are one repeating pattern instead of hundreds of rectangles
        final Runnable tick = new Runnable() { @Override public void run() { invalidate(); postDelayed(this, 33); } };

        Scan(Context c) {
            super(c);
            setClickable(false); setFocusable(false);
            int d3 = Math.max(2, dp(c, 3)), d1 = Math.max(1, dp(c, 1));
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(1, d3, android.graphics.Bitmap.Config.ARGB_8888);
            for (int i = 0; i < d3; i++) bm.setPixel(0, i, i < d1 ? Color.argb(20, 0, 0, 0) : Color.TRANSPARENT);
            lines.setShader(new android.graphics.BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            int d60 = dp(getContext(), 60);
            c.drawRect(0, 0, getWidth(), getHeight(), lines);
            band = ((System.currentTimeMillis() - t0) % 4200) / 4200f;
            float by = band * getHeight();
            p.setShader(new LinearGradient(0, by - d60, 0, by + d60, new int[]{Color.TRANSPARENT, Color.argb(16, 0, 255, 156), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, by - d60, getWidth(), by + d60, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }
    }

    /** Dialogs in the same look as the screens: dark glass, neon frame, monospace. */
    public static class Dlg extends android.app.AlertDialog.Builder {
        private final Context c;
        public Dlg(Context c) { super(c, android.R.style.Theme_DeviceDefault_Dialog_Alert); this.c = c; }
        @Override public android.app.AlertDialog show() {
            android.app.AlertDialog d = super.show();
            try { style(c, d); } catch (Exception ignored) {}
            return d;
        }
    }

    static void paint(View v, float den) {
        final int TXT = Color.parseColor("#D8FFE9"), SUB = Color.parseColor("#7FA897");
        if (v instanceof android.widget.EditText) {
            android.widget.EditText e = (android.widget.EditText) v;
            e.setTextColor(TXT); e.setHintTextColor(SUB); e.setTypeface(Typeface.MONOSPACE);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Color.parseColor("#0A1613")); g.setCornerRadius(10 * den); g.setStroke((int) (1.2f * den), Color.parseColor("#24463B"));
            e.setBackground(g); e.setPadding((int) (12 * den), (int) (10 * den), (int) (12 * den), (int) (10 * den));
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) paint(g.getChildAt(i), den);
        }
    }

    public static void style(Context c, android.app.AlertDialog d) {
        final int GREEN = Color.parseColor("#00FF9C"), TXT = Color.parseColor("#D8FFE9"), SUB = Color.parseColor("#7FA897"), AMBER = Color.parseColor("#FFB300");
        final float den = c.getResources().getDisplayMetrics().density;
        android.view.Window w = d.getWindow();
        if (w == null) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.parseColor("#F50B1A16"), Color.parseColor("#F5040A0C")});
        g.setCornerRadius(20 * den); g.setStroke((int) (1.6f * den), Color.parseColor("#C800FF9C"));
        w.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(g, (int) (20 * den), (int) (12 * den), (int) (20 * den), (int) (12 * den)));
        w.setDimAmount(0.82f);
        android.widget.TextView tt = d.findViewById(c.getResources().getIdentifier("alertTitle", "id", "android"));
        if (tt != null) { tt.setTextColor(GREEN); tt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tt.setTextSize(17); tt.setShadowLayer(7 * den, 0, 0, GREEN); tt.setLetterSpacing(0.04f); }
        android.widget.TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) { msg.setTextColor(TXT); msg.setTypeface(Typeface.MONOSPACE); msg.setTextSize(13.5f); msg.setLineSpacing(3 * den, 1f); }
        int[] which = {android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL};
        int[] cols = {GREEN, SUB, AMBER};
        for (int i = 0; i < 3; i++) {
            android.widget.Button b = d.getButton(which[i]);
            if (b != null) { b.setTextColor(cols[i]); b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); b.setAllCaps(false); b.setTextSize(14); }
        }
        android.widget.ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(new android.graphics.drawable.ColorDrawable(Color.parseColor("#16302A")));
            lv.setDividerHeight(Math.max(1, (int) den));
            lv.setPadding((int) (8 * den), 0, (int) (8 * den), (int) (6 * den));
            android.view.ViewGroup.OnHierarchyChangeListener hl = new android.view.ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    if (child instanceof android.widget.TextView) {
                        android.widget.TextView t = (android.widget.TextView) child;
                        t.setTextColor(TXT); t.setTypeface(Typeface.MONOSPACE); t.setTextSize(14);
                        t.setPadding((int) (14 * den), (int) (13 * den), (int) (14 * den), (int) (13 * den));
                    }
                }
                @Override public void onChildViewRemoved(View parent, View child) {}
            };
            lv.setOnHierarchyChangeListener(hl);
            for (int i = 0; i < lv.getChildCount(); i++) hl.onChildViewAdded(lv, lv.getChildAt(i));
        }
        paint(w.getDecorView(), den);
    }
}
