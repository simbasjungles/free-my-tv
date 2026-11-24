package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Settings > Sleep Timer > Show Time Left: the sleep timer's minutes left over anything on screen, where picked
 * (Position), while the timer runs, in the home screen's light or dark glass. A foreground service, so Android keeps it
 * while another app plays, in a process of its own (:sleep) that reads nothing: the end, the place and what day or night
 * depends on come in its Intent.
 */
public class SleepOverlay extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView view;
    private WindowManager.LayoutParams layout;
    private int margin;
    private BitmapDrawable moon;
    private GradientDrawable pill;
    private long endsAt, sunrise, sunset;
    private int darkFrom, darkUntil;
    private String theme = "auto", language;

    /** Settings > Sleep Timer > Position: the top center first, then around the screen. */
    private static final int[] GRAVITY = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.TOP | Gravity.RIGHT,
            Gravity.BOTTOM | Gravity.RIGHT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.LEFT,
            Gravity.TOP | Gravity.LEFT};
    static final int PLACES = GRAVITY.length;

    static String placeName(int place) {
        switch (place) {
            case 1: return Ui.tr("Top right");
            case 2: return Ui.tr("Bottom right");
            case 3: return Ui.tr("Bottom center");
            case 4: return Ui.tr("Bottom left");
            case 5: return Ui.tr("Top left");
            default: return Ui.tr("Top center");
        }
    }

    /**
     * Starts or stops it to match the timer and the choice (from the :home process, where both live). Android TV has no
     * screen to allow drawing over other apps, so the first time ADB allows it; where the TV refuses, the home screen
     * shows the minutes instead (HomeView).
     */
    static void update(Context c) {
        HomeData.Weather w = HomeData.get(c).weather;
        Intent i = new Intent(c, SleepOverlay.class).putExtra("endsAt", Prefs.sleepAt()).putExtra("place", Prefs.sleepPlace())
                .putExtra("theme", Prefs.theme()).putExtra("sunrise", w.sunrise).putExtra("sunset", w.sunset)
                .putExtra("darkFrom", Prefs.darkFrom()).putExtra("darkUntil", Prefs.darkUntil())
                .putExtra("language", Prefs.language());
        boolean wanted = Prefs.sleepShown() && SleepTimer.minutesLeft(c) > 0;
        if (wanted && Settings.canDrawOverlays(c)) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
            if (wanted && Capabilities.overlay(c)) allow(c.getApplicationContext());
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean allowing = new java.util.concurrent.atomic.AtomicBoolean();

    private static void allow(Context c) {
        if (!allowing.compareAndSet(false, true)) return;   // one at a time, however fast the timer is stepped
        new Thread(() -> {
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                adb.shell("appops set " + HomeMode.PKG + " SYSTEM_ALERT_WINDOW allow");
            } catch (java.io.IOException e) {
                AppLog.w("over other apps: " + e.getMessage());   // ADB didn't answer: tried again next time, not refused
                allowing.set(false);
                return;
            }
            boolean works = Settings.canDrawOverlays(c);
            for (int i = 0; i < 5 && !works; i++) {   // Android 8 can take a moment to see it
                android.os.SystemClock.sleep(200);
                works = Settings.canDrawOverlays(c);
            }
            Capabilities.prove(c, Capabilities.OVERLAY, works);
            allowing.set(false);
            if (works) new Handler(Looper.getMainLooper()).post(() -> update(c));
        }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now >= endsAt) {
                stopSelf();
                return;
            }
            view.setText(Ui.trf("Off in {time}", "time", SleepTimer.duration((int) ((endsAt - now + 59_999) / 60_000))));
            // The home screen's glass: white with dark ink by day, smoked with light ink at night (Settings > Theme).
            boolean night = HomeData.isNight(theme, now, sunrise, sunset, darkFrom, darkUntil);
            int ink = night ? 0xEBFFFFFF : 0xFF1D1D1F;
            view.setTextColor(ink);
            moon.setTint(ink);
            pill.setColor(night ? 0xCC1C1C1E : 0xE6F5F5F7);
            handler.postDelayed(this, 15_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // In the foreground first: a quick stop right after the start must not find it still in the background.
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sleep", Ui.tr("Sleep Timer"), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "sleep").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Sleep Timer")).build());
        Ui.init(this);   // its own process: the app's font isn't loaded here yet
        SleepTimer.listen(this);
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        view = new TextView(this);
        view.setTypeface(Ui.regular);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k);
        pill = new GradientDrawable();
        pill.setCornerRadius(40 * k);
        view.setBackground(pill);
        view.setPadding(Math.round(24 * k), Math.round(12 * k), Math.round(28 * k), Math.round(12 * k));
        moon = new BitmapDrawable(getResources(), Symbols.get(this, "bedtime"));
        moon.setBounds(0, 0, Math.round(26 * k), Math.round(26 * k));
        view.setCompoundDrawables(moon, null, null, null);
        view.setCompoundDrawablePadding(Math.round(12 * k));
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        margin = Math.round(48 * k);
        layout.y = margin;
        getSystemService(WindowManager.class).addView(view, layout);
    }

    /** Every update (a new time, Position, Theme, Language): drawn again now. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        endsAt = intent.getLongExtra("endsAt", 0);
        String lang = intent.getStringExtra("language");
        if (lang != null && !lang.equals(language)) {   // a new one from the settings: this process has its own catalogue
            language = lang;
            Ui.applyLanguage(lang);
        }
        theme = intent.getStringExtra("theme");
        sunrise = intent.getLongExtra("sunrise", 0);
        sunset = intent.getLongExtra("sunset", 0);
        darkFrom = intent.getIntExtra("darkFrom", 19 * 60);
        darkUntil = intent.getIntExtra("darkUntil", 6 * 60 + 30);
        int gravity = GRAVITY[intent.getIntExtra("place", 0) % PLACES];
        if (layout.gravity != gravity) {
            layout.gravity = gravity;
            // x moves it from the left or right edge, and from the middle when centered
            layout.x = (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL ? 0 : margin;
            getSystemService(WindowManager.class).updateViewLayout(view, layout);
        }
        handler.removeCallbacks(tick);
        tick.run();
        return START_REDELIVER_INTENT;   // if Android stops the process, the pill comes back as it was
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (view != null) getSystemService(WindowManager.class).removeView(view);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
