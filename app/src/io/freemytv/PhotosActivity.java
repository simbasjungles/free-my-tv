package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Photos (Home Settings > My Photos > OK): every photo in a grid, in the screensaver's order. On a photo, OK opens
 * what can be done with it: in the screensaver or not, behind the home screen, move it, see it whole, remove it. The
 * row above holds how long each one shows, whether they're shuffled, the home screen's background and Remove all. The
 * choices are Photos' (choices.txt). Thumbnails are small (RGB_565, at most about 12 MB of them), decoded one at a
 * time in the background, and all of it goes when the screen closes.
 */
public class PhotosActivity extends Activity {
    private PhotosView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new PhotosView(this);
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Leaving (Home, another app): closed, so its thumbnails don't stay in memory behind. */
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    static final class PhotosView extends View {
        private static final int COLS = 5, VISIBLE_ROWS = 3;
        private static final float CELL_W = 328, CELL_H = 184.5f, GAP_X = 28, GAP_Y = 34, GRID_TOP = 322, OPTIONS_Y = 178;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;
        /** The options row's buttons. */
        private static final int O_SLIDE = 0, O_ORDER = 1, O_HOME = 2, O_REMOVE = 3, OPTIONS = 4;
        /** The menu on a photo. */
        private static final int M_SAVER = 0, M_HOME = 1, M_MOVE = 2, M_VIEW = 3, M_REMOVE = 4, MENU = 5;

        private final Activity activity;
        private final float k;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final LruCache<String, Thumb> thumbs = new LruCache<String, Thumb>(12 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Thumb t) { return t.bitmap.getByteCount(); }
            @Override protected void entryRemoved(boolean evicted, String key, Thumb old, Thumb now) {
                if (old != null && old != now) old.bitmap.recycle();
            }
        };
        private final Set<String> loading = new HashSet<>();

        private List<File> photos = new ArrayList<>();
        /** -1: the options row; else the photo's index. */
        private int focus;
        private int option;
        private int scrollRow;
        private boolean menuOpen;
        private int menuFocus;
        /** Moving the focused photo: the order before (Back puts it back). */
        private List<File> moveFrom;
        private Bitmap whole;
        private boolean viewing;
        private boolean released;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint background = new Paint();
        private final Paint title, count, optionLabel, optionValue, hint, badge, menuLabel, menuValue, empty;
        private final RectF cell = new RectF(), rect = new RectF();
        private final Matrix matrix = new Matrix();

        PhotosView(Activity a) {
            super(a);
            activity = a;
            k = getResources().getDisplayMetrics().widthPixels / 1920f;
            setFocusable(true);
            title = Ui.text(Ui.displaySemibold, 54 * k, 0xFFFFFFFF);
            count = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
            optionLabel = Ui.text(Ui.regular, 21 * k, 0x99FFFFFF);
            optionValue = Ui.text(Ui.semibold, 26 * k, 0xF2FFFFFF);
            hint = Ui.text(Ui.regular, 24 * k, 0x8CFFFFFF);
            badge = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
            menuLabel = Ui.text(Ui.medium, 28 * k, 0xF2FFFFFF);
            menuValue = Ui.text(Ui.regular, 26 * k, 0xB3FFFFFF);
            menuValue.setTextAlign(Paint.Align.RIGHT);
            empty = Ui.text(Ui.regular, 30 * k, 0xB3FFFFFF);
            empty.setTextAlign(Paint.Align.CENTER);
            ring.setStyle(Paint.Style.STROKE);
            reload();
            focus = photos.isEmpty() ? -1 : 0;
        }

        private Context ctx() {
            return getContext();
        }

        private void reload() {
            photos = new ArrayList<>(Photos.files(ctx()));
            if (focus >= photos.size()) focus = photos.size() - 1;
        }

        void release() {
            released = true;
            worker.shutdownNow();
            thumbs.evictAll();
            if (whole != null) whole.recycle();
            whole = null;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            background.setShader(new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0C, Shader.TileMode.CLAMP));
        }

        // MARK: - Thumbnails

        private static final class Thumb {
            final Bitmap bitmap;
            final BitmapShader shader;

            Thumb(Bitmap b) {
                bitmap = b;
                shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
        }

        /** The thumbnail if it's ready; else it's asked for (once) and drawn when it comes. */
        private Thumb thumb(File f) {
            String key = f.getName();
            Thumb t = thumbs.get(key);
            if (t != null || loading.contains(key) || released) return t;
            loading.add(key);
            final int w = Math.round(CELL_W * 1.1f * k), h = Math.round(CELL_H * 1.1f * k);
            worker.execute(() -> {
                Bitmap b = decodeCover(f, w, h);
                main.post(() -> {
                    loading.remove(key);
                    if (released) {
                        if (b != null) b.recycle();
                        return;
                    }
                    if (b != null) thumbs.put(key, new Thumb(b));
                    invalidate();
                });
            });
            return null;
        }

        /** `f` scaled to cover w × h (the cell crops it), in RGB_565: a third of the memory, and no alpha to keep. */
        private static Bitmap decodeCover(File f, int w, int h) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                if (bounds.outWidth <= 0) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = 1;
                // Halved while it still covers w × h; the last step is a scale (createScaledBitmap)
                while (bounds.outWidth / (o.inSampleSize * 2) >= w && bounds.outHeight / (o.inSampleSize * 2) >= h) {
                    o.inSampleSize *= 2;
                }
                Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
                if (src == null) return null;
                float s = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
                if (s >= 0.95f) return src;
                Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)),
                        Math.max(1, Math.round(src.getHeight() * s)), true);
                if (out != src) src.recycle();
                return out;
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("photos: thumbnail of " + f.getName() + ": " + e.getMessage());
                return null;
            }
        }

        /** The photo whole, on screen (View). */
        private void showWhole(File f) {
            viewing = true;
            final int w = getWidth(), h = getHeight();
            worker.execute(() -> {
                Bitmap b = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), bounds);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = bounds.outWidth >= 2 * w && bounds.outHeight >= 2 * h ? 2 : 1;
                    b = BitmapFactory.decodeFile(f.getPath(), o);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("photos: " + f.getName() + ": " + e.getMessage());
                }
                final Bitmap done = b;
                main.post(() -> {
                    if (released || !viewing) {
                        if (done != null) done.recycle();
                        return;
                    }
                    whole = done;
                    if (done == null) viewing = false;
                    invalidate();
                });
            });
            invalidate();
        }

        private void closeWhole() {
            viewing = false;
            if (whole != null) whole.recycle();
            whole = null;
            invalidate();
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), background);
            if (viewing) {
                drawWhole(c);
                return;
            }
            float left = left();
            c.drawText(Ui.tr("My Photos"), left, 118 * k, title);
            c.drawText(Ui.plural("plural|one:{n} photo|other:{n} photos", photos.size()),
                    left + title.measureText(Ui.tr("My Photos")) + 28 * k, 118 * k, count);
            drawOptions(c, left);
            if (photos.isEmpty()) {
                String none = Ui.tr("Send them from a computer: see “Your own photos” in the README.");
                fit(empty, none, 30 * k, getWidth() - 2 * left);
                c.drawText(none, getWidth() / 2f, 620 * k, empty);
            }
            File home = Photos.homePhoto(ctx());
            int first = scrollRow * COLS, last = Math.min(photos.size(), (scrollRow + VISIBLE_ROWS) * COLS);
            for (int i = first; i < last; i++) if (i != focus) drawPhoto(c, i, home);
            if (focus >= first && focus < last) drawPhoto(c, focus, home);
            if (menuOpen) drawMenu(c);
            String h = hintText();
            fit(hint, h, 24 * k, getWidth() - 2 * left);
            c.drawText(h, left, 1040 * k, hint);
            hint.setTextSize(24 * k);
        }

        private float left() {
            return (1920 - (COLS * CELL_W + (COLS - 1) * GAP_X)) / 2 * k;
        }

        private String[] optionTexts() {
            int s = Photos.slideSeconds(ctx());
            String slide = s < 60 ? Ui.trf("{n} s", "n", s) : Ui.trf("{n} min", "n", s / 60);
            String home = Photos.homeOff(ctx()) ? Ui.tr("Off")
                    : Photos.homePhoto(ctx()) != null ? Ui.tr("This photo") : Ui.tr("Every hour");
            return new String[] {
                    Ui.tr("Each photo"), slide,
                    Ui.tr("Order"), Photos.shuffled(ctx()) ? Ui.tr("Shuffled") : Ui.tr("In order"),
                    Ui.tr("Home background"), home,
                    "", Ui.tr("Remove all")};
        }

        private void drawOptions(Canvas c, float left) {
            String[] t = optionTexts();
            // All in one row: in a longer language (or a wider font) the buttons and their text get smaller together.
            optionLabel.setTextSize(21 * k);
            optionValue.setTextSize(26 * k);
            float natural = 0, gaps = (OPTIONS - 1) * 20 * k, room = getWidth() - 2 * left;
            for (int i = 0; i < OPTIONS; i++) {
                natural += Math.max(optionLabel.measureText(t[i * 2]), optionValue.measureText(t[i * 2 + 1])) + 64 * k;
            }
            float shrink = natural + gaps > room ? (room - gaps) / natural : 1;
            optionLabel.setTextSize(21 * k * shrink);
            optionValue.setTextSize(26 * k * shrink);
            float x = left, top = OPTIONS_Y * k, h = 92 * k, inset = 32 * k * shrink;
            for (int i = 0; i < OPTIONS; i++) {
                String label = t[i * 2], value = t[i * 2 + 1];
                float w = Math.max(optionLabel.measureText(label), optionValue.measureText(value)) + 2 * inset;
                rect.set(x, top, x + w, top + h);
                boolean on = focus < 0 && option == i;
                fill.setColor(on ? 0xFFF5F5F7 : 0x1FFFFFFF);
                c.drawRoundRect(rect, 22 * k, 22 * k, fill);
                optionLabel.setColor(on ? 0x991C1C1E : 0x99FFFFFF);
                optionValue.setColor(on ? INK : 0xF2FFFFFF);
                if (label.isEmpty()) {
                    c.drawText(value, x + inset, rect.centerY() + Ui.halfCap(optionValue), optionValue);
                } else {
                    c.drawText(label, x + inset, top + 36 * k, optionLabel);
                    c.drawText(value, x + inset, top + 72 * k, optionValue);
                }
                x += w + 20 * k;
            }
            optionLabel.setTextSize(21 * k);
            optionValue.setTextSize(26 * k);
        }

        private void cellRect(int i, RectF out) {
            int row = i / COLS - scrollRow, col = i % COLS;
            float x = left() + col * (CELL_W + GAP_X) * k, y = (GRID_TOP + row * (CELL_H + GAP_Y)) * k;
            out.set(x, y, x + CELL_W * k, y + CELL_H * k);
        }

        private void drawPhoto(Canvas c, int i, File home) {
            File f = photos.get(i);
            cellRect(i, cell);
            boolean focused = i == focus && !menuOpen;
            boolean moving = i == focus && moveFrom != null;
            if (i == focus) {
                float grow = 0.06f;
                cell.inset(-cell.width() * grow / 2, -cell.height() * grow / 2);
            }
            float r = 16 * k;
            boolean out = !Photos.inSaver(ctx(), f);
            Thumb t = thumb(f);
            if (t == null) {
                fill.setColor(0x14FFFFFF);
                c.drawRoundRect(cell, r, r, fill);
            } else {
                float s = Math.max(cell.width() / t.bitmap.getWidth(), cell.height() / t.bitmap.getHeight());
                matrix.setScale(s, s);
                matrix.postTranslate(cell.centerX() - t.bitmap.getWidth() * s / 2, cell.centerY() - t.bitmap.getHeight() * s / 2);
                t.shader.setLocalMatrix(matrix);
                imagePaint.setShader(t.shader);
                imagePaint.setAlpha(out ? 90 : 255);
                c.drawRoundRect(cell, r, r, imagePaint);
                imagePaint.setShader(null);
            }
            // Its place in the screensaver's order, and a house on the home screen's own.
            String place = "#" + (i + 1);
            float bw = badge.measureText(place) + 24 * k;
            rect.set(cell.left + 12 * k, cell.bottom - 48 * k, cell.left + 12 * k + bw, cell.bottom - 12 * k);
            fill.setColor(0x99000000);
            c.drawRoundRect(rect, 18 * k, 18 * k, fill);
            c.drawText(place, rect.left + 12 * k, rect.centerY() + Ui.halfCap(badge), badge);
            if (f.equals(home)) {
                rect.set(cell.right - 60 * k, cell.top + 12 * k, cell.right - 12 * k, cell.top + 60 * k);
                fill.setColor(0xCC000000);
                c.drawRoundRect(rect, 24 * k, 24 * k, fill);
                Symbols.draw(ctx(), c, "home", rect.centerX(), rect.centerY(), 28 * k, 0xFFFFFFFF);
            }
            if (out) {
                rect.set(cell.right - (f.equals(home) ? 120 : 60) * k, cell.top + 12 * k,
                        cell.right - (f.equals(home) ? 72 : 12) * k, cell.top + 60 * k);
                fill.setColor(0xCC000000);
                c.drawRoundRect(rect, 24 * k, 24 * k, fill);
                Symbols.draw(ctx(), c, "bedtime", rect.centerX(), rect.centerY(), 26 * k, 0x80FFFFFF);
            }
            if (focused || moving) {
                ring.setStrokeWidth(5 * k);
                ring.setColor(moving ? AMBER : 0xFFFFFFFF);
                rect.set(cell);
                rect.inset(-7 * k, -7 * k);
                c.drawRoundRect(rect, r + 7 * k, r + 7 * k, ring);
            }
        }

        private String[] menuTexts() {
            File f = photos.get(focus);
            return new String[] {
                    Ui.tr("In the screensaver"), Photos.inSaver(ctx(), f) ? Ui.tr("On") : Ui.tr("Off"),
                    Ui.tr("Home background"), f.equals(Photos.homePhoto(ctx())) ? Ui.tr("On") : Ui.tr("Off"),
                    Ui.tr("Move"), "",
                    Ui.tr("View"), "",
                    Ui.tr("Remove"), ""};
        }

        private void drawMenu(Canvas c) {
            fill.setColor(0x99000000);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            String[] t = menuTexts();
            // As wide as its longest row needs (label, a gap, value), within the screen
            float widest = 0;
            for (int i = 0; i < MENU; i++) {
                widest = Math.max(widest, menuLabel.measureText(t[i * 2]) + (t[i * 2 + 1].isEmpty() ? 0 : 48 * k + menuValue.measureText(t[i * 2 + 1])));
            }
            float rowH = 84 * k, pad = 18 * k, w = Math.min(getWidth() - 160 * k, Math.max(620 * k, widest + 2 * pad + 56 * k));
            float x = (getWidth() - w) / 2f, y = (getHeight() - MENU * rowH - 2 * pad) / 2f;
            rect.set(x, y, x + w, y + MENU * rowH + 2 * pad);
            fill.setColor(0xF2262321);
            c.drawRoundRect(rect, 30 * k, 30 * k, fill);
            for (int i = 0; i < MENU; i++) {
                float top = y + pad + i * rowH;
                boolean on = i == menuFocus;
                if (on) {
                    rect.set(x + pad, top + 4 * k, x + w - pad, top + rowH - 4 * k);
                    fill.setColor(0xFFF5F5F7);
                    c.drawRoundRect(rect, 20 * k, 20 * k, fill);
                }
                menuLabel.setColor(on ? INK : i == M_REMOVE ? 0xFFFF8A80 : 0xF2FFFFFF);
                menuValue.setColor(on ? 0xB31C1C1E : 0xB3FFFFFF);
                float base = top + rowH / 2 + Ui.halfCap(menuLabel);
                float valueW = t[i * 2 + 1].isEmpty() ? 0 : menuValue.measureText(t[i * 2 + 1]) + 48 * k;
                fit(menuLabel, t[i * 2], 28 * k, w - 2 * pad - 56 * k - valueW);
                c.drawText(t[i * 2], x + pad + 28 * k, base, menuLabel);
                menuLabel.setTextSize(28 * k);
                if (!t[i * 2 + 1].isEmpty()) c.drawText(t[i * 2 + 1], x + w - pad - 28 * k, base, menuValue);
            }
        }

        private void drawWhole(Canvas c) {
            fill.setColor(0xFF000000);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            if (whole == null) return;
            float s = Math.min((float) getWidth() / whole.getWidth(), (float) getHeight() / whole.getHeight());
            float w = whole.getWidth() * s, h = whole.getHeight() * s;
            rect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            c.drawBitmap(whole, null, rect, imagePaint);
        }

        private String hintText() {
            if (moveFrom != null) return Ui.tr("← → to move · OK to drop · Back to cancel");
            if (menuOpen) return Ui.tr("OK picks, Back cancels.");
            if (focus < 0) return Ui.tr("OK or the arrows change the value.");
            return Ui.tr("OK: what to do with this photo. Back: done.");
        }

        /** `p` at `size`, or smaller so `text` fits in `max` (a longer language, a wider font). */
        private static void fit(Paint p, String text, float size, float max) {
            p.setTextSize(size);
            float w = p.measureText(text);
            if (w > max && w > 0) p.setTextSize(size * max / w);
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (viewing) {
                // Closed by the next key pressed, not by letting go of the OK that opened it (that closed it at once).
                if (code == KeyEvent.KEYCODE_HOME) return false;
                if (down && e.getRepeatCount() == 0) closeWhole();
                return true;
            }
            if (!down) return ok || code == KeyEvent.KEYCODE_BACK;
            if (ok && e.getRepeatCount() > 0) return true;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (moveFrom != null) {
                    File moving = photos.get(focus);
                    photos = moveFrom;
                    focus = photos.indexOf(moving);
                    moveFrom = null;
                    follow();
                } else if (menuOpen) {
                    menuOpen = false;
                } else {
                    activity.finish();
                }
                invalidate();
                return true;
            }
            if (moveFrom != null) return moveKey(code, ok);
            if (menuOpen) return menuKey(code, ok);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus < 0) option = Math.max(0, option - 1);
                    else if (focus % COLS > 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus < 0) option = Math.min(OPTIONS - 1, option + 1);
                    else if (focus % COLS < COLS - 1 && focus + 1 < photos.size()) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (focus >= COLS) focus -= COLS;
                    else focus = -1;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (focus < 0 && !photos.isEmpty()) focus = 0;
                    else if (focus + COLS < photos.size()) focus += COLS;
                    else if (focus / COLS < (photos.size() - 1) / COLS) focus = photos.size() - 1;
                    break;
                default:
                    if (!ok) return false;
                    if (focus < 0) changeOption();
                    else {
                        menuOpen = true;
                        menuFocus = 0;
                    }
            }
            follow();
            invalidate();
            return true;
        }

        /** Scrolls so the focused photo's row shows. */
        private void follow() {
            if (focus < 0) {
                scrollRow = 0;
                return;
            }
            int row = focus / COLS;
            if (row < scrollRow) scrollRow = row;
            if (row >= scrollRow + VISIBLE_ROWS) scrollRow = row - VISIBLE_ROWS + 1;
        }

        private void changeOption() {
            Context c = ctx();
            switch (option) {
                case O_SLIDE: {
                    int now = Photos.slideSeconds(c), next = Photos.SLIDES[0];
                    for (int i = 0; i < Photos.SLIDES.length; i++) {
                        if (Photos.SLIDES[i] == now) next = Photos.SLIDES[(i + 1) % Photos.SLIDES.length];
                    }
                    Photos.setSlideSeconds(c, next);
                    break;
                }
                case O_ORDER:
                    Photos.setShuffled(c, !Photos.shuffled(c));
                    break;
                case O_HOME:
                    // Every hour → Off → Every hour; a chosen photo goes back to every hour first.
                    if (Photos.homePhoto(c) != null) Photos.setHomePhoto(c, null);
                    else Photos.setHomeOff(c, !Photos.homeOff(c));
                    break;
                case O_REMOVE:
                    askRemove(null);
                    break;
            }
        }

        private boolean menuKey(int code, boolean ok) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) menuFocus = Math.max(0, menuFocus - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) menuFocus = Math.min(MENU - 1, menuFocus + 1);
            else if (ok) {
                Context c = ctx();
                File f = photos.get(focus);
                switch (menuFocus) {
                    case M_SAVER:
                        Photos.setInSaver(c, f, !Photos.inSaver(c, f));
                        break;
                    case M_HOME:
                        Photos.setHomePhoto(c, f.equals(Photos.homePhoto(c)) ? null : f);
                        break;
                    case M_MOVE:
                        menuOpen = false;
                        moveFrom = new ArrayList<>(photos);
                        break;
                    case M_VIEW:
                        menuOpen = false;
                        showWhole(f);
                        break;
                    case M_REMOVE:
                        menuOpen = false;
                        askRemove(f);
                        break;
                }
            } else if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            invalidate();
            return true;
        }

        private boolean moveKey(int code, boolean ok) {
            int to = focus;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: to = focus - 1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: to = focus + 1; break;
                case KeyEvent.KEYCODE_DPAD_UP: to = focus - COLS; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: to = focus + COLS; break;
                default:
                    if (!ok) return false;
                    moveFrom = null;
                    Photos.setOrder(ctx(), photos);
                    invalidate();
                    return true;
            }
            to = Math.max(0, Math.min(photos.size() - 1, to));
            if (to != focus) {
                File f = photos.remove(focus);
                photos.add(to, f);
                focus = to;
                follow();
            }
            invalidate();
            return true;
        }

        /** Asks before removing one photo (or all of them, with null). */
        private void askRemove(File f) {
            String question = f != null ? Ui.tr("Remove this photo?")
                    : Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", photos.size());
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(question)
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        if (f != null) {
                            thumbs.remove(f.getName());
                            Photos.remove(ctx(), f);
                        } else {
                            thumbs.evictAll();
                            Photos.removeAll(ctx());
                        }
                        reload();
                        if (photos.isEmpty()) focus = -1;
                        follow();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps them
        }
    }
}
