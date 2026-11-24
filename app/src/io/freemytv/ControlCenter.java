package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Control Center: smoked-glass pills over the blurred home screen (Sleep, Wi-Fi, Bluetooth, Inputs, Settings and
 * four round buttons). Inputs opens a tray in the same space, with the inputs to pick from; Wi-Fi and Bluetooth open
 * the TV's own screens (nothing here changes the network). HomeView draws it on a layer of its own and sends it the keys.
 *
 * Every number below is in the 1920 × 1080 space and comes from measuring a screenshot of the tvOS Launcher's own
 * control center at that size (grid, radii, type sizes, icon sizes, fill and rim colors).
 */
final class ControlCenter {
    interface Host {
        boolean night();
        void settings();
        void allApps();
        void liveTv();
        /** The theme was switched: change it now, and blur the screen again. */
        void themeChanged();
        void open(HomeApps.Item item);
        /** Something moved without a key: draw again. */
        void repaint();
    }

    // The grid: two columns of 242 px with 16 px between, rows of 116 px; the round buttons 100 px apart by 133.3.
    private static final float LEFT = 1330, COL = 258, TILE_W = 242, ROW_H = 116, ROW = 132, ROUND = 100, WIDTH = 500;
    private static final float TOP = 152, ROUND_TOP = 552, RADIUS = 50, FOCUS = 1.05f;
    /** The trays reuse the whole area: rows of 84 px, 12 apart, five in view. */
    private static final float TRAY_H = 84, TRAY_PITCH = 96, TRAY_VISIBLE = 5;
    /** Where the time sits against the tiles: its baseline is 54 px above the first row. */
    private static final float TIME_BASELINE = 98;
    private static final int GRID = 0, INPUTS = 1;

    private final Activity activity;
    private final Host host;
    private final float k;
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Tile> rows = new ArrayList<>();
    private final Spring shown = new Spring(0.3f, 0.85f, 0), trayT = new Spring(0.3f, 0.85f, 0), scroll = new Spring(0.3f, 0.85f, 0);
    private final RectF button = new RectF(), r = new RectF(), full = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<Integer, Shader> shaders = new HashMap<>();
    private Bitmap backdrop;
    private boolean backdropNight;
    private final Spring tint = new Spring(0.25f, 0.9f, 0);
    private boolean open;
    private int focus, mode, rowFocus;
    private float dy;

    private static final class Tile {
        String label, sub, symbol;
        float iconBox;
        final RectF rect = new RectF();
        final Spring pop = new Spring(0.28f, 0.85f, 0);
        boolean round, big, on, dim;
        String key;
        Runnable run;
    }

    ControlCenter(Activity a, Host host, float k) {
        this.activity = a;
        this.host = host;
        this.k = k;
        text.setTypeface(Ui.semibold);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2 * k);
    }

    boolean visible() {
        return open || shown.value > 0.001f;
    }

    /** `from` is the top button that opens it: the time lines up with it, and it stays sharp and white over the blur. */
    void open(RectF from) {
        button.set(from);
        dy = from.centerY() + 10.5f * k - TIME_BASELINE * k;
        mode = GRID;
        trayT.snap(0);
        tiles.clear();
        Context c = activity;
        add(Ui.tr("Sleep"), null, "power", 72, true, false, () -> HomeApps.sleep(activity,
                () -> activity.runOnUiThread(() -> toast(Ui.tr("Couldn't put the TV to sleep.")))));
        add(Ui.tr("Wi-Fi"), wifi(c), "cc_wifi", 37, false, false, () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        add(Ui.tr("Bluetooth"), bluetooth(c), "cc_bluetooth", 38, false, false, () -> launch(bluetoothScreen()));
        add(Ui.tr("Inputs"), null, "login", 36, false, false, this::openInputs);
        add(Ui.tr("Settings"), null, "settings", 38, false, false, () -> { close(); host.settings(); });
        place(0, LEFT, TOP, TILE_W, 2 * ROW_H + (ROW - ROW_H));
        place(1, LEFT + COL, TOP, TILE_W, ROW_H);
        place(2, LEFT + COL, TOP + ROW, TILE_W, ROW_H);
        place(3, LEFT, TOP + 2 * ROW, TILE_W, ROW_H);
        place(4, LEFT + COL, TOP + 2 * ROW, TILE_W, ROW_H);
        // Round buttons: voice, cast (or all apps, where the TV has no cast screen), dark mode, live TV.
        float step = (COL + TILE_W - ROUND) / 3;
        place(add(Ui.tr("Voice"), null, "mic", 40, false, true, () -> launch(new Intent(Intent.ACTION_ASSIST))), LEFT, ROUND_TOP, ROUND, ROUND);
        Intent cast = new Intent(Settings.ACTION_CAST_SETTINGS);
        Tile slot = cast.resolveActivity(activity.getPackageManager()) != null
                ? add(Ui.tr("Cast"), null, "cast", 38, false, true, () -> launch(cast))
                : add(Ui.tr("All Apps"), null, "apps", 36, false, true, () -> { close(); host.allApps(); });
        place(slot, LEFT + step, ROUND_TOP, ROUND, ROUND);
        Tile dark = add(Ui.tr("Dark mode"), null, "bedtime", 38, false, true, () -> {
            Prefs.setTheme(host.night() ? "light" : "dark");
            host.themeChanged();
        });
        dark.on = host.night();
        place(dark, LEFT + 2 * step, ROUND_TOP, ROUND, ROUND);
        place(add(Ui.tr("Live TV"), null, "live_tv", 42, false, true, () -> { close(); host.liveTv(); }), LEFT + 3 * step, ROUND_TOP, ROUND, ROUND);
        focus = 0;
        open = true;
        for (Tile t : tiles) t.pop.snap(0);
    }

    void close() {
        open = false;
    }

    /** The home screen, shrunk and blurred, that everything floats on (set by HomeView before opening). */
    void setBackdrop(Bitmap small, boolean night) {
        backdrop = small;
        backdropNight = night;
        tint.snap(night ? 1 : 0);
    }

    /** The theme changed after the screen was copied: tint the copy toward the new one until a new copy arrives. */
    void themeSwitched(boolean night) {
        tint.target = night ? 1 : 0;
    }

    private Tile add(String label, String sub, String symbol, float iconBox, boolean big, boolean round, Runnable run) {
        Tile t = new Tile();
        t.label = label;
        t.sub = sub;
        t.symbol = symbol;
        t.iconBox = iconBox;
        t.big = big;
        t.round = round;
        t.run = run;
        tiles.add(t);
        return t;
    }

    private void place(int i, float x, float y, float w, float h) {
        place(tiles.get(i), x, y, w, h);
    }

    private void place(Tile t, float x, float y, float w, float h) {
        t.rect.set(x * k, y * k + dy, (x + w) * k, (y + h) * k + dy);
    }

    // MARK: - Inputs tray

    private void openTray(int which) {
        mode = which;
        rowFocus = 0;
        scroll.snap(0);
        rows.clear();
        trayT.target = 1;
    }

    private void backToGrid() {
        mode = GRID;
        trayT.target = 0;
    }

    private Tile row(String label, String sub, String symbol, Runnable run) {
        Tile t = new Tile();
        t.key = label;
        t.label = label;
        t.sub = sub;
        t.symbol = symbol;
        t.iconBox = 34;
        t.run = run;
        rows.add(t);
        return t;
    }

    private void openInputs() {
        openTray(INPUTS);
        for (HomeApps.Item it : HomeApps.allInputs(activity)) {
            Tile t = row(it.label, it.kind == HomeApps.LIVE_TV ? Ui.tr("Antenna, cable") : it.connected ? Ui.tr("Connected") : Ui.tr("No signal"),
                    it.kind == HomeApps.LIVE_TV ? "live_tv" : "hdmi", () -> { close(); host.open(it); });
            t.dim = it.kind == HomeApps.INPUT && !it.connected;
        }
        if (rows.isEmpty()) row(Ui.tr("No inputs found"), null, "hdmi", () -> { }).dim = true;
    }

    // MARK: - Drawing

    /** Advances the animations; true while something moves. */
    boolean step(float dt) {
        shown.target = open ? 1 : 0;
        boolean moving = shown.step(dt) | trayT.step(dt) | tint.step(dt);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.pop.target = open && mode == GRID && i == focus ? 1 : 0;
            moving |= t.pop.step(dt);
        }
        for (int i = 0; i < rows.size(); i++) {
            Tile t = rows.get(i);
            t.pop.target = open && mode != GRID && i == rowFocus ? 1 : 0;
            moving |= t.pop.step(dt);
        }
        float last = Math.max(0, rows.size() - TRAY_VISIBLE);
        scroll.target = Math.max(0, Math.min(last, Math.max(rowFocus - (TRAY_VISIBLE - 1), Math.min(scroll.target, rowFocus))));
        moving |= scroll.step(dt);
        return moving;
    }

    void draw(Canvas c, float night, float w, float h) {
        float a = Ui.clamp(shown.value);
        if (a <= 0.004f || tiles.isEmpty()) return;
        if (backdrop != null) {
            photo.setAlpha(Math.round(255 * a));
            full.set(0, 0, w, h);
            c.drawBitmap(backdrop, null, full, photo);
            float shift = tint.value - (backdropNight ? 1 : 0);
            if (Math.abs(shift) > 0.01f) {
                fill.setColor(Ui.alpha(shift > 0 ? 0xFF000000 : 0xFFFFFFFF, Math.abs(shift) * (shift > 0 ? 0.6f : 0.45f) * a));
                c.drawRect(0, 0, w, h, fill);
            }
        }
        fill.setShader(null);
        fill.setColor(Ui.alpha(night > 0.5f ? 0xFF000000 : 0xFF1C1C1E, (backdrop != null ? 0.42f - 0.2f * night : 0.55f) * a));
        c.drawRect(0, 0, w, h, fill);
        // The time, and the button that opened it, sharp over the blur.
        label(c, Ui.time(System.currentTimeMillis()), LEFT * k + 13 * k, button.centerY() + 10.5f * k, Ui.alpha(0xFFFFFFFF, a), 29 * k, Paint.Align.LEFT);
        fill.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawCircle(button.centerX(), button.centerY(), button.width() / 2, fill);
        glyph(c, "tune", button.centerX(), button.centerY(), 30 * k, Ui.alpha(0xFF1C1C1E, a));
        float t = Ui.clamp(trayT.value);
        if (t < 0.99f) {
            for (int i = 0; i < tiles.size(); i++) {
                Tile tile = tiles.get(i);
                if (tile.symbol.equals("bedtime")) tile.on = host.night();
                drawTile(c, tile, a * (1 - t), RADIUS * k, false);
            }
        }
        if (t > 0.01f) {
            int save = c.save();
            c.clipRect(LEFT * k - 20 * k, TOP * k + dy - 16 * k, (LEFT + WIDTH) * k + 20 * k, (TOP + TRAY_VISIBLE * TRAY_PITCH) * k + dy);
            for (int i = 0; i < rows.size(); i++) {
                Tile row = rows.get(i);
                float y = (TOP + (i - scroll.value) * TRAY_PITCH) * k + dy;
                if (y < TOP * k + dy - TRAY_PITCH * k || y > (TOP + TRAY_VISIBLE * TRAY_PITCH) * k + dy) continue;
                row.rect.set(LEFT * k, y, (LEFT + WIDTH) * k, y + TRAY_H * k);
                drawTile(c, row, a * t, TRAY_H / 2 * k, true);
            }
            c.restoreToCount(save);
        }
    }

    private void drawTile(Canvas c, Tile t, float a, float rad, boolean tray) {
        if (a <= 0.004f) return;
        float f = Ui.clamp(t.pop.value), g = 1 + (tray ? 0.03f : FOCUS - 1) * f;
        if (t.round) rad = t.rect.width() / 2;
        float white = Math.max(f, !tray && t.on ? 1 : 0);
        int save = c.save();
        c.scale(g, g, t.rect.centerX(), t.rect.centerY());
        c.translate(t.rect.left, t.rect.top);   // drawn at the origin: one gradient serves every pill of a height
        float pw = t.rect.width(), ph = t.rect.height();
        // Smoked glass: a dark fill that lightens at the bottom edge, and a lighter rim.
        fill.setShader(shader(Math.round(ph)));
        fill.setAlpha(Math.round(255 * a * (1 - white)));
        r.set(0, 0, pw, ph);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        if (white < 0.99f) {
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.3f * a * (1 - white)));
            r.set(k, k, pw - k, ph - k);
            c.drawRoundRect(r, rad - k, rad - k, rim);
        }
        if (white > 0.01f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, a * white));
            fill.setShadowLayer(26 * k, 0, 10 * k, Ui.alpha(0xFF000000, 0.35f * a * white));
            r.set(0, 0, pw, ph);
            c.drawRoundRect(r, rad, rad, fill);
            fill.clearShadowLayer();
        }
        int ink = Ui.alpha(white > 0.5f ? 0xFF1C1C1E : 0xFFFFFFFF, a * (t.dim ? 0.45f : 1));
        float cx = pw / 2, cy = ph / 2;
        if (t.round) {
            glyph(c, t.symbol, cx, cy, t.iconBox * k, ink);
        } else if (t.big) {
            glyph(c, t.symbol, cx, cy - 28 * k, t.iconBox * k, ink);
            label(c, t.label, cx, cy + 62 * k, ink, 25 * k, Paint.Align.CENTER);
        } else {
            float iconX = tray ? 46 * k : 49.5f * k, textX = tray ? 88 * k : 92 * k;
            glyph(c, t.symbol, iconX, cy + 0.5f * k, (tray ? 30 : t.iconBox) * k, ink);
            boolean two = t.sub != null;
            float shift = tray ? 2 : 8;
            label(c, ellipsize(t.label, pw - textX - 24 * k, 24 * k), textX, cy + (two ? -shift : 9) * k, ink, 24 * k, Paint.Align.LEFT);
            if (two) label(c, t.sub, textX, cy + (tray ? 24 : 25) * k, Ui.alpha(ink, 0.6f), 21 * k, Paint.Align.LEFT);
        }
        c.restoreToCount(save);
    }

    /** The pill's fill for a height: dark, with a lighter glow in the last few pixels, like the original's glass. */
    private Shader shader(int h) {
        Shader s = shaders.get(h);
        if (s == null) {
            float glow = Math.min(0.16f, 16 * k / h);
            s = new LinearGradient(0, 0, 0, h, new int[] {0xD11E1E20, 0xD11E1E20, 0xE0484A4D},
                    new float[] {0, 1 - glow, 1}, Shader.TileMode.CLAMP);
            shaders.put(h, s);
        }
        return s;
    }

    private String ellipsize(String s, float width, float size) {
        text.setTextSize(size);
        return android.text.TextUtils.ellipsize(s, new android.text.TextPaint(text), width, android.text.TextUtils.TruncateAt.END).toString();
    }

    /** An icon whose longest side is `box` pixels, centered on (cx, cy). */
    private void glyph(Canvas c, String name, float cx, float cy, float box, int color) {
        Bitmap b = Symbols.get(activity, name);
        if (b == null) return;
        Symbols.draw(activity, c, name, cx, cy, box * 144f / Math.max(b.getWidth(), b.getHeight()), color);
    }

    private void label(Canvas c, String s, float x, float y, int color, float size, Paint.Align align) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    // MARK: - Keys

    /** Handles a key while it is up (HomeView sends every key here). */
    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int dx = 0, dyKey = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dyKey = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dyKey = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    if (mode == GRID) tiles.get(focus).run.run();
                    else if (rowFocus < rows.size()) rows.get(rowFocus).run.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (mode != GRID) backToGrid();
                else close();
                return true;
            default:
                return true;
        }
        if (mode != GRID) {
            if (dyKey != 0) rowFocus = Math.max(0, Math.min(rows.size() - 1, rowFocus + dyKey));
            else if (dx < 0) backToGrid();
            return true;
        }
        RectF from = tiles.get(focus).rect;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            if (i == focus) continue;
            RectF o = tiles.get(i).rect;
            float ahead = dx != 0 ? (o.centerX() - from.centerX()) * dx : (o.centerY() - from.centerY()) * dyKey;
            if (ahead <= 1) continue;
            float side = dx != 0 ? Math.abs(o.centerY() - from.centerY()) : Math.abs(o.centerX() - from.centerX());
            float score = ahead + side * 2;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        if (best >= 0) focus = best;
        return true;
    }

    /** The TV's Bluetooth screen, or its settings home where it has none: Free My TV doesn't change the network or pair anything. */
    private Intent bluetoothScreen() {
        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        return i.resolveActivity(activity.getPackageManager()) != null ? i : new Intent(Settings.ACTION_SETTINGS);
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
            close();
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(Ui.tr("Not available on this TV."));
        }
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    private static String wifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm == null || cm.getActiveNetwork() == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Ui.tr("Ethernet");
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? Ui.tr("Connected") : Ui.tr("Not connected");
    }

    private static String bluetooth(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "bluetooth_on", 0) == 1 ? Ui.tr("On") : Ui.tr("Off");
    }
}
