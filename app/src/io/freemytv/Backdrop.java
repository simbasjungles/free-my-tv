package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the card (the photo without scrims, cropped wide, already at the focused card size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /** The card at its focused size (1.05×) and the narrow one (from the poster). */
    static final int WIDE_W = ui(1092), WIDE_H = ui(464), TALL_W = ui(336), TALL_H = ui(464);

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap wide;                  // the photo on the cards
    final BitmapShader wideShader;      // its shader, for the rounded cards: it goes away with the backdrop
    final int[] light, lightGlass;     // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this(image, frost, glass, wide, light, lightGlass, night, nightGlass, light, night, FW, FH);
    }

    /** `lightBg` and `nightBg` (bw × bh): the background itself, when it isn't the frosted version. */
    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass, int[] lightBg, int[] nightBg, int bw, int bh) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.wide = wide;
        wideShader = wide == null ? null : new BitmapShader(wide, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = lightBg == null ? null : Bitmap.createBitmap(lightBg, bw, bh, Bitmap.Config.ARGB_8888);
        nightImage = nightBg == null ? null : Bitmap.createBitmap(nightBg, bw, bh, Bitmap.Config.ARGB_8888);
    }

    static Backdrop make(Bitmap src, boolean forLight) {
        return make(src, forLight, false);
    }

    /**
     * `own`: one of your photos (Photos). Its background is less blurred than a pick's; behind the clock, date and
     * weather it is whitened (darkened at night) as much as a pick's, so the text stays readable, then less toward the
     * right, so it still reads as your photo. The glass and the drawer stay as they are.
     */
    static Backdrop make(Bitmap src, boolean forLight, boolean own) {
        // Cropped to 16:9 at the source's resolution first, then filtered down (no step over 2×).
        Bitmap crop = center(src, (float) W / H);
        if (crop != src) src.recycle();
        Bitmap out = Ui.downscale(crop, W, H);
        if (out != crop) crop.recycle();
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        Canvas c = new Canvas(out);
        if (forLight) {
            // Light design: everything comes from the photo without scrims.
            Bitmap wide = card(out, WIDE_W, WIDE_H);
            int[] frost = reduce(out, FW, FH, 3, 3);
            int[] light = whiten(frost.clone(), 0.82f);
            int[] glass = reduce(out, GW, GH, 2, 2);
            int[] lightGlass = whiten(glass.clone(), 0.74f);
            int[] night = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass, 0.56f);
            if (own) {
                int[] sharp = reduce(out, GW, GH, 1, 1);
                int[] lightBg = ramp(sharp.clone(), 0.82f, 0.45f, true), nightBg = ramp(sharp, 0.76f, 0.5f, false);
                out.recycle();
                return new Backdrop(null, frost, null, wide, light, lightGlass, night, nightGlass, lightBg, nightBg, GW, GH);
            }
            out.recycle();
            return new Backdrop(null, frost, null, wide, light, lightGlass, night, nightGlass);
        }

        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, W * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.4f, W, H, scrim);
        // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
        scrim.setShader(new android.graphics.RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, W * 0.8f, H * 0.65f);
        c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null);
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The center of the photo at the card's aspect ratio, filtered down to the exact size. */
    static Bitmap card(Bitmap full, int w, int h) {
        Bitmap b = center(full, (float) w / h);
        Bitmap out = Ui.downscale(b, w, h);
        if (b != full && b != out) b.recycle();
        return out == full ? gpu(full.copy(Bitmap.Config.ARGB_8888, false)) : gpu(out);
    }

    /** The center of the image at that aspect ratio (the image itself when it already has it). */
    private static Bitmap center(Bitmap b, float ratio) {
        int cw = Math.min(b.getWidth(), Math.round(b.getHeight() * ratio));
        int ch = Math.min(b.getHeight(), Math.round(cw / ratio));
        return Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Whitens (or darkens) by `left` up to 45% of the width, easing to `right` by 70% (a GW × GH image). */
    private static int[] ramp(int[] px, float left, float right, boolean light) {
        int[] col = new int[GH];
        for (int x = 0; x < GW; x++) {
            float t = Math.max(0, Math.min(1, (x / (float) GW - 0.45f) / 0.25f));
            float a = left + (right - left) * t * t * (3 - 2 * t);
            for (int y = 0; y < GH; y++) col[y] = px[y * GW + x];
            if (light) whiten(col, a); else darken(col, a);
            for (int y = 0; y < GH; y++) px[y * GW + x] = col[y];
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
