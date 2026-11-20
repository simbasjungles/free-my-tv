package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 * The sizes the layout uses are scaled down ahead of time (warm, in steps of at most 2×) and drawn pixel for pixel at a
 * whole-pixel position: a GPU shrinking a ~150 px PNG to 40 px in one bilinear step skips pixels, and the edges come
 * out jagged. Drawing never makes a bitmap: a size in between (a focus animation) is the closest larger copy, filtered.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** Scaled copies per symbol, newest first; only touched holding PAINT. A few: the layout's sizes change with the
     *  screen and the language, never per frame, and the oldest one goes. */
    private static final int SIZES = 6;
    private static final Map<String, Bitmap[]> SIZED = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** The point size that draws the symbol's bitmap at the given height. */
    static float pointSizeForHeight(Context c, String name, float height) {
        Bitmap b = get(c, name);
        return b == null ? 0 : height * PX_PER_POINT / b.getHeight();
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, name, b, cx, cy, pointSize, color);
        }
    }

    /** Scales `pointSize` ahead of time (the layout step, or a background thread), so drawing it is pixel for pixel. */
    static void warm(Context c, String name, float pointSize) {
        Bitmap b = pointSize > 0 ? get(c, name) : null;   // 0 before the first layout
        if (b == null) return;
        int w = px(b.getWidth(), pointSize), h = px(b.getHeight(), pointSize);
        if (w >= b.getWidth() || h >= b.getHeight()) return;   // full size or larger: the PNG itself
        synchronized (PAINT) {
            Bitmap[] sizes = SIZED.get(name);
            if (sizes == null) SIZED.put(name, sizes = new Bitmap[SIZES]);
            for (Bitmap s : sizes) if (s != null && s.getWidth() == w && s.getHeight() == h) return;
            Bitmap s = b;
            while (s.getWidth() / 2 >= w && s.getHeight() / 2 >= h) s = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
            System.arraycopy(sizes, 0, sizes, 1, SIZES - 1);
            sizes[0] = Bitmap.createScaledBitmap(s, w, h, true);
        }
    }

    private static int px(int png, float pointSize) {
        return Math.max(1, Math.round(png * pointSize / PX_PER_POINT));
    }

    private static void drawLocked(Canvas canvas, String name, Bitmap b, float cx, float cy, float pointSize, int color) {
        PAINT.setColor(color);
        int w = px(b.getWidth(), pointSize), h = px(b.getHeight(), pointSize);
        // The smallest copy at least this size (the PNG when there's none).
        Bitmap s = b;
        Bitmap[] sizes = SIZED.get(name);
        if (sizes != null) {
            for (Bitmap z : sizes) {
                if (z != null && z.getWidth() >= w && z.getHeight() >= h && z.getWidth() < s.getWidth()) s = z;
            }
        }
        if (s != b && s.getWidth() == w && s.getHeight() == h) {
            canvas.drawBitmap(s, Math.round(cx - w / 2f), Math.round(cy - h / 2f), PAINT);
            return;
        }
        float fw = b.getWidth() * pointSize / PX_PER_POINT, fh = b.getHeight() * pointSize / PX_PER_POINT;
        RECT.set(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2);
        canvas.drawBitmap(s, null, RECT, PAINT);
    }

    /** Every symbol weather() returns, warmed at `pointSize` (the card shows whichever the forecast brings). */
    static void warmWeather(Context c, float pointSize) {
        for (String name : new String[] {"sunny", "clear_night", "partly_cloudy_day", "partly_cloudy_night", "cloud",
                "foggy", "rainy", "snowflake", "thunderstorm"}) {
            warm(c, name, pointSize);
        }
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
