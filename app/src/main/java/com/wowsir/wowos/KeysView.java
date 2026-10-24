package com.wowsir.wowos;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The glass keyboard surface. Drawn on one canvas (no child views) so it stays
 * light on a 512 MB box; the highlight glides between keys with a short animation.
 */
final class KeysView extends View {

    static final int SHIFT = -1;
    static final int MODE = -2;
    static final int ENTER = -4;
    static final int DEL = -5;
    static final int LEFT = -21;
    static final int RIGHT = -22;

    static final class Key {
        final String label;
        final int code;
        final float weight;
        final RectF rect = new RectF();

        Key(String label, int code, float weight) {
            this.label = label;
            this.code = code;
            this.weight = weight;
        }

        boolean special() { return code < 0; }
    }

    interface Listener { void onKey(Key key); }

    private final List<List<Key>> letters = new ArrayList<>();
    private final List<List<Key>> symbols = new ArrayList<>();
    private List<List<Key>> rows;

    private int selRow = 1;
    private int selCol = 0;
    private boolean shift;
    private boolean capsLock;
    private String enterLabel = "↵";
    private Listener listener;

    private final RectF hl = new RectF();
    private final RectF hlFrom = new RectF();
    private final RectF hlTo = new RectF();
    private ValueAnimator anim;
    private float pressFlash;

    private final Paint keyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specialFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint();
    private final Paint panelEdge = new Paint();
    private final float d;

    KeysView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        setFocusable(false);

        keyFill.setColor(0x24FFFFFF);
        specialFill.setColor(0x3AFFFFFF);
        keyStroke.setStyle(Paint.Style.STROKE);
        keyStroke.setStrokeWidth(1 * d);
        keyStroke.setColor(0x33FFFFFF);
        hlStroke.setStyle(Paint.Style.STROKE);
        hlStroke.setStrokeWidth(2 * d);
        hlStroke.setColor(0xF2FFFFFF);
        text.setColor(0xF2FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(20 * d);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        smallText.setColor(0xD9FFFFFF);
        smallText.setTextAlign(Paint.Align.CENTER);
        smallText.setTextSize(14 * d);
        smallText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panelEdge.setColor(0x40FFFFFF);
        panelEdge.setStrokeWidth(1 * d);

        build();
        rows = letters;
    }

    private void build() {
        letters.add(row("1234567890"));
        letters.add(row("qwertyuiop"));
        letters.add(row("asdfghjkl@"));
        List<Key> r3 = new ArrayList<>();
        r3.add(new Key("⇧", SHIFT, 1.5f));
        r3.addAll(row("zxcvbnm"));
        r3.add(new Key("⌫", DEL, 1.5f));
        letters.add(r3);
        letters.add(bottom("?123", "."));

        symbols.add(row("1234567890"));
        symbols.add(row("!#$%&*()-?"));
        symbols.add(row("_=+/\\:;\"'~"));
        List<Key> s3 = row(",.<>{}[]");
        s3.add(new Key("⌫", DEL, 2f));
        symbols.add(s3);
        symbols.add(bottom("ABC", ","));
    }

    private static List<Key> row(String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            char ch = chars.charAt(i);
            r.add(new Key(String.valueOf(ch), ch, 1f));
        }
        return r;
    }

    private static List<Key> bottom(String modeLabel, String punct) {
        List<Key> r = new ArrayList<>();
        r.add(new Key(modeLabel, MODE, 1.5f));
        r.add(new Key("◀", LEFT, 1f));
        r.add(new Key("space", ' ', 4f));
        r.add(new Key("▶", RIGHT, 1f));
        r.add(new Key(punct, punct.charAt(0), 1f));
        r.add(new Key("↵", ENTER, 1.5f));
        return r;
    }

    // ------------------------------------------------------------------ state

    void setListener(Listener l) { listener = l; }

    void setEnterLabel(String label) {
        enterLabel = label;
        invalidate();
    }

    void reset(boolean numeric) {
        shift = false;
        capsLock = false;
        rows = numeric ? symbols : letters;
        selRow = numeric ? 0 : 1;
        selCol = 0;
        layoutKeys();
        snapHighlight();
    }

    boolean isShifted() { return shift || capsLock; }

    /** Called after a letter is typed: one-shot shift turns itself off. */
    void consumeShift() {
        if (shift && !capsLock) {
            shift = false;
            invalidate();
        }
    }

    void toggleShift() {
        if (capsLock) {
            capsLock = false;
            shift = false;
        } else if (shift) {
            capsLock = true;
        } else {
            shift = true;
        }
        invalidate();
    }

    void toggleMode() {
        rows = rows == letters ? symbols : letters;
        selRow = Math.min(selRow, rows.size() - 1);
        selCol = Math.min(selCol, rows.get(selRow).size() - 1);
        layoutKeys();
        snapHighlight();
    }

    Key selected() { return rows.get(selRow).get(selCol); }

    String labelFor(Key k) {
        if (k.code == ENTER) return enterLabel;
        if (!k.special() && k.label.length() == 1 && Character.isLetter(k.label.charAt(0)) && isShifted()) {
            return k.label.toUpperCase();
        }
        return k.label;
    }

    // ------------------------------------------------------------------ navigation

    /** Returns false when moving up off the top row (the keyboard should close). */
    boolean move(int dRow, int dCol) {
        if (dCol != 0) {
            int n = rows.get(selRow).size();
            selCol = (selCol + dCol + n) % n;
        } else if (dRow != 0) {
            int target = selRow + dRow;
            if (target < 0) return false;
            if (target >= rows.size()) target = rows.size() - 1;
            float cx = selected().rect.centerX();
            selRow = target;
            selCol = nearest(rows.get(selRow), cx);
        }
        animateHighlight();
        return true;
    }

    private static int nearest(List<Key> row, float x) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < row.size(); i++) {
            float dist = Math.abs(row.get(i).rect.centerX() - x);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return best;
    }

    void press() {
        pressFlash = 1f;
        invalidate();
        postDelayed(new Runnable() {
            @Override public void run() { pressFlash = 0f; invalidate(); }
        }, 90);
        if (listener != null) listener.onKey(selected());
    }

    // ------------------------------------------------------------------ layout & drawing

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(270 * d));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys();
        panel.setShader(new LinearGradient(0, 0, 0, h, 0xF21C2445, 0xF60E1430, Shader.TileMode.CLAMP));
        hlFill.setShader(new LinearGradient(0, 0, 0, h, 0x66FFFFFF, 0x33FFFFFF, Shader.TileMode.CLAMP));
        snapHighlight();
    }

    private void layoutKeys() {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) return;
        float side = Math.max(24 * d, w * 0.08f);
        float top = 14 * d;
        float bottomPad = 12 * d;
        float gap = 8 * d;
        float rowH = (h - top - bottomPad - gap * (rows.size() - 1)) / rows.size();
        float usable = w - side * 2;
        for (int r = 0; r < rows.size(); r++) {
            List<Key> row = rows.get(r);
            float total = 0;
            for (Key k : row) total += k.weight;
            float unit = (usable - gap * (row.size() - 1)) / total;
            float x = side;
            float y = top + r * (rowH + gap);
            for (Key k : row) {
                float kw = unit * k.weight;
                k.rect.set(x, y, x + kw, y + rowH);
                x += kw + gap;
            }
        }
    }

    private void snapHighlight() {
        if (getWidth() == 0) return;
        if (anim != null) anim.cancel();
        hl.set(selected().rect);
        invalidate();
    }

    private void animateHighlight() {
        if (anim != null) anim.cancel();
        hlFrom.set(hl);
        hlTo.set(selected().rect);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(110);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                hl.set(lerp(hlFrom.left, hlTo.left, t), lerp(hlFrom.top, hlTo.top, t),
                        lerp(hlFrom.right, hlTo.right, t), lerp(hlFrom.bottom, hlTo.bottom, t));
                invalidate();
            }
        });
        anim.start();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        c.drawRect(0, 0, w, getHeight(), panel);
        c.drawLine(0, 0.5f * d, w, 0.5f * d, panelEdge);

        float radius = 12 * d;
        for (List<Key> row : rows) {
            for (Key k : row) {
                c.drawRoundRect(k.rect, radius, radius, k.special() ? specialFill : keyFill);
                c.drawRoundRect(k.rect, radius, radius, keyStroke);
            }
        }

        // Highlight sits on top of the key glass but under the label.
        hlFill.setAlpha(pressFlash > 0 ? 255 : 200);
        c.drawRoundRect(hl, radius, radius, hlFill);
        c.drawRoundRect(hl, radius, radius, hlStroke);

        for (List<Key> row : rows) {
            for (Key k : row) {
                String label = labelFor(k);
                boolean small = label.length() > 2;
                Paint p = small ? smallText : text;
                int saved = p.getColor();
                if (k.code == SHIFT && isShifted()) p.setColor(capsLock ? 0xFFFFB347 : 0xFFFFD9A8);
                float y = k.rect.centerY() - (p.descent() + p.ascent()) / 2;
                c.drawText(label, k.rect.centerX(), y, p);
                p.setColor(saved);
            }
        }
    }
}
