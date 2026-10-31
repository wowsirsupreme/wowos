package com.wowsir.wowos;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Pulls a usable accent colour out of the home wallpaper. */
final class ColorExtract {

    private ColorExtract() { }

    /**
     * The most vibrant colour in the image: high saturation, mid brightness, skipping
     * near-black and near-white pixels. Returns {@code fallback} for a grey/flat image.
     * Brightness is then normalised so the accent reads on the dark WOW OS UI.
     */
    static int accent(Bitmap bmp, int fallback) {
        if (bmp == null) return fallback;
        Bitmap s = null;
        try {
            s = Bitmap.createScaledBitmap(bmp, 40, 40, true);
            float bestScore = 0f;
            int best = fallback;
            float[] hsv = new float[3];
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    int c = s.getPixel(x, y);
                    if (Color.alpha(c) < 128) continue;
                    Color.colorToHSV(c, hsv);
                    float sat = hsv[1], val = hsv[2];
                    if (val < 0.25f || val > 0.97f) continue;      // skip near-black / near-white
                    float score = sat * Math.min(val, 0.9f);
                    if (score > bestScore) { bestScore = score; best = c; }
                }
            }
            if (bestScore < 0.12f) return fallback;                // greyscale wallpaper
            Color.colorToHSV(best, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.6f, Math.min(0.92f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return fallback;
        } finally {
            if (s != null && s != bmp) { try { s.recycle(); } catch (Throwable ignored) { } }
        }
    }

    /** True when text on this colour should be dark. */
    static boolean isLight(int color) {
        double l = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return l > 0.6;
    }
}
