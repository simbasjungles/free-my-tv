package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Updates.schedule(this);   // the week's update check, once (a fresh install has had no boot or update yet)
        Ui.init(this);
        SleepTimer.listen(this);   // :home may start here, from the launcher icon
        SleepTimer.check(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (view != null) view.stopKeyPage();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system); with Watch from the
            // screensaver on, OK opens the title first.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean ok = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                        if (ok && Prefs.saverWatch()) preview.watch();
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                POWER = 6, TMDB_KEY = 7, PICKS = 8, PICK_FROM = 9, WATCH_APPS = 10;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The main menu: eight rows, then the comparison, the picks (with the Watchmode credit) and two notices, all on
        // one 1080 px screen: the rows share the 462 px that seven took.
        private static final float MAIN_TOP = 140, ROW_H = 53.75f, MAIN_GAP = 4;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private boolean someServicesOff;   // is a streaming service on the TV switched off in Picks from (counted when the screen opens)
        private final List<String[]> services = new ArrayList<>();   // {key, name} of the streaming services on the TV (Picks from)
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        /** The shelf editor's first row: the order set here or the last one opened first (Prefs.shelfRecent). */
        private static final String ORDER_ROW = "#order";
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private float touchX, touchY;
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_SLEEP = -2, PICK_PLACE = -3, PICK_PHOTOS = -4,
                PICK_ALBUM = -5;
        /**
         * The pickers from PICK_SLEEP down (Sleep Timer's, then My Photos' with Immich): their choices as {value, name},
         * and the value now.
         */
        private final List<String[]> choices = new ArrayList<>();
        private String choiceNow = "";
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private JSONObject updates = new JSONObject();   // Updates' file (the main process writes it), read in poll
        private long checkAskedAt, installAskedAt;       // OK on Updates, on the wall clock as the file's "at"
        private String installed = "";                   // this app's version (an update starts a new process)
        // TMDB key: the page a phone opens (KeyPage), its QR code and what the screen says, all made when they change,
        // never while drawing.
        private KeyPage keyPage;
        private boolean keyWatchmode;   // the screen is the Watchmode key's, else TMDB's
        private Bitmap keyQr;
        private boolean keyChecking;
        private int[] keyRowIds = new int[0];
        private StaticLayout keyPurpose, keySteps, keyStatus;
        private String keyHelp;   // the purpose and the steps, for TalkBack
        private Paint keyHeading, keyUrl, qrPaint, qrSlot;
        // Continue Watching apps: {package, name}, null until the background scan is done; hidden items when opened.
        private List<String[]> watchApps;
        private int watchScan, watchHiddenCount;
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy, framePending;
        private String pollStamp = "";
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private Toast whyToast;   // why the focused option is unavailable
        private String[] whyRows;  // each row's reason, worked out once per screen (null: again on the next frame)
        private int whyScreen = -1, whyScroll = -1;
        private int sleepLeft;     // the sleep timer's minutes left, read with the state, not on every frame
        private int whyState = -1;
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        // The aurora's own clock, which runs only in the frames drawn: it rests with the screen, and the next key or the
        // minute's redraw doesn't jump it ahead (at this speed, a minute moves it a whole noise cell).
        private float skySeconds;
        private long requestedAt;   // the switch asked for: its stamp, to tell whether its job ever started
        private boolean stalled;    // it didn't: a TV maker's background blocker (stalledText says what to do)
        private final boolean phone;   // installed on a phone: the settings say where it goes (HomeMode.phone)
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice, rowValueFit;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                String updatesWas = updatesValue();
                updates = Updates.state(getContext());
                if (screen == HOME_SETTINGS && pickScroll + focus == updatesRow() && !updatesWas.equals(updatesValue())) {
                    spoken.focusMoved();   // TalkBack reads the Updates row's new value
                }
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) {
                    pendingAt = 0;
                    stalled = false;
                } else if (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt >= 10_000) {
                    // Asked 10 s ago and its job never ran: say why, rather than quietly showing Original again (#364).
                    pendingAt = 0;
                    if (!ModeJob.started(getContext(), requestedAt)) {
                        stalled = true;
                        AppLog.w("the switch's job didn't start" + (RemoteKeys.TCL ? " (TCL's AUTO_START not allowed?)" : ""));
                        announceForAccessibility(stalledText());
                    }
                }
                // What can change the reasons away from a key: a switch (or the watchdog's revert) and the overlay's and the
                // listener's checks.
                int whyNow = (freeActive() ? 1 : 0) | (Capabilities.refused(getContext(), Capabilities.OVERLAY) ? 2 : 0)
                        | (Capabilities.refused(getContext(), Capabilities.LISTENER) ? 4 : 0);
                if (whyNow != whyState) whyRows = null;
                whyState = whyNow;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                // Redrawn only when something shown changed: also My Photos' count (Immich's arrive while it shows) and
                // the minute (the notices' "{n} min ago").
                String stamp = state.toString() + recs.toString() + updates.toString() + ":" + sleepLeft + ":" + whyNow
                        + ":" + hasKey + ":" + pendingAt + ":" + stalled + ":" + busy() + ":" + updatesValue()
                        + ":" + (Trakt.available() ? traktValue() : "") + ":" + System.currentTimeMillis() / 60_000
                        + ":" + (screen == HOME_SETTINGS ? Photos.files(getContext()).size() : 0);
                if (!stamp.equals(pollStamp)) invalidate();
                pollStamp = stamp;
                handler.postDelayed(this, busy() || updating() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            Shaders.settings(scene.palette);
            scene.setMotion(0.5f, 0.2f);
            phone = HomeMode.phone(a);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Free My TV");
            installed = Updates.installedVersion(a);
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    if (screen == HOME_SETTINGS && pickScroll + focus == TOP_ROW && id - 1 != focus) commitWatch();
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen, and why where it's unavailable. */
        private String rowText(int row) {
            String why = unavailable(row);
            return why == null ? rowValueText(row) : rowValueText(row) + ", " + Ui.tr("Unavailable") + ". " + why;
        }

        private String rowValueText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("Sleep Timer"),
                            Ui.tr("TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS:
                case PICKS:
                case POWER: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = listLabels(), values = listValues();
                    int at = keys ? row : pickScroll + row;
                    return at >= labels.length ? "" : values[at].equals("›") ? labels[at] : labels[at] + ", " + values[at];   // "›" is only drawn
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    // "›" (Immich albums opens a list) is only drawn, as in Home Settings
                    return pickLabels.get(at).replace("›", "").trim() + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case PICK_FROM: {
                    int at = pickScroll + row;
                    if (at >= services.size()) return "";
                    return services.get(at)[1] + ", " + onOff(!Prefs.serviceOff(services.get(at)[0])) + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", services.size());
                }
                case WATCH_APPS: {
                    int at = pickScroll + row;
                    if (watchApps == null || at >= listSize()) return "";
                    if (at == watchApps.size()) return hiddenLabel();
                    String[] app = watchApps.get(at);
                    return app[1] + ", " + onOff(!Prefs.watchOff().contains(app[0])) + ", "
                            + Ui.trf("{i} of {n}", "i", at + 1, "n", watchApps.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    if (app[0].equals(ORDER_ROW)) return app[1] + ", " + shelfOrderValue();
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                case TMDB_KEY:
                    if (row >= keyRowIds.length) return "";
                    // TalkBack can't see the drawn text: the first row also says what the key is for and how to get one.
                    return row == 0 && keyHelp != null ? keyLabel(keyRowIds[0]) + ". " + keyHelp : keyLabel(keyRowIds[row]);
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            whyRows = null;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            if (watchGrantPending) handler.postDelayed(askWatch, WATCH_GRANT_MS);
            invalidate();
            spoken.readAgain();
            Photos.importInbox(getContext(), this::invalidate);   // photos a computer just sent
        }

        void pause() {
            commitWatch();
            if (running && !busy() && memoryMissing()) {   // measured once the home screen is back in front
                AppLog.w("memory of " + state.optString("mode") + " missing: measuring again");
                ModeJob.schedule(getContext(), ModeJob.MEMORY, state.optString("mode"), 5_000);
            }
            running = false;
            handler.removeCallbacks(poll);
            handler.removeCallbacks(askWatch);
            Choreographer.getInstance().removeFrameCallback(this);
            framePending = false;
            if (keyPage != null) {   // the phone's page answers only while its code shows (not after Home, standby, another app)
                stopKeyPage();
                setKeyStatus(Ui.tr("This code has expired."), false);
                keyRowsChanged();
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            framePending = false;
            if (!running) return;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            Symbols.warm(getContext(), "sparkles", 150 * k);
            Symbols.warm(getContext(), "chevron_right", 22 * k);
            Symbols.warm(getContext(), "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowValueFit = new TextPaint(rowValue);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            keyHeading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            keyUrl = Ui.text(Ui.medium, 27 * k, 0xF2FFFFFF);
            qrPaint = new Paint();   // no filtering: each module stays a sharp square
            qrPaint.setFilterBitmap(false);
            qrSlot = new Paint(Paint.ANTI_ALIAS_FLAG);
            qrSlot.setColor(0x14FFFFFF);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        /** Memory is measured two minutes after a switch: until then its cells say it's coming. */
        private boolean memorySoon() {
            return System.currentTimeMillis() - state.optLong("since") < 180_000;
        }

        /**
         * The mode in use was measured at its switch, but its memory wasn't, well after it should have been (the job
         * didn't run, ADB was away, or its home screen wasn't in front then). Only after a switch: before one, asking
         * would bring up the TV's ADB prompt.
         */
        private boolean memoryMissing() {
            String mode = state.optString("mode");
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject m = metrics == null ? null : metrics.optJSONObject(mode);
            if (m == null || !state.has("since") || memorySoon()) return false;
            if (HomeMode.FREE.equals(mode) ? !freeActive() : state.optString("originalHome").isEmpty()) return false;
            return !m.has("memAvailableMB") || !m.has("homePssMB");
        }

        /**
         * Why a switch never started, and what to do. TCL's TclAppBoot blocks the job until its permission is allowed
         * from a computer, and an uninstall takes that back (#364, #375): the README's command, for both of its names.
         */
        private static String stalledText() {
            return RemoteKeys.TCL
                    ? Ui.trf("TCL blocks Free My TV from running in the background, so nothing changed. From a computer, "
                            + "run this, then try again: {command}", "command",
                            "adb shell \"appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow\"")
                    : Ui.tr("The TV didn't let Free My TV run in the background, so nothing changed. Try again in a minute.");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 8;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return Math.min(SETTINGS_VISIBLE, settingLabels().length);
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case POWER: return powerRows().length;
                case TMDB_KEY: return keyRowIds.length;
                case PICKS: return picksLabels().length;
                case PICK_FROM: return Math.min(PICK_VISIBLE, services.size());
                case WATCH_APPS: return Math.min(PICK_VISIBLE, listSize());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            boolean moving = screen == MAIN && busy(); // Only the main list draws the switch spinner.
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                moving |= pop[i].step(dt);
            }
            // Frames only while something moves; whatever changes the screen calls invalidate(), which starts them again.
            if (running && moving && !framePending) {
                framePending = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;   // the spinner's
            skySeconds += dt;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(skySeconds);
            }
            scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == TMDB_KEY) drawKey(c);
            else if (screen == PICK_FROM) drawServices(c);
            else if (screen == WATCH_APPS) drawWatchApps(c);
            else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original");
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"),
                    Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("Top Buttons"),
                    Ui.tr("Remote Buttons"), Ui.tr("Sleep Timer"), Ui.tr("TV Settings")};
            String[] values = {working ? "" : mode, Ui.tr("Preview"), "", "", "", "", "", ""};
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                int ink = off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), right, base, rowValue);
                } else if (i >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 20) * k;
            String error = phone ? HomeMode.phoneText() : stalled ? stalledText() : state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14). A stalled switch's
                // ends with a command, which must never be cut: eight there.
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(stalled ? 8 : 5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: add yours in Home Settings > TMDB key.");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Recs.watchmodeKey().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Recs.watchmodeKey().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            if (a == null && b == null) {   // before the first switch: what the table will hold, not six dashes
                String line = Ui.tr("Measured at each switch, for both home screens.");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
                return y + 82 * k;
            }
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            // The mode just switched to: its memory comes two minutes later, so its cells wait with "…", not "—".
            boolean soon = !busy() && memorySoon();
            JSONObject current = HomeMode.FREE.equals(state.optString("mode")) ? b : a;
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                boolean waits = soon && i > 0;
                c.drawText(metric(a, rows[i][1], waits && current == a), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1], waits && current == b), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key, boolean soon) {
            if (m == null || !m.has(key)) return soon ? "…" : "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        /** A mouse (or a touch screen) clicks a row; the wheel and hover work too. */
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = e.getX();
                    touchY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.hypot(e.getX() - touchX, e.getY() - touchY) < 24 * k) {
                        int row = rowAt(e.getX(), e.getY());
                        if (row >= 0) {
                            leaveTopRow(row);
                            focus = row - pickScroll;
                            activate();
                            whyRows = null;   // as after OK
                            invalidate();
                            spoken.focusMoved();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }

        /** The row the pointer was last over (screen * 1000 + row, -1: none): only a new one moves the focus. */
        private int hoveredRow = -1;

        /** As on the home screen: the focus moves when the pointer comes onto another row, not at every nudge. */
        @Override public boolean onHoverEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                int row = rowAt(e.getX(), e.getY()), at = row - pickScroll, code = row < 0 ? -1 : screen * 1000 + row;
                boolean entered = code != hoveredRow;
                hoveredRow = code;
                if (entered && row >= 0 && at != focus) {
                    leaveTopRow(row);
                    focus = at;
                    invalidate();
                    spoken.focusMoved();
                }
            }
            return true;
        }

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

        /** The row under the pointer, counted from the list's first row (pickScroll's offset included). */
        private int rowAt(float x, float y) {
            if (x < LIST_X * k || x > LIST_R * k) return -1;
            for (int i = 0; i < rowCount(); i++) {
                RectF r = rowBox(i);
                if (!r.isEmpty() && r.contains(x, y)) return pickScroll + i;
            }
            return -1;
        }

        /** As the arrows and TalkBack do: leaving "Top of the screen" on Continue Watching asks for its permission. */
        private void leaveTopRow(int row) {
            if (screen == HOME_SETTINGS && pickScroll + focus == TOP_ROW && row != TOP_ROW) commitWatch();
        }

        private void wheel(int dir) {
            int before = pickScroll + focus;
            if (scrolling()) {
                if (dir > 0 && pickScroll + rowCount() < listSize()) pickScroll++;
                else if (dir < 0 && pickScroll > 0) pickScroll--;
            } else if (dir > 0 && focus < rowCount() - 1) {
                focus++;
            } else if (dir < 0 && focus > 0) {
                focus--;
            }
            if (screen == HOME_SETTINGS && before == TOP_ROW && pickScroll + focus != TOP_ROW) commitWatch();
            invalidate();
            spoken.focusMoved();
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && screen == HOME_SETTINGS && pickScroll + focus == TOP_ROW) commitWatch();
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && screen == HOME_SETTINGS && pickScroll + focus == TOP_ROW) commitWatch();
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        spoken.focusMoved();   // TalkBack reads the new value or place
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == POWER || screen == PICKS || screen == PICK_FROM)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    whyRows = null;   // a value or the rows' order changed: what each row needs may have too
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) {
                            activate();
                            whyRows = null;   // the same after OK
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == HOME_SETTINGS) commitWatch();
                            if (screen == PICKER && pickFor == PICK_ALBUM) {
                                openPhotoSources();
                            } else if (screen == PICKER && pickFor == PICK_PHOTOS) {
                                showSettingsRow(PHOTOS_ROW);
                            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                                focus = powerFocus(pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE);
                                pickScroll = 0;   // the picker's scroll isn't this short list's
                                screen = POWER;
                            } else if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                showSettingsRow(pickFor == PICK_CITY ? CITY_ROW : 0);
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == TMDB_KEY) {
                                closeKeyScreen();
                            } else if (screen == PICKS) {
                                focus = PICKS_ROW;
                                pickScroll = 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICK_FROM) {
                                countServicesOff();
                                pickScroll = 0;   // the Picks screen has a few rows
                                focus = 1;
                                screen = PICKS;
                            } else if (screen == WATCH_APPS) {
                                focus = WATCH_ROW;   // on the list's first screen
                                pickScroll = 0;
                                screen = HOME_SETTINGS;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 4 : 3)
                                        : screen == REMOTE_KEYS ? 5 : screen == POWER ? 6 : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"},
                CLOCKS = {"auto", "24", "12"};
        /** Home Settings: language, temperature, theme, clock, my photos, sounds, top of the screen, picks, Continue
         *  Watching's apps, watch from the screensaver, Android apps, status icons, Recent Apps, weather, city, forecast,
         *  now playing, Home Assistant (only on builds with its address), quotes, one row per coin, then the TMDB and Watchmode keys and
         *  updates (the last row). */
        private static final int CLOCK_ROW = 3, PHOTOS_ROW = 4, TOP_ROW = 6, PICKS_ROW = 7, WATCH_ROW = 8,
                SAVER_ROW = 9, ANDROID_APPS_ROW = 10, STATUS_ICONS_ROW = 11, RECENTS_ROW = 12, POWER_ON_ROW = 13,
                WEATHER_ROW = 14, CITY_ROW = 15, FORECAST_ROW = 16, PLAYING_ROW = 17, HOME_ROW = Config.HOME_ASSISTANT ? 18 : -1,
                QUOTES_ROW = Config.HOME_ASSISTANT ? 19 : 18, COIN_ROWS = QUOTES_ROW + 1;
        /** No "home" on Xiaomi TVs: their remote sends one Home press as a burst of presses (HomeView.BURST_MS). */
        private static final String[] RECENTS = RemoteKeys.XIAOMI ? new String[] {"off", "drawer"}
                : new String[] {"off", "drawer", "home"};
        /** Home Settings rows on screen at once (what fits above the hints at 1080p); a longer list scrolls. */
        private static final int SETTINGS_VISIBLE = 10;

        private boolean dollar() {
            return "BRL".equals(Place.currency());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Clock"));
            l.add(Ui.tr("My Photos"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Top of the screen"));
            l.add(Ui.tr("Picks"));
            l.add(Ui.tr("Continue Watching apps"));
            l.add(Ui.tr("Watch from the screensaver"));
            l.add(Ui.tr("Android apps"));
            l.add(Ui.tr("Status icons"));   // before the weather widget: City and Forecast are the widget's
            l.add(Ui.tr("Recent Apps"));
            l.add(Ui.tr("At power-on"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Forecast"));
            l.add(Ui.tr("Now Playing widget"));
            if (HOME_ROW >= 0) l.add("Home Assistant");
            l.add(Ui.tr("Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dollar"));
            l.add(Ui.tr("TMDB key"));
            l.add(Ui.tr("Watchmode key"));
            l.add(Ui.tr("Updates"));
            return l.toArray(new String[0]);
        }

        /** The Updates row: the last one, after the coins and the keys. */
        private int updatesRow() {
            return settingLabels().length - 1;
        }

        private int keyRow() {
            return updatesRow() - 2;
        }

        private int watchmodeRow() {
            return updatesRow() - 1;
        }

        /** A key's row: "Not set", or the key's last 4 characters (all that's ever shown of it). */
        private static String keyValue(String key) {
            return key.length() < 4 ? Ui.tr("Not set") : "•••• " + key.substring(key.length() - 4);
        }

        private String[] picksLabels() {
            return Trakt.available() ? new String[] {Ui.tr("Picks per tab"), Ui.tr("Picks from"), Ui.tr("Cartoons and anime"), "Trakt"}
                    : new String[] {Ui.tr("Picks per tab"), Ui.tr("Picks from"), Ui.tr("Cartoons and anime")};
        }

        private String[] picksValues() {
            String from = someServicesOff ? Ui.tr("services|Some") : Ui.tr("services|All");
            String animation = onOff(!Prefs.hideAnimation());
            return Trakt.available() ? new String[] {String.valueOf(Prefs.cards()), from, animation, traktValue()}
                    : new String[] {String.valueOf(Prefs.cards()), from, animation};
        }

        /** The Trakt row on the Picks screen (the last one), only with a Trakt API app in config.properties; else -1. */
        private static int traktRow() {
            return Trakt.available() ? 3 : -1;
        }

        private String traktValue() {
            Context c = getContext();
            String code = Trakt.code();
            if (code != null) return Ui.trf("{url}, code {code}", "url", Trakt.url(), "code", code);
            if (Trakt.busy()) return Ui.tr("Connecting…");
            if (Trakt.signedIn(c)) return Trakt.user(c).isEmpty() ? Ui.tr("Signed in") : Trakt.user(c);
            return Trakt.message().isEmpty() ? Ui.tr("Sign in") : Trakt.message();
        }

        /** OK on the Trakt row: shows a code to sign in with, or asks before signing out. */
        private void traktOk() {
            Context c = getContext();
            if (!Trakt.signedIn(c)) {
                // TalkBack reads the row again at each step: the code as it appears, then signed in (or why not).
                Trakt.signIn(c, () -> handler.post(() -> {
                    invalidate();
                    spoken.focusMoved();
                }));
                return;
            }
            String user = Trakt.user(c).isEmpty() ? "Trakt" : Trakt.user(c);
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Trakt")
                    .setMessage(Ui.trf("Sign out of {name}? The picks go back to TMDB's alone.", "name", user))
                    .setPositiveButton(Ui.tr("Sign out"), (d, which) -> { Trakt.signOut(c); invalidate(); })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            int photos = Photos.files(getContext()).size();
            v[PHOTOS_ROW] = photos == 0 ? Ui.tr("photos|None")
                    : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off");
            if (ImmichPhotos.configured(getContext())) v[PHOTOS_ROW] = immichPhotosValue(photos);
            String clock = Prefs.clockFormat();
            v[CLOCK_ROW] = clock.equals("24") ? Ui.tr("24 hours") : clock.equals("12") ? Ui.tr("12 hours") : Ui.tr("Automatic");
            v[5] = onOff(Prefs.sounds());
            v[PICKS_ROW] = "›";   // opens its own screen: Picks per tab and Picks from
            v[SAVER_ROW] = onOff(Prefs.saverWatch());
            String top = Prefs.topContent();
            v[TOP_ROW] = top.equals(Prefs.TOP_CONTINUE) ? Ui.tr("Continue Watching")
                    : top.equals(Prefs.TOP_NONE) ? Ui.tr("Clock")
                    : Ui.tr("Picks");
            v[WATCH_ROW] = watchCount();
            String rec = Prefs.recents();
            v[RECENTS_ROW] = rec.equals("home") ? Ui.tr("recents|Drawer and Home twice") : rec.equals("drawer") ? Ui.tr("recents|In the drawer") : Ui.tr("Off");
            String power = Prefs.powerOn();
            v[POWER_ON_ROW] = power.equals("home") ? Ui.tr("power|Home screen") : power.equals("live") ? Ui.tr("Live TV") : Ui.tr("TV's choice");
            v[ANDROID_APPS_ROW] = onOff(Prefs.androidApps());
            v[STATUS_ICONS_ROW] = onOff(Prefs.statusIcons());
            v[WEATHER_ROW] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[FORECAST_ROW] = Prefs.forecast().equals("days") ? Ui.tr("Next days") : Ui.tr("Next hours");
            v[PLAYING_ROW] = onOff(Prefs.nowPlaying());
            if (HOME_ROW >= 0) v[HOME_ROW] = onOff(HomeAssistant.on(getContext()));
            v[QUOTES_ROW] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length - 3; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            v[v.length - 3] = keyValue(Recs.key());
            v[v.length - 2] = keyValue(Recs.watchmodeKey());
            v[v.length - 1] = updatesValue();
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        /**
         * What the focused screen's row `i` needs from the TV (Capabilities.NEED_…), or null. A new option that needs
         * something adds its line here; drawing, keys and TalkBack then treat it as unavailable where the TV lacks it.
         */
        private String need(int i) {
            switch (screen) {
                case MAIN:
                    return i == 0 ? Capabilities.NEED_SWITCH : i == 5 ? Capabilities.NEED_REMOTE_BUTTONS
                            : i == 6 ? Capabilities.NEED_ADB : null;
                case HOME_SETTINGS:
                    return pickScroll + i == PLAYING_ROW ? Capabilities.NEED_LISTENER
                            : pickScroll + i == HOME_ROW ? Capabilities.NEED_HOME_ASSISTANT
                            : pickScroll + i == POWER_ON_ROW ? Capabilities.NEED_LIVE_TV : null;
                case POWER: {
                    int id = powerRows()[i];
                    return id == P_WHERE ? Capabilities.NEED_OVERLAY : null;
                }
                case SHELF_EDIT: {   // only to add it: one already among the buttons can still move or go
                    String key = shelfRows.get(pickScroll + i)[0];
                    if (!editingTop || shelfOrder.contains(key)) return null;
                    return HomeApps.TOP_LIVE.equals(key) ? Capabilities.NEED_LIVE_TV
                            : HomeApps.TOP_STORE.equals(key) ? Capabilities.NEED_APP_STORE : null;
                }
                default:
                    return null;
            }
        }

        /** Why row `i` can't be used here, for the person to read; null where it works. */
        private String unavailable(int i) {
            int n = rowCount();
            if (i < 0 || i >= n) return null;
            if (whyRows == null || whyRows.length != n || whyScreen != screen || whyScroll != pickScroll) {
                whyRows = new String[n];
                whyScreen = screen;
                whyScroll = pickScroll;
                for (int r = 0; r < n; r++) {
                    String need = need(r);
                    whyRows[r] = screen == HOME_SETTINGS && pickScroll + r >= COIN_ROWS && pickScroll + r < keyRow() && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
                }
            }
            return whyRows[i];
        }

        /** OK on an unavailable row: a toast says why; false if it isn't unavailable. */
        private boolean tellWhy() {
            String why = unavailable(focus);
            if (whyToast != null) whyToast.cancel();
            whyToast = why == null ? null : Toast.makeText(getContext(), why, Toast.LENGTH_LONG);
            if (whyToast != null) whyToast.show();
            return why != null;
        }

        /**
         * Sleep Timer's rows, by what they set: the timer, whether it shows on screen and (where it shows over other
         * apps) where. Values are picked from lists (OK), as on the TV's own settings; ← → step them.
         */
        private static final int P_SLEEP = 0, P_SHOW = 1, P_WHERE = 2;

        private int[] powerRows() {
            return Prefs.sleepShown() ? new int[] {P_SLEEP, P_SHOW, P_WHERE} : new int[] {P_SLEEP, P_SHOW};
        }

        private int powerRow() {
            int[] rows = powerRows();
            return rows[Math.min(focus, rows.length - 1)];
        }

        private int powerFocus(int id) {
            int[] rows = powerRows();
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
            return 0;
        }

        private String[] powerLabels() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = Ui.tr("Sleep Timer"); break;
                    case P_SHOW: out[i] = Ui.tr("Show Time Left"); break;
                    default: out[i] = Ui.tr("Position");
                }
            }
            return out;
        }

        private String[] powerValues() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft)); break;
                    case P_SHOW: out[i] = onOff(Prefs.sleepShown()); break;
                    default: out[i] = SleepOverlay.placeName(Prefs.sleepPlace());
                }
            }
            return out;
        }

        /** OK on a Sleep Timer row: its list of choices, or (on a switch) the other value. */
        private void activatePower() {
            Context c = getContext();
            List<String[]> list = new ArrayList<>();
            switch (powerRow()) {
                case P_SLEEP:
                    list.add(new String[] {"0", Ui.tr("Off")});
                    for (int m : new int[] {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240}) {
                        list.add(new String[] {String.valueOf(m), SleepTimer.duration(m)});
                    }
                    int left = SleepTimer.minutesLeft(c);
                    openChoices(PICK_SLEEP, list, String.valueOf(left));
                    return;
                case P_WHERE:
                    for (int i = 0; i < SleepOverlay.PLACES; i++) list.add(new String[] {String.valueOf(i), SleepOverlay.placeName(i)});
                    openChoices(PICK_PLACE, list, String.valueOf(Prefs.sleepPlace()));
                    return;
                default:
                    adjust(1);
            }
        }

        private void openChoices(int which, List<String[]> list, String now) {
            choices.clear();
            choices.addAll(list);
            choiceNow = now;
            openPicker(which);
        }

        /** OK in a Sleep Timer picker: applied, and back to its row. */
        private void pickPower() {
            Context c = getContext();
            String[] choice = choices.get(pickScroll + focus);
            int row = pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE;
            if (pickFor == PICK_SLEEP) {
                SleepTimer.set(c, Integer.parseInt(choice[0]));
                sleepLeft = SleepTimer.minutesLeft(c);
            } else {
                Prefs.setSleepPlace(Integer.parseInt(choice[0]));
                SleepOverlay.update(c);
            }
            pickScroll = 0;
            screen = POWER;
            focus = powerFocus(row);
        }

        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == POWER ? powerLabels() : screen == PICKS ? picksLabels() : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == POWER ? powerValues() : screen == PICKS ? picksValues() : settingValues();
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        /** Android 12 and newer only let an app see Bluetooth connections with this permission. */
        private void requestBluetooth() {
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 1);
            }
        }

        private String[] keyLabels() {
            if (RemoteKeys.GOOGLE_STREAMER) return new String[] {Ui.tr("YouTube button"), Ui.tr("Netflix button"),
                    Ui.tr("Custom button") + " \u2605"};
            if (RemoteKeys.JVC_SKYWORTH) return new String[] {Ui.tr("Inputs"), Ui.tr("Settings button")};
            if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI_PATCHWALL_ONLY) return new String[] {Ui.tr("PatchWall button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                    Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                    Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button"), Ui.tr("SonyLIV button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button"), Ui.tr("Settings button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // An empty choice leaves the key to the system's receiver.
            appLabels.add(RemoteKeys.GOOGLE_STREAMER ? Ui.tr("Google default")
                    : RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            if (RemoteKeys.JVC_SKYWORTH) {
                appPkgs.add(Prefs.TV_SHORTCUTS);
                appLabels.add(Ui.tr("Inputs"));
                appPkgs.add(Prefs.QUICK_SETTINGS);
                appLabels.add(Ui.tr("Quick Settings"));
            }
            if (RemoteKeys.TCL) {
                appPkgs.add(Prefs.QUICK_PANEL);
                appLabels.add(Ui.tr("Quick Settings"));
                // The Settings button's default, listed even where TV Settings has no launcher entry.
                boolean listed = false;
                for (String[] f : found) listed |= f[0].equals("com.android.tv.settings");
                if (!listed) {
                    appPkgs.add("com.android.tv.settings");
                    appLabels.add(Ui.tr("TV Settings"));
                }
            }
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add("Free My TV");
            }
            if (RemoteKeys.HISENSE || RemoteKeys.GOOGLE_STREAMER) {
                appPkgs.add(Prefs.ALL_APPS);
                appLabels.add(Ui.tr("All Apps"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            // Refused or without Free My TV mode, Now Playing can still be turned off (and the refusal is then forgotten).
            boolean turnOff = screen == HOME_SETTINGS && pickScroll + focus == PLAYING_ROW && Prefs.nowPlaying();
            if (unavailable(focus) != null && !turnOff) return;
            if (screen == PICKS) {
                if (focus == 0) {
                    int n = Prefs.cards() - 3, span = Recs.MAX_CARDS - 3 + 1;
                    Prefs.setCards(3 + (n + dir + span) % span);
                } else if (focus == 2) {
                    Prefs.setHideAnimation(!Prefs.hideAnimation());
                }
                return;
            }
            if (screen == POWER) {
                int row = powerRow();
                if (row == P_SLEEP) {
                    SleepTimer.step(getContext(), dir);
                    sleepLeft = SleepTimer.minutesLeft(getContext());
                } else if (row == P_SHOW) {
                    Prefs.setSleepShown(!Prefs.sleepShown());
                    SleepOverlay.update(getContext());
                } else if (row == P_WHERE) {
                    Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
                    SleepOverlay.update(getContext());
                }
                focus = powerFocus(row);   // rows come and go with the switches above them
                return;
            }
            if (screen == PICK_FROM) {
                toggleService();
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                HomeMode.ensureRemoteService(getContext(), 3_000);   // all on Nothing: the service goes off
                return;
            }
            int row = pickScroll + focus;
            switch (row) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    SleepOverlay.update(getContext());   // the pill's glass follows
                    break;
                }
                case PHOTOS_ROW:
                    if (ImmichPhotos.configured(getContext())) return;   // OK opens the choice of source
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                case CLOCK_ROW: {
                    int at = java.util.Arrays.asList(CLOCKS).indexOf(Prefs.clockFormat());
                    Prefs.setClockFormat(CLOCKS[(at + dir + CLOCKS.length) % CLOCKS.length]);
                    break;
                }
                case 5: Prefs.setSounds(!Prefs.sounds()); break;
                case PICKS_ROW: return;
                case SAVER_ROW: Prefs.setSaverWatch(!Prefs.saverWatch()); break;
                case TOP_ROW: {
                    int at = java.util.Arrays.asList(Prefs.TOP_CONTENT).indexOf(Prefs.topContent());
                    String next = Prefs.TOP_CONTENT[(at + dir + Prefs.TOP_CONTENT.length) % Prefs.TOP_CONTENT.length];
                    Prefs.setTopContent(next);
                    handler.removeCallbacks(askWatch);
                    watchGrantPending = false;
                    watchPickPending = true;
                    break;
                }
                case WATCH_ROW: return;   // a list of its own (OK)
                case RECENTS_ROW: {
                    int at = java.util.Arrays.asList(RECENTS).indexOf(Prefs.recents());
                    Prefs.setRecents(RECENTS[(at + dir + RECENTS.length) % RECENTS.length]);
                    break;
                }
                case POWER_ON_ROW: {   // read at the next full start (HomeMode.bootCheck, HomeActivity)
                    int at = java.util.Arrays.asList(Prefs.POWER_ON).indexOf(Prefs.powerOn());
                    Prefs.setPowerOn(Prefs.POWER_ON[(at + dir + Prefs.POWER_ON.length) % Prefs.POWER_ON.length]);
                    return;
                }
                case ANDROID_APPS_ROW: Prefs.setAndroidApps(!Prefs.androidApps()); break;
                case STATUS_ICONS_ROW: {
                    boolean on = !Prefs.statusIcons();
                    Prefs.setStatusIcons(on);
                    if (on) requestBluetooth();
                    break;
                }
                case WEATHER_ROW: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case FORECAST_ROW:   // both are in memory: nothing to fetch
                    Prefs.setForecast(Prefs.forecast().equals("days") ? "hours" : "days");
                    return;
                case PLAYING_ROW:   // the listener follows the widget: allowed while it's on, taken back when it goes off
                    Prefs.setNowPlaying(!Prefs.nowPlaying());
                    HomeMode.nowPlayingAccess(getContext());
                    break;
                case HOME_ROW:   // off: what it read is forgotten, and nothing is asked of the server until it's on again
                    Prefs.setHomeAssistant(!Prefs.homeAssistant());
                    if (!Prefs.homeAssistant()) HomeAssistant.forget(getContext());
                    return;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (row >= keyRow() || !Prefs.quotes()) return;   // the keys and Updates act on OK only
                    Prefs.setCoin(row - COIN_ROWS, !Prefs.coin(row - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        // MARK: - Updates (GitHub's releases)

        /**
         * OK on Updates: installs one that's ready, else checks now. Both run as jobs in the main process, as the week's
         * check does, so one process writes the state; this screen reads it in poll.
         */
        private void updateNow() {
            Context c = getContext();
            updates = Updates.state(c);
            long now = System.currentTimeMillis();
            if (Updates.ready(updates, installed)) {
                if (now - installAskedAt < 5000) return;   // Android's installer is on its way
                installAskedAt = now;
                ModeJob.schedule(c, ModeJob.INSTALL, "", 0);
            } else {
                if (Updates.busy(updates) || checkAsked()) return;
                checkAskedAt = now;
                ModeJob.schedule(c, ModeJob.UPDATE, "", 0);
            }
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 500);
            invalidate();
            spoken.focusMoved();
        }

        /** OK asked for a check that hasn't started yet (the job writes "checking" as it starts). */
        private boolean checkAsked() {
            long now = System.currentTimeMillis();
            return now - checkAskedAt < 10_000 && updates.optLong("at") < checkAskedAt;
        }

        /** The Updates row: what the last check found, or "Checking…" from OK until the check starts. */
        private String updatesValue() {
            return checkAsked() ? Ui.tr("Checking…") : Updates.summary(updates, installed);
        }

        /** A check or an install on its way or under way: poll reads the file more often. */
        private boolean updating() {
            return Updates.busy(updates) || System.currentTimeMillis() - Math.max(checkAskedAt, installAskedAt) < 10_000;
        }

        // MARK: - TMDB key (from a phone through KeyPage's QR code, or typed with the remote)

        private static final int K_NEW = 0, K_TYPE = 1, K_REMOVE = 2;
        private static final float QR_SIDE = 300;

        private static String keyLabel(int id) {
            return id == K_NEW ? Ui.tr("New code") : id == K_TYPE ? Ui.tr("Type it with the remote") : Ui.tr("Remove the key");
        }

        private void openKeyScreen(boolean watchmode) {
            keyWatchmode = watchmode;
            screen = TMDB_KEY;
            pickScroll = 0;
            focus = 0;
            keyStatus = null;
            keyRowIds = new int[0];
            // The steps, in the TV's language, beside the code; the phone's page says the same.
            String steps = Ui.tr("Free, and it takes two minutes:") + (watchmode
                    ? "\n1. " + Ui.tr("Create an account at api.watchmode.com.")
                    + "\n2. " + Ui.tr("Copy the API key it gives you.")
                    + "\n3. " + Ui.tr("Scan this code with your phone and paste the key there.")
                    : "\n1. " + Ui.tr("Create an account at themoviedb.org.")
                    + "\n2. " + Ui.tr("Open Settings → API and request a developer key.")
                    + "\n3. " + Ui.tr("Scan this code with your phone and paste the API Read Access Token (or the API Key) there."));
            int width = Math.round((LIST_R - LIST_X - 60 - QR_SIDE - 40) * k);
            keySteps = StaticLayout.Builder.obtain(steps, 0, steps.length(), notice, width).setLineSpacing(6 * k, 1).build();
            String purpose = watchmode ? Ui.tr("The key makes a pick open the title's page in its app, where the app allows it.")
                    : Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.");
            keyPurpose = StaticLayout.Builder.obtain(purpose, 0, purpose.length(), notice, Math.round((LIST_R - LIST_X - 60) * k))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            keyHelp = purpose + " " + steps.replace('\n', ' ');
            startKeyPage();
        }

        private void closeKeyScreen() {
            stopKeyPage();
            showSettingsRow(keyWatchmode ? watchmodeRow() : keyRow());
        }

        /**
         * Back on Home Settings with the focus on `row`, scrolled so it shows: City, past the first screen since Continue
         * Watching's rows, came back with the focus off screen (and the picker's scroll isn't this list's).
         */
        private void showSettingsRow(int row) {
            screen = HOME_SETTINGS;
            int rows = settingLabels().length, visible = Math.min(SETTINGS_VISIBLE, rows);
            pickScroll = row < visible ? 0 : Math.max(0, Math.min(row - 3, rows - visible));
            focus = row - pickScroll;
        }

        /** A new page and its code (a new port and token); the old one stops. */
        private void startKeyPage() {
            stopKeyPage();
            InetAddress lan = KeyPage.lanAddress(getContext());
            if (lan == null) {
                setKeyStatus(Ui.tr("The TV isn't on a network: connect it, or type the key."), true);
            } else {
                try {
                    keyPage = new KeyPage(lan, keyWatchmode, keyListener);
                    keyQr = qrBitmap(Qr.encode(keyPage.url));
                    // While the code waits (5 minutes at most), the TV's screensaver doesn't cover it.
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setKeyStatus(Ui.tr("Waiting for your phone. The code works for 5 minutes."), false);
                } catch (IOException | RuntimeException e) {
                    stopKeyPage();
                    setKeyStatus(Ui.trf("Error: {error}.", "error", e.getMessage()), true);
                }
            }
            keyRowsChanged();
        }

        void stopKeyPage() {
            if (keyPage != null) keyPage.close();
            keyPage = null;
            keyQr = null;
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        /** The QR code's modules as a bitmap, one pixel each, with the light margin readers need (4 modules). */
        private static Bitmap qrBitmap(boolean[][] modules) {
            int quiet = 4, n = modules.length + 2 * quiet;
            int[] pixels = new int[n * n];
            java.util.Arrays.fill(pixels, 0xFFFFFFFF);
            for (int y = 0; y < modules.length; y++) {
                for (int x = 0; x < modules.length; x++) if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
            }
            return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
        }

        private final KeyPage.Listener keyListener = new KeyPage.Listener() {
            @Override public void checking() {
                handler.post(() -> {
                    keyChecking = true;
                    setKeyStatus(keyWatchmode ? Ui.tr("Checking the key with Watchmode…") : Ui.tr("Checking the key with TMDB…"), false);
                    keyRowsChanged();
                });
            }

            @Override public String accept(String key, boolean watchmode) {
                return watchmode ? saveWatchmodeKey(key) : saveKey(key);
            }

            @Override public void closed(KeyPage page, boolean expired) {
                handler.post(() -> {
                    if (page != keyPage) return;   // an older page, or the key came another way
                    stopKeyPage();
                    if (expired) setKeyStatus(Ui.tr("This code has expired."), false);
                    keyRowsChanged();
                });
            }
        };

        /** Off the UI thread: checks the key with TMDB and saves it if it works. Null when it's saved, else why not. */
        private String saveKey(String pasted) {
            String key = Recs.cleanKey(pasted);
            String why = key.isEmpty() ? Ui.tr("That doesn't look like a TMDB key.") : Recs.checkKey(key);
            if (why == null) Prefs.setTmdbKey(key);
            keyChecked(why, false, Ui.tr("Saved. The picks arrive in a moment."));
            return why;
        }

        /** As saveKey, for the Watchmode key. */
        private String saveWatchmodeKey(String pasted) {
            String key = Recs.cleanKey(pasted);
            String why = key.isEmpty() ? Ui.tr("That doesn't look like a Watchmode key.") : Recs.checkWatchmodeKey(key);
            if (why == null) Prefs.setWatchmodeKey(key);
            keyChecked(why, true, Ui.tr("Saved. The picks' pages arrive in a moment."));
            return why;
        }

        private void keyChecked(String why, boolean watchmode, String saved) {
            handler.post(() -> {
                keyChecking = false;
                if (why == null) Recs.get(getContext()).refreshIfStale();
                if (watchmode == keyWatchmode) {   // not if the other key's screen opened meanwhile
                    if (why == null) {
                        stopKeyPage();   // typed: the phone's page has nothing left to do
                        setKeyStatus(saved, false);
                    } else {
                        setKeyStatus(why, true);
                    }
                }
                keyRowsChanged();
            });
        }

        /** The rows for now: a new code once the last one is used up, typing, and removing a key given on the TV. */
        private void keyRowsChanged() {
            int was = focus < keyRowIds.length ? keyRowIds[focus] : K_TYPE;
            int[] ids = new int[3];
            int n = 0;
            if (keyPage == null && !keyChecking) ids[n++] = K_NEW;
            ids[n++] = K_TYPE;
            if (!(keyWatchmode ? Prefs.watchmodeKey() : Prefs.tmdbKey()).isEmpty()) ids[n++] = K_REMOVE;
            boolean added = n > 0 && ids[0] == K_NEW && (keyRowIds.length == 0 || keyRowIds[0] != K_NEW);
            keyRowIds = java.util.Arrays.copyOf(ids, n);
            if (screen != TMDB_KEY) return;
            focus = 0;   // a new code just became possible: there first
            if (!added) for (int i = 0; i < n; i++) if (ids[i] == was) focus = i;
            invalidate();
            spoken.focusMoved();   // the rows changed under TalkBack: it reads the focused one again
        }

        private void setKeyStatus(String text, boolean error) {
            int width = Math.round((LIST_R - LIST_X - 60) * k);
            TextPaint paint = error ? new TextPaint(warn) : notice;
            keyStatus = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            if (screen == TMDB_KEY && running) announceForAccessibility(text);   // not over another app
            invalidate();
        }

        private void activateKey() {
            if (focus >= keyRowIds.length) return;
            switch (keyRowIds[focus]) {
                case K_NEW: startKeyPage(); break;
                case K_TYPE: askKey(); break;
                default: askRemoveKey();
            }
        }

        /** The fallback: the key typed with the remote (the on-screen keyboard), then checked as a phone's would be. */
        private void askKey() {
            if (keyChecking) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("API Read Access Token or API Key"));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Save"), (d, which) -> typedKey(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                typedKey(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void typedKey(String text) {
            if (text.trim().isEmpty() || keyChecking) return;
            keyChecking = true;
            boolean watchmode = keyWatchmode;
            setKeyStatus(watchmode ? Ui.tr("Checking the key with Watchmode…") : Ui.tr("Checking the key with TMDB…"), false);
            keyRowsChanged();
            new Thread(() -> {
                if (watchmode) saveWatchmodeKey(text);
                else saveKey(text);
            }, "Key").start();
        }

        /** Asks, then forgets the key given on the TV (one built in from config.properties is used again). */
        private void askRemoveKey() {
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(keyWatchmode ? Ui.tr("Remove the Watchmode key?") : Ui.tr("Remove the TMDB key?"))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        if (keyWatchmode) Prefs.setWatchmodeKey("");
                        else Prefs.setTmdbKey("");
                        keyStatus = null;   // the row and Home Settings say it
                        keyRowsChanged();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps it
        }

        /** The rows, the code with the steps beside it, the address under it for a phone without a camera, and the status. */
        private void drawKey(Canvas c) {
            float x = LIST_X * k + 30 * k, pitch = LIST_ROW_H + LIST_ROW_GAP;
            c.drawText(keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key"), x, (LIST_TOP - 24) * k, keyHeading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(keyLabel(keyRowIds[i]), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            }
            float top = (LIST_TOP + 20 + rows * pitch + 16) * k;
            if (keyPurpose != null) {   // what the key is for, then how to get one
                c.save();
                c.translate(x, top);
                keyPurpose.draw(c);
                c.restore();
                top += keyPurpose.getHeight() + 26 * k;
            }
            Bitmap qr = keyQr;
            if (qr != null) {
                int n = qr.getWidth();
                float side = Math.max(1, (int) (QR_SIDE * k / n)) * n;   // whole pixels per module
                tmp.set(x, top, x + side, top + side);
                c.drawBitmap(qr, null, tmp, qrPaint);
            } else {   // used up or expired: where the code goes, until New code
                tmp.set(x, top, x + QR_SIDE * k, top + QR_SIDE * k);
                c.drawRoundRect(tmp, 12 * k, 12 * k, qrSlot);
            }
            float below = top + QR_SIDE * k;
            if (keySteps != null) {
                c.save();
                c.translate(x + (QR_SIDE + 40) * k, top);
                keySteps.draw(c);
                c.restore();
                below = Math.max(below, top + keySteps.getHeight());
            }
            below += 44 * k;
            KeyPage page = keyPage;
            if (page != null) {
                c.drawText(Ui.tr("No camera? On a phone on the same network, open:"), x, below, notice);
                c.drawText(page.url, x, below + 38 * k, keyUrl);
                below += 90 * k;
            }
            StaticLayout status = keyStatus;
            if (status != null) {
                c.save();
                c.translate(x, below - status.getLineBaseline(0));
                status.draw(c);
                c.restore();
            }
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        private void askRemovePhotos() {
            int n = Photos.files(getContext()).size();
            if (n == 0) return;
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", n))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        Photos.removeAll(getContext());
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps them
        }

        // MARK: - My Photos from an Immich server (ImmichPhotos)

        private boolean albumsAsked;

        /** My Photos with an Immich server: where they come from, and how many came. */
        private String immichPhotosValue(int photos) {
            if (!albumsAsked) {
                albumsAsked = true;
                ImmichPhotos.loadAlbums(getContext(), this::invalidate);
            }
            String source = Prefs.photoSource();
            if ("default".equals(source)) return Ui.tr("photos|Default");
            if (!Prefs.photos()) return Ui.tr("Off");   // nothing syncs: OK on a choice turns it on again
            String from = "album".equals(source) ? Ui.trf("Immich: {album}", "album", Prefs.photoAlbumName()) : Ui.tr("Immich: all");
            return photos == 0 ? from : from + " · " + Ui.plural("plural|one:{n} photo|other:{n} photos", photos);
        }

        /** OK on My Photos with an Immich server: the picks' photos, all of Immich, or one of its albums. */
        private void openPhotoSources() {
            List<String[]> list = new ArrayList<>();
            list.add(new String[] {"default", Ui.tr("photos|Default")});
            list.add(new String[] {"immich", Ui.tr("Immich: all photos")});
            list.add(new String[] {"album", Ui.tr("Immich albums") + "  ›"});
            openChoices(PICK_PHOTOS, list, Prefs.photoSource());
        }

        private void openAlbums() {
            List<String[]> list = new ArrayList<>();
            for (ImmichPhotos.Album a : ImmichPhotos.albums) list.add(new String[] {a.id, a.name});
            openChoices(PICK_ALBUM, list, "album".equals(Prefs.photoSource()) ? Prefs.photoAlbum() : "");
        }

        /** OK in My Photos' lists: applied (the photos change at the next home screen), and back to its row. */
        private void pickPhotoSource() {
            String[] choice = choices.get(pickScroll + focus);
            if (pickFor == PICK_PHOTOS && "album".equals(choice[0])) {
                if (!ImmichPhotos.albums.isEmpty()) {
                    openAlbums();
                    return;
                }
                // Not read yet: the list opens when they come
                ImmichPhotos.loadAlbums(getContext(), () -> {
                    if (screen == PICKER && pickFor == PICK_PHOTOS && !ImmichPhotos.albums.isEmpty()) {
                        openAlbums();
                        spoken.focusMoved();
                    }
                    invalidate();
                });
                return;
            }
            boolean album = pickFor == PICK_ALBUM;
            String source = album ? "album" : choice[0], id = album ? choice[0] : "", name = album ? choice[1] : "";
            if (!source.equals(Prefs.photoSource()) || !id.equals(Prefs.photoAlbum())) {
                Prefs.setPhotoSource(source, id, name);
                ImmichPhotos.sourceChanged(getContext());
            }
            // Also when it's the same choice: My Photos turned off earlier (before the key came) is on again
            Prefs.setPhotos(!"default".equals(source));
            showSettingsRow(PHOTOS_ROW);
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == HOME_SETTINGS || screen == PICK_FROM || screen == WATCH_APPS;
        }

        private int listSize() {
            if (screen == WATCH_APPS) return watchApps == null ? 0 : watchApps.size() + (watchHiddenCount > 0 ? 1 : 0);
            return screen == SHELF_EDIT ? shelfRows.size() : screen == HOME_SETTINGS ? settingLabels().length : screen == PICK_FROM ? services.size() : pickLabels.size();
        }

        // MARK: - Continue Watching apps

        /** How long the ADB grant gets before Android's permission dialog asks instead. */
        private static final long WATCH_GRANT_MS = 10_000;

        private final Runnable askWatch = this::askWatch;
        private boolean watchPickPending, watchGrantPending;

        /** Stepping through the values only saves them: leaving the row with Continue Watching on asks for the permission. */
        private void commitWatch() {
            if (!watchPickPending) return;
            watchPickPending = false;
            handler.removeCallbacks(askWatch);
            if (Prefs.topContent().equals(Prefs.TOP_CONTINUE) && !WatchNext.allowed(getContext())) {
                HomeMode.requestWatchGrant(getContext());
                watchGrantPending = true;
                handler.postDelayed(askWatch, WATCH_GRANT_MS);
            }
        }

        /** The ADB grant didn't come through (yet): Android's own permission dialog asks instead. */
        private void askWatch() {
            if (!running) return;
            watchGrantPending = false;
            if (Prefs.topContent().equals(Prefs.TOP_CONTINUE) && !WatchNext.allowed(getContext())) {
                activity.requestPermissions(new String[] {WatchNext.PERMISSION}, 1);
            }
        }

        private boolean watchLocked() {
            return !Prefs.topContent().equals(Prefs.TOP_CONTINUE);
        }

        /** Looks for the apps that can feed Continue Watching, in the background (only here, never on the home screen). */
        private void scanWatchApps() {
            final int scan = ++watchScan;
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                final List<String[]> found = WatchNext.sources(c);
                handler.post(() -> {
                    if (scan != watchScan) return;
                    watchApps = found;
                    if (screen == WATCH_APPS) {
                        pickScroll = 0;
                        focus = 0;
                        spoken.focusMoved();
                    }
                    invalidate();
                });
            }, "watch-apps").start();
        }

        /** "5 of 7": the apps on, of those found. */
        private String watchCount() {
            if (watchApps == null) return "…";
            Set<String> off = Prefs.watchOff();
            int on = 0;
            for (String[] app : watchApps) if (!off.contains(app[0])) on++;
            return Ui.trf("{on} of {total}", "on", on, "total", watchApps.size());
        }

        private String hiddenLabel() {
            return Ui.trf("Show hidden items ({n})", "n", watchHiddenCount);
        }

        private void openWatchApps() {
            watchHiddenCount = Prefs.watchHidden().size();
            pickScroll = 0;
            focus = 0;
            screen = WATCH_APPS;
        }

        /** OK: an app on or off, or the hidden items back. */
        private void toggleWatchApp() {
            int at = pickScroll + focus;
            if (watchApps == null || at >= listSize()) return;
            if (at == watchApps.size()) {
                Prefs.setWatchHidden(new HashSet<>());
                watchHiddenCount = 0;
                int size = listSize();
                if (pickScroll > 0 && pickScroll + PICK_VISIBLE > size) pickScroll = Math.max(0, size - PICK_VISIBLE);
                focus = Math.max(0, Math.min(size - pickScroll, PICK_VISIBLE) - 1);
                announceForAccessibility(Ui.tr("Hidden items are back"));
                spoken.focusMoved();
                return;
            }
            Set<String> off = Prefs.watchOff();
            String pkg = watchApps.get(at)[0];
            if (!off.remove(pkg)) off.add(pkg);
            Prefs.setWatchOff(off);
            spoken.focusMoved();
        }

        private void drawWatchApps(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Continue Watching apps"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            if (watchApps == null || watchApps.isEmpty() && watchHiddenCount == 0) {
                c.drawText(watchApps == null ? Ui.tr("Looking for the apps…")
                        : Ui.tr("No app on this TV shares what you watch."), LIST_X * k + 30 * k, (LIST_TOP + 56) * k, hint);
                return;
            }
            Set<String> off = Prefs.watchOff();
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                boolean hiddenRow = at == watchApps.size();
                boolean on = hiddenRow || !off.contains(watchApps.get(at)[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(on ? 0xF2FFFFFF : 0x80FFFFFF, on ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                String label = hiddenRow ? hiddenLabel() : watchApps.get(at)[1];
                c.drawText(TextUtils.ellipsize(label, new TextPaint(rowLabel), 560 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                if (!hiddenRow) c.drawText(onOff(on), rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK turns an app on or off in Continue Watching."), LIST_X * k + 30 * k, hy, hint);
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** The order row (shelf only), then selected apps, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            if (!editingTop) shelfRows.add(new String[] {ORDER_ROW, Ui.tr("Shelf order")});
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) {
                if (!shelfOrder.contains(cand[0]) && !cand[0].equals(ORDER_ROW)) shelfRows.add(cand);
            }
        }

        /** The shelf puts the last app opened first, so the places set here don't apply (and can't be changed). */
        private boolean shelfOrderLocked() {
            return !editingTop && Prefs.shelfRecent();
        }

        /** "My order" deletes the list of apps opened: nothing is kept while the shelf doesn't use it. */
        private void switchShelfOrder() {
            boolean on = !Prefs.shelfRecent();
            Prefs.setShelfRecent(on);
            if (!on) Recent.clear(getContext());
        }

        private String shelfOrderValue() {
            return Prefs.shelfRecent() ? Ui.tr("Last opened first") : Ui.tr("My order");
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (pkg.equals(ORDER_ROW)) {
                switchShelfOrder();
                return;
            }
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.maxShelf(getContext())) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (pkg.equals(ORDER_ROW)) {   // two values: either arrow switches
                switchShelfOrder();
                return;
            }
            if (shelfOrderLocked()) return;
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            boolean locked = shelfOrderLocked();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                if (app[0].equals(ORDER_ROW)) {
                    rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                    c.drawText(app[1], rowRect.left + 30 * k, base, rowLabel);
                    c.drawText(shelfOrderValue(), rowRect.right - 30 * k, base, rowValue);
                    continue;
                }
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f)
                        : blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                // The places are greyed while the shelf puts the last app opened first: they don't apply then.
                rowValue.setColor(off ? blend(0x59FFFFFF, 0x591C1C1E, f)
                        : locked && place >= 0 ? blend(0x4DFFFFFF, 0x4D1C1C1E, f) : blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (place >= 0 && !off) {
                    // On the shelf: a check, as in the pickers, after its place.
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                    right -= cw + 16 * k;
                }
                c.drawText(off ? Ui.tr("Unavailable") : place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), right, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            if (!editingTop && HomeApps.lowRam(getContext())) {
                c.drawText(Ui.tr("This TV has little memory, so the shelf holds fewer apps."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
            int max = HomeApps.maxShelf(getContext());
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : locked && max == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. The last one opened comes first.")
                    : locked ? Ui.trf("OK adds or removes (up to {n}). The last one opened comes first.", "n", max)
                    : max == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", max),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else if (which <= PICK_SLEEP) {
                pickCurrent = -1;
                for (int i = 0; i < choices.size(); i++) {
                    pickLabels.add(choices.get(i)[1]);
                    if (choices.get(i)[0].equals(choiceNow)) pickCurrent = i;
                }
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String title = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language")
                    : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer") : pickFor == PICK_PLACE ? Ui.tr("Position")
                    : pickFor == PICK_PHOTOS ? Ui.tr("My Photos") : pickFor == PICK_ALBUM ? Ui.tr("Immich albums")
                    : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f));
                // A long name (an Immich album) stops short of the check mark.
                c.drawText(TextUtils.ellipsize(pickLabels.get(at), new TextPaint(rowLabel), rowRect.width() - 130 * k,
                        TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), rowRect.right - 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowValue);
                } else if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        /** "Some" only when one of the services the TV has is off (one it doesn't have, a typo in hide_services, or one found by name that starts off, doesn't count). */
        private void countServicesOff() {
            someServicesOff = false;
            for (String[] service : Recs.installedServices(getContext())) someServicesOff |= service[0].indexOf('.') < 0 && Prefs.serviceOff(service[0]);
        }

        private void toggleService() {
            int at = pickScroll + focus;
            if (at >= services.size()) return;
            String key = services.get(at)[0];
            Prefs.setServiceOff(key, !Prefs.serviceOff(key));
        }

        /** Picks from: one row per streaming service on the TV, On or Off. */
        private void drawServices(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Picks from"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = Math.min(PICK_VISIBLE, services.size());
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                String[] service = services.get(pickScroll + i);   // the list scrolls past 9 services, like the pickers
                c.drawText(service[1], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(onOff(!Prefs.serviceOff(service[0])), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(services.isEmpty() ? Ui.tr("No streaming apps on this TV.")
                    : Ui.tr("Titles from a service that is off are left out of the picks."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = listLabels();
            String[] values = listValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(keys ? Ui.tr("Remote Buttons") : screen == POWER ? Ui.tr("Sleep Timer") : screen == PICKS ? Ui.tr("Picks") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float pitch = LIST_ROW_H + LIST_ROW_GAP;
            int rows = keys ? labels.length : rowCount(), from = keys ? 0 : pickScroll;
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = unavailable(i) != null, watchOff = !keys && from + i == WATCH_ROW && watchLocked();
                if (locked) values[from + i] = Ui.tr("Unavailable");
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (watchOff) rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                if (locked || watchOff) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[from + i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (!keys && !locked && from + i == WATCH_ROW) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k,
                            blend(watchOff ? 0x33FFFFFF : 0x66FFFFFF, watchOff ? 0x331C1C1E : 0x991C1C1E, f));
                    right -= cw + 16 * k;
                }
                // A long value (an update that failed, a city) stops short of its label.
                float room = right - rowRect.left - 70 * k - rowLabel.measureText(labels[from + i]);
                c.drawText(TextUtils.ellipsize(values[from + i], rowValueFit, room, TextUtils.TruncateAt.END).toString(),
                        right, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * pitch + 36) * k;
            // A list longer than the screen says where we are in it, as the app picker does. Photos say where they come
            // from; on the Sleep Timer screen, the timer and Position open a list on OK.
            String where = rows < labels.length ? (from + focus + 1) + " / " + labels.length + "  ·  " : "";
            c.drawText(where + (screen == HOME_SETTINGS && from + focus == PHOTOS_ROW ? (ImmichPhotos.configured(getContext())
                    ? Ui.tr("OK chooses where they come from: default, Immich or an album.")
                    : Photos.files(getContext()).isEmpty()
                    ? Ui.tr("Send them from a computer: see “Your own photos” in the README.")
                    : Ui.tr("OK removes them; the arrows turn them on or off."))
                    : screen == POWER && powerRow() != P_SHOW && unavailable(focus) == null ? Ui.tr("OK opens the list. ← → change the value.")
                    : screen == HOME_SETTINGS && from + focus == POWER_ON_ROW && unavailable(focus) == null
                    ? Ui.tr("Only after a full start, not from standby.")
                    : screen == HOME_SETTINGS && (from + focus == keyRow() || from + focus == watchmodeRow()) ? Ui.tr("OK: send it from your phone, or type it.")
                    : screen == HOME_SETTINGS && from + focus == updatesRow() ? (Updates.ready(updates, installed)
                    ? Ui.tr("OK installs it; Android asks first.") : Ui.tr("OK checks for a new version."))
                    : Ui.tr("OK or the arrows change the value.")), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE || RemoteKeys.JVC_SKYWORTH ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default: the button opens what it always did.")
                        : RemoteKeys.HISENSE ? Ui.tr("Netflix: the TV itself controls that button.")
                        : RemoteKeys.GOOGLE_STREAMER ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
                // No key service on this TV (RemoteKeys.FILTER_SAFE): inside other apps the buttons do nothing.
                if (!RemoteKeys.FILTER_SAFE) c.drawText(Ui.tr("On this TV they work on Free My TV's home screen only."),
                        LIST_X * k + 30 * k, hy + 76 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (tellWhy()) return;
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    requestedAt = HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                    stalled = false;
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                showSettingsRow(CITY_ROW);
            } else if (screen == PICKER && (pickFor == PICK_PHOTOS || pickFor == PICK_ALBUM)) {
                pickPhotoSource();
            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                pickPower();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                focus = 0;
                pickScroll = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                HomeMode.ensureRemoteService(c, 3_000);
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == WATCH_APPS) {
                toggleWatchApp();
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == WATCH_ROW) {
                if (watchLocked()) return;
                openWatchApps();
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && pickScroll + focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && pickScroll + focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PHOTOS_ROW && ImmichPhotos.configured(getContext())) {
                openPhotoSources();
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PHOTOS_ROW) {
                askRemovePhotos();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == updatesRow()) {
                updateNow();
                return;
            } else if (screen == HOME_SETTINGS && (pickScroll + focus == keyRow() || pickScroll + focus == watchmodeRow())) {
                openKeyScreen(pickScroll + focus == watchmodeRow());
            } else if (screen == TMDB_KEY) {
                activateKey();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PICKS_ROW) {
                countServicesOff();
                screen = PICKS;
                pickScroll = 0;   // this list is short: it draws from row 0
                focus = 0;
            } else if (screen == PICKS && focus == 1) {
                services.clear();
                services.addAll(Recs.installedServices(c));
                screen = PICK_FROM;
                pickScroll = 0;
                focus = 0;
            } else if (screen == PICK_FROM) {
                toggleService();
                invalidate();
                return;
            } else if (screen == POWER) {
                activatePower();
            } else if (screen == PICKS && focus == traktRow()) {
                traktOk();
                invalidate();
                return;
            } else if (screen == PICKS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
                pickScroll = 0;
                watchApps = null;
                scanWatchApps();
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == 6) {
                pickScroll = 0;
                screen = POWER;
                focus = 0;
            } else {
                // As the drawer's TV Settings: where nothing answers the standard request (an HY300 Pro+ in Free My TV
                // mode, #379), Android TV's own settings, and never a crash.
                HomeApps.open(activity, HomeApps.tvSettings(), null);
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
