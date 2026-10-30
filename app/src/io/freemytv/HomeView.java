package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.usb.UsbManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the focused card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours or days
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, RECENT = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float WATCH_GAP = 40;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_STATUS = 25, L_STATUS_GAP = 22;
    private static final String[] STATUS_SYMBOLS = {"wifi", "ethernet", "bluetooth", "usb"};
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_WATCH = 6;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The background follows the focused pick once it has been there this long (see bgFollow). */
    private static final long BG_FOLLOW_MS = 500;
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final ConnectivityManager connectivity;
    private final BluetoothAdapter bluetooth;
    private boolean networkRegistered, devicesRegistered, mediaRegistered;
    private int network;   // 0 = not connected, 1 = Wi-Fi, 2 = Ethernet
    private boolean bluetoothConnected, usbConnected;
    /** The status layer spans the screen while the icons are on (they sit at the left); off, it keeps its old width. */
    private boolean statusWide;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { if (recentsOpen()) recents.openFocused(); else activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return a11yHolds(id); }
        @Override public void hold(int id) { if (recentsOpen()) recents.closeFocused(); else if (sheetOpen) a11yHold(); else if (canReorder()) startReorder(); else showMenu(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer, recentLayer;
    private RecentApps recents;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 3: Explore, Movies, Shows with a TMDB key, YouTube with a YouTube app) and the round
    // buttons at the top right (from B0, up to HomeApps.MAX_TOP); the cards
    private static final int B0 = 4;
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    private String[] tabKinds = {}, tabNames = {};
    private final Spring[] topFocus = new Spring[B0 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[B0 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[Recs.MAX_CARDS];
    /** How open (wide) each card is: 1 for the one at cardIndex, 0 for the others; the widths always add up to the same. */
    private final Spring[] cardOpen = new Spring[Recs.MAX_CARDS];
    /** Picks per tab (Prefs.cards()), read when the cards update, not on every frame. */
    private int cardSlots = 3;
    /** The row scrolls in whole cards: the focused one stays among the first three. */
    private final Spring cardScroll = new Spring(0.32f, 0.78f, 0);
    // Settings > Top of the screen: the picks (above), what you were watching in your apps (a row of 16:9 cards that
    // scrolls, Z_WATCH) or nothing. Its texts are measured once per list and its pictures decoded once (WatchArt), so
    // drawing the row costs no more than the picks.
    private String topMode = Prefs.TOP_PICKS;
    private List<WatchNext.Item> watching = new ArrayList<>();
    private final Spring[] watchFocus = new Spring[WatchNext.MAX];
    private final Spring watchScroll = new Spring(0.32f, 1f, 0);
    private int watchIndex;
    private boolean watchAllowed = true;
    private boolean watchLoaded;   // read at least once: before that, no "nothing half-watched"
    private String[] watchTitles, watchMetas;
    private float watchTextsK;
    private int watchTextsGen = -1;
    private LinearGradient watchSheen, watchRim;   // focus sheen and rim, made once per card size (not per frame)
    private float watchShaderW;
    private WatchArt watchArt;
    // Holding OK on a card: a small menu (hide it, open the app, cancel) on its own layer, drawn only while it shows.
    private static final float MENU_W = 720, MENU_PAD = 44, MENU_INSET = 16, MENU_ROW = 76, MENU_STEP = 80, MENU_R = 36;
    private final Spring menu = new Spring(0.26f, 0.86f, 0), menuPick = new Spring(0.22f, 0.9f, 0);
    private final String[] menuOptions = new String[3];
    private final RectF menuPanel = new RectF();
    private final Set<String> hiddenNow = new HashSet<>();   // hidden this session (a reload already under way may not know)
    private boolean menuOpen;
    private int menuIndex;
    private WatchNext.Item menuItem;
    private String menuTitle = "", menuSub = "";
    private float menuOptionsTop;
    private Paint menuTitlePaint, menuSubPaint, menuOptionPaint;
    private final RectF[] cardRects = new RectF[Recs.MAX_CARDS];
    // The YouTube tab: a row per category (YouTube.Row). ↑ ↓ go between rows (ytScroll moves the page), ← → along a row
    // (ytSlide moves it); each row remembers its place (ytCol). Two rows show between the tabs and the shelf.
    private static final float YT_TOP = 244, YT_ROW_H = 270, YT_LABEL = 32, YT_CARD_TOP = 56, YT_CARD_W = 300, YT_CARD_H = 169, YT_GAP = 28;
    private static final int YT_ROWS = 16, YT_VISIBLE = 5;
    private List<YouTube.Row> ytRows = new ArrayList<>();
    private final List<String[]> ytTitles = new ArrayList<>();
    // The same titles whole, and how wide: a focused one that doesn't fit scrolls (tickTitle).
    private final List<String[]> ytFull = new ArrayList<>();
    private final List<float[]> ytFullW = new ArrayList<>();
    /** The focused title's scroll: which card, since when it's focused, and how far it has moved (px; -1: not moving). */
    private int titleRow = -1, titleCol = -1;
    private long titleSince;
    private float titleShift = -1;
    private final Runnable titleWake = this::wake;
    private int ytRow;
    private final int[] ytCol = new int[YT_ROWS];
    private final Spring ytScroll = new Spring(0.4f, 0.9f, 0);
    private final Spring[] ytSlide = new Spring[YT_ROWS];
    private final RectF ytCard = new RectF(), ytShadow = new RectF();
    private final Runnable thumbReady = () -> {
        heroDirty = true;
        wake();
    };
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;
    // Continue Watching: the background takes the focused card's colour (worked out once per picture, WatchArt), easing
    // from one to the next. Drawn as a plain fill under a fixed shade, so a colour change creates nothing per frame.
    // Until the first picture's colour is in (a cold start reads the list and the pictures), the tint is a cool grey,
    // so the background and the glass already look as they will and only ease to the card's colour.
    private static final int TINT_WAIT = 0xFF8C93A0;
    private Shader tintShade;
    private int tintFrom = TINT_WAIT, tintTo = TINT_WAIT;
    private boolean tinted;   // a card's colour is in (else TINT_WAIT)
    private final Spring tintMix = new Spring(0.7f, 1f, 1);
    // The glass (shelf, drawer) reads that same colour: two flat images blended while it eases (Android 13+, where the
    // glass reads images), so nothing is made per frame. On older TVs the glass is a flat fill of glassLight().
    private int[] tintGlassPx;
    private boolean glassEasing;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    /**
     * A card in the YouTube rows opened an app: the first Home back comes to the same tab and card, like Back; a second
     * Home resets. keptAt: when that first Home came (the rest of a burst of Home presses counts as it).
     */
    private boolean keepPlace;
    private long keptAt;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    private float touchX, touchY;
    private final RectF hitBox = new RectF();   // the mouse's hit tests (menu rows, Continue Watching cards)

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;   // the weather card shows the next days (Home Settings → Forecast) and has them
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case RECENT: if (recents != null) recents.draw(c, w, h); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            setNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 2
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
        }

        @Override public void onLost(Network network) {
            setNetwork(0);
        }
    };

    /** Wi-Fi or Ethernet, for the icon at the top; none when the TV has no network. */
    private void setNetwork(int type) {
        if (type == network) return;
        network = type;
        status.invalidate();
    }

    private final BroadcastReceiver deviceCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /** The drive broadcasts, in a filter of their own (see startStatus). */
    private final BroadcastReceiver mediaCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /**
     * Bluetooth (when something is connected) and a USB drive, for the icons at the top. Only the audio profiles count:
     * a Bluetooth remote is always connected, and its HID connection would keep the icon on for good. The queries go to
     * LOADER: a Bluetooth remote that reconnects on the first key press after a while would otherwise pay for the
     * profile states, the storage volumes and the binder calls on that very press.
     */
    private void updateDevices() {
        LOADER.execute(() -> {
            boolean bt = false;
            try {
                if (bluetooth != null && bluetooth.isEnabled()) {
                    bt = bluetooth.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                            || bluetooth.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
                }
            } catch (RuntimeException ignored) {
            }
            boolean drive = false;
            try {
                StorageManager sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        // Only a mounted one: #192's unmounted drive doesn't show.
                        if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            drive = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            boolean btOn = bt, driveOn = drive;
            handler.post(() -> setDevices(btOn, driveOn));
        });
    }

    private void setDevices(boolean bt, boolean drive) {
        if (bt == bluetoothConnected && drive == usbConnected) return;
        bluetoothConnected = bt;
        usbConnected = drive;
        status.invalidate();
    }

    /** Reads the icons' state and listens for changes only while the setting is on (nothing runs with it off). */
    private void startStatus() {
        if (!Prefs.statusIcons()) return;
        updateDevices();
        // The registration hands the current network over at once, so the first read is this callback. With no
        // network at all it hands nothing, and the icon would keep the one from before the pause (Wi-Fi turned off).
        if (connectivity != null && !networkRegistered) {
            try {
                if (connectivity.getActiveNetwork() == null) setNetwork(0);
                connectivity.registerDefaultNetworkCallback(networkCallback, handler);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!devicesRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            // Headphones connect their link first and the audio a moment later; with a Bluetooth remote already
            // connected the adapter's own state doesn't change then, so the profiles' broadcasts say it.
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(deviceCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(deviceCallback, f);
                devicesRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        // A mounted drive's broadcast carries a file:// URI: in the same filter as the Bluetooth actions it would make
        // those never match (they have no data), so it gets a filter of its own.
        if (!mediaRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_MEDIA_MOUNTED);
            f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(Intent.ACTION_MEDIA_EJECT);
            f.addAction(Intent.ACTION_MEDIA_REMOVED);
            f.addDataScheme("file");
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(mediaCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(mediaCallback, f);
                mediaRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopStatus() {
        if (connectivity != null && networkRegistered) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkRegistered = false;
        }
        if (devicesRegistered || mediaRegistered) {
            // Registered once per filter, so unregistered once per filter.
            if (devicesRegistered) {
                try {
                    getContext().unregisterReceiver(deviceCallback);
                } catch (RuntimeException ignored) {
                }
                devicesRegistered = false;
            }
            if (mediaRegistered) {
                try {
                    getContext().unregisterReceiver(mediaCallback);
                } catch (RuntimeException ignored) {
                }
                mediaRegistered = false;
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            playing.start();   // the listener allowed after the screen showed (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.refreshTabs();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, this::onPlaying);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        connectivity = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth = bm == null ? null : bm.getAdapter();
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        } catch (RuntimeException e) {   // some projectors (Rockchip rk3566 HY300) ship without the launcherapps service
            AppLog.w("no app-change callback: " + e.getMessage());
        }
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        recentLayer = new Layer(a, RECENT);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer, recentLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        recentLayer.setVisibility(INVISIBLE);
        drawerLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < Recs.MAX_CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardOpen[i] = new Spring(0.32f, 0.78f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < watchFocus.length; i++) watchFocus[i] = new Spring(0.32f, 0.78f, 0);
        topMode = Prefs.topContent();
        watchArt = new WatchArt(a, () -> {
            heroDirty = true;
            updateTint();
            heroLayer.invalidate();
            wake();
        });
        for (int i = 0; i < YT_ROWS; i++) ytSlide[i] = new Spring(0.36f, 0.88f, 0);
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        // Home comes before this (onNewIntent, then onResume): still kept here, the video was left with Back, and the
        // next Home resets as always.
        keepPlace = false;
        checkNight(false);
        boolean h24 = Ui.h24();
        if (h24 != clock24) {   // 12/24 hours changed (the TV's or the Clock row's): the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.refreshTabs();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        ImmichPhotos.syncIfDue(getContext(), ownReady);   // or a new choice from the Immich server, every 6 hours
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        startStatus();
        handler.postDelayed(tick, 15_000);
        playing.start();
        checkNotices();
        hiddenNow.retainAll(Prefs.watchHidden());   // Home Settings may have shown the hidden ones again
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(bgFollow);
        cancelReorder(true);
        if (recents != null) recents.closeNow();
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        closeMenu(true);
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(bars);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        stopStatus();
    }

    void destroy() {
        pause();
        try {
            if (launcherApps != null) launcherApps.unregisterCallback(packages);
        } catch (RuntimeException ignored) {
        }
        click.release();
        watchArt.close();   // its thread is shared: nothing is left running for this view
    }

    /**
     * Starting state (Home pressed while the home screen is showing): first app focused, the first tab at its first
     * pick, drawer and sheet closed.
     */
    void reset(boolean animated) {
        wake();
        long now = SystemClock.uptimeMillis();
        if (keepPlace) {
            keepPlace = false;
            keptAt = now;
            return;
        }
        if (keptAt != 0 && now - keptAt < BURST_MS) return;
        cancelReorder(!animated);
        closeMenu(!animated);
        if (recents != null) recents.close();   // Home closes Recent Apps too (twice opens it: HomeActivity)
        zone = Z_SHELF;
        shelfIndex = 0;
        cardIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        cardIndex = 0;
        ytRow = 0;
        java.util.Arrays.fill(ytCol, 0);
        if (rowsMode()) syncRow(!animated);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= B0 && topIndex >= B0 + lightButtons.size()) {
            topIndex = lightButtons.isEmpty() ? tab : B0 + lightButtons.size() - 1;
            if (topIndex < B0 && !picksOn()) zone = Z_SHELF;   // without the tabs nothing at the top can hold focus
            else if (topIndex < B0) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        // The icons at the top left need the whole width; with them off the layer keeps its old size (the corner buttons
        // only), so the GPU still composites a small layer.
        statusWide = Prefs.statusIcons();
        measure(status, statusWide ? width : Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(menuLayer, width, height);
        measure(toastLayer, width, Math.round(220 * kk));   // full width: a toast may sit right of a long title
        measure(recentLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, statusWide ? 0 : width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        menuLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, 0, 0);
        recentLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        watchArt.setScale(k);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        // No photo yet (or none at all, in Continue Watching): the glass reads the plain background from the first frame.
        if (bgTo == null && bgFadeAt == 0) neutralGlass();
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = tintShade = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        menuTitlePaint = Ui.text(Ui.semibold, 34 * k, L_INK);
        menuSubPaint = Ui.text(Ui.medium, 25 * k, L_DIM);
        menuOptionPaint = Ui.text(Ui.medium, 30 * k, L_INK);
        if (menuOpen) layoutMenu();
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        onPlaying();   // the line fits the new size; a title that came before the first layout bounces and is said now
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        settleWhileSliding();
        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | step(cardOpen, dt) | cardScroll.step(dt)
                | step(watchFocus, dt) | watchScroll.step(dt) | ytScroll.step(dt) | step(ytSlide, dt);
        boolean tintMoving = tintMix.step(dt);
        if (tintMoving) {
            sky.invalidate();
            platter.invalidate();   // the glass follows the background's colour
            heroDirty = true;       // the cards' text colours too
            statusDirty = true;     // and the corner buttons' glass, on the status layer
            if (glassEasing) scene.setGlassMix(tintMix.value);
        }
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean menuMoving = menu.step(dt) | menuPick.step(dt);
        if (menuMoving || nightMoving && menuOpen) menuLayer.invalidate();
        else if (!menuOpen && menuLayer.getVisibility() == VISIBLE) menuLayer.setVisibility(INVISIBLE);
        boolean recentMoving = recents != null && recents.step(dt);
        if (recents != null) {
            if (recents.visible() != (recentLayer.getVisibility() == VISIBLE)) recentLayer.setVisibility(recents.visible() ? VISIBLE : INVISIBLE);
            if (recentMoving) recentLayer.invalidate();   // idle, the cards are drawn once
            setUnderRecents(recents.covering());
        }
        boolean titleMoving = tickTitle(up);
        heroMoving |= titleMoving;

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (clockMode()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || menuMoving || sheetMoving || toastFading || heroFading || bgFading || nightMoving || tintMoving || titleMoving || sheetOpen
                || sheet.value > 0.001f || recentMoving || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private final int[] underState = new int[9];
    private boolean underHidden;

    /** Recent Apps covers the screen: the layers below aren't drawn (on slower TVs they cost more than the cards). */
    private void setUnderRecents(boolean covered) {
        if (covered == underHidden) return;
        underHidden = covered;
        View[] under = {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer};
        for (int i = 0; i < under.length; i++) {
            if (covered) {
                underState[i] = under[i].getVisibility();
                under[i].setVisibility(INVISIBLE);
            } else {
                under[i].setVisibility(underState[i]);
            }
        }
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /**
     * While the drawer slides, the focus in the drawer, the hero and the capsule jumps instead of animating: the shelf's
     * focus alone is cheap over the sliding sky (10 ms a frame on a Realtek TCL), but a redraw of the drawer's cards or
     * of the hero on top of it took 35 to 45 ms of GPU there (most frames dropped when a key came during the slide).
     */
    private void settleWhileSliding() {
        if (drawer.settled()) return;
        if (settle(drawerFocus)) drawerDirty = true;
        if (settle(cardFocus) | settle(topFocus)) heroDirty = statusDirty = true;
        if (settle(statusFocus)) statusDirty = true;
        if (!heroFocus.settled()) {
            heroFocus.snap(heroFocus.target);
            heroDirty = true;
        }
    }

    private static boolean settle(Spring[] springs) {
        boolean moved = false;
        for (Spring s : springs) {
            if (s.settled()) continue;
            s.snap(s.target);
            moved = true;
        }
        return moved;
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /**
     * No photo: the glass reads the background's own colour (not Scene's default aurora, which isn't on screen): the
     * gray, or in Continue Watching the focused card's tint.
     */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            glassEasing = false;
            return;
        }
        plainImage(neutralFrostPx, Backdrop.FW, Backdrop.FH, tintTo);
        plainImage(neutralGlassPx, Backdrop.GW, Backdrop.GH, tintTo);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
        glassEasing = false;
    }

    /** The plain background (no photo) as an image for the glass, row by row (`card`: the tint's colour). */
    private void plainImage(int[] px, int width, int height, int card) {
        for (int j = 0; j < height; j++) {
            java.util.Arrays.fill(px, j * width, (j + 1) * width, plainAt((j + 0.5f) / height, card));
        }
    }

    /** The plain background's colour at height y (0 = top, 1 = bottom), as drawLightSky draws it. */
    private int plainAt(float y, int card) {
        if (!continueMode()) return blend(0xFFEBEBF0, 0xFF141416, night);
        return blend(tintColor(card), 0xFF000000, 0.2f * y);   // under drawTint's shade
    }

    /** The card's colour as a pale tint by day, a deep one at night (drawTint, before its shade). */
    private int tintColor(int card) {
        return blend(blend(0xFFEEEFF3, card, 0.34f), blend(0xFF141416, card, 0.3f), night);
    }

    /** The day glass's colour: white, or in Continue Watching white with a little of the background's tint. */
    private int glassLight() {
        if (!continueMode()) return 0xFFFFFFFF;
        return blend(0xFFFFFFFF, blend(tintFrom, tintTo, Ui.clamp(tintMix.value)), 0.18f);
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
        if (continueMode()) applyWatchContrast();
    }

    private int watchTextBg, watchQuoteBg, watchTitleInk, watchMetaInk, watchQuoteInk;
    private int watchTitleColor, watchMetaColor, watchQuoteColor;
    private boolean watchColorsReady;

    /** Contrast changes only with the background or the day/night text colours. */
    private void applyWatchContrast() {
        int card = blend(tintFrom, tintTo, Ui.clamp(tintMix.value));
        int behind = plainAt((L_CARDS_TOP + WatchArt.H + 70) * k / Math.max(1, h), card);
        int quoteBg = plainAt((L_TABBAR_TOP + L_TABBAR_H / 2) * k / Math.max(1, h), card);
        int title = lCardTitle.getColor(), meta = lCardMeta.getColor(), quote = lQuote.getColor();
        if (!watchColorsReady || behind != watchTextBg || quoteBg != watchQuoteBg
                || title != watchTitleInk || meta != watchMetaInk || quote != watchQuoteInk) {
            watchTextBg = behind;
            watchQuoteBg = quoteBg;
            watchTitleInk = title;
            watchMetaInk = meta;
            watchQuoteInk = quote;
            watchTitleColor = readable(title, behind);
            watchMetaColor = readable(meta, behind);
            watchQuoteColor = readable(quote, quoteBg);
            watchColorsReady = true;
        }
        lCardTitle.setColor(watchTitleColor);
        lCardMeta.setColor(watchMetaColor);
        lQuote.setColor(watchQuoteColor);
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(glassLight(), SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;
    // What's playing, cut to fit the tab row and the clock's row: once per title, never while drawing.
    private String playingTab, playingClock;
    private float playingTabWidth, playingTabCap, playingClockCap;
    private final RectF barRect = new RectF();
    // The bars bounce for a few seconds when the title changes, then stand still: bouncing all along redrew the top
    // layer 10 times a second (~8% of a core on a Skyworth's Cortex-A55), the still screen nothing.
    private static final long BAR_FRAME_MS = 100, BARS_BOUNCE_MS = 3000;
    private long barsUntil;
    private String shownTitle;   // kept while the screen is hidden: showing it again with the same title is no change
    private final Runnable bars = new Runnable() {
        @Override public void run() {
            if (!running || playingTab == null) return;
            heroDirty = true;
            wake();
            if (SystemClock.uptimeMillis() < barsUntil) handler.postDelayed(this, BAR_FRAME_MS);
        }
    };

    private void bounceBars() {
        handler.removeCallbacks(bars);
        if (playingTab == null) return;
        barsUntil = SystemClock.uptimeMillis() + BARS_BOUNCE_MS;
        handler.post(bars);
    }

    /** What's playing changed (or stopped). */
    private void onPlaying() {
        fitPlaying();
        heroDirty = true;
        wake();
        // Only a new title bounces the bars and is said to TalkBack: not a subtitle some players rewrite every second (the
        // time), and not the same title again when the screen shows.
        String title = playing.title, now = playing.text;
        if (title != null && now != null && running && playingTab != null && !title.equals(shownTitle)) {
            shownTitle = title;
            bounceBars();
            if (readerOn()) announceForAccessibility(Ui.trf("Now playing: {what}", "what", now.replace(" · ", ", ")));
        }
    }

    /**
     * A screen reader is on (TalkBack, or another service that speaks): only then is a new title announced. Other
     * accessibility services (a button mapper, a sleep timer) would get the announcement too, for nothing.
     */
    private boolean readerOn() {
        AccessibilityManager am = getContext().getSystemService(AccessibilityManager.class);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    private void fitPlaying() {
        String now = playing.text;
        if (now == null || lQuote == null) {
            playingTab = playingClock = null;
            return;
        }
        fitPaint.set(lQuote);
        playingTab = TextUtils.ellipsize(now, fitPaint, 640 * k, TextUtils.TruncateAt.END).toString();
        playingTabWidth = lQuote.measureText(playingTab);
        playingTabCap = Ui.cap(lQuote);
        playingClockCap = Ui.cap(lClockQuote);
        fitPaint.set(lClockQuote);
        playingClock = TextUtils.ellipsize(now, fitPaint, (CONTENT_R - MARGIN - 60) * k, TextUtils.TruncateAt.END).toString();
    }

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = clockMode();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = B0; i < B0 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // What an app is playing takes the quotes' place while it plays.
        String line = clock ? playingClock : playingTab;
        if (line != null) {
            float cap = clock ? playingClockCap : playingTabCap, gap = cap * 0.6f;
            float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + cap / 2;
            float x = clock ? MARGIN * k : right - playingTabWidth - gap - NowPlaying.barsWidth(cap);
            NowPlaying.drawBars(c, x, base, cap, text, SystemClock.uptimeMillis() < barsUntil, barRect);
            c.drawText(line, x + NowPlaying.barsWidth(cap) + gap, base, text);
            text.setColor(textColor);
            return;
        }
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /** The image cropped at the center to the cell's shape (not stretched), at `alpha`. */
    private void coverImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float sw = Math.min(iw, cell.width() / cell.height() * ih), sl = (iw - sw) / 2;
        float sx = cell.width() / sw, sy = cell.height() / ih;
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        c.translate(-sl, 0);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(sl, 0, sl + sw, ih, r / sx, r / sy, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, glassLight(), lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours or 5 days, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k;
        if (textDays) {
            float hw = (r.right - 30 * k - hx) / 5;
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(dayText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                // The high, then the low dimmed, centered together (closer than the next day, so the pairs read apart).
                float maxW = hourTemp.measureText(dayMaxText[i]), minW = dayLow.measureText(dayMinText[i]), gap = 6 * k;
                float left = hc - (maxW + gap + minW) / 2;
                c.drawText(dayMaxText[i], left + maxW / 2, r.top + 196 * k, hourTemp);
                c.drawText(dayMinText[i], left + maxW + gap + minW / 2, r.top + 196 * k, dayLow);
            }
        } else {
            float hw = (r.right - 30 * k - hx) / 6;
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
            }
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();   // no days yet (an older forecast, offline): the hours
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(cardOpen, cardIndex, snap);
        float scrollTo = Math.max(0, cardIndex - 2);
        heroChanged |= cardScroll.target != scrollTo;
        cardScroll.target = scrollTo;
        if (snap) cardScroll.snap(scrollTo);
        if (picksMode() && !cards.isEmpty()) {
            handler.removeCallbacks(bgFollow);
            handler.postDelayed(bgFollow, BG_FOLLOW_MS);
        }
        heroChanged |= aim(watchFocus, !sheetOpen && zone == Z_WATCH ? watchIndex : -1, snap);
        updateTint();
        // The row scrolls so the focused card is at most the third one on screen.
        float watchTo = Math.max(0, Math.min(watchIndex - 2, watching.size() - 3));
        if (watchScroll.target != watchTo || snap) {
            heroChanged = true;
            if (snap) watchScroll.snap(watchTo); else watchScroll.target = watchTo;
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens it (it closes the sheet). */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        reset(false);
        openSheet();
    }

    private boolean allAppsOnResume;

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private float titleRight;   // where the top title ends (0: none), so toasts don't cover it

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        // Centered, unless that covers the title (a long one, such as Continue Watching): then just right of it.
        float left = Math.max((w - tw) / 2f - padX, Math.min(titleRight + 40 * k, w - tw - 2 * padX - MARGIN * k));
        tmp.set(left, toastTop, left + tw + 2 * padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            // Only read when it's shown: with the picks or the clock, Continue Watching costs nothing.
            final String mode = Prefs.topContent();
            final boolean allowed = WatchNext.allowed(c);
            final List<WatchNext.Item> w = mode.equals(Prefs.TOP_CONTINUE) ? WatchNext.load(c) : new ArrayList<>();
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                setTopContent(mode, w, allowed);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    /** A mouse (or a touch screen) clicks what is under it, like OK on the focused item. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = e.getX();
                touchY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.hypot(e.getX() - touchX, e.getY() - touchY) < 24 * k) tap(e.getX(), e.getY());
                return true;
            default:
                return true;
        }
    }

    /** A mouse moves the focus to what is under the pointer, like a trackpad on tvOS. */
    @Override public boolean onHoverEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) hover(e.getX(), e.getY());
        return true;
    }

    /** The mouse wheel moves through the shelf, the cards, the drawer and All Apps. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) {
                wheel(v < 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    /**
     * The item the pointer was last over (zone * 1000 + index, -1: none). Hover moves the focus only when the pointer
     * comes onto another item, as LG's mouseover does: a resting pointer nudged by a hand (an air mouse) doesn't pull
     * the focus back from where the arrows took it.
     */
    private int hovered = -1;
    private static final int H_MENU = 7, H_GRID = 8;   // hover's codes for the menu's rows and All Apps' cells

    /** True when the pointer has just come onto (zone, index); index -1 (nothing there) only forgets the last one. */
    private boolean entered(int zone, int index) {
        int code = index < 0 ? -1 : zone * 1000 + index;
        if (code == hovered) return false;
        hovered = code;
        return code >= 0;
    }

    private void hover(float x, float y) {
        wake();
        if (reordering) return;
        if (menuOpen) {   // only the menu's rows: what's behind it stays as it was
            int row = menuAt(x, y);
            if (entered(H_MENU, row)) moveMenu(row - menuIndex);
            return;
        }
        if (sheetOpen) {
            int hit = gridAt(x, y, true);
            if (entered(H_GRID, hit) && hit != gridIndex) {
                gridIndex = hit;
                retargetGrid(false);
                gridDirty = true;
            }
            return;
        }
        if (drawerOpen) {
            int hit = hit(drawerRects, x, y);
            if (entered(Z_DRAWER, hit) && (zone != Z_DRAWER || drawerIndex != hit)) {
                zone = Z_DRAWER;
                drawerIndex = hit;
                retarget(false);
            }
            return;
        }
        int z = Z_TOP, hit = topAt(x, y);
        if (hit < 0) {
            z = Z_CARDS;
            hit = cardAt(x, y, true);
        }
        if (hit < 0) {
            z = Z_WATCH;
            hit = watchAt(x, y, true);
        }
        if (hit < 0) {
            z = Z_SHELF;
            hit = tileAt(x, y);
        }
        if (!entered(z, hit)) return;
        int now = zone == Z_TOP ? topIndex : zone == Z_CARDS ? focusedCard() : zone == Z_WATCH ? watchIndex : shelfIndex;
        if (zone == z && now == hit) return;
        zone = z;
        if (z == Z_TOP) {
            topIndex = hit;
            if (hit < B0) selectTab(hit);   // as with the arrows (and on LG): focusing a tab selects it
        } else if (z == Z_CARDS) {
            focusCard(hit);
        } else if (z == Z_WATCH) {
            watchIndex = hit;
        } else {
            shelfIndex = hit;
        }
        retarget(false);
    }

    private void wheel(int dir) {
        wake();
        if (reordering) return;   // the arrows move the lifted app; the wheel would move the focus off it
        if (menuOpen) {
            moveMenu(dir);
        } else if (sheetOpen) {
            int next = Math.max(0, Math.min(others.size() - 1, gridIndex + dir * COLUMNS));
            if (next != gridIndex) {
                gridIndex = next;
                retargetGrid(false);
                gridDirty = true;
            }
        } else if (drawerOpen) {
            int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dir));
            if (next != drawerIndex) {
                zone = Z_DRAWER;
                drawerIndex = next;
                retarget(false);
            }
        } else if (zone == Z_CARDS && rowsMode()) {   // a row at a time, as in All Apps: the page scrolls
            int next = Math.max(0, Math.min(Math.min(YT_ROWS, ytRows.size()) - 1, ytRow + dir));
            if (next != ytRow) {
                ytRow = next;
                syncRow(false);
                retarget(false);
            }
        } else if (zone == Z_CARDS && !cards.isEmpty()) {
            int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dir));
            if (next != cardIndex) {
                cardIndex = next;
                retarget(false);
            }
        } else if (zone == Z_WATCH && !watching.isEmpty()) {
            int next = Math.max(0, Math.min(watching.size() - 1, watchIndex + dir));
            if (next != watchIndex) {
                watchIndex = next;
                retarget(false);
            }
        } else if (!shelf.isEmpty()) {
            int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dir));
            if (next != shelfIndex) {
                zone = Z_SHELF;
                shelfIndex = next;
                retarget(false);
            }
        }
    }

    private void tap(float x, float y) {
        wake();
        if (reordering) return;
        if (menuOpen) {   // a click on a row picks it; anywhere else closes the menu (never what's behind it)
            int row = menuAt(x, y);
            if (row < 0) {
                closeMenu(false);
                click.move();
                return;
            }
            menuIndex = row;
            menuPick.target = row;
            pickMenu();
            return;
        }
        if (sheetOpen) {
            int hit = gridAt(x, y, false);
            if (hit >= 0) {
                gridIndex = hit;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                activate();
            }
            return;
        }
        if (drawerOpen) {
            int hit = hit(drawerRects, x, y);
            if (hit >= 0) {
                zone = Z_DRAWER;
                drawerIndex = hit;
                retarget(false);
                activate();
            }
            return;
        }
        int hit = topAt(x, y);
        if (hit >= 0) {
            zone = Z_TOP;
            topIndex = hit;
            retarget(false);
            activate();
            return;
        }
        hit = cardAt(x, y, false);
        if (hit >= 0) {
            zone = Z_CARDS;
            focusCard(hit);
            retarget(false);
            activate();
            return;
        }
        hit = watchAt(x, y, false);
        if (hit >= 0) {
            zone = Z_WATCH;
            watchIndex = hit;
            retarget(false);
            activate();
            return;
        }
        hit = tileAt(x, y);
        if (hit >= 0) {
            zone = Z_SHELF;
            shelfIndex = hit;
            retarget(false);
            activate();
        }
    }

    /** The menu's row under a point, if any. */
    private int menuAt(float x, float y) {
        for (int i = 0; i < menuOptions.length; i++) {
            menuRow(i, hitBox);
            if (hitBox.contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * The tab or round button under a point: the tabs only with the picks, and only the buttons there now (a shorter
     * list leaves the old places of the others in topRects).
     */
    private int topAt(float x, float y) {
        int hit = hit(topRects, x, y);
        return hit >= B0 + lightButtons.size() || hit >= 0 && hit < B0 && (!picksMode() || hit >= tabKinds.length) ? -1 : hit;
    }

    /**
     * The picks' card under a point, if any. Hovering (`still`) only takes a card that keeps its row where it is: the
     * row scrolls so the focused card is the third (retarget), so hovering the one peeking in at the edge, or the first
     * one of a scrolled row, would slide the next one under the pointer, and the focus would run along the row as the
     * pointer moves. A click takes any card.
     */
    private int cardAt(float x, float y, boolean still) {
        if (rowsMode()) return rowCardAt(x, y, still);
        int hit = hit(cardRects, x, y);
        if (hit < 0 || hit >= cards.size()) return -1;
        return still && Math.max(0, hit - 2) != cardScroll.target ? -1 : hit;
    }

    /**
     * The YouTube tab's card under a point, as row * Recs.MAX_CARDS + card, or -1. Hovering (`still`) only takes one
     * that leaves the page and its row where they are (syncRow's targets), for the same reason as cardAt.
     */
    private int rowCardAt(float x, float y, boolean still) {
        if (y < (YT_TOP - 2) * k || y > (L_PLATTER_TOP - 4) * k) return -1;   // drawRows' clip
        int n = Math.min(YT_ROWS, ytRows.size());
        for (int r = 0; r < n; r++) {
            int size = ytRows.get(r).picks.size();
            for (int i = 0; i < size && i < Recs.MAX_CARDS; i++) {
                ytCardRect(r, i, hitBox);
                if (!hitBox.contains(x, y)) continue;
                if (still && (Math.max(0, Math.min(r, n - 2)) != ytScroll.target
                        || Math.max(0, Math.min(i - 2, size - YT_VISIBLE)) != ytSlide[r].target)) return -1;
                return r * Recs.MAX_CARDS + i;
            }
        }
        return -1;
    }

    /** Focuses the card cardAt found: on the YouTube tab, that row and its place in it. */
    private void focusCard(int hit) {
        if (!rowsMode()) {
            cardIndex = hit;
            return;
        }
        ytRow = hit / Recs.MAX_CARDS;
        ytCol[ytRow] = hit % Recs.MAX_CARDS;
        syncRow(false);
    }

    /** The focused card, numbered as cardAt numbers it. */
    private int focusedCard() {
        return rowsMode() ? ytRow * Recs.MAX_CARDS + ytCol[ytRow] : cardIndex;
    }

    /** The Continue Watching card under a point, if any (only the ones on screen; `still` as in cardAt). */
    private int watchAt(float x, float y, boolean still) {
        if (!continueMode()) return -1;
        for (int i = 0; i < watching.size(); i++) {
            watchRect(i, hitBox);
            if (hitBox.left > w || hitBox.right <= watchLeftEdge()) continue;
            if (!hitBox.contains(x, y)) continue;
            return still && Math.max(0, Math.min(i - 2, watching.size() - 3)) != watchScroll.target ? -1 : i;
        }
        return -1;
    }

    /**
     * The shelf tile under a point, if any: only the ones on screen. The next tile's place is just past the glass, at the
     * screen's edge, and a pointer resting there would scroll the shelf one app at a time to its end.
     */
    private int tileAt(float x, float y) {
        int last = Math.min(tileRects.length, shelfFirst + Math.min(shelf.size(), shelfVisible));
        for (int i = shelfFirst; i < last; i++) if (tileRects[i].contains(x, y)) return i;
        return -1;
    }

    private static int hit(RectF[] rects, float x, float y) {
        for (int i = 0; i < rects.length; i++) if (!rects[i].isEmpty() && rects[i].contains(x, y)) return i;
        return -1;
    }

    /**
     * The All Apps cell under a point, if any (the sheet's own layout, scrolled). Hovering (`still`) only takes the
     * rows on screen: a row peeks in at the bottom (and at the top once scrolled), and focusing it would scroll the
     * next one under the pointer (retargetGrid's rows).
     */
    private int gridAt(float x, float y, boolean still) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k, top = GRID_TOP * k - scroll.value * GRID_ROW * k;
        float cw = GRID_W * k, ch = GRID_H * k, gap = GRID_GAP * k, row = GRID_ROW * k;
        for (int i = 0; i < others.size(); i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float x0 = left + col * (cw + gap), y0 = top + r * row;
            if (x >= x0 && x < x0 + cw && y >= y0 && y < y0 + ch) {
                if (still && (r < scroll.target || r > scroll.target + VISIBLE_ROWS - 1)) return -1;
                focusedCell.set(x0, y0, x0 + cw, y0 + ch);
                return i;
            }
        }
        return -1;
    }

    boolean onKey(KeyEvent e) {
        wake();
        if (menuOpen) return menuKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (recents != null && recents.isOpen()) {   // not as it fades out: an OK or ▲ then is the home screen's, not a card's
            int was = recents.focus();
            boolean handled = recents.key(e);
            recentLayer.invalidate();
            if (recents.focus() != was) spoken.focusMoved();
            if (!recents.visible() || recents.count() == 0) spoken.readAgain();
            return handled;
        }
        if (down && e.getKeyCode() == KeyEvent.KEYCODE_APP_SWITCH && !Prefs.recents().equals("off")) {
            openRecents();
            return true;
        }
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode, no menu
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf; on a Continue
                // Watching card: its menu. A timer, since not every remote repeats a held key, or the long-press repeat,
                // whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canHold();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                // The remote's Settings button (TCL's sends NOTIFICATION) opens TV Settings, as Hisense's gear does by
                // default; ours stay on the gear on screen. Hisense's gear is a Remote Buttons choice (RemoteKeys); where
                // the system takes the key first, it never gets here.
                if (RemoteKeys.HISENSE) return false;
                if (down && e.getRepeatCount() == 0) HomeApps.open(activity, HomeApps.tvSettings(), this, w / 2, h / 2, 1, 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    /** Home pressed twice (HomeActivity). */
    void showRecents() {
        openRecents();
    }

    /** Recent Apps over a blurred copy of the screen (without the copy, over a dark veil). */
    private void openRecents() {
        if (recents == null) {
            recents = new RecentApps(activity, new RecentApps.Host() {
                @Override public void open(HomeApps.Item item) {
                    handler.post(() -> {
                        if (!HomeApps.open(activity, item, HomeView.this, getWidth() / 2, getHeight() / 2, 1, 1)) {
                            showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
                        }
                    });
                }
                @Override public void repaint() {
                    recentLayer.postInvalidate();
                    handler.post(() -> { wake(); spoken.readAgain(); });   // the list arrived: TalkBack reads the cards
                }
            }, k);
        }
        ScreenBlur.capture(activity, handler, blurred -> {
            recents.setBackdrop(blurred);
            recents.open();
            recentLayer.setVisibility(VISIBLE);
            recentLayer.invalidate();
            spoken.readAgain();
            wake();
        });
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (menuOpen) {
            pickMenu();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < B0) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - B0);
            r = topRects[topIndex];
        } else if (zone == Z_WATCH) {
            if (watchIndex >= watching.size()) return;
            click.open();
            openWatching(watching.get(watchIndex));
            return;
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
            if (rowsMode()) {
                ytCardRect(ytRow, cardIndex, ytCard);
                r = ytCard;
                keepPlace = true;
            }
            YouTube.get(getContext()).opened(p);
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.RECENTS) {
            openRecents();
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move;
    // on a Continue Watching card, its menu opens

    private void hold() {
        if (sheetOpen) {
            toggleShelf();
        } else if (canReorder()) {
            startReorder();
        } else if (canHold()) {
            handler.removeCallbacks(holdStart);
            holdArmed = false;
            okDown = false;     // the menu gets keys now: this press's release must not pick an option
            showMenu();
        }
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8, MENU_ITEMS = 10, RECENT_CARDS = 11;

    private boolean recentsOpen() {
        return recents != null && recents.isOpen();   // not while it fades out: TalkBack reads the home screen again
    }

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (recentsOpen()) {   // it covers the screen: only its cards are read (or, with none, its message)
            for (int i = 0; i < Math.max(1, recents.count()); i++) out.add(a11yId(RECENT_CARDS, i));
            return;
        }
        if (menuOpen) {
            for (int i = 0; i < menuOptions.length; i++) out.add(a11yId(MENU_ITEMS, i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (picksMode()) for (int i = 0; i < tabKinds.length; i++) out.add(a11yId(Z_TOP, i));
        for (int i = B0; i < B0 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(Recs.MAX_CARDS, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        if (continueMode()) for (int i = 0; i < watching.size(); i++) out.add(a11yId(Z_WATCH, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case RECENT_CARDS:
                if (!recentsOpen()) return "";
                return recents.count() == 0 ? Ui.tr("Recent Apps") + ". " + recents.emptyText() : recents.spoken(i);
            case MENU_ITEMS:
                if (!menuOpen || i >= menuOptions.length) return "";
                return (i == 0 && menuItem != null ? menuItem.title + ". " : "") + menuOptions[i] + ", "
                        + Ui.trf("{i} of {n}", "i", i + 1, "n", menuOptions.length);
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= B0) return label(lightButtons, i - B0);
                if (i >= tabNames.length) return "";
                return Ui.trf("{name}, tab {i} of {n}", "name", tabNames[i], "i", i + 1, "n", tabNames.length);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_WATCH:
                if (i >= watching.size()) return "";
                WatchNext.Item it = watching.get(i);
                String left = timeLeft(it);
                return it.title + (it.episode != null ? ", " + it.episode : "") + (left != null ? ", " + left : "")
                        + (it.app != null && !it.app.isEmpty() ? ". " + Ui.trf("App: {app}", "app", it.app) : "");
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case RECENT_CARDS:
                if (!recentsOpen()) return false;
                if (recents.count() == 0) recents.emptyBounds(a11yRect);
                else if (!recents.bounds(i, a11yRect)) return false;
                dy = 0;
                break;
            case MENU_ITEMS:
                if (!menuOpen || i >= menuOptions.length) return false;
                menuRow(i, a11yRect);
                dy = 0;
                break;
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= B0 + lightButtons.size() || (i < B0 && i >= tabKinds.length)) return false;
                a11yRect.set(topRects[i]);
                dy = (i < B0 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(Recs.MAX_CARDS, cards.size())) return false;
                if (rowsMode()) ytCardRect(ytRow, i, a11yRect);
                else a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_WATCH:
                if (i >= watching.size()) return false;
                watchRect(i, a11yRect);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    /** What holding OK does on an item, TalkBack offers as a long click. */
    private boolean a11yHolds(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == RECENT_CARDS) return recentsOpen() && recents.stoppable(i);   // TalkBack's long click closes it, as ▲ does
        if (group == GRID) return canToggleShelf(i);
        if (group == Z_SHELF) {   // TalkBack's long click lifts the app, as holding OK does
            return !reordering && !menuOpen && !sheetOpen && shelf.size() > 1 && i < shelf.size()
                    && shelf.get(i).kind == HomeApps.APP && shelf.get(i).pkg != null;
        }
        return group == Z_WATCH && !menuOpen && continueMode() && i < watching.size();
    }

    private int a11yFocused() {
        if (recentsOpen()) return a11yId(RECENT_CARDS, recents.count() == 0 ? 0 : recents.focus());
        if (menuOpen) return a11yId(MENU_ITEMS, menuIndex);
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_WATCH: return watchIndex < watching.size() ? a11yId(Z_WATCH, watchIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /**
     * The background is the focused pick's, as on Netflix and Prime Video: once the focus has rested on it BG_FOLLOW_MS,
     * so a quick run along the row fades once. Its backdrop is already in memory (this tab's picks all are).
     */
    private final Runnable bgFollow = new Runnable() {
        @Override public void run() {
            if (!running || !picksMode() || cards.isEmpty()) return;
            Backdrop b = recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
            // Not built yet (updateCards shows it when it is), or already there: nothing to fade, no frame to draw
            if (b == null || b == bgTo) return;
            showBackdrop(b);
            wake();
        }
    };

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (recentsOpen()) {
            if (group == RECENT_CARDS) recents.setFocus(i);
            recentLayer.invalidate();
            return;
        }
        if (menuOpen) {
            if (group == MENU_ITEMS && i < menuOptions.length && i != menuIndex) moveMenu(i - menuIndex);
            return;
        }
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= B0 + lightButtons.size() || (i < B0 && (!picksMode() || i >= tabKinds.length))) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < B0) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= Math.min(Recs.MAX_CARDS, cards.size())) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                if (rowsMode()) {
                    ytCol[ytRow] = i;
                    syncRow(false);
                }
                break;
            case Z_WATCH:
                if (i >= watching.size()) return;
                if (drawerOpen) closeDrawer();
                watchIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < tabNames.length; i++) {
            float tw = Math.max(lTabOn.measureText(tabNames[i]), lTab.measureText(tabNames[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[B0 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        if (Prefs.statusIcons()) {   // at the sizes drawStatusIcon uses: drawing never scales a 150 px PNG down to 25
            for (String name : STATUS_SYMBOLS) {
                Symbols.warm(getContext(), name, Symbols.pointSizeForHeight(getContext(), name, L_STATUS * k * statusScale(name)));
            }
        }
        Symbols.warmWeather(getContext(), 40 * k);
        heroDirty = statusDirty = true;
        layoutCards();
    }

    /** The cards side by side: the one at cardIndex wide, the others tall, sliding as `cardOpen` moves. */
    private void layoutCards() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g, x = MARGIN * k - cardScroll.value * (tall + g);
        for (int i = 0; i < Recs.MAX_CARDS; i++) {
            float w = Math.max(tall * 0.5f, tall + (wide - tall) * cardOpen[i].value);
            cardRects[i].set(x, top, x + w, top + ch);
            snap(cardRects[i]);
            x += w + g;
        }
    }

    /**
     * The tabs: Explore, Movies and Shows with a TMDB key; YouTube with SmartTube or YouTube on the TV. Checked with the
     * picks (every 15 s), never while drawing.
     */
    private void updateTabs() {
        List<String> kinds = new ArrayList<>(), names = new ArrayList<>();
        if (Recs.hasKey(getContext())) {
            for (int i = 0; i < 3; i++) {
                kinds.add(TAB_KIND[i]);
                names.add(tabs()[i]);
            }
        }
        if (YouTube.get(getContext()).on()) {
            kinds.add("yt");
            names.add("YouTube");
        }
        String[] k2 = kinds.toArray(new String[0]), n2 = names.toArray(new String[0]);
        if (java.util.Arrays.equals(k2, tabKinds) && java.util.Arrays.equals(n2, tabNames)) return;
        tabKinds = k2;
        tabNames = n2;
        if (tab >= tabKinds.length) tab = 0;
        if (zone == Z_TOP && topIndex < B0 && topIndex >= tabKinds.length) topIndex = tabKinds.length > 0 ? tab : B0;
        layoutLight();
    }

    /** The cards of the current tab (Explore, Movies, Shows; YouTube has rows) and the background taken from the focused one. */
    private void updateCards() {
        cardSlots = Prefs.cards();
        updateTabs();
        if (!picksMode()) {
            // No picks on screen: no cards, and the plain gradient behind, or with the clock your photo if there is one.
            if (!cards.isEmpty()) cards = new ArrayList<>();
            boolean own = clockMode() && Photos.on(getContext());
            Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : null;
            if (b != null || !own) showBackdrop(b);
            heroDirty = true;
            return;
        }
        if (rowsMode()) {
            updateRows();
            return;
        }
        List<Recs.Pick> list = recs.now(tabKinds[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) zone = Z_SHELF;
            retarget(false);   // the wide card and the scroll follow the clamped index, whatever has the focus
        }
        // This tab's cards, then the first three of the others (what shows at first): switching tabs is quick, and
        // the images stay under Recs's cap in memory. The YouTube tab's backgrounds load as its rows are focused.
        for (Recs.Pick p : list) recs.backdrop(p);
        for (int t = 0; t < tabKinds.length; t++) {
            if (t == tab || "yt".equals(tabKinds[t])) continue;
            List<Recs.Pick> other = recs.now(tabKinds[t]);
            for (int i = 0; i < Math.min(3, other.size()); i++) recs.backdrop(other.get(i));
        }
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady)
                : cards.isEmpty() ? null : recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    // MARK: - Continue watching (Settings > Top of the screen)

    /** Picks need a TMDB key: without one, "picks" shows the clock, like "nothing". */
    private boolean picksMode() {
        return topMode.equals(Prefs.TOP_PICKS) && picksOn();
    }

    /** The clock, the date and the weather: nothing at the top, or picks without a TMDB key. */
    private boolean clockMode() {
        return !picksMode() && !continueMode();
    }

    private boolean continueMode() {
        return topMode.equals(Prefs.TOP_CONTINUE);
    }

    /** What the top shows now (from reload, off the main thread) and, for Continue Watching, its list. */
    private void setTopContent(String mode, List<WatchNext.Item> list, boolean allowed) {
        if (!hiddenNow.isEmpty()) {
            List<WatchNext.Item> shown = new ArrayList<>(list.size());
            for (WatchNext.Item it : list) if (!hiddenNow.contains(WatchNext.hideKey(it))) shown.add(it);
            list = shown;
        }
        boolean modeChanged = !mode.equals(topMode);
        boolean listChanged = !sameWatching(list, watching);
        // The permission (granted or taken back) and the first read change what an empty row says: redraw for those.
        boolean textChanged = allowed != watchAllowed || !watchLoaded && mode.equals(Prefs.TOP_CONTINUE);
        watchAllowed = allowed;
        if (mode.equals(Prefs.TOP_CONTINUE)) watchLoaded = true;
        if (!modeChanged && !listChanged) {
            if (textChanged) heroDirty = true;
            return;
        }
        topMode = mode;
        if (modeChanged) closeMenu(false);
        watching = list;
        watchTitles = null;
        watchIndex = Math.min(watchIndex, Math.max(0, list.size() - 1));
        boolean lost = zone == Z_WATCH && list.isEmpty() || !picksMode() && (zone == Z_CARDS || zone == Z_TOP && topIndex < B0);
        if (modeChanged || lost) zone = Z_SHELF;
        if (modeChanged) {
            updateCards();
            if (bgTo == null && bgFadeAt == 0) neutralGlass();   // the plain background depends on the mode too
            sky.invalidate();   // the background's gradient depends on the mode
        }
        retarget(modeChanged);
        heroDirty = true;
    }

    private static boolean sameWatching(List<WatchNext.Item> a, List<WatchNext.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WatchNext.Item x = a.get(i), y = b.get(i);
            // The episode and the link too: "S2 E6" becoming "S2 E7" keeps the title, often the picture and a 0 position.
            if (!x.pkg.equals(y.pkg) || !x.title.equals(y.title) || x.position != y.position || x.duration != y.duration
                    || !java.util.Objects.equals(x.episode, y.episode) || !java.util.Objects.equals(x.intent, y.intent)
                    || !java.util.Objects.equals(x.picture(), y.picture())) return false;
        }
        return true;
    }

    /** Card i's place on screen (without its focus growth), scrolled with the row. */
    private void watchRect(int i, RectF out) {
        float step = (WatchArt.W + WATCH_GAP) * k;
        float x = MARGIN * k + i * step - watchScroll.value * step, y = L_CARDS_TOP * k;
        out.set(x, y, x + WatchArt.W * k, y + WatchArt.H * k);
    }

    /** Each card's title and details line ("Netflix · S2 E5 · 23 min left"), cut to the card's width once per list. */
    private void prepareWatchTexts() {
        int n = watching.size();
        watchTitles = new String[n];
        watchMetas = new String[n];
        TextPaint tp = new TextPaint(lCardTitle), mp = new TextPaint(lCardMeta);
        float width = WatchArt.W * k;
        for (int i = 0; i < n; i++) {
            WatchNext.Item it = watching.get(i);
            watchTitles[i] = TextUtils.ellipsize(it.title, tp, width, TextUtils.TruncateAt.END).toString();
            StringBuilder meta = new StringBuilder(it.app == null ? "" : it.app);
            if (it.episode != null) meta.append(meta.length() > 0 ? " · " : "").append(it.episode);
            String left = timeLeft(it);
            if (left != null) meta.append(meta.length() > 0 ? " · " : "").append(left);
            watchMetas[i] = TextUtils.ellipsize(meta.toString(), mp, width, TextUtils.TruncateAt.END).toString();
        }
        watchTextsK = k;
        watchTextsGen = Ui.generation;
    }

    private static String timeLeft(WatchNext.Item it) {
        if (it.progress() < 0) return null;
        long min = Math.max(1, (it.duration - it.position) / 60_000);
        return min >= 60 ? Ui.trf("{h} h {m} min left", "h", min / 60, "m", min % 60) : Ui.trf("{n} min left", "n", min);
    }

    private void drawWatchRow(Canvas c) {
        if (watching.isEmpty()) {
            if (!watchLoaded) return;   // not read yet: saying "nothing" now could be wrong
            String text = watchAllowed ? Ui.tr("Nothing half-watched in your apps.")
                    : Ui.tr("Missing permission: pick Continue Watching again in Home Settings.");
            c.drawText(text, MARGIN * k, (L_CARDS_TOP + 60) * k, lCardMeta);
            return;
        }
        if (watchTitles == null || watchTextsK != k || watchTextsGen != Ui.generation) prepareWatchTexts();
        int top = -1;
        for (int i = 0; i < watching.size(); i++) if (watchFocus[i].value > 0.02f && (top < 0 || watchFocus[i].value > watchFocus[top].value)) top = i;
        // Cards scrolled past the left margin slide out under this edge instead of leaving a sliver there (the first
        // card's focus growth and shadow still fit).
        c.save();
        c.clipRect(watchLeftEdge(), 0, w, h);
        for (int i = 0; i < watching.size(); i++) if (i != top) drawWatchCard(c, i);
        if (top >= 0) drawWatchCard(c, top);
        c.restore();
    }

    private float watchLeftEdge() {
        return (MARGIN - 32) * k;
    }

    /** `ink`, or as much nearer black (light backgrounds) or white (dark ones) as it takes for 4.5:1 on `bg`. */
    private static int readable(int ink, int bg) {
        float lb = luminance(bg);
        int target = lb > 0.18f ? 0xFF000000 : 0xFFFFFFFF;   // either one reaches 4.5:1 on its side of 0.18
        for (int i = 0; i < 10; i++) {
            int c = blend(ink, target, i / 10f);
            float lc = luminance(blend(bg, c | 0xFF000000, (c >>> 24) / 255f));
            if ((Math.max(lb, lc) + 0.05f) / (Math.min(lb, lc) + 0.05f) >= 4.5f) return c;
        }
        return target;
    }

    /** Relative luminance (WCAG) of an opaque colour. */
    private static float luminance(int c) {
        return 0.2126f * linear((c >> 16) & 255) + 0.7152f * linear((c >> 8) & 255) + 0.0722f * linear(c & 255);
    }

    private static float linear(int v) {
        float x = v / 255f;
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4);
    }

    /** Like a pick's card (same focus, shadow, sheen and rim), with the progress bar on the picture. */
    private void drawWatchCard(Canvas c, int i) {
        watchRect(i, cell);
        if (cell.left > w || cell.right <= watchLeftEdge()) return;   // scrolled off screen
        WatchNext.Item it = watching.get(i);
        float v = watchFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = cell.width(), bh = cell.height(), r0 = 18 * k, r = r0 * s;
        float cx = cell.centerX(), cy = cell.centerY() - 6 * k * v, textLeft = cell.left, textTop = cell.bottom;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        Bitmap img = watchArt.get(it.pictureUri, it.pictureKey, it.bannerKey, it.pkg);
        if (img != null) {
            roundImage(c, img, watchArt.shader(img), cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        float progress = it.progress();
        if (progress >= 0) {
            float inset = 16 * k * s, bar = 6 * k * s, bottom = cell.bottom - inset;
            fill.setShader(null);
            fill.setColor(0x73000000);
            tmp.set(cell.left + inset, bottom - bar, cell.right - inset, bottom);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
            fill.setColor(0xF2FFFFFF);
            tmp.right = tmp.left + Math.max(bar, tmp.width() * progress);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
        }
        if (f > 0.01f) {
            // Drawn at the card's resting size, scaled with it: the gradients are made once, not on every frame.
            if (watchSheen == null || watchShaderW != bw) {
                watchSheen = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                watchRim = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                watchShaderW = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(watchSheen);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(watchRim);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        c.drawText(watchTitles[i], textLeft, textTop + 46 * k, lCardTitle);
        if (!watchMetas[i].isEmpty()) c.drawText(watchMetas[i], textLeft, textTop + 80 * k, lCardMeta);
    }

    /** Resumes it where you left it (the app's own link); if that link can't be used, the app itself. */
    private void openWatching(WatchNext.Item it) {
        Intent link = it.intent == null ? null : linkIntent(it.intent, it.pkg);
        if (link != null && link.resolveActivity(activity.getPackageManager()) != null) {
            try {
                activity.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("continue watching link: " + e.getMessage());
            }
        }
        if (!openApp(it)) showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
    }

    /**
     * Another app wrote this link (#94's linkIntent, by @GizmoH2o): it only ever opens that app, never ours or a third
     * one, with no selector and no permissions handed along. Null if it can't be used.
     */
    private static Intent linkIntent(String uri, String pkg) {
        if (HomeMode.PKG.equals(pkg)) return null;
        Intent intent;
        try {
            intent = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {   // a bad launchFlags= throws NumberFormatException
            AppLog.w("continue watching link: " + e.getMessage());
            return null;
        }
        if (intent.getComponent() != null && !intent.getComponent().getPackageName().equals(pkg)) return null;
        intent.setSelector(null);
        intent.setPackage(pkg);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return intent;
    }

    /** The item's app by name, for a toast (its package if the name is unknown). */
    private static String appName(WatchNext.Item it) {
        return it.app != null && !it.app.isEmpty() ? it.app : it.pkg;
    }

    /** The item's app, from its start (guarded like HomeApps.open: a missing or refusing app shows a toast). */
    private boolean openApp(WatchNext.Item it) {
        Intent launch = HomeApps.launchIntent(activity.getPackageManager(), it.pkg);
        if (launch == null) return false;
        try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.pkg + ": " + e.getMessage());
            return false;
        }
    }

    // MARK: - Continue Watching: the menu of a held card

    private boolean canHold() {
        return zone == Z_WATCH && !sheetOpen && !drawerOpen && continueMode() && watchIndex < watching.size();
    }

    /** The focused card's menu (held OK, or TalkBack's long click). */
    private void showMenu() {
        if (menuOpen || !canHold() || menuTitlePaint == null) return;
        menuItem = watching.get(watchIndex);
        float width = (MENU_W - 2 * MENU_PAD) * k;
        String title = menuItem.title + (menuItem.episode != null ? " · " + menuItem.episode : "");
        menuTitle = TextUtils.ellipsize(title, new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
        menuSub = menuItem.app == null ? "" : TextUtils.ellipsize(menuItem.app, new TextPaint(menuSubPaint), width,
                TextUtils.TruncateAt.END).toString();
        menuOptions[0] = Ui.tr("Hide from Continue Watching");
        menuOptions[1] = Ui.tr("Open the app");
        menuOptions[2] = Ui.tr("Cancel");
        layoutMenu();
        menuOpen = true;
        menuIndex = 0;
        menuPick.snap(0);
        menu.target = 1;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
        wake();
        click.open();
        spoken.focusMoved();
    }

    /** The panel, centered on the screen: title, the app's name (if known) and the three options. */
    private void layoutMenu() {
        float rows = (MENU_PAD + 34 + (menuSub.isEmpty() ? 0 : 38) + 32 + 2 * MENU_STEP + MENU_ROW + MENU_INSET) * k;
        float left = (w - MENU_W * k) / 2, top = (h - rows) / 2;
        menuPanel.set(left, top, left + MENU_W * k, top + rows);
        menuOptionsTop = top + (MENU_PAD + 34 + (menuSub.isEmpty() ? 0 : 38) + 32) * k;
    }

    private void menuRow(int i, RectF out) {
        float y = menuOptionsTop + i * MENU_STEP * k;
        out.set(menuPanel.left + MENU_INSET * k, y, menuPanel.right - MENU_INSET * k, y + MENU_ROW * k);
    }

    private void closeMenu(boolean snap) {
        if (!menuOpen && !snap) return;
        boolean was = menuOpen;
        menuOpen = false;
        menu.target = 0;
        if (snap) {
            menu.snap(0);
            menuLayer.setVisibility(INVISIBLE);
        }
        menuLayer.invalidate();
        wake();
        if (was) spoken.focusMoved();
    }

    private boolean menuKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveMenu(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveMenu(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) pickMenu();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closeMenu(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void moveMenu(int d) {
        int next = Math.max(0, Math.min(menuOptions.length - 1, menuIndex + d));
        if (next == menuIndex) return;
        menuIndex = next;
        menuPick.target = next;
        menuLayer.invalidate();
        wake();
        click.move();
        spoken.focusMoved();
    }

    private void pickMenu() {
        WatchNext.Item it = menuItem;
        int choice = menuIndex;
        closeMenu(false);
        if (it == null) return;
        if (choice == 0) {
            click.open();
            hideWatching(it);
        } else if (choice == 1) {
            click.open();
            if (openApp(it)) return;
            showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
        } else {
            click.move();
        }
    }

    /** Remembered (Settings > Home Settings shows it again) and taken off the row now; the focus stays in place. */
    private void hideWatching(WatchNext.Item it) {
        String key = WatchNext.hideKey(it);
        Set<String> hidden = Prefs.watchHidden();
        hidden.add(key);
        Prefs.setWatchHidden(hidden);
        hiddenNow.add(key);
        List<WatchNext.Item> list = new ArrayList<>(watching.size());
        for (WatchNext.Item x : watching) if (!key.equals(WatchNext.hideKey(x))) list.add(x);
        watching = list;
        watchTitles = null;
        watchIndex = Math.min(watchIndex, Math.max(0, list.size() - 1));
        if (list.isEmpty() && zone == Z_WATCH) zone = Z_SHELF;
        retarget(false);
        heroDirty = true;
        showToast(Ui.tr("Hidden. Settings > Home Settings shows them again."));
    }

    /** Only while it shows (its layer is invisible otherwise); texts were cut to size when it opened. */
    private void drawMenu(Canvas c) {
        float v = menu.value, a = Ui.clamp(v);
        if (a <= 0.004f) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(tone(0x38000000, 0x80000000), a));
        c.drawRect(screen, fill);
        float s = 0.92f + 0.08f * v, r = MENU_R * k;
        c.save();
        c.scale(s, s, menuPanel.centerX(), menuPanel.centerY());
        tmp.set(menuPanel);
        tmp.offset(0, 18 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.5f) * a));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        fill.setColor(Ui.alpha(tone(0xFFFFFFFF, 0xFF2C2C2E), a));
        c.drawRoundRect(menuPanel, r, r, fill);
        border.setColor(Ui.alpha(tone(0x14000000, 0x1FFFFFFF), a));
        c.drawRoundRect(menuPanel, r, r, border);
        float x = menuPanel.left + MENU_PAD * k, y = menuPanel.top + (MENU_PAD + 34) * k;
        menuTitlePaint.setColor(Ui.alpha(tone(L_INK, WHITE), a));
        c.drawText(menuTitle, x, y, menuTitlePaint);
        if (!menuSub.isEmpty()) {
            menuSubPaint.setColor(Ui.alpha(tone(L_DIM, 0xFF98989D), a));
            c.drawText(menuSub, x, y + 38 * k, menuSubPaint);
        }
        // The focus pill slides between the options.
        float rowR = (MENU_R - MENU_INSET) * k, pick = menuPick.value;
        menuRow(0, tmp);
        tmp.offset(0, pick * MENU_STEP * k);
        fill.setColor(Ui.alpha(tone(L_INK, WHITE), a));
        c.drawRoundRect(tmp, rowR, rowR, fill);
        int ink = tone(L_INK, WHITE), onPill = tone(WHITE, L_INK);
        for (int i = 0; i < menuOptions.length; i++) {
            menuRow(i, tmp);
            float f = Ui.clamp(1 - Math.abs(pick - i));
            int base = i == 0 ? tone(0xFFD70015, 0xFFFF6961) : ink;   // hiding is the destructive one: red
            menuOptionPaint.setColor(Ui.alpha(blend(base, onPill, f), a));
            c.drawText(menuOptions[i], tmp.left + 28 * k, tmp.centerY() + Ui.halfCap(menuOptionPaint), menuOptionPaint);
        }
        c.restore();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        // A tab opens at its first pick, its row at the start: at once, as sliding back would first show the new
        // tab's cards scrolled to where the last tab was.
        cardIndex = 0;
        aim(cardOpen, 0, true);
        cardScroll.snap(0);
        updateCards();
        if (rowsMode()) syncRow(true);
        heroDirty = true;
    }

    /**
     * Background: the focused card's photo, heavily blurred and almost white; without it, a light gradient. At night,
     * the same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (continueMode()) {
                drawTint(c);
            } else if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f && !continueMode()) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    /** The card's colour as a pale tint by day, a deep one at night, with a fixed shade toward the bottom. */
    private void drawTint(Canvas c) {
        fill.setShader(null);
        fill.setColor(tintColor(blend(tintFrom, tintTo, Ui.clamp(tintMix.value))));
        c.drawRect(screen, fill);
        if (tintShade == null) tintShade = new LinearGradient(0, 0, 0, h, 0x00000000, 0x33000000, Shader.TileMode.CLAMP);
        fill.setShader(tintShade);
        c.drawRect(screen, fill);
        fill.setShader(null);
    }

    /** The colour to ease toward: the focused card's (or the last one focused), once its picture is in. */
    private void updateTint() {
        if (!continueMode() || watching.isEmpty()) {
            if (tinted) {
                tinted = false;
                tintFrom = tintTo = TINT_WAIT;
                tintMix.snap(1);
                sky.invalidate();
                platter.invalidate();
                if (bgTo == null && bgFadeAt == 0) neutralGlass();
            }
            return;
        }
        WatchNext.Item it = watching.get(Math.min(watchIndex, watching.size() - 1));
        Integer color = watchArt.color(it.pictureKey, it.bannerKey);
        if (color == null || tinted && color == tintTo) return;
        // The first colour eases in from TINT_WAIT like any other change: no jump when the pictures arrive.
        tinted = true;
        tintFrom = blend(tintFrom, tintTo, Ui.clamp(tintMix.value));
        tintTo = color;
        tintMix.snap(0);
        tintMix.target = 1;
        easeGlass();
        sky.invalidate();
        platter.invalidate();
        drawerDirty = heroDirty = true;
        wake();
    }

    /**
     * The glass eases from the old tint to the new one with the background: both as images, blended by the doFrame
     * (Android 13+, where the glass reads them). Before that, the glass is a flat fill of glassLight(), which already
     * follows the tint: no images to fill on every move.
     */
    private void easeGlass() {
        if (bgTo != null || bgFadeAt != 0 || Build.VERSION.SDK_INT < 33) return;
        if (tintGlassPx == null) tintGlassPx = new int[Backdrop.GW * Backdrop.GH];
        plainImage(neutralGlassPx, Backdrop.GW, Backdrop.GH, tintFrom);
        plainImage(tintGlassPx, Backdrop.GW, Backdrop.GH, tintTo);
        scene.setGlassImages(neutralGlassPx, tintGlassPx);
        scene.setGlassMix(0);
        plainImage(neutralFrostPx, Backdrop.FW, Backdrop.FH, tintTo);   // the drawer's glass: closed while the row moves
        scene.setFrost(neutralFrostPx);
        nightPair = false;
        glassEasing = true;
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (clockMode()) {
            titleRight = 0;
            drawClock(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        String hello = picksMode() ? Ui.tr("For You") : Ui.tr("Continue Watching");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        titleRight = MARGIN * k + lTitle.measureText(hello);
        if (!picksMode()) {
            if (continueMode()) drawWatchRow(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < tabNames.length; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabNames[i], r.centerX() - p.measureText(tabNames[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (rowsMode()) {
            drawRows(c);
        } else {
            layoutCards();
            int top = -1;
            int slots = cardSlots;   // the others aren't drawn: at 3, no card peeks in at the right edge
            for (int i = 0; i < slots; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
            for (int i = 0; i < slots; i++) if (i != top) drawLightCard(c, i);
            if (top >= 0) drawLightCard(c, top);
        }
        if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
    }

    // MARK: - The YouTube tab: a row per category

    /** The YouTube tab shows rows (the other tabs, the carousel). */
    private boolean rowsMode() {
        return tabKinds.length > 0 && "yt".equals(tabKinds[tab]);
    }

    /** Card i of row r where it is right now (the springs move the page and the rows). */
    private void ytCardRect(int r, int i, RectF out) {
        float y = (YT_TOP + (r - ytScroll.value) * YT_ROW_H + YT_CARD_TOP) * k;
        float x = (MARGIN + (i - ytSlide[r].value) * (YT_CARD_W + YT_GAP)) * k;
        out.set(x, y, x + YT_CARD_W * k, y + YT_CARD_H * k);
    }

    /** The rows (every 15 s and when YouTube has news): each row keeps its place, the focus stays where it was. */
    private void updateRows() {
        List<YouTube.Row> rows = YouTube.get(getContext()).rows();
        if (!sameRows(rows, ytRows)) {
            ytRows = rows;
            // The titles under the cards, shortened once here (not on every frame).
            ytTitles.clear();
            ytFull.clear();
            ytFullW.clear();
            titleRow = -1;
            fitPaint.set(lCardMeta);
            for (YouTube.Row row : rows) {
                String[] t = new String[row.picks.size()], full = new String[t.length];
                float[] fullW = new float[t.length];
                for (int i = 0; i < t.length; i++) {
                    String s = row.picks.get(i).title == null ? "" : row.picks.get(i).title;
                    t[i] = TextUtils.ellipsize(s, fitPaint, YT_CARD_W * k, TextUtils.TruncateAt.END).toString();
                    full[i] = s;
                    fullW[i] = fitPaint.measureText(s);
                }
                ytTitles.add(t);
                ytFull.add(full);
                ytFullW.add(fullW);
            }
            ytRow = Math.max(0, Math.min(ytRow, Math.min(YT_ROWS, rows.size()) - 1));
            for (int r = 0; r < YT_ROWS; r++) ytCol[r] = r < rows.size() ? Math.max(0, Math.min(ytCol[r], rows.get(r).picks.size() - 1)) : 0;
            if (zone == Z_CARDS && rows.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        syncRow(false);
        spoken.focusMoved();
    }

    private static boolean sameRows(List<YouTube.Row> a, List<YouTube.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).title.equals(b.get(i).title) || !samePicks(a.get(i).picks, b.get(i).picks)) return false;
        }
        return true;
    }

    /**
     * The focused row: its cards stand in for `cards` (OK, TalkBack), the page and the rows slide so it shows, and the
     * background follows the focused video.
     */
    private void syncRow(boolean snap) {
        int n = Math.min(YT_ROWS, ytRows.size());
        heroDirty = true;
        if (n == 0) {
            cards = new ArrayList<>();
            cardIndex = 0;
            showBackdrop(null);
            return;
        }
        cards = ytRows.get(ytRow).picks;
        cardIndex = ytCol[ytRow];
        float scroll = Math.max(0, Math.min(ytRow, n - 2));     // the focused row on top (the last one, second)
        ytScroll.target = scroll;
        if (snap) ytScroll.snap(scroll);
        for (int r = 0; r < n; r++) {
            float slide = Math.max(0, Math.min(ytCol[r] - 2, ytRows.get(r).picks.size() - YT_VISIBLE));
            ytSlide[r].target = slide;
            if (snap) ytSlide[r].snap(slide);
        }
        // The background is built only once the focus rests (each one is a full-size photo, blurred and kept: passing
        // along a row would otherwise build one per card).
        handler.removeCallbacks(rowBackdrop);
        if (snap) rowBackdrop.run();
        else handler.postDelayed(rowBackdrop, 400);
    }

    private final Runnable rowBackdrop = this::showRowBackdrop;

    private void showRowBackdrop() {
        if (!rowsMode() || cards.isEmpty() || cardIndex >= cards.size()) return;
        Backdrop b = recs.backdrop(cards.get(cardIndex));
        if (b != null) showBackdrop(b);
    }

    /** ↑ ↓ between the rows (past the first, the tabs; past the last, the shelf); ← → along a row. */
    private boolean moveRows(int dx, int dy) {
        int n = Math.min(YT_ROWS, ytRows.size());
        if (zone != Z_CARDS) {
            if (n > 0) {
                zone = Z_CARDS;
            } else if (zone == Z_SHELF) {
                zone = Z_TOP;
                topIndex = tab;
            } else {
                zone = Z_SHELF;
            }
        } else if (dx != 0) {
            int next = Math.max(0, Math.min(ytRows.get(ytRow).picks.size() - 1, ytCol[ytRow] + dx));
            if (next == ytCol[ytRow]) return true;
            ytCol[ytRow] = next;
        } else if (dy < 0) {
            if (ytRow == 0) {
                zone = Z_TOP;
                topIndex = tab;
            } else {
                ytRow--;
            }
        } else if (ytRow >= n - 1) {
            zone = Z_SHELF;
        } else {
            ytRow++;
        }
        syncRow(false);
        retarget(false);
        click.move();
        return true;
    }

    /** The focused title rests this long, scrolls once at TITLE_SPEED to its end, rests, and goes back to its start. */
    private static final long TITLE_WAIT = 1500, TITLE_HOLD = 2000;
    private static final float TITLE_SPEED = 70;

    /**
     * The focused row card's title, when it's wider than the card: moves it along (titleShift) and says whether it's
     * moving now. While it rests, nothing is redrawn: titleWake brings the frames back when it moves on. Once round,
     * then it stays at its start until the focus moves (nothing moves on a screen left alone).
     */
    private boolean tickTitle(long up) {
        int r = rowsMode() && zone == Z_CARDS ? ytRow : -1, col = r >= 0 ? ytCol[r] : -1;
        if (r != titleRow || col != titleCol) {
            boolean was = titleShift >= 0;
            titleRow = r;
            titleCol = col;
            titleSince = up;
            titleShift = -1;
            handler.removeCallbacks(titleWake);
            if (was) heroDirty = true;
        }
        if (r < 0 || r >= ytFullW.size() || col >= ytFullW.get(r).length) return false;
        float over = ytFullW.get(r)[col] - YT_CARD_W * k;
        if (over <= 0) return false;
        long travel = (long) (over / (TITLE_SPEED * k) * 1000), t = up - titleSince;
        if (t >= TITLE_WAIT + travel + TITLE_HOLD) {
            if (titleShift != -1) heroDirty = true;
            titleShift = -1;
            return false;
        }
        if (t < TITLE_WAIT) {
            if (titleShift != -1) heroDirty = true;
            titleShift = -1;
            handler.removeCallbacks(titleWake);
            handler.postDelayed(titleWake, TITLE_WAIT - t);
            return false;
        }
        if (t >= TITLE_WAIT + travel) {
            if (titleShift != over) heroDirty = true;
            titleShift = over;
            handler.removeCallbacks(titleWake);
            handler.postDelayed(titleWake, TITLE_WAIT + travel + TITLE_HOLD - t);
            return false;
        }
        titleShift = over * (t - TITLE_WAIT) / travel;
        return true;
    }

    /**
     * The rows, below the tabs and above the shelf: each category's name, then its videos (YouTube's thumbnails, gray
     * until they arrive). The focused card only grows, rises and gets the shadow and a light rim, as the picks do.
     */
    private void drawRows(Canvas c) {
        float top = (YT_TOP - 2) * k, bottom = (L_PLATTER_TOP - 4) * k;    // rows scrolled up leave no sliver
        YouTube yt = YouTube.get(getContext());
        c.save();
        c.clipRect(0, top, w, bottom);
        for (int r = 0; r < Math.min(YT_ROWS, ytRows.size()); r++) {
            float y = (YT_TOP + (r - ytScroll.value) * YT_ROW_H) * k;
            if (y > bottom || y + YT_ROW_H * k < top) continue;
            YouTube.Row row = ytRows.get(r);
            c.drawText(row.title, MARGIN * k, y + YT_LABEL * k, lCardTitle);
            String[] titles = r < ytTitles.size() ? ytTitles.get(r) : null;
            for (int i = 0; i < row.picks.size(); i++) {
                ytCardRect(r, i, ytCard);
                if (ytCard.right < 0 || ytCard.left > w) continue;
                boolean focused = zone == Z_CARDS && r == ytRow && i == ytCol[r];
                float v = focused && i < cardFocus.length ? cardFocus[i].value : 0, f = Ui.clamp(v), s = 1 + 0.05f * v;
                float bw = ytCard.width(), bh = ytCard.height(), cx = ytCard.centerX(), cy = ytCard.centerY() - 4 * k * v;
                cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
                float rr = 14 * k * s;
                if (f > 0.01f) {
                    ytShadow.set(cell);
                    ytShadow.offset(0, 12 * k * f);
                    shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.3f, 0.5f) * f));
                    shadows.draw(c, ytShadow, rr, DEEP_BLUR * k, shadowDeep);
                }
                Recs.Pick p = row.picks.get(i);
                Bitmap thumb = "yt".equals(p.kind) ? yt.thumb(p.video, thumbReady) : null;
                BitmapShader thumbShader = thumb != null ? yt.shader(p.video) : null;
                if (thumbShader != null) {
                    coverImage(c, thumb, thumbShader, cell, rr, 1);
                } else {
                    fill.setShader(null);
                    fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                    c.drawRoundRect(cell, rr, rr, fill);
                }
                if (f > 0.01f) {
                    rim.setShader(null);
                    rim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
                    rim.setStrokeWidth(3 * k);
                    c.drawRoundRect(cell, rr, rr, rim);
                    rim.setStrokeWidth(2.5f * k);
                }
                if (titles != null && i < titles.length) {
                    int color = lCardMeta.getColor();
                    if (focused) lCardMeta.setColor(lCardTitle.getColor());
                    if (focused && titleShift >= 0 && r == titleRow && i == titleCol) {
                        // Whole and moving, inside the card's width (the rest shows as it passes).
                        c.save();
                        c.clipRect(ytCard.left, ytCard.bottom, ytCard.right, ytCard.bottom + 48 * k);
                        c.drawText(ytFull.get(r)[i], ytCard.left - titleShift, ytCard.bottom + 34 * k, lCardMeta);
                        c.restore();
                    } else {
                        c.drawText(titles[i], ytCard.left, ytCard.bottom + 34 * k, lCardMeta);
                    }
                    lCardMeta.setColor(color);
                }
            }
        }
        c.restore();
    }

    private boolean picksOn() {
        return tabKinds.length > 0;
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        if (base.right < 0 || base.left > getWidth()) return;   // slid off screen
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : b.wide;
        Recs.Poster poster = recs.poster(p);
        float open = Ui.clamp(cardOpen[i].value);
        if (poster != null) {   // narrow: the poster; as the card widens the backdrop fades in over it
            float back = img == null ? 0 : Ui.clamp((open - 0.1f) / 0.6f);
            if (back < 0.999f) coverImage(c, poster.bitmap, poster.shader, cell, r, 1);   // not once the backdrop covers it
            if (back > 0.001f) coverImage(c, img, b.wideShader, cell, r, back);
        } else if (img != null) {
            coverImage(c, img, b.wideShader, cell, r, 1);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
            }
            if (lightSheens[i] == null) {   // in a unit box: the matrix below stretches them to the card as it widens
                lightSheens[i] = new LinearGradient(-0.5f, -0.5f, 0, 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -0.5f, 0, 0.5f, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            imageMatrix.setScale(bw, bh);
            lightSheens[i].setLocalMatrix(imageMatrix);
            lightRims[i].setLocalMatrix(imageMatrix);
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        // The cards peeking in at the sides show only their image: their text would start off screen.
        if (p == null || base.left < 0 || base.left > (CONTENT_R - 100) * k) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (open > 0.02f && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            int a = lCardMeta.getAlpha();
            lCardMeta.setAlpha(Math.round(a * open));
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
            lCardMeta.setAlpha(a);
        }
        // A narrow card shows only its title: why it's there (Trakt's "Up next: S2 E5") goes under it, fading into the
        // whole meta (which starts with it) as the card widens.
        if (open < 0.98f && p.tag != null) {
            fitPaint.set(lCardMeta);
            int a = lCardMeta.getAlpha();
            lCardMeta.setAlpha(Math.round(a * (1 - open)));
            c.drawText(TextUtils.ellipsize(p.tag, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
            lCardMeta.setAlpha(a);
        }
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        drawStatusIcons(c);
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            float f = Ui.clamp(topFocus[B0 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(glassLight(), SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[B0].left) - 28 * k, cy = L_BUTTON_CY * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    /** Wi-Fi/Ethernet, Bluetooth (when something is connected) and a USB drive, at the top left. */
    private void drawStatusIcons(Canvas c) {
        if (!Prefs.statusIcons()) return;
        float height = L_STATUS * k, gap = L_STATUS_GAP * k;
        int color = Ui.alpha(tone(L_INK, 0xEBFFFFFF), 0.85f);
        float x = MARGIN * k;
        // The top title ("For You", or "Continue Watching") is on this line too: the icons start after it, not on it.
        if (!clockMode()) x += lTitle.measureText(picksMode() ? Ui.tr("For You") : Ui.tr("Continue Watching")) + gap;
        if (network != 0) x = drawStatusIcon(c, network == 2 ? "ethernet" : "wifi", x, height, gap, color);
        if (bluetoothConnected) x = drawStatusIcon(c, "bluetooth", x, height, gap, color);
        if (usbConnected) drawStatusIcon(c, "usb", x, height, gap, color);
    }

    /** Narrow symbols need a little more height to look as big as wide ones. */
    private static float statusScale(String name) {
        if ("bluetooth".equals(name)) return 1.18f;
        if ("usb".equals(name)) return 1.3f;
        return 1;
    }

    /** Draws a status icon left-aligned at `left`; returns where the next one starts. */
    private float drawStatusIcon(Canvas c, String name, float left, float height, float gap, int color) {
        float size = Symbols.pointSizeForHeight(getContext(), name, height * statusScale(name));
        float width = Symbols.width(getContext(), name, size);
        Symbols.draw(getContext(), c, name, left + width / 2, L_BUTTON_CY * k, size, color);
        return left + width + gap;
    }

    private boolean moveLight(int dx, int dy) {
        if (rowsMode() && (zone == Z_CARDS || (zone == Z_SHELF && dy < 0) || (zone == Z_TOP && dy > 0))) return moveRows(dx, dy);
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (!picksMode()) {
                    // No tabs or picks: up goes to what you were watching, or to the corner buttons.
                    if (continueMode() && !watching.isEmpty()) {
                        zone = Z_WATCH;
                    } else if (!lightButtons.isEmpty()) {
                        zone = Z_TOP;
                        topIndex = B0;
                    } else {
                        return true;
                    }
                    break;
                }
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksMode() ? tab : B0;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_WATCH:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(watching.size() - 1, watchIndex + dx));
                    if (next == watchIndex) return true;
                    watchIndex = next;
                } else if (dy < 0) {
                    if (lightButtons.isEmpty()) return true;
                    zone = Z_TOP;
                    topIndex = B0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_TOP:
                if (dx != 0 && !picksMode() && topIndex + dx < B0) return true;   // no tabs to the left
                if (dy > 0 && !picksMode()) {
                    zone = continueMode() && !watching.isEmpty() ? Z_WATCH : Z_SHELF;
                    break;
                }
                if (dx != 0) {
                    // From the last tab to the first button and back (the slots between them are empty).
                    int next = topIndex + dx;
                    if (dx > 0 && topIndex < B0 && next >= tabKinds.length) next = lightButtons.isEmpty() ? topIndex : B0;
                    if (dx < 0 && topIndex >= B0 && next < B0) next = tabKinds.length > 0 ? tabKinds.length - 1 : topIndex;
                    next = Math.max(0, Math.min(B0 + lightButtons.size() - 1, next));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < B0) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (topIndex < B0 && !lightButtons.isEmpty()) {
                    topIndex = B0;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
