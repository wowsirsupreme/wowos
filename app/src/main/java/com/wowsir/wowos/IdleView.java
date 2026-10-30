package com.wowsir.wowos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * The idle screen: an original night scene — drifting black feathers, a couple of
 * crows gliding past, and a pair of glowing red eyes that pulse and blink, with the
 * clock beneath. Drawn entirely on the canvas (no bitmaps) so it stays light on a
 * low-memory TV. It evokes a dark, watchful mood without copying any specific design.
 */
final class IdleView extends View {

    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    private long startMs;
    private long lastFrame;
    private float blinkTimer;      // seconds until next blink
    private float blink;           // 0 open .. 1 shut
    private boolean blinking;

    private Feather[] feathers;
    private Crow[] crows;
    private float gaze;            // -1..1 slow horizontal gaze drift
    private float gazeTarget;

    IdleView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        setKeepScreenOn(false);
        p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    // ------------------------------------------------------------------ model

    private static final class Feather {
        float x, y, vy, vx, rot, vrot, size, alpha;
    }

    private static final class Crow {
        float x, y, vx, flap, flapSpeed, scale;
    }

    private void seed() {
        int w = getWidth(), h = getHeight();
        feathers = new Feather[14];
        for (int i = 0; i < feathers.length; i++) feathers[i] = newFeather(w, h, true);
        crows = new Crow[2];
        for (int i = 0; i < crows.length; i++) crows[i] = newCrow(w, h);
        blinkTimer = 2.5f + rnd.nextFloat() * 3f;
        gazeTarget = (rnd.nextFloat() - 0.5f) * 2f;
        startMs = System.currentTimeMillis();
        lastFrame = startMs;
    }

    private Feather newFeather(int w, int h, boolean anywhere) {
        Feather f = new Feather();
        f.x = rnd.nextFloat() * w;
        f.y = anywhere ? rnd.nextFloat() * h : -40 * d;
        f.size = (10 + rnd.nextFloat() * 16) * d;
        f.vy = (8 + rnd.nextFloat() * 18) * d / 1000f;   // px per ms
        f.vx = (rnd.nextFloat() - 0.5f) * 10 * d / 1000f;
        f.rot = rnd.nextFloat() * 360;
        f.vrot = (rnd.nextFloat() - 0.5f) * 40 / 1000f;
        f.alpha = 0.25f + rnd.nextFloat() * 0.4f;
        return f;
    }

    private Crow newCrow(int w, int h) {
        Crow c = new Crow();
        boolean leftToRight = rnd.nextBoolean();
        c.scale = (0.7f + rnd.nextFloat() * 0.8f);
        c.vx = (leftToRight ? 1 : -1) * (30 + rnd.nextFloat() * 40) * d / 1000f;
        c.x = leftToRight ? -80 * d : w + 80 * d;
        c.y = (0.12f + rnd.nextFloat() * 0.4f) * h;
        c.flapSpeed = 4f + rnd.nextFloat() * 3f;
        c.flap = rnd.nextFloat();
        return c;
    }

    // ------------------------------------------------------------------ animation loop

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            float dt = Math.min(60, now - lastFrame);
            lastFrame = now;
            step(dt);
            invalidate();
            postDelayed(this, 33);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(new Runnable() {
            @Override public void run() {
                if (getWidth() > 0) { seed(); removeCallbacks(frame); post(frame); }
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(frame);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0) seed();
    }

    private void step(float dt) {
        int w = getWidth(), h = getHeight();
        for (Feather f : feathers) {
            f.y += f.vy * dt;
            f.x += f.vx * dt + (float) Math.sin((lastFrame + f.hashCode()) / 700.0) * 0.15f;
            f.rot += f.vrot * dt;
            if (f.y - f.size > h) { Feather n = newFeather(w, h, false); copy(n, f); }
        }
        for (Crow c : crows) {
            c.x += c.vx * dt;
            c.flap += c.flapSpeed * dt / 1000f;
            if (c.vx > 0 && c.x > w + 100 * d) copy(newCrow(w, h), c);
            if (c.vx < 0 && c.x < -100 * d) copy(newCrow(w, h), c);
        }
        // gaze drifts slowly toward a target, picking a new one now and then
        gaze += (gazeTarget - gaze) * Math.min(1f, dt / 1200f);
        if (Math.abs(gazeTarget - gaze) < 0.05f && rnd.nextFloat() < dt / 3000f) {
            gazeTarget = (rnd.nextFloat() - 0.5f) * 2f;
        }
        // blink timing
        if (blinking) {
            blink += dt / 120f;               // ~240ms full blink
            if (blink >= 2f) { blink = 0; blinking = false; blinkTimer = 2.5f + rnd.nextFloat() * 4f; }
        } else {
            blinkTimer -= dt / 1000f;
            if (blinkTimer <= 0) blinking = true;
        }
    }

    private static void copy(Feather s, Feather dst) {
        dst.x = s.x; dst.y = s.y; dst.vy = s.vy; dst.vx = s.vx; dst.rot = s.rot;
        dst.vrot = s.vrot; dst.size = s.size; dst.alpha = s.alpha;
    }

    private static void copy(Crow s, Crow dst) {
        dst.x = s.x; dst.y = s.y; dst.vx = s.vx; dst.flap = s.flap;
        dst.flapSpeed = s.flapSpeed; dst.scale = s.scale;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);

        // faint deep-red vignette glow behind the eyes
        p.setShader(new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.5f,
                0x223A0000, 0x00000000, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (feathers != null) for (Feather f : feathers) drawFeather(c, f);
        if (crows != null) for (Crow cr : crows) drawCrow(c, cr);

        drawEyes(c, w * 0.5f, h * 0.42f, w);
        drawClock(c, w, h);
        drawHint(c, w, h);
    }

    private void drawFeather(Canvas c, Feather f) {
        c.save();
        c.translate(f.x, f.y);
        c.rotate(f.rot);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb((int) (f.alpha * 255), 10, 10, 14));
        Path path = new Path();
        float s = f.size;
        path.moveTo(0, -s);
        path.cubicTo(s * 0.5f, -s * 0.3f, s * 0.4f, s * 0.5f, 0, s);
        path.cubicTo(-s * 0.4f, s * 0.5f, -s * 0.5f, -s * 0.3f, 0, -s);
        c.drawPath(path, p);
        p.setColor(Color.argb((int) (f.alpha * 160), 60, 60, 70));
        p.setStrokeWidth(1f * d);
        c.drawLine(0, -s, 0, s, p);
        c.restore();
    }

    private void drawCrow(Canvas c, Crow cr) {
        c.save();
        c.translate(cr.x, cr.y);
        c.scale(cr.scale * (cr.vx < 0 ? -1 : 1), cr.scale);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xEA000000);
        float wing = (float) Math.sin(cr.flap * Math.PI * 2) * 10 * d;
        Path path = new Path();
        path.moveTo(-26 * d, wing);               // left wing tip
        path.quadTo(-8 * d, -4 * d, 0, 0);        // to body
        path.quadTo(8 * d, -4 * d, 26 * d, wing); // right wing tip
        path.quadTo(8 * d, 4 * d, 0, 5 * d);      // underside
        path.quadTo(-8 * d, 4 * d, -26 * d, wing);
        path.close();
        c.drawPath(path, p);
        c.restore();
    }

    private void drawEyes(Canvas c, float cx, float cy, int w) {
        float eyeW = Math.min(w * 0.14f, 150 * d);
        float gap = eyeW * 1.35f;
        drawEye(c, cx - gap, cy, eyeW);
        drawEye(c, cx + gap, cy, eyeW);
    }

    private void drawEye(Canvas c, float cx, float cy, float eyeW) {
        float eyeH = eyeW * 0.52f;
        float open = 1f - lidAmount();
        if (open <= 0.02f) { // shut: a thin red line
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCCFF2A1A);
            c.drawRect(cx - eyeW * 0.5f, cy - 1.2f * d, cx + eyeW * 0.5f, cy + 1.2f * d, p);
            return;
        }
        float curH = eyeH * open;

        // outer glow
        p.setShader(new RadialGradient(cx, cy, eyeW * 0.9f, 0x66FF2A1A, 0x00000000, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, eyeW * 0.9f, p);
        p.setShader(null);

        // almond eye shape, clipped, filled with a red radial
        Path eye = new Path();
        eye.moveTo(cx - eyeW * 0.5f, cy);
        eye.quadTo(cx, cy - curH, cx + eyeW * 0.5f, cy);
        eye.quadTo(cx, cy + curH, cx - eyeW * 0.5f, cy);
        eye.close();
        c.save();
        c.clipPath(eye);
        p.setShader(new RadialGradient(cx, cy, eyeW * 0.6f, 0xFFFF6A3A, 0xFF8A0A06, Shader.TileMode.CLAMP));
        c.drawRect(cx - eyeW, cy - eyeH, cx + eyeW, cy + eyeH, p);
        p.setShader(null);

        // pupil (slit), drifting with the gaze — deliberately generic, not a tomoe pattern
        float px = cx + gaze * eyeW * 0.18f;
        p.setColor(0xFF140000);
        c.drawOval(px - eyeW * 0.07f, cy - curH * 0.8f, px + eyeW * 0.07f, cy + curH * 0.8f, p);
        // bright catchlight
        p.setColor(0x88FFD2C2);
        c.drawCircle(px - eyeW * 0.12f, cy - curH * 0.35f, eyeW * 0.04f, p);
        c.restore();

        // eyelid rim
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(0x552A0000);
        c.drawPath(eye, p);
    }

    /** 0 = open, up to 1 = fully shut, following the blink animation. */
    private float lidAmount() {
        if (!blinking) return 0f;
        return blink <= 1f ? blink : 2f - blink; // down then up
    }

    private void drawClock(Canvas c, int w, int h) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(TimeSync.nowMillis());
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String time = (String) DateFormat.format(h24 ? "HH:mm" : "h:mm", cal);
        String date = (String) DateFormat.format("EEEE, d MMMM", cal);

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xF2FFFFFF);
        p.setTextSize(64 * d);
        float ty = h * 0.72f;
        c.drawText(time, w / 2f, ty, p);
        p.setColor(0x99FFFFFF);
        p.setTextSize(18 * d);
        c.drawText(date, w / 2f, ty + 34 * d, p);
    }

    private void drawHint(Canvas c, int w, int h) {
        // gentle fade-in of the dismiss hint
        long age = System.currentTimeMillis() - startMs;
        int a = (int) (Math.min(1f, age / 3000f) * 110);
        p.setColor(Color.argb(a, 255, 255, 255));
        p.setTextSize(13 * d);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("Press any key", w / 2f, h - 28 * d, p);
    }
}
