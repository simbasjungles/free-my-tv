package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Continue watching" cards' pictures, decoded in the background once, at the card's size on screen (16:9,
 * centre-cropped), and kept in memory. Drawing only reuses them. Pictures already on the TV are read through the apps'
 * providers (content:// and android.resource://). Some apps (Netflix, JioHotstar) leave an https address instead: those
 * are fetched from where the app said, through NetPicture's checks (https only, never the home network, pictures only,
 * size and time limits), on a thread of their own so a slow server doesn't hold the others back. Plex's titles, once
 * you sign in to it on the TV (Plex.java), bring their pictures from your own Plex server and Plex's artwork. The app
 * never opens files (file://) or plain http. Anything else, or a picture that can't be read, shows the app's TV banner
 * instead, and isn't tried again for a while (sooner for the network, which may only be late after the TV starts).
 */
final class WatchArt {
    /** The card in the 1920×1080 layout; the pictures are made at this times the UI's scale (setScale). */
    static final int W = 520, H = 292;
    private static final int MAX_BYTES = 8 << 20;
    /** At most this many pixels decoded (~8 MB): a 60000×300 picture is sampled down before it's decoded. */
    private static final long MAX_PIXELS = 2_000_000;

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One thread for the whole process: a home screen started over (new settings) doesn't leave one behind. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** The pictures that come from the network wait on their own thread. */
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile int pw = W, ph = H;   // the pictures' size in pixels
    private static final long RETRY_MS = 10 * 60_000L, NET_RETRY_MS = 2 * 60_000L;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failed = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();   // each picture's colour, for the background
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();   // for the rounded corners, made with the picture
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= 16) return false;   // ~600 KB each at 1080p: the row and a little more
            shaders.remove(eldest.getValue());
            return true;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
    }

    /**
     * The card's picture, or the app's banner if it has none or it failed; null while loading (in the background;
     * onLoaded runs on the main thread when it's in).
     */
    Bitmap get(Uri uri, String key, String bannerKey, String pkg) {
        if (uri != null || key != null && key.startsWith(Plex.PICTURE)) {
            Bitmap b = fetch(key, uri, pkg);
            if (b != null || !failedRecently(key)) return b;
        }
        return fetch(bannerKey, null, pkg);
    }

    /** The UI's scale (width / 1920): pictures already made at another size are made again. */
    void setScale(float k) {
        int width = Math.max(1, Math.round(W * k)), height = Math.max(1, Math.round(H * k));
        if (width == pw && height == ph) return;
        pw = width;
        ph = height;
        synchronized (cache) {
            cache.clear();
            shaders.clear();
        }
    }

    /** The picture's shader (get's pictures only). */
    BitmapShader shader(Bitmap b) {
        synchronized (cache) {
            return shaders.get(b);
        }
    }

    /** The colour of the card's picture (or of the banner in its place), or null until it's decoded. */
    Integer color(String key, String bannerKey) {
        synchronized (cache) {
            if (key != null) {
                Integer c = colors.get(key);
                Long at = failed.get(key);
                if (c != null || at == null || System.currentTimeMillis() - at >= retryMs(key)) return c;
            }
            return colors.get(bannerKey);
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        synchronized (cache) {
            cache.clear();
            shaders.clear();
            colors.clear();
        }
    }

    private boolean cached(String key) {
        synchronized (cache) {
            return cache.containsKey(key);
        }
    }

    private boolean failedRecently(String uri) {
        synchronized (cache) {
            Long at = failed.get(uri);
            return at != null && System.currentTimeMillis() - at < retryMs(uri);
        }
    }

    private static boolean fromNetwork(String key) {
        return key != null && key.regionMatches(true, 0, "https:", 0, 6);
    }

    /** How long a picture that failed waits (the keys of the network's pictures are their addresses). */
    private static long retryMs(String key) {
        return fromNetwork(key) ? NET_RETRY_MS : RETRY_MS;
    }

    private Bitmap fetch(String key, Uri uri, String pkg) {
        if (closed) return null;
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null || pending.contains(key)) return b;
            Long at = failed.get(key);
            if (at != null && System.currentTimeMillis() - at < retryMs(key)) return null;
            pending.add(key);
        }
        (uri != null && "https".equalsIgnoreCase(uri.getScheme()) ? NET : IO).execute(() -> {
            if (closed) return;
            Bitmap b = null;
            try {
                b = uri != null ? load(read(uri)) : key.startsWith(Plex.PICTURE)
                        ? load(Plex.picture(app, key)) : banner(pkg);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching picture: " + e.getMessage());
            }
            Integer color = b == null ? null : colorOf(b);
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // A Plex picture that failed: its banner in the same task, not queued behind the other cards' pictures.
            String bannerKey = "banner:" + pkg;
            Bitmap banner = null;
            if (b == null && uri == null && key.startsWith(Plex.PICTURE) && !cached(bannerKey)) {
                try {
                    banner = banner(pkg);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    AppLog.w("continue watching banner: " + e.getMessage());
                }
            }
            Integer bannerColor = banner == null ? null : colorOf(banner);
            BitmapShader bannerShader = banner == null ? null
                    : new BitmapShader(banner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            synchronized (cache) {
                pending.remove(key);
                if (closed) return;
                if (b != null) {
                    shaders.put(b, shader);
                    cache.put(key, b);
                    colors.put(key, color);
                } else {
                    failed.put(key, System.currentTimeMillis());
                }
                if (banner != null && cache.get(bannerKey) == null) {
                    shaders.put(banner, bannerShader);
                    cache.put(bannerKey, banner);
                    colors.put(bannerKey, bannerColor);
                }
            }
            main.post(() -> { if (!closed) onLoaded.run(); });   // the picture, or the banner in its place
        });
        return null;
    }

    private Bitmap load(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable picture");
        int cw = pw, ch = ph, bw = bounds.outWidth, bh = bounds.outHeight;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the card's, or while it's still too many pixels (either side too big).
        for (int s = 1; bw / (s * 2) >= cw && bh / (s * 2) >= ch || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IOException("unreadable picture");
        // Centre crop to 16:9.
        int sw = src.getWidth(), sh = src.getHeight();
        Rect from = (long) sw * ch > (long) sh * cw ? new Rect((sw - sh * cw / ch) / 2, 0, (sw + sh * cw / ch) / 2, sh)
                : new Rect(0, (sh - sw * ch / cw) / 2, sw, (sh + sw * ch / cw) / 2);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    /**
     * One colour for the whole picture, worked out once here in the background: the average of a tiny copy, with its
     * saturation lifted (a plain average comes out greyish) and its brightness kept in the middle.
     */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** The app's TV banner (or its icon) on a dark card, as the shelf shows apps without a logo. */
    private Bitmap banner(String pkg) throws IOException {
        PackageManager pm = app.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("no app " + pkg);
        }
        int cw = pw, ch = ph;
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF15161C);
        // TV banners are 16:9 already: edge to edge; an icon goes in the middle.
        boolean wide = d.getIntrinsicWidth() > d.getIntrinsicHeight();
        int s = Math.round(ch * 0.5f);
        if (wide) d.setBounds(0, 0, cw, ch);
        else d.setBounds((cw - s) / 2, (ch - s) / 2, (cw + s) / 2, (ch + s) / 2);
        d.draw(c);
        return out;
    }

    /** content:// and android.resource:// come from the app's provider; https:// through NetPicture's checks. */
    private byte[] read(Uri uri) throws IOException {
        if ("https".equalsIgnoreCase(uri.getScheme())) return NetPicture.get(uri.toString());
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return NetPicture.readAll(in, MAX_BYTES, System.currentTimeMillis() + 12_000);
        }
    }
}
