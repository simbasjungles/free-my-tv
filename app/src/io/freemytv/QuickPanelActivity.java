package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.List;

/**
 * The quick settings panel a TCL remote's Settings button can open in Free My TV mode (a Remote Buttons choice; TV
 * Settings is the default), over whatever is playing (Google TV's
 * own panel lives in its home app, which is off in our mode): the time, and tiles for the inputs, the TV's picture and
 * sound panels, network, remotes and accessories, accessibility and all settings. Each tile opens the TV's own screen;
 * Inputs lists them right here. Back, or the Settings button again, closes it.
 * Drawn as liquid glass: TVs turn window blur off (and video plays on its own plane, under any blur), so the glass is
 * painted, nearly opaque so it reads over anything: the home screen's look (white with dark ink by day, smoked with
 * white ink at night, Settings > Theme), faint light inside, bright rims, and one focus lens that flows between the tiles.
 */
public class QuickPanelActivity extends Activity {
    /** A tile: its label, icon, accent color and the screens it may open, the first one the TV has wins. */
    private static final class Tile {
        final String label, symbol;
        final int color;
        final Intent[] intents;

        Tile(String label, String symbol, int color, Intent... intents) {
            this.label = label;
            this.symbol = symbol;
            this.color = color;
            this.intents = intents;
        }
    }

    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final int INPUTS = 0;
    /** The panel in a 1920×1080 space: its size and the margin to the screen's corner. */
    private static final float W = 600, H = 800, MARGIN = 40;

    private PanelView view;
    private Click click;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        click = new Click(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(MARGIN * k);
        lp.y = Math.round(MARGIN * k);
        lp.width = Math.round(W * k);
        lp.height = Math.round(H * k);
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        overridePendingTransition(0, 0);   // the panel springs in by itself
        view = new PanelView(this, k, tiles());
        setContentView(view);
    }

    private Tile[] tiles() {
        return new Tile[] {
            new Tile(Ui.tr("Inputs"), "hdmi", 0xFF30B0C7),
            new Tile(Ui.tr("Display"), "tv_settings", 0xFFFF9F0A,
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            new Tile(Ui.tr("Sound"), "speaker", 0xFFFF375F,
                    new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_SOUND_SETTINGS)),
            new Tile(Ui.tr("Network"), "wifi", 0xFF0A84FF, new Intent(Settings.ACTION_WIFI_SETTINGS)),
            new Tile(Ui.tr("Bluetooth"), "bluetooth", 0xFF3F7CFF,
                    new Intent("com.android.tv.settings.DEVICES").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            new Tile(Ui.tr("Accessibility"), "accessibility", 0xFF30D158, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            new Tile(Ui.tr("Settings"), "settings", 0xFF8E8E9A, new Intent(Settings.ACTION_SETTINGS)),
        };
    }

    /** The Settings button again (RemoteKeys starts this activity a second time): closes the panel. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.close();
    }

    /** Leaving for another app or the home screen closes it, like Google TV's panel. */
    @Override protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        click.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean open(Intent... intents) {
        HomeMode.holdHome(this, 6_000);   // Fire TV: TV Settings is the launcher's own window
        for (Intent intent : intents) {
            try {
                startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("quick panel: couldn't open " + intent + ": " + e.getMessage());
            }
        }
        return false;
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private final class PanelView extends View {
        private static final float PAD = 40, GAP = 14, TILE_H = 132, GRID_TOP = 184, ROW_H = 76, ROW_GAP = 10,
                CARD_R = 46, TILE_R = 30, CHIP_R = 25, SYMBOL = 24;
        private static final int ROWS_VISIBLE = 6;
        /** TalkBack's ids: a tile is its index + 1, an Inputs row ROW_IDS + its index + 1. */
        private static final int ROW_IDS = 1000;

        private final float k;
        private final Tile[] tiles;
        private List<HomeApps.Item> inputs;   // non-null: the Inputs list is showing (or fading out)
        private boolean inputsOpen, closing;
        private int focus, inputFocus;

        // Motion: the panel coming in, the focus lens (position and size), a press, the grid / Inputs list swap.
        private final Spring show = new Spring(0.5f, 0.78f, 0);
        private final Spring lensX = new Spring(0.36f, 0.72f, 0), lensY = new Spring(0.36f, 0.72f, 0);
        private final Spring lensW = new Spring(0.36f, 0.8f, 0), lensH = new Spring(0.36f, 0.8f, 0);
        private final Spring press = new Spring(0.22f, 0.6f, 0);
        private final Spring page = new Spring(0.4f, 0.9f, 0);
        private long lastFrame;

        // The home screen's look: white glass with dark ink by day, smoked glass with white ink at night.
        private final boolean night;
        private final int ink, focusInk, tileFill;
        private final String inputsTitle = Ui.tr("Inputs"), noInputs = Ui.tr("No inputs");
        private String dateText = "", clockText = "";
        private float clockBearing;
        private final Spoken spoken;

        private final Paint date, clock, label, rowLabel, title, empty;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint layer = new Paint(), atop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), card = new RectF();
        private LinearGradient tint, sheen, cardRim, tileRim, lens;
        private RadialGradient[] glows;
        private final float[] glowX = {0.2f, 0.95f, 0.3f}, glowY = {0.15f, 0.55f, 0.95f};
        // Each chip color's gradient, made once and looked up by color (no boxing in onDraw).
        private final int[] chipColor = new int[16];
        private final LinearGradient[] chipShader = new LinearGradient[16];
        private int chips;
        private final float[] tileX = new float[8], tileY = new float[8], labelSize = new float[8];   // labelSize: shrunk to fit long words
        private float tileW;

        private final Runnable frame = new Runnable() {
            @Override public void run() {
                invalidate();
            }
        };
        /** Once a minute: the date and time, formatted here rather than every frame. */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                dateText = Ui.date(now);
                clockText = Ui.clock(now);
                clockBearing = Ui.leftBearing(clock, clockText);
                invalidate();
                postDelayed(this, 60_000 - now % 60_000);
            }
        };

        PanelView(Context c, float k, Tile[] tiles) {
            super(c);
            this.k = k;
            this.tiles = tiles;
            night = HomeData.get(c).isNight(System.currentTimeMillis());
            ink = night ? 0xFFFFFFFF : 0xFF1D1D1F;
            focusInk = night ? 0xFF1D1D1F : 0xFFFFFFFF;
            tileFill = night ? 0x17FFFFFF : 0x0D000000;
            date = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.8f));
            clock = Ui.numbers(Ui.displaySemibold, 80 * k, ink);
            label = Ui.text(Ui.semibold, 24 * k, ink);
            rowLabel = Ui.text(Ui.semibold, 26 * k, ink);
            title = Ui.text(Ui.semibold, 30 * k, ink);
            empty = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.7f));
            rim.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (!inputsOpen) {
                        for (int i = 0; i < tiles.length; i++) out.add(i + 1);
                        return;
                    }
                    int first = firstRow();
                    for (int i = first; i < Math.min(inputs.size(), first + ROWS_VISIBLE); i++) out.add(ROW_IDS + i + 1);
                }
                @Override public String text(int id) {
                    if (id <= ROW_IDS) return id - 1 < tiles.length ? tiles[id - 1].label : "";
                    int i = id - ROW_IDS - 1;
                    if (inputs == null || i >= inputs.size()) return "";
                    return inputs.get(i).label + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", inputs.size());
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (id <= ROW_IDS) {
                        int i = id - 1;
                        if (inputsOpen || i >= tiles.length) return false;
                        out.set(Math.round(tileX[i]), Math.round(tileY[i]), Math.round(tileX[i] + tileW),
                                Math.round(tileY[i] + TILE_H * k));
                        return true;
                    }
                    int i = id - ROW_IDS - 1;
                    if (!inputsOpen || i >= inputs.size()) return false;
                    float y = rowTop(i);
                    out.set(Math.round(PAD * k), Math.round(y), Math.round(getWidth() - PAD * k), Math.round(y + ROW_H * k));
                    return true;
                }
                @Override public int focused() { return inputsOpen ? ROW_IDS + inputFocus + 1 : focus + 1; }
                @Override public void focus(int id) {
                    if (id > ROW_IDS) inputFocus = id - ROW_IDS - 1;
                    else focus = id - 1;
                    aimLens(false);
                    invalidate();
                }
                @Override public void click(int id) { select(); }
            });
            atop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            for (Tile t : tiles) Symbols.warm(c, t.symbol, SYMBOL * k);
            Symbols.warm(c, "tv", SYMBOL * k);
            show.target = 1;
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            card.set(0, 0, w, h);
            // ~95% opaque: with no blur behind it, the glass has to hold the text on its own, over a white or black screen.
            tint = night ? new LinearGradient(0, 0, 0, h, 0xF22C2C30, 0xF71C1C1E, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, h, 0xF7FBFBFD, 0xF2EBEBF0, Shader.TileMode.CLAMP);
            sheen = new LinearGradient(0, 0, 0, h * 0.42f, night ? 0x1FFFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            cardRim = new LinearGradient(0, 0, w, h, new int[] {0xB3FFFFFF, 0x1AFFFFFF, 0x14FFFFFF, 0x66FFFFFF},
                    new float[] {0, 0.35f, 0.65f, 1}, Shader.TileMode.CLAMP);
            tileRim = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0x40FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0x0A000000, 0x1A000000, Shader.TileMode.CLAMP);
            lens = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0xFFFFFFFF, 0xFFE4E8F4, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0xFF3A3A3C, 0xFF1D1D1F, Shader.TileMode.CLAMP);
            // Faint light resting in the glass: it doesn't drift, the panel draws only while something moves.
            float r = 380 * k;
            glows = new RadialGradient[] {
                new RadialGradient(0, 0, r, 0x267A4DFF, 0x007A4DFF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r, 0x2022C4FF, 0x0022C4FF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r * 0.85f, 0x1AFF3D8B, 0x00FF3D8B, Shader.TileMode.CLAMP),
            };
            tileW = (w - 2 * PAD * k - GAP * k) / 2;
            float room = tileW - 44 * k;
            for (int i = 0; i < tiles.length; i++) {
                label.setTextSize(24 * k);
                labelSize[i] = 24 * k * Math.min(1, room / Math.max(1, label.measureText(tiles[i].label)));
            }
            for (int i = 0; i < tiles.length; i++) {
                tileX[i] = PAD * k + (i % 2) * (tileW + GAP * k);
                tileY[i] = GRID_TOP * k + (i / 2) * (TILE_H + GAP) * k;
            }
            aimLens(true);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            tick.run();
            spoken.readAgain();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            removeCallbacks(frame);
            super.onDetachedFromWindow();
        }

        void close() {
            if (closing) return;
            closing = true;
            show.target = 0;
            invalidate();
        }

        /** Points the lens at the focused tile, or the focused row of the Inputs list. */
        private void aimLens(boolean snap) {
            float x, y, w, h;
            if (inputsOpen) {
                x = PAD * k;
                y = rowTop(inputFocus);
                w = getWidth() - 2 * PAD * k;
                h = ROW_H * k;
            } else {
                x = tileX[focus];
                y = tileY[focus];
                w = tileW;
                h = TILE_H * k;
            }
            if (snap) {
                lensX.snap(x);
                lensY.snap(y);
                lensW.snap(w);
                lensH.snap(h);
            } else {
                lensX.target = x;
                lensY.target = y;
                lensW.target = w;
                lensH.target = h;
            }
        }

        /** The first Inputs row on screen: the list scrolls to keep the focused one in view. */
        private int firstRow() {
            return Math.max(0, Math.min(inputFocus - ROWS_VISIBLE + 1, inputs == null ? 0 : inputs.size() - ROWS_VISIBLE));
        }

        private float rowTop(int i) {
            return (GRID_TOP + 58) * k + (i - firstRow()) * (ROW_H + ROW_GAP) * k;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = show.step(dt);
            moving |= lensX.step(dt) | lensY.step(dt) | lensW.step(dt) | lensH.step(dt);
            moving |= press.step(dt) | page.step(dt);
            if (closing && show.value < 0.03f) {
                finish();
                return;
            }
            if (!inputsOpen && page.value < 0.01f) inputs = null;

            float s = Math.max(0, show.value);
            c.save();
            c.translate(-(1 - s) * (getWidth() + MARGIN * k), 0);
            int alpha = Math.round(255 * Math.min(1, s * 1.4f));
            c.saveLayerAlpha(card, alpha);
            drawGlass(c);
            drawHeader(c);
            float p = page.value;
            if (p < 0.999f) drawTiles(c, 1 - p);
            if (inputs != null && p > 0.001f) drawInputs(c, p);
            c.restore();
            c.restore();
            if (moving) {
                postOnAnimation(frame);
            } else {
                lastFrame = 0;   // at rest: the next move starts from one frame, not from the time spent still
            }
        }

        /** Tinted glass with faint light inside, a sheen at the top and a bright rim. */
        private void drawGlass(Canvas c) {
            float r = CARD_R * k;
            c.saveLayer(card, layer);
            fill.setAlpha(255);   // a shader is still scaled by the paint's alpha, which the last tile left low
            fill.setShader(tint);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            float w = card.width(), h = card.height();
            for (int i = 0; i < glows.length; i++) {
                c.save();
                c.translate(glowX[i] * w, glowY[i] * h);
                atop.setShader(glows[i]);
                c.drawCircle(0, 0, 400 * k, atop);
                c.restore();
            }
            atop.setShader(null);
            c.restore();
            fill.setShader(sheen);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.6f * k);
            rim.setShader(cardRim);
            rect.set(card);
            rect.inset(0.8f * k, 0.8f * k);
            c.drawRoundRect(rect, r, r, rim);
            rim.setShader(null);
        }

        private void drawHeader(Canvas c) {
            c.drawText(dateText, PAD * k, (PAD + 24) * k, date);
            c.drawText(clockText, PAD * k - clockBearing, (PAD + 116) * k, clock);
        }

        /** The grid: glass tiles, the lens under the focused one, then each tile's colored chip and label. */
        private void drawTiles(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.translate(0, (1 - a) * -24 * k);
            float r = TILE_R * k;
            for (int i = 0; i < tiles.length; i++) {
                rect.set(tileX[i], tileY[i], tileX[i] + tileW, tileY[i] + TILE_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (!inputsOpen) drawLens(c, r);
            for (int i = 0; i < tiles.length; i++) {
                float on = nearness(tileX[i], tileY[i], tileW);
                float lift = on * (1 - 0.04f * press.value);
                float cx = tileX[i] + tileW / 2, cy = tileY[i] + TILE_H * k / 2;
                c.save();
                c.scale(1 + 0.03f * lift, 1 + 0.03f * lift, cx, cy);
                float chipX = tileX[i] + (22 + CHIP_R) * k, chipY = tileY[i] + (22 + CHIP_R) * k;
                drawChip(c, chipX, chipY, tiles[i].color, tiles[i].symbol, on);
                label.setColor(mix(ink, focusInk, on));
                label.setTextSize(labelSize[i]);
                c.drawText(tiles[i].label, tileX[i] + 22 * k, tileY[i] + TILE_H * k - 24 * k, label);
                c.restore();
            }
            c.restoreToCount(saved);
        }

        /** The Inputs list: a title and one glass row per input, the lens on the focused one. */
        private void drawInputs(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.translate(0, (1 - a) * 24 * k);
            c.drawText(inputsTitle, PAD * k, (GRID_TOP + 30) * k, title);
            if (inputs.isEmpty()) {
                c.drawText(noInputs, PAD * k, (GRID_TOP + 100) * k, empty);
                c.restoreToCount(saved);
                return;
            }
            float r = 24 * k, w = getWidth() - 2 * PAD * k;
            int first = firstRow();
            int last = Math.min(inputs.size(), first + ROWS_VISIBLE);
            for (int i = first; i < last; i++) {
                float y = rowTop(i);
                rect.set(PAD * k, y, PAD * k + w, y + ROW_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (inputsOpen) drawLens(c, r);
            for (int i = first; i < last; i++) {
                HomeApps.Item it = inputs.get(i);
                float y = rowTop(i);
                float on = nearness(PAD * k, y, w);
                float dim = it.connected ? 1 : 0.7f;   // still 4.5:1 on the light glass
                drawChip(c, PAD * k + 46 * k, y + ROW_H * k / 2, it.kind == HomeApps.LIVE_TV ? 0xFFFF9F0A : 0xFF30B0C7,
                        it.symbol != null ? it.symbol : "hdmi", on);
                rowLabel.setColor(Ui.alpha(mix(ink, focusInk, on), Math.max(dim, on)));
                c.drawText(it.label, PAD * k + 92 * k, y + ROW_H * k / 2 + Ui.halfCap(rowLabel), rowLabel);
            }
            c.restoreToCount(saved);
        }

        /** How much the lens covers this tile (1 = resting on it): the label and chip follow it as it flows. */
        private float nearness(float x, float y, float w) {
            float d = Math.abs(lensX.value - x) + Math.abs(lensY.value - y);
            return Math.max(0, 1 - d / (w * 0.6f));
        }

        private void drawRim(Canvas c, RectF r, float radius) {
            c.save();
            c.translate(r.left, r.top);
            rim.setStrokeWidth(1.2f * k);
            rim.setShader(tileRim);
            rect.set(0.6f * k, 0.6f * k, r.width() - 0.6f * k, r.height() - 0.6f * k);
            c.drawRoundRect(rect, radius, radius, rim);
            rim.setShader(null);
            c.restore();
        }

        /**
         * The focus lens: a bright drop of glass that flows to the focused tile, stretching along its way like a
         * liquid and squashing a little when OK is pressed, with a soft glow of the tile's color around it.
         */
        private void drawLens(Canvas c, float radius) {
            float vx = Math.abs(lensX.velocity), vy = Math.abs(lensY.velocity);
            float stretchX = Math.min(0.16f, vx / (9000 * k)), stretchY = Math.min(0.16f, vy / (9000 * k));
            float w = lensW.value * (1 + stretchX - stretchY * 0.5f) * (1 - 0.04f * press.value);
            float h = lensH.value * (1 + stretchY - stretchX * 0.5f) * (1 - 0.04f * press.value);
            float cx = lensX.value + lensW.value / 2, cy = lensY.value + lensH.value / 2;
            int accent = inputsOpen ? 0xFF30B0C7 : tiles[focus].color;
            for (int i = 3; i >= 1; i--) {
                float g = i * 7 * k;
                rect.set(cx - w / 2 - g, cy - h / 2 - g, cx + w / 2 + g, cy + h / 2 + g);
                fill.setColor(Ui.alpha(accent, 0.10f));
                c.drawRoundRect(rect, radius + g, radius + g, fill);
            }
            rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.save();
            c.translate(0, rect.top);
            fill.setAlpha(255);
            fill.setShader(lens);
            rect.offset(0, -rect.top);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.4f * k);
            rim.setColor(night ? 0xFFFFFFFF : 0x33000000);
            c.drawRoundRect(rect, radius, radius, rim);
            c.restore();
        }

        /** A circle in the tile's color (lighter at the top, like lit glass) with its white symbol. */
        private void drawChip(Canvas c, float cx, float cy, int color, String symbol, float on) {
            float r = CHIP_R * k;
            LinearGradient lit = null;
            for (int i = 0; i < chips && lit == null; i++) if (chipColor[i] == color) lit = chipShader[i];
            if (lit == null) {
                lit = new LinearGradient(0, -r, 0, r, mix(color, 0xFFFFFFFF, 0.28f), color, Shader.TileMode.CLAMP);
                if (chips < chipColor.length) {
                    chipColor[chips] = color;
                    chipShader[chips++] = lit;
                }
            }
            c.save();
            c.translate(cx, cy);
            fill.setAlpha(255);
            fill.setShader(lit);
            c.drawCircle(0, 0, r, fill);
            fill.setShader(null);
            c.restore();
            rim.setStrokeWidth(1f * k);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.35f + 0.3f * on));
            c.drawCircle(cx, cy, r - 0.5f * k, rim);
            Symbols.draw(getContext(), c, symbol, cx, cy, SYMBOL * k, 0xFFFFFFFF);
        }

        boolean onKey(KeyEvent e) {
            if (closing) return true;
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (inputsOpen) {
                            inputsOpen = false;
                            page.target = 0;
                            aimLens(false);
                            invalidate();
                            spoken.focusMoved();
                        } else {
                            close();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_NOTIFICATION:
                    if (!down) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(code);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) {
                        press.target = 1;
                        invalidate();
                    } else if (!down) {
                        press.target = 0;
                        invalidate();
                        if (spoken.keyClick()) select();   // TalkBack's click may have come first for this press
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void move(int code) {
            if (inputsOpen) {
                int before = inputFocus;
                if (code == KeyEvent.KEYCODE_DPAD_UP) inputFocus = Math.max(0, inputFocus - 1);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) inputFocus = Math.min(inputs.size() - 1, inputFocus + 1);
                if (inputFocus == before) return;
            } else {
                int before = focus;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && focus % 2 == 1) focus--;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && focus % 2 == 0 && focus + 1 < tiles.length) focus++;
                if (code == KeyEvent.KEYCODE_DPAD_UP && focus >= 2) focus -= 2;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && focus + 2 < tiles.length) focus += 2;
                if (focus == before) return;
            }
            click.move();
            aimLens(false);
            invalidate();
            spoken.focusMoved();
        }

        private void select() {
            click.open();
            if (inputsOpen) {
                if (inputs.isEmpty()) return;
                if (HomeApps.open(QuickPanelActivity.this, inputs.get(inputFocus), this, 0, 0, getWidth(), getHeight())) finish();
                return;
            }
            if (focus == INPUTS) {
                inputs = HomeApps.allInputs(getContext());
                inputsOpen = true;
                inputFocus = 0;
                page.target = 1;
                aimLens(false);
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (open(tiles[focus].intents)) finish();
        }
    }
}
