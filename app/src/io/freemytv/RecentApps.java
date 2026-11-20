package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recent Apps: the apps that are open, as big cards over the blurred home screen. ← → pick one, OK opens it, ▲ closes
 * it (the TV's own ADB stops the app) and ▼ or Back leave.
 * HomeView draws it on a layer of its own and sends it the keys. The numbers are in the 1920 × 1080 space and come
 * from measuring the tvOS Launcher's switcher.
 */
final class RecentApps {
    interface Host {
        void open(HomeApps.Item item);
        /** Something changed without a key (the list arrived, an app was closed): draw again. */
        void repaint();
    }

    private static final float CARD_W = 670, CARD_H = 377, CENTER_X = 960, CENTER_Y = 560, GLOW = 340;
    /** Where a card's left edge sits at 0, 1, 2 and 3 places from the focused one (the next ones tuck under their neighbour). */
    private static final float[] LEFT = {625, 1035, 1625, 2125};
    private static final float[] SCALE = {1, 0.88f, 0.78f, 0.7f};
    private static final Pattern SIZE = Pattern.compile("sz=(\\d+)");
    /**
     * The task's affinity ("A=10087:com.netflix.ninja"; Android 8 to 10 write it without the uid, and Android 12 and 13
     * as "10087-si:" for a singleInstance activity, such as Kodi's) or its intent ("I=").
     */
    private static final Pattern PACKAGE = Pattern.compile("(?:A=(?:\\d+(?:-si)?:)?|I=)([\\w.]+)");

    private final Activity activity;
    private final Host host;
    private final float k;
    private final List<Card> cards = new ArrayList<>();
    private final Spring shown = new Spring(0.3f, 0.85f, 0), scroll = new Spring(0.3f, 0.82f, 0);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            photo = new Paint(Paint.FILTER_BITMAP_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), full = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap backdrop;
    private boolean open, loading;
    private int focus;
    private String message;
    /** Each open() starts a load; only the newest one's list is kept. */
    private int generation;

    private static final class Card {
        HomeApps.Item item;
        Paint paint;                 // the card's art as a shader, made once
        int glow;                    // the art's average color
        RadialGradient glowShader;   // the glow behind it, made with the paint
        boolean stoppable;           // ▲ may close it (HomeMode.mayStop, not an accessibility service's app or the keyboard, nor neededByTv)
        final Spring gone = new Spring(0.28f, 0.9f, 0);
        boolean closing;
    }

    RecentApps(Activity a, Host host, float k) {
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

    /** Open, not fading out: the keys and TalkBack are its own (once closed, the home screen's again). */
    boolean isOpen() {
        return open;
    }

    /** Opaque over the whole screen: what is below needn't be drawn. */
    boolean covering() {
        return open && shown.value > 0.999f && backdrop != null;
    }

    void open() {
        cards.clear();
        focus = 0;
        scroll.snap(0);
        loading = true;
        message = null;
        open = true;
        final int gen = ++generation;
        new Thread(() -> load(gen), "recent-apps").start();
    }

    void close() {
        open = false;
    }

    /** Gone at once (another app came to the front): back home, the switcher isn't seen fading out. */
    void closeNow() {
        open = false;
        shown.snap(0);
    }

    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
    }

    // MARK: - Data

    /** How many cards there are, and what TalkBack reads for one. */
    int count() {
        return open ? cards.size() : 0;
    }

    /** What shows (and TalkBack reads) while there are no cards: loading, nothing open, or why it couldn't read them. */
    String emptyText() {
        return message != null ? message : loading ? Ui.tr("Looking for open apps…") : Ui.tr("No apps are open.");
    }

    /** Where that message is, for TalkBack. */
    void emptyBounds(RectF out) {
        out.set((CENTER_X - 500) * k, (CENTER_Y - 40) * k, (CENTER_X + 500) * k, (CENTER_Y + 20) * k);
    }

    int focus() {
        return focus;
    }

    void setFocus(int i) {
        if (i >= 0 && i < cards.size()) focus = i;
    }

    String spoken(int i) {
        if (i >= cards.size()) return "";
        Card c = cards.get(i);
        return c.item.label + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", cards.size())
                + (c.stoppable ? ". " + Ui.tr("Press ▲ on an app to close it") : "");
    }

    /** OK on the focused card (TalkBack's click too). */
    void openFocused() {
        if (focus >= cards.size()) return;
        HomeApps.Item item = cards.get(focus).item;
        close();
        host.open(item);
    }

    /** The open apps, newest first: the tasks Android keeps (with an activity, or with a process still running). */
    private void load(int gen) {
        List<Card> found = new ArrayList<>();
        String error = null;
        try (Adb adb = Adb.connect(activity.getApplicationContext(), 10_000, null)) {
            String recents = adb.shell("dumpsys activity recents");
            String ps = adb.shell("ps -A -o NAME");
            // Android turns a stopped app's accessibility service off for good, and the keyboard is the only way to type.
            Set<String> keep = new HashSet<>();
            for (String s : adb.shell("settings get secure enabled_accessibility_services").split(":")) keep.add(owner(s));
            keep.add(owner(adb.shell("settings get secure default_input_method")));
            Set<String> alive = new HashSet<>();
            for (String line : ps.split("\n")) alive.add(line.trim());
            Map<String, String> launchable = new HashMap<>();
            for (String[] app : HomeApps.launchable(activity)) launchable.put(app[0], app[1]);
            Set<String> seen = new HashSet<>();
            for (String line : recents.split("\n")) {
                if (!line.contains("Recent #")) continue;
                Matcher pkg = PACKAGE.matcher(line);
                if (!pkg.find()) continue;
                String name = pkg.group(1);
                // The affinity can be longer than the package (YouTube's task is "com.google.android.youtube.tv.main").
                while (!launchable.containsKey(name) && name.indexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'));
                Matcher sz = SIZE.matcher(line);
                boolean hasActivity = sz.find() && Integer.parseInt(sz.group(1)) > 0;
                boolean running = alive.contains(name);
                if (!(hasActivity || running) || name.equals(activity.getPackageName()) || !launchable.containsKey(name) || HomeApps.isHidden(name) || !seen.add(name)) continue;
                Card c = new Card();
                c.item = HomeApps.recentItem(activity, name, launchable.get(name));
                c.stoppable = HomeMode.mayStop(name) && !keep.contains(name);
                found.add(c);
            }
            for (Card c : found) shade(c);
            // As before anything goes off: what the TV's inputs or the remote's Input key need stays (a force-stopped
            // app gets nothing until it's opened again). Not checked: none of them closes this time.
            Set<String> closable = new HashSet<>();
            for (Card c : found) if (c.stoppable) closable.add(c.item.pkg);
            Set<String> needed;
            try {
                needed = closable.isEmpty() ? closable : HomeMode.neededByTv(adb, closable);
            } catch (IOException e) {
                needed = null;
            }
            for (Card c : found) if (needed == null || needed.contains(c.item.pkg)) c.stoppable = false;
        } catch (IOException | RuntimeException e) {   // a crash here would take the home screen down (and count for the watchdog)
            AppLog.w("recent apps: " + e);
            found.clear();
            error = Ui.tr("Couldn't read the recent apps. Allow USB debugging for Free My TV.");
        }
        final String shownError = error;
        activity.runOnUiThread(() -> {
            if (gen != generation) return;   // opened again since: the newer load answers
            loading = false;
            message = shownError;
            cards.clear();
            cards.addAll(found);
            focus = 0;
            scroll.snap(0);
            host.repaint();
        });
    }

    /** The card's art as a shader (drawn rounded), and its average color for the glow behind it. */
    private void shade(Card c) {
        Bitmap art = c.item.tile;
        if (art == null) return;
        c.paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.paint.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        Bitmap tiny = Bitmap.createScaledBitmap(art, 1, 1, true);
        c.glow = tiny.getPixel(0, 0) | 0xFF000000;
        if (tiny != art) tiny.recycle();
        c.glowShader = new RadialGradient(0, 0, GLOW * k, new int[] {c.glow, c.glow & 0x00FFFFFF}, new float[] {0.15f, 1}, Shader.TileMode.CLAMP);
    }

    /** "com.app/.Service" or "com.app/com.app.Ime" → "com.app"; empty for "null" or nothing. */
    private static String owner(String component) {
        String s = component.trim();
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : "";
    }

    boolean stoppable(int i) {
        return i < cards.size() && cards.get(i).stoppable;
    }

    void closeFocused() {
        if (focus >= cards.size()) return;
        final Card c = cards.get(focus);
        if (c.closing) return;
        final String pkg = c.item.pkg;
        if (!c.stoppable) return;
        c.closing = true;
        c.gone.target = 1;
        new Thread(() -> {
            try (Adb adb = Adb.connect(activity.getApplicationContext(), 10_000, null)) {
                adb.shell("am force-stop " + pkg);
            } catch (IOException | RuntimeException e) {
                AppLog.w("close app: " + e);
            }
            try { Thread.sleep(900); } catch (InterruptedException ignored) { }
            activity.runOnUiThread(host::repaint);   // the card has gone by then: TalkBack reads the ones left
        }, "close-app").start();
    }

    // MARK: - Drawing

    boolean step(float dt) {
        shown.target = open ? 1 : 0;
        boolean moving = shown.step(dt);
        scroll.target = focus;
        moving |= scroll.step(dt);
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            moving |= c.gone.step(dt);
            if (c.closing && c.gone.value > 0.97f) {
                cards.remove(i);
                if (focus >= cards.size()) focus = Math.max(0, cards.size() - 1);
                scroll.target = focus;
                moving = true;
            }
        }
        return moving;   // still while the list loads: repaint() draws it when it comes
    }

    void draw(Canvas c, float w, float h) {
        float a = Ui.clamp(shown.value);
        if (a <= 0.004f) return;
        if (backdrop != null) {
            photo.setAlpha(Math.round(255 * a));
            full.set(0, 0, w, h);
            c.drawBitmap(backdrop, null, full, photo);
        }
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, (backdrop != null ? 0.55f : 0.85f) * a));
        c.drawRect(0, 0, w, h, fill);
        label(c, Ui.tr("Recent Apps"), 108 * k, 108 * k, Ui.alpha(0xFFFFFFFF, a), 37 * k, Paint.Align.LEFT);
        if (cards.isEmpty()) {
            label(c, emptyText(), CENTER_X * k, CENTER_Y * k, Ui.alpha(0xFFFFFFFF, 0.6f * a), 28 * k, Paint.Align.CENTER);
            return;
        }
        // Far cards first, the focused one on top.
        float at = scroll.value;
        int count = cards.size();
        int near = Math.round(at);
        for (int dist = 3; dist >= 0; dist--) {
            for (int side = 1; side >= -1; side -= 2) {
                int i = near + side * dist;
                if (i < 0 || i >= count || (dist == 0 && side == -1)) continue;
                drawCard(c, cards.get(i), i - at, a);
            }
        }
        if (focus < count && cards.get(focus).stoppable) label(c, Ui.tr("Press ▲ on an app to close it"), CENTER_X * k, 975 * k, Ui.alpha(0xFFFFFFFF, 0.55f * a), 22 * k, Paint.Align.CENTER);
    }

    /** Where card `i` is on screen now (TalkBack). */
    boolean bounds(int i, RectF out) {
        if (!open || i >= cards.size()) return false;
        place(cards.get(i), i - scroll.value, out);
        return true;
    }

    /** Card `d` places from the focused one (negative: to its left), into `out`; returns its scale. */
    private float place(Card card, float d, RectF out) {
        float ad = Math.abs(d);
        float s = interpolate(SCALE, ad), w = CARD_W * s, h = CARD_H * s;
        float left = interpolate(LEFT, ad);
        float x0 = d >= 0 ? left : 1920 - (left + w);   // to the left they mirror
        float top = CENTER_Y - h / 2 - 260 * Ui.clamp(card.gone.value);   // a closing card rises
        out.set(x0 * k, top * k, (x0 + w) * k, (top + h) * k);
        return s;
    }

    private void drawCard(Canvas c, Card card, float d, float a) {
        float ad = Math.abs(d);
        float s = place(card, d, r);
        float alpha = a * (1 - Ui.clamp(card.gone.value));
        float rad = 30 * k * s;
        if (ad < 0.5f && card.glowShader != null) {   // the app's color, soft, behind the focused card
            float gl = (1 - ad * 2) * alpha;
            fill.setShader(card.glowShader);
            fill.setAlpha(Math.round(255 * 0.75f * gl));
            c.save();
            c.translate(r.left + 40 * k, r.centerY());
            c.drawCircle(0, 0, GLOW * k, fill);
            c.restore();
            fill.setShader(null);
        }
        fill.setColor(Ui.alpha(0xFF000000, 0.35f * alpha));
        r.offset(0, 10 * k);
        c.drawRoundRect(r, rad, rad, fill);
        r.offset(0, -10 * k);
        if (card.paint != null) {
            float sx = r.width() / card.item.tile.getWidth(), sy = r.height() / card.item.tile.getHeight();
            matrix.setScale(sx, sy);
            matrix.postTranslate(r.left, r.top);
            card.paint.getShader().setLocalMatrix(matrix);
            card.paint.setAlpha(Math.round(255 * alpha));
            c.drawRoundRect(r, rad, rad, card.paint);
        } else {
            fill.setColor(Ui.alpha(0xFF2C2C2E, alpha));
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (ad < 0.5f) {
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.5f * alpha * (1 - ad * 2)));
            c.drawRoundRect(r, rad, rad, rim);
        }
        label(c, card.item.label, r.left + 12 * k, r.top - 16 * k, Ui.alpha(0xFFFFFFFF, 0.9f * alpha), 22 * k, Paint.Align.LEFT);
    }

    private static float interpolate(float[] table, float at) {
        int i = Math.min(table.length - 2, (int) at);
        float t = Math.min(1, at - i);
        return table[i] + (table[i + 1] - table[i]) * t;
    }

    private void label(Canvas c, String s, float x, float y, int color, float size, Paint.Align align) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    // MARK: - Keys

    boolean key(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && focus > 0) focus--;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && focus < cards.size() - 1) focus++;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && e.getRepeatCount() == 0) closeFocused();   // one app a press: held, it went on to the next
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) openFocused();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_APP_SWITCH:
                if (down) close();
                return true;
            default:
                return false;   // volume, mute, media and the TV's own keys go on to the system, as on the home screen
        }
    }
}
