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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plex, for someone who signs in on the TV (Home Settings > Continue Watching apps > Plex): Continue Watching reads what
 * they're in the middle of from their own Plex Media Server (paused titles and the next episode, as Plex's own row
 * has them), then their Plex Watchlist, and OK opens the title in Plex. Plex only shares its own row through Google
 * TV's Engage service, so Android TV models (a Sony BRAVIA on Android 12) never get it in Watch Next; this asks Plex.
 *
 * Signing in is Plex's own TV code: the TV shows four characters, they type them at plex.tv/link. The token stays in
 * :home's private "plex" preferences (as Trakt's), only travels in the X-Plex-Token header, never follows a redirect
 * and never goes to the log. Until they sign in, nothing here talks to Plex.
 */
final class Plex {
    private Plex() {}

    static final String PKG = "com.plexapp.android";
    /** Continue Watching's picture key for a Plex picture (WatchArt asks Plex for it). */
    static final String PICTURE = "plex:";
    /** Titles kept from each list. */
    static final int CONTINUE_MAX = 8, WATCHLIST_MAX = 8;
    private static final String TV = "https://plex.tv/api/v2";
    private static final String DISCOVER = "https://discover.provider.plex.tv";
    private static final long EVERY = 60_000L;
    private static final int MAX_BYTES = 8 << 20;

    /** What Continue Watching shows from Plex: in progress first (Plex's order), then the watchlist. */
    static final class Lists {
        final List<WatchNext.Item> watching = new ArrayList<>(), watchlist = new ArrayList<>();
    }

    // Sign-in in progress (Settings shows it in the Plex row); null when none.
    private static volatile String code, message = "";
    private static volatile boolean signingIn;
    /** The token whose lists are being fetched now, or null: another account's slow fetch doesn't hold up a new one. */
    private static volatile String fetchingFor;
    private static volatile long fetchedAt;
    /** Your server's pictures failed to connect: until then, the others don't wait for it (WatchArt shows banners). */
    private static volatile long picturesDownUntil;
    private static volatile Runnable listener;
    private static final Object SIGN_IN = new Object();

    /** Only :home has the account (see Trakt: on Android 11 to 12L the screensaver runs in the main process). */
    static boolean available() {
        return Trakt.inHome();
    }

    /** Plex is on the TV and can be opened: without it, nothing is fetched or shown (signing out still works). */
    static boolean installed(Context c) {
        return HomeApps.launchIntent(c.getPackageManager(), PKG) != null;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("plex", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return available() && !sp(c).getString("token", "").isEmpty();
    }

    /** "chase · Living Room" (the account and its server), "" if not signed in. */
    static String user(Context c) {
        if (!signedIn(c)) return "";
        String user = sp(c).getString("user", ""), server = sp(c).getString("serverName", "");
        return server.isEmpty() ? user : user.isEmpty() ? server : Ui.trf("{user} · {server}", "user", user, "server", server);
    }

    static String code() { return signingIn ? code : null; }
    static String url() { return "plex.tv/link"; }
    static boolean busy() { return signingIn; }
    static String message() { return message; }

    /** Runs (on Plex's thread) when a fetch brought a different list: the home screen reads Continue Watching again. */
    static void setListener(Runnable r) {
        listener = r;
    }

    // MARK: - Sign in and out

    /** Starts the code sign-in in the background; `changed` runs (on that thread) at every step. */
    static void signIn(Context c, Runnable changed) {
        synchronized (SIGN_IN) {
            if (signingIn || !available() || signedIn(c)) return;   // one account at a time: sign out first
            signingIn = true;
        }
        code = null;
        message = "";
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject pin = new JSONObject(request(app, "POST", TV + "/pins?strong=false", null, 8000));
                code = pin.getString("code");
                changed.run();
                long until = System.currentTimeMillis() + pin.optLong("expiresIn", 900) * 1000L;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(2000);
                    String token = new JSONObject(request(app, "GET", TV + "/pins/" + pin.getLong("id"), null, 8000))
                            .optString("authToken");
                    if (token.isEmpty() || "null".equals(token)) continue;   // not typed yet
                    String name = "";
                    try {
                        name = new JSONObject(request(app, "GET", TV + "/user", token, 8000)).optString("username");
                    } catch (Exception e) {
                        AppLog.w("plex user: " + e.getMessage());
                    }
                    synchronized (Plex.class) {
                        // A new account starts clean: no server or lists of an earlier one can be mixed in.
                        String client = sp(app).getString("client", "");
                        sp(app).edit().clear().putString("client", client).putString("token", token).putString("user", name).apply();
                    }
                    try {
                        findServer(app, token);
                    } catch (Exception e) {
                        AppLog.w("plex server: " + e.getMessage());
                    }
                    fetchedAt = 0;
                    AppLog.w("plex: signed in");
                    return;
                }
                message = Ui.tr("The code expired. Press OK for a new one.");
            } catch (Exception e) {
                message = Ui.trf("Couldn't sign in: {error}", "error", e.getMessage());
                AppLog.w("plex sign-in: " + e.getMessage());
            } finally {
                signingIn = false;
                changed.run();
            }
        }, "plex").start();
    }

    /** Forgets the account and its server here (the client id stays: it's this TV). */
    static void signOut(Context c) {
        synchronized (Plex.class) {
            String client = sp(c).getString("client", "");
            sp(c).edit().clear().putString("client", client).apply();
        }
        message = "";
        fetchedAt = 0;
        Runnable l = listener;
        if (l != null) l.run();
    }

    private static synchronized String clientId(Context c) {
        String id = sp(c).getString("client", "");
        if (id.isEmpty()) {
            id = "freemytv-" + UUID.randomUUID();
            sp(c).edit().putString("client", id).apply();
        }
        return id;
    }

    // MARK: - Your server

    /**
     * Your Plex Media Server (an owned one first) and the first of its https addresses that answers: local, then
     * remote, then Plex's relay. Saved, so the next fetches go straight to it.
     */
    private static void findServer(Context c, String token) throws Exception {
        JSONArray list = new JSONArray(request(c, "GET", TV + "/resources?includeHttps=1&includeRelay=1", token, 8000));
        List<JSONObject> servers = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            if (!r.optString("provides").contains("server")) continue;
            if (r.optBoolean("owned")) servers.add(0, r); else servers.add(r);
        }
        if (servers.isEmpty()) throw new IOException("no Plex Media Server on this account");
        for (JSONObject s : servers) {
            String serverToken = s.optString("accessToken", token);
            JSONArray connections = s.optJSONArray("connections");
            List<String> uris = new ArrayList<>();
            for (int pass = 0; pass < 3 && connections != null; pass++) {
                for (int i = 0; i < connections.length(); i++) {
                    JSONObject cn = connections.getJSONObject(i);
                    int rank = cn.optBoolean("relay") ? 2 : cn.optBoolean("local") ? 0 : 1;
                    if (rank == pass && cn.optString("uri").startsWith("https://")) uris.add(cn.optString("uri"));
                }
            }
            for (String uri : uris) {
                try {
                    request(c, "GET", uri + "/identity", serverToken, 4000);
                } catch (Exception e) {
                    continue;
                }
                synchronized (Plex.class) {
                    if (!token.equals(sp(c).getString("token", ""))) throw new IOException("signed out meanwhile");
                    sp(c).edit().putString("serverUri", uri).putString("serverId", s.optString("clientIdentifier"))
                            .putString("serverToken", serverToken).putString("serverName", s.optString("name")).apply();
                }
                AppLog.w("plex: server found, " + (uris.indexOf(uri) == 0 ? "first address" : "a later address"));
                return;
            }
        }
        throw new IOException("couldn't reach your Plex server");
    }

    /** A GET on your server; if the saved address stopped answering, the server is found again (once). */
    private static JSONObject server(Context c, String token, String path) throws Exception {
        if (sp(c).getString("serverUri", "").isEmpty()) findServer(c, token);
        try {
            return new JSONObject(request(c, "GET", sp(c).getString("serverUri", "") + path, sp(c).getString("serverToken", ""), 6000));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Plex returned HTTP")) throw e;
            findServer(c, token);
            return new JSONObject(request(c, "GET", sp(c).getString("serverUri", "") + path, sp(c).getString("serverToken", ""), 6000));
        }
    }

    // MARK: - Lists

    /**
     * The last lists fetched (from this process's preferences, quick), and a fetch in the background when they're over
     * a minute old; the listener runs if it brings something new. Empty when not signed in.
     */
    static Lists lists(Context c) {
        Lists out = new Lists();
        if (!signedIn(c) || !installed(c)) return out;
        String saved = sp(c).getString("lists", "");
        try {
            if (!saved.isEmpty()) {
                JSONObject o = new JSONObject(saved);
                String server = sp(c).getString("serverId", "");
                parse(o.optJSONArray("watching"), server, out.watching);
                parse(o.optJSONArray("watchlist"), server, out.watchlist);
            }
        } catch (Exception ignored) {
        }
        refreshIfStale(c);
        return out;
    }

    static void refreshIfStale(Context c) {
        long age = System.currentTimeMillis() - fetchedAt;
        if (!signedIn(c) || age >= 0 && age < EVERY) return;
        Context app = c.getApplicationContext();
        // The account this fetch is for: signed out (or into another one) meanwhile, its result is dropped.
        final String token = sp(app).getString("token", "");
        if (token.equals(fetchingFor)) return;
        fetchingFor = token;
        fetchedAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                if (!installed(app)) return;
                // Each list on its own: no server reachable still leaves the watchlist, and the other way round.
                JSONObject saved = savedLists(app);
                JSONArray watching = saved.optJSONArray("watching"), watchlist = saved.optJSONArray("watchlist");
                boolean got = false;
                // Watchlist titles are looked up on your server only while it answers this refresh: a server that's
                // down would otherwise be waited on again for every title.
                boolean[] serverUp = {true};
                try {
                    watching = watching(app, token);
                    got = true;
                } catch (Exception e) {
                    serverUp[0] = false;
                    AppLog.w("plex continue watching: " + e.getMessage());
                }
                try {
                    watchlist = watchlist(app, token, serverUp);
                    got = true;
                } catch (Exception e) {
                    AppLog.w("plex watchlist: " + e.getMessage());
                    // Plex refused the account's token (this TV removed from the account, or every session signed
                    // out): signed out here too, so the row drops its titles and Sign in is offered again. A network
                    // failure keeps everything.
                    if (e.getMessage() != null && e.getMessage().startsWith("Plex returned HTTP 401")) {
                        synchronized (Plex.class) {
                            if (token.equals(sp(app).getString("token", ""))) signOut(app);
                        }
                        return;
                    }
                }
                if (!got) return;
                String json = new JSONObject().put("watching", watching == null ? new JSONArray() : watching)
                        .put("watchlist", watchlist == null ? new JSONArray() : watchlist).toString();
                synchronized (Plex.class) {
                    if (!token.equals(sp(app).getString("token", "")) || json.equals(sp(app).getString("lists", ""))) return;
                    sp(app).edit().putString("lists", json).apply();
                }
                Runnable l = listener;
                if (l != null) l.run();
            } catch (Exception e) {
                AppLog.w("plex: " + e.getMessage());
            } finally {
                if (token.equals(fetchingFor)) fetchingFor = null;
            }
        }, "plex").start();
    }

    private static JSONObject savedLists(Context c) {
        try {
            return new JSONObject(sp(c).getString("lists", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Continue Watching from your server, in Plex's order: newer servers have the hub, older ones On Deck. */
    private static JSONArray watching(Context c, String token) throws Exception {
        JSONArray list = null;
        for (String path : new String[] {"/hubs/continueWatching/items", "/hubs/home/continueWatching", "/library/onDeck"}) {
            try {
                list = metadata(server(c, token, path + "?X-Plex-Container-Start=0&X-Plex-Container-Size=20"));
                break;
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("Plex returned HTTP 404")) throw e;
            }
        }
        JSONArray out = new JSONArray();
        String id = sp(c).getString("serverId", "");
        for (int i = 0; list != null && i < list.length() && out.length() < CONTINUE_MAX; i++) {
            JSONObject m = list.getJSONObject(i);
            String type = m.optString("type");
            boolean episode = type.equals("episode");
            if (!episode && !type.equals("movie")) continue;
            String art = episode ? first(m, "grandparentArt", "art", "thumb") : first(m, "art", "thumb");
            String link = link(id, m.optString("key"));
            if (link == null) continue;
            out.put(new JSONObject().put("title", episode ? first(m, "grandparentTitle", "title") : m.optString("title"))
                    .put("season", episode ? m.optInt("parentIndex") : 0).put("episode", episode ? m.optInt("index") : 0)
                    .put("duration", m.optLong("duration")).put("position", m.optLong("viewOffset"))
                    .put("engaged", m.optLong("lastViewedAt") * 1000).put("picture", art).put("intent", link));
        }
        return out;
    }

    /** The Plex Watchlist, newest first: the one on your server opens there (it plays), else its page in Plex. */
    private static JSONArray watchlist(Context c, String token, boolean[] serverUp) throws Exception {
        JSONArray list = metadata(new JSONObject(request(c, "GET", DISCOVER
                + "/library/sections/watchlist/all?X-Plex-Container-Start=0&X-Plex-Container-Size=20", token, 8000)));
        JSONArray out = new JSONArray();
        for (int i = 0; list != null && i < list.length() && out.length() < WATCHLIST_MAX; i++) {
            JSONObject m = list.getJSONObject(i);
            String type = m.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            String art = first(m, "art", "thumb"), slug = m.optString("slug");
            String link = serverUp[0] ? libraryLink(c, token, m.optString("guid"), serverUp) : null;
            if (link == null && !slug.isEmpty()) link = "https://watch.plex.tv/" + (type.equals("show") ? "show/" : "movie/") + slug;
            if (!art.startsWith("https://")) art = "";   // the watchlist's artwork is Plex's own https address
            out.put(new JSONObject().put("title", m.optString("title")).put("picture", art)
                    .put("intent", link == null ? "" : link).put("watchlist", true));
        }
        return out;
    }

    /** The link to the title on your server, found by Plex's guid; null if it isn't there or the server is down. */
    private static String libraryLink(Context c, String token, String guid, boolean[] serverUp) {
        if (guid.isEmpty() || sp(c).getString("serverUri", "").isEmpty()) return null;
        try {
            JSONArray list = metadata(server(c, token, "/library/all?guid=" + URLEncoder.encode(guid, "UTF-8")));
            String key = list != null && list.length() > 0 ? list.getJSONObject(0).optString("key", "") : "";
            // The server that answered (a lookup may have moved to another one), not the one saved before it.
            return key.isEmpty() ? null : link(sp(c).getString("serverId", ""), key);
        } catch (IOException e) {
            // Plex answered "no" (an HTTP error): only this title. No answer at all: the server is down for this refresh.
            if (e.getMessage() == null || !e.getMessage().startsWith("Plex returned HTTP")) serverUp[0] = false;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Plex's own link to a title on your server (the one its Continue Watching row uses). */
    private static String link(String serverId, String key) {
        if (key.isEmpty() || serverId.isEmpty()) return null;
        try {
            return "plex://watch/video?uri=" + URLEncoder.encode("server://" + serverId + "/com.plexapp.plugins.library" + key, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static void parse(JSONArray a, String server, List<WatchNext.Item> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("title").isEmpty()) continue;
            WatchNext.Item it = WatchNext.Item.plex(o.optString("picture"), server);
            it.title = o.optString("title");
            int season = o.optInt("season"), episode = o.optInt("episode");
            if (o.optBoolean("watchlist")) it.episode = Ui.tr("On your watchlist");
            else if (season > 0 || episode > 0) it.episode = Ui.trf("S{season} E{episode}", "season", season, "episode", episode);
            it.duration = o.optLong("duration");
            it.position = o.optLong("position");
            it.engaged = o.optLong("engaged");
            it.intent = o.optString("intent").isEmpty() ? null : o.optString("intent");
            out.add(it);
        }
    }

    /** The items of a reply: MediaContainer.Metadata, or the first hub's. */
    private static JSONArray metadata(JSONObject o) {
        JSONObject mc = o.optJSONObject("MediaContainer");
        if (mc == null) return null;
        JSONArray list = mc.optJSONArray("Metadata");
        if (list != null) return list;
        JSONArray hubs = mc.optJSONArray("Hub");
        for (int i = 0; hubs != null && i < hubs.length(); i++) {
            JSONArray m = hubs.optJSONObject(i) == null ? null : hubs.optJSONObject(i).optJSONArray("Metadata");
            if (m != null) return m;
        }
        return null;
    }

    private static String first(JSONObject m, String... keys) {
        for (String k : keys) if (!m.optString(k).isEmpty()) return m.optString(k);
        return "";
    }

    // MARK: - Pictures

    /**
     * A card's picture: Plex's artwork (an https address it gave) as it is, or your server's, made 1280×720 by the
     * server. Off the UI thread (WatchArt's).
     */
    static byte[] picture(Context c, String key) throws IOException {
        // The key is PICTURE + server + "|" + the picture (WatchNext.Item.plex).
        String image = key.startsWith(PICTURE) && key.indexOf('|') > 0 ? key.substring(key.indexOf('|') + 1) : "";
        if (!signedIn(c) || image.isEmpty()) throw new IOException("no Plex picture");
        HttpURLConnection conn;
        boolean server = !image.startsWith("https://");
        if (server && System.currentTimeMillis() < picturesDownUntil) throw new IOException("Plex server down");
        if (!server) {
            conn = (HttpURLConnection) new URL(image).openConnection();
        } else {
            String url = sp(c).getString("serverUri", "") + "/photo/:/transcode?width=1280&height=720&minSize=1&upscale=1&url="
                    + URLEncoder.encode(image, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("X-Plex-Token", sp(c).getString("serverToken", ""));
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(8000);
        try {
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                if (server) picturesDownUntil = System.currentTimeMillis() + EVERY;   // no answer: a minute's rest
                throw e;
            }
            if (status != 200) throw new IOException("Plex picture: HTTP " + status);
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    // MARK: - HTTP

    /** Errors never carry the URL; the token is only ever in the header, which a redirect would not get either. */
    private static String request(Context c, String method, String url, String token, int timeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Plex-Product", "Free My TV");
        conn.setRequestProperty("X-Plex-Version", "1.0");
        conn.setRequestProperty("X-Plex-Platform", "Android");
        conn.setRequestProperty("X-Plex-Device-Name", "Free My TV");
        conn.setRequestProperty("X-Plex-Client-Identifier", clientId(c));
        if (token != null) conn.setRequestProperty("X-Plex-Token", token);
        try {
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                throw new IOException("can't reach Plex (" + e.getClass().getSimpleName() + ")");
            }
            if (status < 200 || status >= 300) throw new IOException("Plex returned HTTP " + status);
            try (InputStream in = conn.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("too large");
        }
        return out.toByteArray();
    }
}
