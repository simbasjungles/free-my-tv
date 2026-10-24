package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.icu.text.PluralRules;
import android.os.Build;

import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Android 8 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    /**
     * The language in use: "en" or one with a catalogue in assets/i18n (pt, es, de, fr, zh-CN…), picked in the settings
     * or, with "auto", the TV's own (English when there is no catalogue for it).
     */
    static volatile String language = "en";
    /** Bumped when the language changes: text cached by the screens is made again. */
    static volatile int generation;
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        // Typeface.create(family, weight, italic) is Android 9+; Android 8 only has regular and bold.
        if (Build.VERSION.SDK_INT >= 28) return Weighted.create(weight);
        return Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Only this class touches the Android 9 call, so Ui itself still verifies on Android 8 (like Recording.Node). */
    private static final class Weighted {
        static Typeface create(int weight) {
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    // MARK: - Text in the viewer's language

    /*
     * Each language is a catalogue, assets/i18n/<lang>.json, keyed by the English text in the code: Ui.tr("Live TV").
     * A key "context|Text" is "Text" where a language needs another word (falls back to the plain "Text", then to the
     * English). Text built at runtime is a pattern with named placeholders: Ui.trf("{n} min ago", "n", 12).
     * tools/translate.py keeps the catalogues in step with the code. The active catalogue is one HashMap, swapped
     * whole when the language changes, so a lookup is a get() that allocates nothing.
     */
    private static volatile Map<String, String> catalogue = Collections.emptyMap();
    private static volatile Locale locale = Locale.US;
    private static volatile PluralRules plurals = PluralRules.forLocale(Locale.US);
    private static volatile String[] languages;
    private static final Map<String, String> CONTEXT_FREE = new ConcurrentHashMap<>();
    /** The usual country for each language's dates and numbers, when the TV itself is in another language. */
    private static final String[][] REGIONS = {
        {"pt", "BR"}, {"es", "ES"}, {"de", "DE"}, {"fr", "FR"}, {"it", "IT"}, {"nl", "NL"}, {"pl", "PL"}, {"sv", "SE"},
        {"da", "DK"}, {"nb", "NO"}, {"fi", "FI"}, {"cs", "CZ"}, {"ro", "RO"}, {"tr", "TR"}, {"ru", "RU"}, {"uk", "UA"},
        {"el", "GR"}, {"ja", "JP"}, {"ko", "KR"}, {"hi", "IN"}, {"id", "ID"}, {"vi", "VN"}, {"th", "TH"},
    };

    /** The text for this English key in the current language (the English itself when there is none). */
    static String tr(String english) {
        String s = catalogue.get(english);
        if (s != null) return s;
        if (english.indexOf('|') < 0) return english;
        String plain = CONTEXT_FREE.get(english);
        if (plain == null) {
            plain = english.substring(english.indexOf('|') + 1);
            CONTEXT_FREE.put(english, plain);
        }
        s = catalogue.get(plain);
        return s != null ? s : plain;
    }

    /** A pattern with named placeholders, filled from name/value pairs: trf("{n} min ago", "n", 12). */
    static String trf(String english, Object... nameValue) {
        String t = tr(english);
        int open = t.indexOf('{');
        if (open < 0) return t;
        StringBuilder b = new StringBuilder(t.length() + 24);
        int from = 0;
        while (open >= 0) {
            int close = t.indexOf('}', open);
            if (close < 0) break;
            b.append(t, from, open);
            boolean found = false;
            for (int i = 0; i + 1 < nameValue.length; i += 2) {
                String name = (String) nameValue[i];
                if (name.length() == close - open - 1 && t.regionMatches(open + 1, name, 0, name.length())) {
                    b.append(nameValue[i + 1]);
                    found = true;
                    break;
                }
            }
            if (!found) b.append(t, open, close + 1);
            from = close + 1;
            open = t.indexOf('{', from);
        }
        return b.append(t, from, t.length()).toString();
    }

    /**
     * A count with its word in the right plural form: plural("plural|one:{n} season|other:{n} seasons", 3). Each
     * catalogue gives one form per plural category its language uses (Unicode CLDR: zero, one, two, few, many, other),
     * so Russian has "one:{n} сезон|few:{n} сезона|many:{n} сезонов" and Japanese just "other:{n}シーズン". The
     * category for n comes from ICU's rules for the current locale; a missing one falls back to "other", then to the last.
     */
    static String plural(String key, long n) {
        String forms = tr(key), category = plurals.select(n), chosen = null, other = null, last = forms;
        int from = 0;
        while (from <= forms.length()) {
            int bar = forms.indexOf('|', from);
            if (bar < 0) bar = forms.length();
            String form = forms.substring(from, bar);
            int colon = form.indexOf(':');
            String name = colon < 0 ? "" : form.substring(0, colon).trim();
            last = colon < 0 ? form : form.substring(colon + 1);
            if (name.equals(category)) chosen = last;
            if (name.equals("other")) other = last;
            from = bar + 1;
        }
        String text = chosen != null ? chosen : other != null ? other : last;
        return text.replace("{n}", String.valueOf(n));
    }

    /** Dates and numbers in the current language (the TV's own country when it is in that language). */
    static Locale locale() {
        return locale;
    }

    /** "en" and every language with a catalogue, sorted by code. */
    static String[] languages() {
        String[] l = languages;
        if (l != null) return l;
        List<String> found = new ArrayList<>();
        found.add("en");
        Context c = Prefs.context();
        try {
            String[] files = c == null ? null : c.getAssets().list("i18n");
            for (String f : files == null ? new String[0] : files) {
                if (f.endsWith(".json") && !f.equals("en.json")) found.add(f.substring(0, f.length() - 5));
            }
        } catch (IOException e) {
            AppLog.w("i18n: " + e.getMessage());
        }
        Collections.sort(found);
        l = found.toArray(new String[0]);
        if (c != null) languages = l;
        return l;
    }

    /** The language's own name, capitalized: "Português", "Deutsch", "Français", "日本語". */
    static String languageName(String code) {
        // Both Chinese catalogues are "中文" to Java: the script tells them apart.
        if (code.equalsIgnoreCase("zh-CN")) return "简体中文";
        if (code.equalsIgnoreCase("zh-TW")) return "繁體中文";
        Locale l = Locale.forLanguageTag(code);
        String name = l.getDisplayLanguage(l);
        if (name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    static synchronized void applyLanguage() {
        applyLanguage(Prefs.language());
    }

    /** The same for a setting ("auto", "fr"…) given by the caller (SleepOverlay gets it in its Intent). */
    static synchronized void applyLanguage(String setting) {
        String lang = resolve(setting);
        Map<String, String> cat = Collections.emptyMap();
        if (!lang.equals("en")) {
            cat = load(lang);
            if (cat == null) {
                lang = "en";
                cat = Collections.emptyMap();
            }
        }
        catalogue = cat;
        locale = localeOf(lang);
        plurals = PluralRules.forLocale(locale);
        language = lang;
        DATE_PATTERNS.clear();
        generation++;
    }

    /** The catalogue for a setting ("auto", "fr", "pt-BR"…), or for the TV's language with "auto"; else "en". */
    private static String resolve(String setting) {
        if (!setting.equals("auto")) {
            String code = match(setting.replace('_', '-'));
            if (code != null) return code;
        }
        Locale tv = Locale.getDefault();
        String lang = base(tv.getLanguage());
        // Taiwan, Hong Kong and Macau write Traditional Chinese: never the Simplified catalogue there.
        if (lang.equals("zh") && ("Hant".equals(tv.getScript()) || "TW".equals(tv.getCountry())
                || "HK".equals(tv.getCountry()) || "MO".equals(tv.getCountry()))) {
            for (String code : languages()) if (code.equalsIgnoreCase("zh-TW")) return code;
            return "en";
        }
        String code = match(lang);
        return code != null ? code : "en";
    }

    private static String match(String tag) {
        String[] all = languages();
        for (String code : all) if (code.equalsIgnoreCase(tag)) return code;
        int dash = tag.indexOf('-');
        String lang = base(dash < 0 ? tag : tag.substring(0, dash));
        for (String code : all) {
            int d = code.indexOf('-');
            if ((d < 0 ? code : code.substring(0, d)).equalsIgnoreCase(lang)) return code;
        }
        return null;
    }

    /** Java's old codes for Indonesian and Hebrew, and Norwegian, as the catalogues name them. */
    private static String base(String lang) {
        lang = lang.toLowerCase(Locale.ROOT);
        return lang.equals("in") ? "id" : lang.equals("iw") ? "he" : lang.equals("no") || lang.equals("nn") ? "nb" : lang;
    }

    private static Locale localeOf(String lang) {
        if (lang.equals("en")) return Locale.US;
        if (lang.equals("pt")) return new Locale("pt", "BR");
        if (lang.indexOf('-') > 0) return Locale.forLanguageTag(lang);
        Locale tv = Locale.getDefault();
        if (lang.equals(base(tv.getLanguage()))) return tv;   // the TV's own Spanish (es-MX, es-AR…), German (de-AT)…
        for (String[] r : REGIONS) if (r[0].equals(lang)) return new Locale(lang, r[1]);
        return new Locale(lang);
    }

    private static Map<String, String> load(String lang) {
        Context c = Prefs.context();
        if (c == null) return null;
        try (InputStream in = c.getAssets().open("i18n/" + lang + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            JSONObject o = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            HashMap<String, String> map = new HashMap<>(o.length() * 2);
            for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, o.getString(key));
            }
            return map;
        } catch (Exception e) {
            AppLog.w("i18n " + lang + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Largest 2w×2h step `downscale` takes (about 10 MB). */
    private static final long SMOOTH_STEP_PIXELS = 2_600_000;

    /**
     * Filtered down to w × h without jagged edges: bilinear filtering reads only 2×2 pixels, so no step shrinks more
     * than 2×. Halves while it can; when over 1.5× is left, goes up to 2w × 2h and halves exactly, else one last step.
     * Never recycles src; may return it (when it already has the size).
     */
    static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        if ((2 * b.getWidth() > 3 * w || 2 * b.getHeight() > 3 * h) && 4L * w * h <= SMOOTH_STEP_PIXELS) {
            Bitmap twice = Bitmap.createScaledBitmap(b, 2 * w, 2 * h, true);
            if (b != src) b.recycle();
            b = twice;
        }
        if (b.getWidth() == w && b.getHeight() == h) return b;
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    static Shader gradient(int height, int top, int bottom) {
        int n = Math.max(2, height);
        int[] px = new int[4 * n];
        for (int y = 0; y < n; y++) {
            float t = y / (float) (n - 1);
            for (int x = 0; x < 4; x++) {
                float d = (BAYER[(y & 3) * 4 + x] + 0.5f) / 16f;
                int c = 0;
                for (int s = 0; s < 32; s += 8) {
                    float a = (top >>> s) & 255, b = (bottom >>> s) & 255;
                    c |= Math.min(255, (int) (a + (b - a) * t + d)) << s;
                }
                px[y * 4 + x] = c;
            }
        }
        Bitmap b = Bitmap.createBitmap(px, 4, n, Bitmap.Config.ARGB_8888);
        return new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
    }

    private static final Map<String, DatePattern> DATE_PATTERNS = new HashMap<>();

    /**
     * The catalogue's SimpleDateFormat pattern for a "format|..." key and its locale, checked once per language: a pattern
     * SimpleDateFormat rejects falls back to the English one, so a bad catalogue can't crash the screens drawing the date.
     */
    static synchronized DatePattern datePattern(String key) {
        DatePattern pattern = DATE_PATTERNS.get(key);
        if (pattern == null) {
            String text = tr(key);
            Locale loc = locale();
            try {
                new SimpleDateFormat(text, loc);
            } catch (IllegalArgumentException e) {
                AppLog.w("i18n " + language + ": invalid date pattern for " + key);
                text = key.substring("format|".length());
                loc = Locale.US;
            }
            pattern = new DatePattern(text, loc);
            DATE_PATTERNS.put(key, pattern);
        }
        return pattern;
    }

    static final class DatePattern {
        final String text;
        final Locale locale;

        DatePattern(String text, Locale locale) {
            this.text = text;
            this.locale = locale;
        }

        String format(long at) {
            return new SimpleDateFormat(text, locale).format(new Date(at));
        }
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return Ui.datePattern("format|h:mm").format(now);
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return once("time", at, () -> Ui.datePattern("format|h:mm a").format(at));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    static String date(long now) {
        return Ui.datePattern("format|EEEE, MMMM d").format(now);
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
