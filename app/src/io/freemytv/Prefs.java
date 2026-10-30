package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Xiaomi's PatchWall button: BUTTON_5, which the TV hands to the same receiver to open PatchWall (its log: "192"). */
    static final int KEY_XIAOMI_PATCHWALL = KeyEvent.KEYCODE_BUTTON_5;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    /**
     * Hisense's buttons (Q6N, MediaTek, Android TV 12 tested; read from the key filter): global keys for Google TV's
     * home app (launcherx), or Hisense's 607 for Disney+. The gear sends NOTIFICATION; ALL_APPS (284) and PROFILE_SWITCH
     * (288) are API 28/29 names, so numbers here.
     */
    static final int KEY_HISENSE_PRIME = 295, KEY_HISENSE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_HISENSE_DISNEY = 607,
            KEY_HISENSE_APPS = 284, KEY_HISENSE_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION, KEY_HISENSE_PROFILE = 288;
    /** Google TV Streamer's Vendor_18d1_Product_9451.kl; MACRO_1 (313) was added in API 34. */
    static final int KEY_GOOGLE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_GOOGLE_NETFLIX = KeyEvent.KEYCODE_BUTTON_4,
            KEY_GOOGLE_CUSTOM = 313;
    /** A button's value that opens this home screen's All Apps. */
    static final String ALL_APPS = HomeMode.PKG + "#all";
    /**
     * The JVC G24's (Skyworth) Inputs button, one key here whichever way it comes (BUTTON_16 with scan code 383 or 79,
     * or BUTTON_14 with 240: SkyworthRemote.shortcut), and its gear, NOTIFICATION; TV_SHORTCUTS and QUICK_SETTINGS
     * are the values that open the TV's input list and our Quick Settings panel.
     */
    static final int KEY_JVC_SHORTCUT = 200_383, KEY_JVC_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    static final String TV_SHORTCUTS = HomeMode.PKG + "#tvshortcuts";
    static final String QUICK_SETTINGS = HomeMode.PKG + "#quicksettings";
    static final int[] KEYS = RemoteKeys.GOOGLE_STREAMER
            ? new int[] {KEY_GOOGLE_YOUTUBE, KEY_GOOGLE_NETFLIX, KEY_GOOGLE_CUSTOM}
            : RemoteKeys.JVC_SKYWORTH ? new int[] {KEY_JVC_SHORTCUT, KEY_JVC_SETTINGS}
            : RemoteKeys.HISENSE ? new int[] {KEY_HISENSE_PRIME, KEY_HISENSE_YOUTUBE, KEY_HISENSE_DISNEY,
                    KEY_HISENSE_APPS, KEY_HISENSE_SETTINGS, KEY_HISENSE_PROFILE}
            : RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI_PATCHWALL_ONLY ? new int[] {KEY_XIAOMI_PATCHWALL}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_YOUTUBE, KEY_XIAOMI_PATCHWALL, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK,
                    KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION, KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The TV's own 12/24-hour setting (Settings → System → Date & time). */
    static boolean clock24() {
        return app != null && android.text.format.DateFormat.is24HourFormat(app);
    }

    /** "auto" (the TV's own 12/24-hour setting), "24" or "12". */
    static String clockFormat() {
        String v = sp().getString("clock", "auto");
        return v.equals("24") || v.equals("12") ? v : "auto";
    }

    static void setClockFormat(String v) { put("clock", v); }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * Reads the file again if another process changed it. On Android 11 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        String before = language();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before)) Ui.applyLanguage();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, int value) {
        sp().edit().putInt(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    /**
     * Without bumping `version`: a new version makes the home screen start over (and fetch the weather and prices
     * again). Continue Watching's lists don't need that: the home screen reads them on every return (reload).
     */
    private static void putQuietly(String key, Set<String> value) {
        sp().edit().putStringSet(key, new HashSet<>(value)).apply();
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** Picks per tab on the home screen: 3 (as it always was) up to Recs.MAX_CARDS. */
    static int cards() {
        return Math.max(3, Math.min(Recs.MAX_CARDS, sp().getInt("cards", 3)));
    }

    static void setCards(int n) { put("cards", n); }

    // MARK: - Picks from

    private static String offList;
    private static int offListAt = -1;

    /** The services (Recs' keys: "netflix", "prime"...) whose titles are left out of the picks, as ",a,b,". */
    private static String offServices() {
        if (offList == null || offListAt != version) {
            String saved = sp().getString("servicesOff", null);
            if (saved == null) {   // not chosen on the TV yet: what hide_services says
                StringBuilder first = new StringBuilder();
                for (String key : Config.HIDE_SERVICES.split(",")) {
                    key = key.trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty()) first.append(first.length() > 0 ? "," : "").append(key);
                }
                saved = first.toString();
            }
            offList = saved.isEmpty() ? "," : "," + saved + ",";
            offListAt = version;
        }
        return offList;
    }

    static boolean serviceOff(String key) {
        return key != null && offServices().contains("," + key + ",");
    }

    static void setServiceOff(String key, boolean off) {
        String list = offServices();
        list = off ? (serviceOff(key) ? list : list + key + ",") : list.replace("," + key + ",", ",");
        put("servicesOff", list.length() > 1 ? list.substring(1, list.length() - 1) : "");
    }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", Config.THEME.trim());
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** Wi-Fi/Ethernet, Bluetooth and USB icons at the top-left of the home screen. */
    static boolean statusIcons() { return sp().getBoolean("statusicons", false); }
    static void setStatusIcons(boolean on) { put("statusicons", on); }

    // MARK: - City

    static String city() { return sp().getString("city", Place.city()).trim(); }
    static String latitude() { return sp().getString("latitude", Place.latitude()); }
    static String longitude() { return sp().getString("longitude", Place.longitude()); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    /** OK on the screensaver opens the title it shows, in its app; any other key only closes it. Off: any key closes it. */
    static boolean saverWatch() { return sp().getBoolean("saverWatch", false); }
    static void setSaverWatch(boolean on) { put("saverWatch", on); }

    // MARK: - Top of the home screen

    static final String TOP_PICKS = "picks", TOP_CONTINUE = "continue", TOP_NONE = "none";
    static final String[] TOP_CONTENT = {TOP_PICKS, TOP_CONTINUE, TOP_NONE};

    /** What the cards under the title show: the recommendations (TMDB), what you were watching in your apps, or nothing. */
    static String topContent() {
        String v = sp().getString("topContent", TOP_PICKS);
        return v.equals(TOP_CONTINUE) || v.equals(TOP_NONE) ? v : TOP_PICKS;
    }

    static void setTopContent(String v) { put("topContent", v); }

    /** Apps left out of Continue Watching (Settings), by package. */
    static Set<String> watchOff() { return new HashSet<>(sp().getStringSet("watchOff", new HashSet<>())); }
    static void setWatchOff(Set<String> packages) { putQuietly("watchOff", packages); }

    /** Single items hidden from Continue Watching (holding OK on a card), as WatchNext.hideKey. */
    static Set<String> watchHidden() { return new HashSet<>(sp().getStringSet("watchHidden", new HashSet<>())); }
    static void setWatchHidden(Set<String> keys) { putQuietly("watchHidden", keys); }

    /** Recent Apps: "off" (the default: Android manages memory on its own), "drawer" (a shortcut in the bottom drawer and the
     *  remote's app-switch key) or "home" (those, and pressing Home twice). The top button is its own choice (Top Buttons). */
    static String recents() {
        String v = sp().getString("recents", "off");
        return v.equals("drawer") || v.equals("home") ? v : "off";
    }

    static void setRecents(String v) { put("recents", v); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }
    /** The weather card's forecast: "hours" (the next 6 hours) or "days" (the next 5 days). */
    static String forecast() { return "days".equals(sp().getString("forecast", "hours")) ? "days" : "hours"; }
    static void setForecast(String v) { put("forecast", v); }

    static boolean nowPlaying() { return sp().getBoolean("nowPlaying", false); }
    static void setNowPlaying(boolean on) {   // committed: the main process reads it right away to allow the listener
        sp().edit().putBoolean("nowPlaying", on).commit();
        version++;
    }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** Your photos (Photos, sent from a computer) on the screensaver and behind the home screen, once imported. */
    static boolean photos() { return sp().getBoolean("photos", true); }
    static void setPhotos(boolean on) { put("photos", on); }

    /**
     * With an Immich server, where My Photos come from: "default" (none of yours: the picks' photos, as without
     * Immich), "immich" (all its photos) or "album" (photoAlbum, its id; photoAlbumName to show).
     */
    static String photoSource() { return sp().getString("photoSource", "immich"); }
    static String photoAlbum() { return sp().getString("photoAlbum", ""); }
    static String photoAlbumName() { return sp().getString("photoAlbumName", ""); }

    static void setPhotoSource(String source, String album, String name) {
        sp().edit().putString("photoSource", source).putString("photoAlbum", album).putString("photoAlbumName", name).apply();
        version++;
    }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    /**
     * Also list the TV's own system apps (Settings, Files, the manufacturer's apps) in All Apps and the shelf editor.
     * Off keeps the list as it always was: the apps people installed, plus the system ones a TV needs there.
     */
    static boolean androidApps() { return sp().getBoolean("androidapps", false); }
    static void setAndroidApps(boolean on) { put("androidapps", on); }

    // MARK: - TMDB key

    /**
     * The TMDB key given on the TV (Home Settings > TMDB key: from a phone, or typed), or "": then config.properties'
     * one (Recs.key). Never logged.
     */
    static String tmdbKey() { return app == null ? "" : sp().getString("tmdbKey", "").trim(); }

    static void setTmdbKey(String key) {   // committed: ScreensaverAlone reads it from the main process
        sp().edit().putString("tmdbKey", key).commit();
        version++;
    }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        put("shelf", String.join(",", packages));
    }

    // MARK: - YouTube tab

    /** The YouTube tab, off until turned on (Home Settings > Picks > YouTube tab). */
    static boolean youtubeOn() {
        return sp().getBoolean("youtube_on", false);
    }

    static void setYoutubeOn(boolean on) {
        put("youtube_on", on);
    }

    /** The YouTube tab's categories (YouTube.CATEGORIES keys); null until picked once (empty: none, the mix). */
    static List<String> youtubeCategories() {
        String v = sp().getString("youtube", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setYoutubeCategories(List<String> keys) {
        put("youtube", String.join(",", keys));
    }

    /** The YouTube tab leaves out videos whose titles are about AI. */
    static boolean youtubeHideAi() {
        return sp().getBoolean("youtube_hide_ai", false);
    }

    static void setYoutubeHideAi(boolean on) {
        put("youtube_hide_ai", on);
    }

    // MARK: - Sleep timer

    /** When the sleep timer ends (System.currentTimeMillis); SleepTimer's alarm says whether it's still on. */
    static long sleepAt() { return sp().getLong("sleepAt", 0); }

    /** When it was set, in uptimeMillis (dumpsys power's clock): a TV turned off since ended it (SleepTimer.check). */
    static long sleepFrom() { return sp().getLong("sleepFrom", 0); }

    // No version++ for the sleep settings: HomeActivity would rebuild the home screen and fetch the weather again for a
    // timer; HomeView reads what it needs on resume and every 15 s (updateSleep).
    static void setSleepAt(long at, long from) {
        sp().edit().putLong("sleepAt", at).putLong("sleepFrom", from).apply();
    }

    /** The sleep timer's minutes left on screen: over any app (SleepOverlay), or where this TV can't, on the home screen. */
    static boolean sleepShown() { return sp().getBoolean("sleepShown", true); }
    static void setSleepShown(boolean on) { sp().edit().putBoolean("sleepShown", on).apply(); }

    /** Where it shows over other apps: SleepOverlay's places, the top center first. */
    static int sleepPlace() { return sp().getInt("sleepPlace", 0); }

    static void setSleepPlace(int place) { sp().edit().putInt("sleepPlace", place).apply(); }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        if (RemoteKeys.GOOGLE_STREAMER) {
            return keyCode == KEY_GOOGLE_YOUTUBE ? "com.google.android.youtube.tv" : "";
        }
        if (RemoteKeys.JVC_SKYWORTH) {
            if (keyCode == KEY_JVC_SHORTCUT) return TV_SHORTCUTS;
            return keyCode == KEY_JVC_SETTINGS ? QUICK_SETTINGS : "";
        }
        if (RemoteKeys.HISENSE) {
            switch (keyCode) {
                case KEY_HISENSE_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_HISENSE_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_HISENSE_DISNEY: return "com.disney.disneyplus";
                case KEY_HISENSE_APPS: return ALL_APPS;
                case KEY_HISENSE_SETTINGS: return "com.android.tv.settings";
                default: return "";
            }
        }
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
