package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.HashMap;

/**
 * Blurred rounded-rectangle shadows for older GPUs. A BlurMaskFilter is rasterized on the CPU and uploaded again every
 * time the shape changes size (every frame of a focus animation): on a Mali-450 (Mi Box 3, Android 9) that alone took
 * ~40 ms a frame. Here each (corner radius, blur) is blurred once into a small alpha bitmap and drawn as a nine-slice
 * (corners as they are, edges and center stretched), tinted with the paint's color.
 * Every Android version now: on the R10G (Google TV 14) the real BlurMaskFilter cost ~18 ms a frame while the focus
 * animation resized the shape, and the cache brought it to 9 ms.
 */
final class Shadows {
    private final HashMap<Long, Bitmap> cache = new HashMap<>();
    private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float[] xs = new float[4], ys = new float[4];
    private final int[] sx = new int[4], sy = new int[4];
    boolean dither;

    /** Draws `shape` (radius `r`) with `paint`, whose BlurMaskFilter was made with radius `blur`. */
    void draw(Canvas c, RectF shape, float r, float blur, Paint paint) {
        int radius = Math.max(0, Math.round(r)), b = Math.max(1, Math.round(blur));
        int m = (int) Math.ceil(b * 1.8f) + 1;        // how far the blur spreads outside the shape
        int corner = radius + 2 * m;                  // the slice that holds the curve and the blur on both sides of it
        int w = slices(shape.left - m, shape.right + m, corner, xs, sx);
        int h = slices(shape.top - m, shape.bottom + m, corner, ys, sy);
        Bitmap mask = mask(radius, b, m, w, h, dither);
        tint.setColor(paint.getColor());
        tint.setFilterBitmap(!dither);
        for (int i = 0; i < 3; i++) {
            if (sy[i + 1] == sy[i]) continue;
            for (int j = 0; j < 3; j++) {
                if (sx[j + 1] == sx[j]) continue;
                src.set(sx[j], sy[i], sx[j + 1], sy[i + 1]);
                dst.set(xs[j], ys[i], xs[j + 1], ys[i + 1]);
                c.drawBitmap(mask, src, dst, tint);
            }
        }
    }

    /**
     * One axis from `from` to `to`: long enough, three slices (corner, 1 px stretched, corner); too short for that, the
     * whole length in one slice, its size rounded to 8 px so a growing shape reuses a few bitmaps. Returns the size.
     */
    private static int slices(float from, float to, int corner, float[] out, int[] src) {
        if (to - from >= 2 * corner + 1) {
            int size = 2 * corner + 1;
            out[0] = from; out[1] = from + corner; out[2] = to - corner; out[3] = to;
            src[0] = 0; src[1] = corner; src[2] = corner + 1; src[3] = size;
            return size;
        }
        int size = Math.max(8, Math.round((to - from) / 8) * 8);
        out[0] = from; out[1] = from; out[2] = to; out[3] = to;
        src[0] = 0; src[1] = 0; src[2] = size; src[3] = size;
        return size;
    }

    private Bitmap mask(int radius, int b, int m, int w, int h, boolean dither) {
        long key = (long) radius << 48 | (long) b << 32 | (long) w << 16 | h | (dither ? Long.MIN_VALUE : 0);
        Bitmap mask = cache.get(key);
        if (mask != null) return mask;
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL));
        new Canvas(full).drawRoundRect(m, m, w - m, h - m, radius, radius, p);
        if (dither) {
            int[] px = new int[w * h];
            full.getPixels(px, 0, w, 0, 0, w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = px[y * w + x] >>> 24;
                    float d = (Ui.BAYER[(y & 3) * 4 + (x & 3)] + 0.5f) / 16f - 0.5f;
                    px[y * w + x] = Math.max(0, Math.min(255, Math.round(a + d * Math.min(64, 2 * a)))) << 24;
                }
            }
            full.setPixels(px, 0, w, 0, 0, w, h);
        }
        mask = full.extractAlpha();
        full.recycle();
        if (cache.size() > 64) cache.clear();     // focus animations step through a few sizes; keep it bounded
        cache.put(key, mask);
        return mask;
    }
}
