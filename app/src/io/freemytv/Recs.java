package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). 3 per hour, or up to 6 (Home Settings).
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key: given on the TV (Home Settings > TMDB key, which the APK on GitHub's
 * releases needs), else config.properties' "tmdb_key", built into the app. It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, meta = "", app, pkg;   // poster: the portrait image of the narrow cards
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        String servicePkg;      // open_in_stremio: the TV's own app it streams on (pkg is then Stremio's)
        String tag;             // Trakt: "Up next: S2 E5", "On your watchlist"…, first in the meta; else null
        int mine;               // Trakt: PROGRESS, LIKED (watchlist, recommended) or 0 (TMDB's)
        String video;           // YouTube tab (kind "yt"): the video's id
        boolean detailed;
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
    }

    interface Listener { void onRecs(); }

    // TMDB's own mirror of api.themoviedb.org, on the images' domain: some ISPs block the other name (Jio, #327)
    private static final String API = "https://api.tmdb.org/3";
    /** The most cards per tab and hour (Prefs.cards() picks how many, 3 by default); the home screen shows 3 at a time and scrolls. */
    static final int MAX_CARDS = 6;   // also the videos in each YouTube row

    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    private static final String POSTERS = "https://image.tmdb.org/t/p/w500";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** How often Trakt is asked whether the account changed (marked watched, the watchlist): one small request. */
    private static final long TRAKT_EVERY = 10 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 6;   // 6: lists of up to POOL + TRAKT_MAX
    /** TMDB's titles in the list (trending, then the new releases), as on main. */
    private static final int POOL = 24;
    /** Trakt's titles in the list: about what the tabs show of them (now(): up to 2 in 5 in progress, 3 in 5 liked). */
    private static final int TRAKT_MAX = MAX_CARDS;
    /** Images kept on disk: a backdrop and a poster for every title the list can hold. */
    private static final int FILES = 2 * (POOL + TRAKT_MAX);
    /** Pick.mine: what they're in the middle of, and what they'd like (watchlist, Trakt's recommendations). */
    private static final int PROGRESS = 1, LIKED = 2;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Germany (TMDB's watch providers for DE)
        {"ard mediathek", "ard", "de.swr.avp.ard.tv"},
        {"zdf", "zdf", "com.zdf.android.mediathek"},
        {"arte", "arte", "tv.arte.plus7"},
        {"joyn", "joyn", "de.prosiebensat1digital.seventv"},   // TMDB: "Joyn" and "Joyn Plus", same app
        {"rtl+", "rtl", "de.rtli.tvnow"},
        {"wow", "wow", "de.sky.online"},                       // Sky's WOW (was Sky Ticket)
        {"magenta", "magenta", "de.telekom.magentatv.tv"},     // TMDB: "MagentaTV" and "Magenta TV+"
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    /** Services matched by their whole name, where a part would catch another: "WOW Presents Plus", "Belas Artes à La Carte". */
    private static final Set<String> WHOLE_NAME = new HashSet<>(Arrays.asList("wow", "arte"));
    /**
     * With open_in_stremio=true and Stremio on the TV: a pick from one of the TV's apps opens on its page in Stremio
     * (stremio:///detail/<type>/<imdb id>) instead of in that app. Only an Open in: what is picked doesn't change.
     */
    private static final String STREMIO = "com.stremio.one";
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("stremio", "Stremio");
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("ard", "ARD Mediathek");
        SERVICE_NAME.put("zdf", "ZDF");
        SERVICE_NAME.put("arte", "ARTE");
        SERVICE_NAME.put("joyn", "Joyn");
        SERVICE_NAME.put("rtl", "RTL+");
        SERVICE_NAME.put("wow", "WOW");
        SERVICE_NAME.put("magenta", "MagentaTV");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
        SERVICE_NAME.put("plex", "Plex");
        SERVICE_NAME.put("smarttube", "SmartTube");
        SERVICE_NAME.put("youtube", "YouTube");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    /** A narrow card's portrait and its shader: the shader holds the bitmap, so they leave together. */
    static final class Poster {
        final Bitmap bitmap;
        final BitmapShader shader;

        Poster(Bitmap bitmap) {
            this.bitmap = bitmap;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Poster> posters = new LinkedHashMap<>();   // narrow-card portraits, same keys as images
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, traktStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    private volatile String traktActivity = "";  // Trakt's last change when the picks were fetched
    private volatile long traktCheckedAt;

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            traktStamp = cache.getLong("traktStamp", 0);
            traktActivity = cache.getString("traktActivity", "");
        } catch (Exception ignored) {
        }
        // Right after the TV starts, the first fetch runs before the Wi-Fi is up and fails, which would hold the old
        // picks for the 10 minutes after a failure: as soon as the network comes, try again.
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (failedAt == 0) return;
                    failedAt = 0;   // the next screen to show fetches at once
                    main.postDelayed(() -> {
                        if (listeners.isEmpty()) return;   // nothing showing: no downloads under another app
                        AppLog.w("recommendations: network up, fetching again");
                        refreshIfStale();
                    }, 2000);   // the routes and DNS settle just after
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("recommendations: no network callback: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !key().isEmpty();
    }

    private static volatile String cachedKey = "";
    private static volatile int keyVersion = -1;

    /**
     * The key given on the TV, else the one built in from config.properties ("" for neither). The home screen asks
     * while drawing: read again only when a setting changed (Prefs.version).
     */
    static String key() {
        int version = Prefs.version;
        if (version != keyVersion) {
            String given = Prefs.tmdbKey();
            cachedKey = given.isEmpty() ? Config.TMDB_KEY.trim() : given;
            keyVersion = version;
        }
        return cachedKey;
    }

    /**
     * A key as pasted (spaces, line breaks taken out), or "" when it can't be one: an API key (32 hex digits) or an API
     * Read Access Token (a JWT, "eyJ…").
     */
    static String cleanKey(String pasted) {
        String key = pasted == null ? "" : pasted.replaceAll("\\s+", "");
        return key.matches("[A-Za-z0-9._-]{16,1024}") ? key : "";
    }

    /** Asks TMDB whether a key works, before it's saved: null if it does, else why not, for the screen. Never logged. */
    static String checkKey(String key) {
        try {
            request("/configuration", key);
            return null;
        } catch (IOException e) {
            if (REJECTED.equals(e.getMessage())) return Ui.tr("TMDB refused this key.");
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (org.json.JSONException e) {
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage());
        } catch (RuntimeException e) {   // never out of KeyPage's thread (the watchdog counts it); the name only: no header value
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's Prefs.cards(), movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        if ("yt".equals(kind)) return YouTube.get(context).now();
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;    // no TMDB key: no recommendations
        List<Pick> progress = new ArrayList<>(), liked = new ArrayList<>(), list = new ArrayList<>();
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)
                    && (p.servicePkg == null || have.computeIfAbsent(p.servicePkg, this::installed))))) {   // app missing or switched off: skip
                (p.mine == PROGRESS ? progress : p.mine == LIKED ? liked : list).add(p);
            }
        }
        long hour = System.currentTimeMillis() / 3600_000L;
        // Trakt first: what they're in the middle of (as Trakt orders it), up to 2 in 5 cards, then their watchlist and
        // Trakt's recommendations, up to 3 in 5 with those; then TMDB's. A new set each hour; Trakt's fill the rest when
        // TMDB's run short. Never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        int cards = Prefs.cards();
        java.util.Set<String> seen = new java.util.HashSet<>();
        take(out, seen, progress, 0, Math.max(1, cards * 2 / 5));
        take(out, seen, liked, hour * cards, Math.max(2, cards * 3 / 5));
        take(out, seen, list, hour * cards, cards);
        take(out, seen, progress, 0, cards);
        take(out, seen, liked, hour * cards, cards);
        return out;
    }

    /** Up to `max` in `out`, starting `start` into `from` (wrapping around), one per franchise. */
    private static void take(List<Pick> out, java.util.Set<String> seen, List<Pick> from, long start, int max) {
        boolean plex = false;
        for (Pick p : out) plex |= p.id < 0;
        for (int i = 0; i < from.size() && out.size() < max; i++) {
            Pick p = from.get((int) ((start + i) % from.size()));
            if (p.id < 0 && plex) continue;   // a Plex title is the surprise: one per tab, not the movie and the show together
            if (seen.add(franchise(p.title))) {
                out.add(p);
                plex |= p.id < 0;
            }
        }
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    /** The YouTube tab's feeds: only the home screen asks (never the screensaver, in whichever process it runs). */
    void refreshTabs() {
        YouTube.get(context).refreshIfStale(this::notifyListeners);
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        // Signing in or out of Trakt fetches the list again, judged only in :home: the screensaver's process on Android
        // 11 to 12L has no Trakt, and must not take the saved list for another account's.
        long trakt = Trakt.available() ? Trakt.stamp(context) : traktStamp;
        boolean newTrakt = trakt != traktStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && !newTrakt && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        boolean checkTrakt = !stale && !newTrakt && Trakt.signedIn(context) && (now - traktCheckedAt > TRAKT_EVERY || now < traktCheckedAt);
        if (!stale && !missing && !checkTrakt && !newTrakt) return;
        keyStamp = stamp;
        traktStamp = trakt;
        fetching = true;
        if (checkTrakt) traktCheckedAt = now;
        io.execute(() -> {
            try {
                boolean list = stale || newTrakt;
                if (checkTrakt) {
                    try {
                        String activity = Trakt.activity(context);
                        if (!activity.isEmpty() && !activity.equals(traktActivity)) {
                            AppLog.w("trakt: the account changed, fetching the picks again");
                            list = true;
                        }
                    } catch (Exception e) {
                        AppLog.w("trakt activity: " + e.getMessage());
                    }
                }
                if (list) {
                    // Only the Trakt account changed: the titles already in the list keep their details.
                    Map<String, Pick> known = new HashMap<>();
                    if (!stale) for (Pick k : picks) if (k.detailed) known.put(key(k), k);
                    fetchList(p, known);
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
            posters.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (!"yt".equals(p.kind) && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && (p.servicePkg == null || have.computeIfAbsent(p.servicePkg, this::installed))
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        // The list's titles only up to what prune() keeps: more would be downloaded again every minute.
        List<Pick> list = picks;
        List<Pick> todo = new ArrayList<>();
        for (int i = 0; i < list.size() && i < POOL + TRAKT_MAX; i++) {
            Pick p = list.get(i);
            if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        }
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the TMDB tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number (videos by their id). */
    static String key(Pick p) {
        return p.kind + ":" + (p.video != null ? p.video : String.valueOf(p.id));
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p, Map<String, Pick> known) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        // Signed in to Trakt: what they're in the middle of comes first, then their watchlist and Trakt's
        // recommendations; what they've watched stays out. Trakt failing leaves the picks as they'd be without it.
        // The list keeps the account it was made with (0: none, and always outside :home), so a list the screensaver's
        // process made on Android 11 to 12L is fetched again when :home starts with it.
        long account = Trakt.stamp(context);
        Trakt.Lists trakt = null;
        if (account != 0) {
            try {
                trakt = Trakt.lists(context);
            } catch (Exception e) {
                AppLog.w("trakt: " + e.getMessage());
            }
        }
        final Set<String> watched = trakt == null ? new HashSet<>() : trakt.watched;
        if (trakt != null) {
            traktActivity = trakt.activity;
            traktCheckedAt = System.currentTimeMillis();
        }
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                if (watched.contains(key(pick))) continue;
                try {
                    if (!reuse(pick, known)) fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // One title of your Plex library per kind (one of the newest 8, another each time the list is made) among the
        // trending ones: a surprise, not half the row. Its images come now, so a server that sleeps later leaves no gray card.
        if (plexOn()) {
            try {
                long turn = System.currentTimeMillis() / 3600_000L;
                hotMovies = interleave(hotMovies, withImages(oneOf(plexPicks("movie"), (int) turn)));
                hotShows = interleave(hotShows, withImages(oneOf(plexPicks("tv"), (int) turn)));
            } catch (Exception e) {
                AppLog.w("plex: " + e);
            }
        } else if (!Config.PLEX_URL.trim().isEmpty()) {
            AppLog.w("plex: plex_url must be an https://….plex.direct address (and plex_token set): ignored");
        }
        newMovies.removeIf(x -> watched.contains(key(x)));
        newShows.removeIf(x -> watched.contains(key(x)));
        List<Pick> progress = new ArrayList<>(), listMovies = new ArrayList<>(), listShows = new ArrayList<>();
        List<Pick> recMovies = new ArrayList<>(), recShows = new ArrayList<>();
        if (trakt != null) {
            // Up to TRAKT_MAX, about what the tabs show: in progress, up to what a tab shows of each kind; then the
            // watchlist and the recommendations, movies and shows alike. Each title costs a TMDB request, so once
            // there are enough the rest aren't looked up.
            int few = MAX_CARDS * 2 / 5, movieCount = 0, showCount = 0;
            for (Trakt.Progress pr : trakt.progress) {
                boolean movie = pr.key.startsWith("movie:");
                if ((movie ? movieCount : showCount) >= few) continue;
                String tag = pr.season == 0 ? Ui.tr("Continue watching")
                        : pr.paused ? Ui.trf("Continue: S{s} E{e}", "s", pr.season, "e", pr.episode)
                        : Ui.trf("Up next: S{s} E{e}", "s", pr.season, "e", pr.episode);
                if (fromTrakt(pr.key, tag, PROGRESS, progress, known)) {
                    if (movie) movieCount++;
                    else showCount++;
                }
            }
            int each = (TRAKT_MAX - progress.size() + 1) / 2;
            for (String k : trakt.watchlistMovies) if (listMovies.size() < each && !watched.contains(k)) fromTrakt(k, Ui.tr("On your watchlist"), LIKED, listMovies, known);
            for (String k : trakt.watchlistShows) if (listShows.size() < each && !watched.contains(k)) fromTrakt(k, Ui.tr("On your watchlist"), LIKED, listShows, known);
            for (String k : trakt.recommendedMovies) if (listMovies.size() + recMovies.size() < each && !watched.contains(k)) fromTrakt(k, Ui.tr("Recommended on Trakt"), LIKED, recMovies, known);
            for (String k : trakt.recommendedShows) if (listShows.size() + recShows.size() < each && !watched.contains(k)) fromTrakt(k, Ui.tr("Recommended on Trakt"), LIKED, recShows, known);
            // Only titles on one of the TV's apps show; the log says how many that left.
            AppLog.w("trakt: " + trakt.progress.size() + " in progress (" + progress.size() + " on the TV's apps), "
                    + (trakt.watchlistMovies.size() + trakt.watchlistShows.size()) + " on the watchlist (" + (listMovies.size() + listShows.size())
                    + "), " + (trakt.recommendedMovies.size() + trakt.recommendedShows.size()) + " recommended (" + (recMovies.size() + recShows.size()) + ")");
        }
        // Trakt's (in progress, the watchlist, the recommendations), then trending and the new releases; movies and
        // shows alternating, without repeats, up to POOL of TMDB's.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick x : progress) if (seen.add(key(x))) list.add(x);
        alternate(list, seen, listMovies, listShows, TRAKT_MAX);
        alternate(list, seen, recMovies, recShows, TRAKT_MAX);
        int ours = list.size();
        alternate(list, seen, hotMovies, hotShows, ours + POOL);
        alternate(list, seen, newMovies, newShows, ours + POOL);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        traktStamp = account;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** A Trakt title ("movie:603") with its details, added to `out` when it's on one of the TV's services. */
    private boolean fromTrakt(String k, String tag, int mine, List<Pick> out, Map<String, Pick> known) {
        Pick p = new Pick();
        p.kind = k.substring(0, k.indexOf(':'));
        p.id = Integer.parseInt(k.substring(k.indexOf(':') + 1));
        p.title = "";
        p.tag = tag;
        p.mine = mine;
        try {
            if (!reuse(p, known)) fetchDetails(p);
        } catch (Exception e) {
            return false;
        }
        if (p.pkg == null || p.backdrop == null) return false;
        out.add(p);
        return true;
    }

    /** The details of the same title in the list before (`known`), with this one's tag; false: not there. */
    private static boolean reuse(Pick p, Map<String, Pick> known) {
        Pick k = known.get(key(p));
        if (k == null) return false;
        p.title = k.title;
        p.backdrop = k.backdrop;
        p.poster = k.poster;
        p.app = k.app;
        p.pkg = k.pkg;
        p.servicePkg = k.servicePkg;
        p.link = k.link;
        String meta = k.meta == null ? "" : k.meta;
        if (k.tag != null && meta.startsWith(k.tag)) meta = meta.substring(k.tag.length()).replaceFirst("^ · ", "");
        p.meta = p.tag == null ? meta : meta.isEmpty() ? p.tag : p.tag + " · " + meta;
        p.detailed = true;
        return true;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /** A list of just one of `list`, the one this hour's period points at (empty for an empty list). */
    private static List<Pick> oneOf(List<Pick> list, int period) {
        List<Pick> out = new ArrayList<>();
        if (!list.isEmpty()) out.add(list.get(Math.floorMod(period, list.size())));
        return out;
    }

    /** The picks whose backdrop could be downloaded (the poster is a bonus: without it the card crops the backdrop). */
    private List<Pick> withImages(List<Pick> list) {
        List<Pick> out = new ArrayList<>();
        for (Pick pick : list) {
            try {
                download(pick);
                out.add(pick);
            } catch (IOException e) {
                AppLog.w("plex image: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<Pick> interleave(List<Pick> a, List<Pick> b) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.poster = o.isNull("poster_path") ? "" : o.optString("poster_path");
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    private static String runtime(int minutes) {
        if (minutes <= 0) return null;
        return minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
    }

    private static String seasons(int n) {
        return n <= 0 ? null : Ui.plural("plural|one:{n} season|other:{n} seasons", n);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images" + (Config.OPEN_IN_STREMIO ? ",external_ids" : "") + "&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        else if (p.backdrop == null && !o.isNull("backdrop_path")) p.backdrop = o.optString("backdrop_path");   // a watchlist title
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        String length = p.kind.equals("movie") ? runtime(o.optInt("runtime")) : seasons(o.optInt("number_of_seasons"));
        if (length != null) parts.add(length);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        if (p.tag != null) parts.add(0, p.tag);
        JSONObject ext = o.optJSONObject("external_ids");
        String imdb = ext == null ? "" : ext.optString("imdb_id", "");
        if (p.app != null && stremio() && imdb.startsWith("tt")) {     // the meta still names the TV's app it's on
            p.servicePkg = p.pkg;   // its switch in Picks from (and the app itself) still decide whether it shows
            p.app = "stremio";
            p.pkg = STREMIO;
            p.link = "stremio:///detail/" + (p.kind.equals("movie") ? "movie" : "series") + "/" + imdb;
        }
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : WHOLE_NAME.contains(s[0]) ? n.equals(s[0]) : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** The streaming services whose app is on this TV, as {key, name}, for Home Settings (Picks from). */
    static List<String[]> installedServices(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] s : SERVICES) {
            if (HomeApps.launchIntent(c.getPackageManager(), s[2]) != null && seen.add(s[1])) out.add(new String[] {s[1], SERVICE_NAME.get(s[1])});
        }
        return out;
    }

    /**
     * The service's app is on the TV, and the person hasn't switched the service off (Home Settings > Picks from; the
     * hide_services line of config.properties is where those switches start: an app the TV ships without a subscription).
     */
    private boolean installed(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg) && Prefs.serviceOff(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private boolean stremio() {
        return Config.OPEN_IN_STREMIO && HomeApps.launchIntent(context.getPackageManager(), STREMIO) != null;
    }

    private long sourceStamp() {
        long stamp = ((key().hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0)) * 31L + Config.PLEX_URL.hashCode();
        if (stremio()) stamp = stamp * 31L + 1;   // off: the same stamp as before, so nothing is fetched again
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm2:" + p.kind + p.id + p.app;   // wm2: links found before the channel check are looked up again
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.id > 0 && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /**
     * A key is set, Watchmode hasn't refused it (a refused key isn't tried again until it changes) and the picks are on
     * the home screen: with Continue Watching or the clock there, nothing opens them, so no lookups spend the quota.
     */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode() && Prefs.topContent().equals(Prefs.TOP_PICKS);
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.id < 0 || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    // Not a channel sold inside another app ("AppleTV+ Amazon Channel", "Paramount+ (Via Amazon Prime)"):
                    // that link opens Amazon or Apple's app, not the one the pick is for.
                    boolean resold = name.contains("channel") || name.contains("(via");
                    boolean same = !resold && (want.equals("max") || WHOLE_NAME.contains(want) ? name.equals(want) : name.contains(want));
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = key();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        return request(path, key);
    }

    private static final String REJECTED = "TMDB rejected the key";

    private static JSONObject request(String path, String key) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(REJECTED);
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Plex (your own library: config.properties "plex_url" and "plex_token")

    /** Only Plex's own https://….plex.direct address (it has a valid certificate): a typo never gets the account token. */
    private static boolean plexOn() {
        String url = Config.PLEX_URL.trim();
        if (Config.PLEX_TOKEN.trim().isEmpty() || !url.startsWith("https://")) return false;
        try {
            String host = new java.net.URI(url).getHost();
            return host != null && host.endsWith(".plex.direct");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** The connection to the server, with the usual certificate checks: plex_url is Plex's own https://...plex.direct address. */
    private static HttpURLConnection plexOpen(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.PLEX_URL.trim().replaceAll("/+$", "") + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // HttpURLConnection keeps custom headers across hosts: the token must not follow a redirect
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        return c;
    }

    private static JSONObject plexGet(String path) throws Exception {
        HttpURLConnection c = plexOpen(path);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Plex returned HTTP " + code);
            return new JSONObject(read(c.getInputStream())).getJSONObject("MediaContainer");
        } finally {
            c.disconnect();
        }
    }

    /** The newest movies ("movie") or shows ("tv") in the Plex libraries, up to 8. Negative id: apart from TMDB's. */
    private List<Pick> plexPicks(String kind) throws Exception {
        List<Pick> out = new ArrayList<>();
        String server = plexGet("/identity").getString("machineIdentifier");
        JSONArray sections = plexGet("/library/sections").getJSONArray("Directory");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject sec = sections.getJSONObject(i);
            if (!sec.optString("type").equals(kind.equals("movie") ? "movie" : "show")) continue;
            JSONArray items = plexGet("/library/sections/" + sec.getString("key")
                    + "/all?sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=8").optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject m = items.getJSONObject(j);
                if (m.optString("art").isEmpty()) continue;
                Pick p = new Pick();
                p.id = -m.getInt("ratingKey");
                p.kind = kind;
                p.title = m.optString("title");
                p.backdrop = m.getString("art");     // the path carries a version: a new image is a new file
                p.poster = m.optString("thumb");
                // Plex's own route to the title page (a movie's info, a show's seasons); the slug only decorates it.
                p.link = "plex://libraries/" + server + "/" + (kind.equals("movie") ? "movie" : "show") + "/"
                        + p.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "/" + m.getInt("ratingKey");
                p.app = "plex";
                p.pkg = "com.plexapp.android";
                List<String> parts = new ArrayList<>();
                if (kind.equals("tv")) parts.add(Ui.tr("Series"));
                JSONArray genres = m.optJSONArray("Genre");
                if (genres != null && genres.length() > 0) {
                    String name = genres.getJSONObject(0).optString("tag");
                    parts.add(genre(name));
                }
                String length = kind.equals("movie") ? runtime(m.optInt("duration") / 60000) : seasons(m.optInt("childCount"));
                if (length != null) parts.add(length);
                parts.add("Plex");
                p.meta = String.join(" · ", parts);
                p.detailed = true;
                out.add(p);
            }
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // MARK: - Images (TMDB originals and YouTube backgrounds on disk, up to 80; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (!f.exists()) {
            boolean video = p.backdrop.startsWith(YouTube.THUMBS);
            HttpURLConnection c = p.id < 0
                    ? plexOpen("/photo/:/transcode?width=1920&height=1080&minSize=1&url=" + URLEncoder.encode(p.backdrop, "UTF-8"))
                    : (HttpURLConnection) new URL(video ? p.backdrop : IMAGES + p.backdrop).openConnection();
            if (video) c.setInstanceFollowRedirects(false);   // i.ytimg.com only
            // A video's full-size thumbnail is missing when it was uploaded below 1080p: then the 720p one, then the small one.
            for (String smaller : video ? new String[] {"hq720.jpg", "hqdefault.jpg"} : new String[0]) {
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 404) break;
                c.disconnect();
                c = (HttpURLConnection) new URL(p.backdrop.replace("maxresdefault.jpg", smaller)).openConnection();
                c.setInstanceFollowRedirects(false);
            }
            // Not followed, a redirect's own page would be saved as the image.
            if (video && c.getResponseCode() != 200) {
                int code = c.getResponseCode();
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            fetchFile(f, c);
        }
        File poster = posterFile(p);
        if (poster == null || poster.exists()) return;
        try {   // the narrow cards: without a poster they crop the backdrop
            fetchFile(poster, p.id < 0
                    ? plexOpen("/photo/:/transcode?width=500&height=750&minSize=1&url=" + URLEncoder.encode(p.poster, "UTF-8"))
                    : (HttpURLConnection) new URL(POSTERS + p.poster).openConnection());
        } catch (IOException e) {
            AppLog.w("poster: " + e.getMessage());
        }
    }

    private File posterFile(Pick p) {
        return p.poster == null || p.poster.isEmpty() ? null : new File(new File(context.getCacheDir(), "recs"), "p" + p.poster.replace("/", ""));
    }

    private void fetchFile(File f, HttpURLConnection c) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            Poster poster = forLight ? loadPoster(p) : null;
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                if (poster != null) posters.put(p.backdrop, poster);
                while (images.size() > (forLight ? Prefs.cards() + 7 : 4)) images.remove(images.keySet().iterator().next());
                posters.keySet().retainAll(images.keySet());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The poster at the narrow card's size, or null (not downloaded yet or unreadable). */
    private Poster loadPoster(Pick p) {
        File f = posterFile(p);
        if (f == null || !f.exists()) return null;
        Bitmap full = BitmapFactory.decodeFile(f.getPath());
        if (full == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        // A poster is 2:3 and the narrow card a little wider: the strip comes off the bottom (the credits' end), not
        // half off the top, where the title often is.
        Bitmap card = Backdrop.card(full, Backdrop.TALL_W, Backdrop.TALL_H, 0);
        if (card != full && !full.isRecycled()) full.recycle();   // the decoded 500 × 750 poster is 1.5 MB
        return new Poster(card);
    }

    Poster poster(Pick p) {
        synchronized (images) {
            return p == null || p.backdrop == null ? null : posters.get(p.backdrop);
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
            posters.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        int keep = FILES + (Prefs.youtubeOn() ? YouTube.BACKDROPS : 0);   // the tab's backgrounds only while it's on
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("servicePkg", p.servicePkg == null ? "" : p.servicePkg)
                        .put("tag", p.tag == null ? "" : p.tag).put("mine", p.mine).put("detailed", p.detailed));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("traktActivity", traktActivity).putLong("traktStamp", traktStamp)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if ((k.startsWith("wm:") || k.startsWith("wm2:")) && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.servicePkg = o.optString("servicePkg").isEmpty() ? null : o.optString("servicePkg");
            p.tag = o.optString("tag").isEmpty() ? null : o.optString("tag");
            p.mine = o.optInt("mine");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
