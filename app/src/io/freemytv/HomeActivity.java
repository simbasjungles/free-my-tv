package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    /** Opens the inputs drawer (the remote's Input key while the stock inputs panel is off, RemoteKeys). */
    static final String EXTRA_INPUTS = "io.freemytv.INPUTS";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else if (getIntent().getBooleanExtra(EXTRA_INPUTS, false)) home.showInputs();
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            liveWaits = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
            if (liveWaits) {
                AppLog.w("at power-on: Live TV in 10 s, unless a key comes first");
                home.postDelayed(powerOnLive, 10_000);
            }
        }
    }

    /**
     * liveWaits: At power-on "Live TV" is still to come in this start. liveDark: the screen went off while it waited (a
     * TV that starts in standby after a power cut, as Android's quiescent boot does, #489): the 10 s start again when
     * the screen comes on with the home screen in front.
     */
    private boolean resumed, liveWaits, liveDark;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!liveWaits) return;
        if (!resumed) {
            if (liveDark) {
                AppLog.w("at power-on: the screen is off, Live TV 10 s after it comes on");
                return;
            }
            liveWaits = false;
            AppLog.w("at power-on: another screen is in front, no Live TV");
            return;
        }
        liveWaits = false;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        if (liveDark && home != null) {   // the screen came on with the home screen in front
            liveDark = false;
            home.removeCallbacks(powerOnLive);
            home.postDelayed(powerOnLive, 10_000);
        }
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        if (liveWaits) {   // the screen going off, not another screen coming to the front
            PowerManager power = getSystemService(PowerManager.class);
            liveDark = power != null && !power.isInteractive();
        }
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (liveDark) {   // Home while Live TV waits for the screen: it counts as a key
            liveWaits = liveDark = false;
            AppLog.w("at power-on: Home came first, no Live TV");
        }
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            home.showAllApps();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_INPUTS, false)) {
            home.showInputs();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) home.showRecents();
        else home.reset(true);
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        if (home != null) {
            home.removeCallbacks(powerOnLive);
            home.destroy();
        }
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (liveWaits) {
            liveWaits = liveDark = false;
            AppLog.w("at power-on: a key came first (" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + (event.getAction() == KeyEvent.ACTION_UP ? " up" : "") + "), no Live TV");
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
