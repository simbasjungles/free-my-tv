package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Music tab (optional: it shows once genres are picked in Home Settings > Picks > Music tab, up to MAX_GENRES): a row per genre with
 * this week's top songs in the TV's country, from Deezer's public charts (its album charts lag years behind), or for a
 * style Deezer has no chart for (lofi, K-pop, house…) the songs of Deezer's own playlist for it. No key, no account. The chart genres' names come from Deezer
 * in the TV's language. They open in the music app on the shelf (Spotify, Deezer, Tidal, Apple Music):
 * Spotify plays the best match, the others open on their home.
 */
final class Music {
    /**
     * The music apps an album can open in, in this order when none is on the shelf. Not YouTube Music: Free My TV mode
     * turns it off (HomeMode.DEBLOAT).
     */
    private static final String[][] APPS = {
        {"com.spotify.tv.android", "spotify"},
        {"deezer.android.tv", "deezer"},
        {"com.aspiro.tidal", "tidal"},
        {"com.apple.android.music", "applemusic"},
    };
    /**
     * Deezer's chart genres that every country has (id, then an English name until Deezer's translated one arrives),
     * then styles without a chart ("q:" and the words that find Deezer's playlist for it; named by styleName).
     */
    static final String[][] GENRES = {
        {"132", "Pop"}, {"116", "Rap/Hip Hop"}, {"152", "Rock"}, {"113", "Dance"}, {"165", "R&B"},
        {"85", "Alternative"}, {"106", "Electro"}, {"466", "Folk"}, {"144", "Reggae"}, {"129", "Jazz"},
        {"98", "Classical"}, {"173", "Films/Games"}, {"464", "Metal"}, {"169", "Soul & Funk"}, {"153", "Blues"},
        {"197", "Latin Music"}, {"95", "Kids"},
        {"q:lofi hip hop", "Lofi Hip Hop"}, {"q:old school rap", "Old School Hip Hop"}, {"q:reggaeton", "Reggaeton"},
        {"q:k-pop", "K-Pop"}, {"q:j-pop", "J-Pop"}, {"q:afrobeats hits", "Afrobeats"}, {"q:house", "House"},
        {"q:techno", "Techno"}, {"q:drum and bass", "Drum & Bass"}, {"q:disco", "Disco"}, {"q:indie", "Indie"},
        {"q:country", "Country"}, {"q:gospel", "Gospel"}, {"q:ambient", "Ambient"}, {"q:acoustic", "Acoustic"},
        {"q:chill", "Chill"}, {"q:workout", "Workout"}, {"q:party hits", "Party"}, {"q:80s pop", "80s"},
        {"q:90s hits", "90s"},
    };
    static final int MAX_GENRES = 8;
    private static final String API = "https://api.deezer.com";
    private static final long EVERY = 24 * 3600_000L;
    private static final int NEW_DAY_HOUR = 5;     // the songs change at 5 am, the TV's time

    private static Music instance;

    static synchronized Music get(Context c) {
        if (instance == null) instance = new Music(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile JSONObject charts = new JSONObject();   // genre id → its albums, as Deezer sent them
    private volatile JSONObject names = new JSONObject();    // genre id → Deezer's name in the TV's language
    private volatile long fetchedAt, failedAt;
    private volatile String fetchedFor = "";
    private volatile boolean fetching;
    // The song last opened from the tab, and until when it may still be playing (its length after it was opened).
    private String lastKey = "";
    private long lastUntil;

    private Music(Context c) {
        context = c;
        store = c.getSharedPreferences("music", Context.MODE_PRIVATE);
        try {
            charts = new JSONObject(store.getString("charts", "{}"));
            names = new JSONObject(store.getString("names", "{}"));
            fetchedAt = store.getLong("at", 0);
            fetchedFor = store.getString("for", "");
        } catch (Exception ignored) {
        }
    }

    /** The genre's name: Deezer's in the TV's language for a chart genre (once fetched), ours for a style. */
    String name(String[] genre) {
        return genre[0].startsWith("q:") ? styleName(genre[1]) : names.optString(genre[0], genre[1]);
    }

    /** The styles' names in the TV's language (most are the same everywhere). */
    private static String styleName(String english) {
        switch (english) {
            case "Lofi Hip Hop": return Ui.tr("Lofi Hip Hop");
            case "Old School Hip Hop": return Ui.tr("Old School Hip Hop");
            case "Reggaeton": return Ui.tr("Reggaeton");
            case "K-Pop": return Ui.tr("K-Pop");
            case "J-Pop": return Ui.tr("J-Pop");
            case "Afrobeats": return Ui.tr("Afrobeats");
            case "House": return Ui.tr("House");
            case "Techno": return Ui.tr("Techno");
            case "Drum & Bass": return Ui.tr("Drum & Bass");
            case "Disco": return Ui.tr("Disco");
            case "Indie": return Ui.tr("Indie");
            case "Country": return Ui.tr("Country");
            case "Gospel": return Ui.tr("Gospel");
            case "Ambient": return Ui.tr("Ambient");
            case "Acoustic": return Ui.tr("Acoustic");
            case "Chill": return Ui.tr("Chill");
            case "Workout": return Ui.tr("Workout");
            case "Party": return Ui.tr("Party");
            case "80s": return Ui.tr("80s");
            case "90s": return Ui.tr("90s");
            default: return english;
        }
    }

    /** The genres in alphabetical order of their names in the TV's language (the Music tab's settings and its rows). */
    String[][] sorted() {
        String[][] out = GENRES.clone();
        java.text.Collator order = java.text.Collator.getInstance(Ui.locale());
        java.util.Arrays.sort(out, (a, b) -> order.compare(name(a), name(b)));
        return out;
    }

    /** Genres picked and a music app on the TV: the home screen shows the Music tab. */
    boolean on() {
        return !Prefs.musicGenres().isEmpty() && app() != null;
    }

    /** The app the albums open in: the first music app on the shelf, else the first one installed. */
    private String[] app() {
        for (String pkg : HomeApps.shelfPackages(context)) for (String[] a : APPS) if (a[0].equals(pkg)) return a;
        for (String[] a : APPS) if (HomeApps.launchIntent(context.getPackageManager(), a[0]) != null) return a;
        return null;
    }

    /** The tab's rows: one per picked genre (in alphabetical order), its songs; genres not fetched yet are left out. */
    List<YouTube.Row> rows() {
        List<YouTube.Row> out = new ArrayList<>();
        String[] app = app();
        if (app == null) return out;
        List<String> picked = Prefs.musicGenres();
        for (String[] g : sorted()) {
            if (!picked.contains(g[0])) continue;
            JSONArray albums = charts.optJSONArray(g[0]);
            if (albums == null || albums.length() == 0) continue;
            YouTube.Row row = new YouTube.Row(name(g));
            for (int i = 0; i < albums.length() && row.picks.size() < Recs.MAX_CARDS; i++) {
                JSONObject a = albums.optJSONObject(i);
                if (a != null) row.picks.add(pick(a, app));
            }
            out.add(row);
        }
        return out;
    }

    private static Recs.Pick pick(JSONObject a, String[] app) {
        Recs.Pick p = new Recs.Pick();
        p.kind = "music";
        p.id = a.optInt("id");
        p.title = a.optString("title");
        String artist = a.optJSONObject("artist") == null ? "" : a.optJSONObject("artist").optString("name");
        p.thumb = a.optJSONObject("album") == null ? null : a.optJSONObject("album").optString("cover_medium", null);
        p.pkg = app[0];
        p.app = app[1];
        p.meta = artist;
        if (app[1].equals("spotify")) {
            try {
                p.link = "spotify:search:" + URLEncoder.encode(artist + " " + p.title, "UTF-8").replace("+", "%20");
            } catch (Exception ignored) {
            }
        }
        p.detailed = true;
        return p;
    }

    /**
     * Whether this song was the last one opened and may still be playing: then OK only brings its app forward (Spotify
     * would start it over). After its length, or for another song, it plays again.
     */
    boolean playing(Recs.Pick p) {
        return Recs.key(p).equals(lastKey) && android.os.SystemClock.elapsedRealtime() < lastUntil;
    }

    /** A song was opened from the tab: remembered for its length (Deezer's, in seconds; 4 minutes when unknown). */
    void opened(Recs.Pick p) {
        if (!"music".equals(p.kind)) return;
        int seconds = 240;
        java.util.Iterator<String> genres = charts.keys();
        while (genres.hasNext()) {
            JSONArray songs = charts.optJSONArray(genres.next());
            for (int i = 0; songs != null && i < songs.length(); i++) {
                JSONObject s = songs.optJSONObject(i);
                if (s != null && s.optInt("id") == p.id && s.optInt("duration") > 0) seconds = s.optInt("duration");
            }
        }
        lastKey = Recs.key(p);
        lastUntil = android.os.SystemClock.elapsedRealtime() + seconds * 1000L;
    }

    /** Fetches the picked genres' songs once a day at 5 am (after a failure, not for 10 minutes); `done` runs when there's news. */
    void refreshIfStale(Runnable done) {
        if (fetching || !on()) return;
        List<String> picked = Prefs.musicGenres();
        // A new day is news too: each day shows other songs (day()), so they come at 5 am.
        String key = "songs|" + day() + "|" + String.join(",", picked) + "|" + Ui.language;
        long now = System.currentTimeMillis();
        boolean changed = !key.equals(fetchedFor);
        if (!changed && now - fetchedAt < EVERY && now >= fetchedAt) return;
        if (!changed && failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                JSONObject n = genreNames();
                JSONObject c = new JSONObject();
                for (String id : picked) {
                    try {
                        c.put(id, id.startsWith("q:") ? playlist(id.substring(2)) : chart(id));
                    } catch (Exception e) {
                        AppLog.w("music chart " + id + ": " + e.getMessage());
                        if (charts.has(id)) c.put(id, charts.get(id));    // keeps what it had
                    }
                }
                names = n;
                charts = c;
                fetchedFor = key;
                fetchedAt = System.currentTimeMillis();
                failedAt = 0;
                store.edit().putString("charts", c.toString()).putString("names", n.toString()).putLong("at", fetchedAt)
                        .putString("for", key).apply();
                done.run();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("music: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** The Music tab's settings: Deezer's genre names in the TV's language, fetched once, after the first genre is picked. */
    void fetchNames(Runnable done) {
        if (names.length() > 0) return;
        io.execute(() -> {
            try {
                JSONObject n = genreNames();
                names = n;
                store.edit().putString("names", n.toString()).apply();
                done.run();
            } catch (Exception e) {
                AppLog.w("music genres: " + e.getMessage());
            }
        });
    }

    /**
     * The songs of Deezer's playlist for these words: among the first five, one of Deezer's editors' named for them, else
     * an editor's, else the first. ("lofi hip hop" finds the Moods editor's "chill lofi" before the Rap editor's
     * "lofi hip-hop".)
     */
    private static JSONArray playlist(String words) throws Exception {
        JSONArray found = new JSONObject(get("/search/playlist?limit=5&q=" + URLEncoder.encode(words, "UTF-8"))).optJSONArray("data");
        if (found == null || found.length() == 0) throw new IOException("no playlist for " + words);
        JSONObject best = found.getJSONObject(0), editor = null;
        String want = letters(words);
        for (int i = 0; i < found.length(); i++) {
            JSONObject p = found.getJSONObject(i), user = p.optJSONObject("user");
            if (user == null || !user.optString("name").contains("Deezer")) continue;
            if (letters(p.optString("title")).contains(want)) {
                editor = p;
                break;
            }
            if (editor == null) editor = p;
        }
        if (editor != null) best = editor;
        // Today's 9: a stretch of the playlist picked at random each day (only 9 songs are fetched).
        int size = Math.max(1, best.optInt("nb_tracks", Recs.MAX_CARDS));
        int start = shuffle(words).nextInt(size);
        String songs = "/playlist/" + best.optLong("id") + "/tracks?limit=";
        JSONArray out = songs(songs + Recs.MAX_CARDS + "&index=" + start);
        if (out.length() < Recs.MAX_CARDS && start > 0) {       // past the end: on from the top
            JSONArray more = songs(songs + (Recs.MAX_CARDS - out.length()) + "&index=0");
            for (int i = 0; i < more.length(); i++) out.put(more.get(i));
        }
        return out;
    }

    /** Only the letters and digits, lowercase: "lofi hip-hop" and "Lofi Hip Hop" are the same. */
    private static String letters(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * A chart genre's 9: picked at random each day from its top 50, in chart order. The first cards change too (a fixed
     * top 3 looked the same day after day), and they're still this week's songs.
     */
    private static JSONArray chart(String id) throws Exception {
        JSONArray top = songs("/chart/" + id + "/tracks?limit=50");
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < top.length(); i++) at.add(i);
        java.util.Collections.shuffle(at, shuffle(id));
        at = new ArrayList<>(at.subList(0, Math.min(Recs.MAX_CARDS, at.size())));
        java.util.Collections.sort(at);
        JSONArray out = new JSONArray();
        for (int i : at) out.put(top.get(i));
        return out;
    }

    private static JSONArray songs(String path) throws Exception {
        JSONArray a = new JSONObject(get(path)).optJSONArray("data");
        return a == null ? new JSONArray() : a;
    }

    /** Which songs show now: the day in the TV's time zone, starting at 5 am. */
    private static long day() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now) - NEW_DAY_HOUR * 3_600_000L) / 86_400_000L;
    }

    /** Random, but the same all day for this genre: the rows don't jump while you browse, and differ per genre. */
    private static java.util.Random shuffle(String genre) {
        java.util.Random r = new java.util.Random(day() * 31 + genre.hashCode());
        r.nextInt();    // close seeds (one day apart) start alike: the first number is skipped
        return r;
    }

    /** Genre id → Deezer's name for it, in the TV's language. */
    private static JSONObject genreNames() throws Exception {
        JSONObject n = new JSONObject();
        JSONArray genres = new JSONObject(get("/genre")).optJSONArray("data");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject g = genres.getJSONObject(i);
            n.put(String.valueOf(g.optInt("id")), g.optString("name"));
        }
        return n;
    }

    /** Deezer answers in the asked language and charts the caller's country. */
    private static String get(String path) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(API + path).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Accept-Language", Ui.language);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
