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
    /** A watchlist title's place on your server is looked up again after this (it may have been added since). */
    private static final long LINKS_EVERY = 6 * 3600_000L;
    /** A picture, and an answer: lists of 20 titles are well under this, and a huge one must not take :home down. */
    private static final int MAX_BYTES = 8 << 20, MAX_JSON = 2 << 20;

    /** What Continue Watching shows from Plex: in progress first (Plex's order), then the watchlist. */
    static final class Lists {
        final List<WatchNext.Item> watching = new ArrayList<>(), watchlist = new ArrayList<>();
    }

    // Sign-in in progress (Settings shows it in the Plex row); null when none.
    private static volatile String code, message = "";
    private static volatile boolean signingIn;
    /** Which sign-in is the current one: cancelling (or leaving Settings) moves it on, and the old one stops asking. */
    private static volatile int signInRun;
    /** The token whose lists are being fetched now, or null: another account's slow fetch doesn't hold up a new one. */
    private static volatile String fetchingFor;
    private static volatile long fetchedAt;
    /** The server didn't answer: it (and plex.tv, for where it is) rests until then, longer each time, 8 minutes at most. */
    private static volatile long serverRestUntil;
    private static volatile int serverFailures;
    /** A server's pictures failed to connect: until then, its others don't wait for it (WatchArt shows banners). */
    private static volatile long picturesDownUntil;
    private static volatile String picturesDown = "";   // that server's id
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
        final int run;
        synchronized (SIGN_IN) {
            if (signingIn || !available() || signedIn(c)) return;   // one account at a time: sign out first
            signingIn = true;
            run = ++signInRun;
        }
        code = null;
        message = "";
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject pin = new JSONObject(request(app, "POST", TV + "/pins?strong=false", null, 8000));
                if (run != signInRun) return;   // cancelled meanwhile
                code = pin.getString("code");
                changed.run();
                long until = System.currentTimeMillis() + pin.optLong("expiresIn", 900) * 1000L, id = pin.getLong("id");
                long wait = 2000;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(wait);
                    if (run != signInRun) return;   // cancelled: the code on plex.tv just expires unused
                    String token;
                    try {
                        token = new JSONObject(request(app, "GET", TV + "/pins/" + id, null, 8000)).optString("authToken");
                    } catch (PlexError e) {
                        // The Wi-Fi blinked, or Plex said "slower": the code on screen is still good. Any other "no" ends it.
                        boolean slower = "Plex returned HTTP 429".equals(e.getMessage());
                        if (!slower && !e.getMessage().startsWith("can't reach Plex")) throw e;
                        if (slower) wait = Math.min(wait + 1000, 10_000);
                        continue;
                    }
                    if (token.isEmpty() || "null".equals(token)) continue;   // not typed yet
                    code = null;   // typed: the row says Connecting… while the account and its server are read
                    changed.run();
                    String name = "";
                    try {
                        name = new JSONObject(request(app, "GET", TV + "/user", token, 8000)).optString("username");
                    } catch (Exception e) {
                        AppLog.w("plex user: " + why(e));
                    }
                    if (run != signInRun) return;   // cancelled as it was typed: that token is never kept
                    synchronized (Plex.class) {
                        // A new account starts clean: no server or lists of an earlier one can be mixed in.
                        String client = sp(app).getString("client", "");
                        sp(app).edit().clear().putString("client", client).putString("token", token).putString("user", name).apply();
                    }
                    try {
                        findServer(app, token);
                    } catch (Exception e) {
                        AppLog.w("plex server: " + why(e));
                    }
                    fetchedAt = 0;
                    serverRestUntil = 0;
                    serverFailures = 0;
                    AppLog.w("plex: signed in");
                    return;
                }
                if (run == signInRun) message = Ui.tr("The code expired. Press OK for a new one.");
            } catch (Exception | OutOfMemoryError e) {
                if (run == signInRun) message = Ui.trf("Couldn't sign in: {error}", "error", why(e));
                AppLog.w("plex sign-in: " + why(e));
            } finally {
                synchronized (SIGN_IN) {
                    if (run == signInRun) signingIn = false;
                }
                changed.run();
            }
        }, "plex").start();
    }

    /** Stops a sign-in that's waiting for its code (Cancel in the Plex menu, or leaving Settings). */
    static void cancelSignIn() {
        synchronized (SIGN_IN) {
            if (!signingIn) return;
            signInRun++;
            signingIn = false;
            code = null;
            message = "";
        }
    }

    /**
     * Forgets the account and its server here (the client id stays: it's this TV), and asks plex.tv to drop the
     * token in the background, best effort (as Trakt's sign-out does): the TV leaves the account's devices.
     */
    static void signOut(Context c) {
        final String token;
        synchronized (Plex.class) {
            token = sp(c).getString("token", "");
            String client = sp(c).getString("client", "");
            sp(c).edit().clear().putString("client", client).apply();
        }
        if (!token.isEmpty()) {
            Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    request(app, "DELETE", TV + "/users/signout", token, 8000);
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("plex sign-out: " + why(e));
                }
            }, "plex").start();
        }
        message = "";
        fetchedAt = 0;
        picturesDownUntil = 0;
        serverRestUntil = 0;
        serverFailures = 0;
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
     * Your Plex Media Server and the first of its https addresses that answers: local, then remote, then Plex's
     * relay. Saved, so the next fetches go straight to it. A server someone shares with the account is used only
     * when the account owns none (yours being off for a night must not move Continue Watching to a friend's), and
     * only with the token Plex made for that server: the account's own token never goes to someone else's.
     */
    private static void findServer(Context c, String token) throws Exception {
        JSONArray list = new JSONArray(request(c, "GET", TV + "/resources?includeHttps=1&includeRelay=1", token, 8000));
        List<JSONObject> servers = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            if (!r.optString("provides").contains("server")) continue;
            if (r.optBoolean("owned")) servers.add(0, r); else servers.add(r);
        }
        if (servers.isEmpty()) throw new PlexError("no Plex Media Server on this account");
        boolean own = servers.get(0).optBoolean("owned");
        for (JSONObject s : servers) {
            if (own && !s.optBoolean("owned")) break;
            String serverToken = s.optString("accessToken", own ? token : "");
            if (serverToken.isEmpty()) continue;
            JSONArray connections = s.optJSONArray("connections");
            List<String> uris = new ArrayList<>();
            for (int pass = 0; pass < 3 && connections != null; pass++) {
                for (int i = 0; i < connections.length(); i++) {
                    JSONObject cn = connections.getJSONObject(i);
                    int rank = cn.optBoolean("relay") ? 2 : cn.optBoolean("local") ? 0 : 1;
                    String uri = rank == pass ? origin(cn.optString("uri")) : null;
                    if (uri != null) uris.add(uri);
                }
            }
            for (String uri : uris) {
                try {
                    request(c, "GET", uri + "/identity", serverToken, 4000);
                } catch (Exception e) {
                    continue;
                }
                synchronized (Plex.class) {
                    if (!token.equals(sp(c).getString("token", ""))) throw new PlexError("signed out meanwhile");
                    sp(c).edit().putString("serverUri", uri).putString("serverId", s.optString("clientIdentifier"))
                            .putString("serverToken", serverToken).putString("serverName", s.optString("name")).apply();
                }
                AppLog.w("plex: server found, " + (uris.indexOf(uri) == 0 ? "first address" : "a later address"));
                return;
            }
        }
        throw new PlexError("couldn't reach your Plex server");
    }

    /**
     * "https://host:port" of a server's address, or null when it isn't https or has more to it (a name before an @,
     * a path, a query): the paths asked of it are added to exactly this.
     */
    private static String origin(String uri) {
        try {
            URL u = new URL(uri);
            if (!"https".equals(u.getProtocol()) || u.getHost().isEmpty() || u.getUserInfo() != null) return null;
            if (!u.getPath().isEmpty() && !u.getPath().equals("/") || u.getQuery() != null || u.getRef() != null) return null;
            return "https://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
        if (!signedIn(c) || !installed(c) || off()) return out;
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

    /** Turned off in Continue Watching apps (signed in or not): nothing is shown, so nothing is asked of Plex. */
    private static boolean off() {
        return Prefs.watchOff().contains(PKG);
    }

    static void refreshIfStale(Context c) {
        long age = System.currentTimeMillis() - fetchedAt;
        if (!signedIn(c) || age >= 0 && age < EVERY || off()) return;
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
                boolean[] serverUp = {System.currentTimeMillis() >= serverRestUntil};
                if (serverUp[0]) {
                    try {
                        watching = watching(app, token);
                        got = true;
                        serverFailures = 0;
                    } catch (Exception e) {
                        serverUp[0] = false;
                        // Tried again at the next refresh, then every 2nd, 4th and 8th (half a minute short, to land on one).
                        serverRestUntil = System.currentTimeMillis() + (EVERY << Math.min(serverFailures++, 3)) - EVERY / 2;
                        AppLog.w("plex continue watching: " + why(e));
                        // The server refused its token (a share taken back, its token renewed): its list and the server
                        // are forgotten, and a later refresh looks for a server again. Silence keeps both.
                        if (e.getMessage() != null && e.getMessage().startsWith("Plex returned HTTP 401")) {
                            synchronized (Plex.class) {
                                if (token.equals(sp(app).getString("token", ""))) {
                                    sp(app).edit().remove("serverUri").remove("serverId").remove("serverToken")
                                            .remove("serverName").remove("links").apply();
                                }
                            }
                            watching = new JSONArray();
                            got = true;
                        }
                    }
                }
                try {
                    watchlist = watchlist(app, token, serverUp);
                    got = true;
                } catch (Exception e) {
                    AppLog.w("plex watchlist: " + why(e));
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
            } catch (Exception | OutOfMemoryError e) {   // nothing escapes: :home's watchdog counts a crash
                AppLog.w("plex: " + why(e));
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
        // Plex lists them newest first; a next episode has no time of its own, so it takes the one before it (just
        // under), and the row mixes them with the other apps' titles without losing Plex's order.
        long last = System.currentTimeMillis();
        for (int i = 0; list != null && i < list.length() && out.length() < CONTINUE_MAX; i++) {
            JSONObject m = list.getJSONObject(i);
            String type = m.optString("type");
            boolean episode = type.equals("episode");
            if (!episode && !type.equals("movie")) continue;
            String art = episode ? first(m, "grandparentArt", "art", "thumb") : first(m, "art", "thumb");
            String link = link(id, m.optString("key"));
            if (link == null) continue;
            long viewed = m.optLong("lastViewedAt") * 1000;
            last = viewed > 0 && viewed < last ? viewed : last - 1;
            out.put(new JSONObject().put("title", episode ? first(m, "grandparentTitle", "title") : m.optString("title"))
                    .put("season", episode ? m.optInt("parentIndex") : 0).put("episode", episode ? m.optInt("index") : 0)
                    .put("duration", m.optLong("duration")).put("position", m.optLong("viewOffset"))
                    .put("engaged", last).put("picture", art).put("intent", link));
        }
        return out;
    }

    /**
     * The Plex Watchlist, newest first: the one on your server opens there (it plays), else its page in Plex. Where
     * each title is on the server is remembered for LINKS_EVERY, so a refresh asks the server only about new titles.
     */
    private static JSONArray watchlist(Context c, String token, boolean[] serverUp) throws Exception {
        JSONArray list = metadata(new JSONObject(request(c, "GET", DISCOVER
                + "/library/sections/watchlist/all?X-Plex-Container-Start=0&X-Plex-Container-Size=20", token, 8000)));
        JSONObject known = links(c), kept = new JSONObject();
        long now = System.currentTimeMillis();
        JSONArray out = new JSONArray();
        for (int i = 0; list != null && i < list.length() && out.length() < WATCHLIST_MAX; i++) {
            JSONObject m = list.getJSONObject(i);
            String type = m.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            String art = first(m, "art", "thumb"), slug = m.optString("slug"), guid = m.optString("guid");
            // {link ("" = not on the server), when, which server}: fresh, or the server can't be asked now.
            JSONObject seen = guid.isEmpty() ? null : known.optJSONObject(guid);
            boolean sameServer = seen != null && seen.optString("s").equals(sp(c).getString("serverId", ""));
            boolean fresh = sameServer && now - seen.optLong("at") >= 0 && now - seen.optLong("at") < LINKS_EVERY;
            String link;
            if (fresh || sameServer && !serverUp[0]) {
                link = seen.optString("l").isEmpty() ? null : seen.optString("l");
                kept.put(guid, seen);
            } else {
                link = serverUp[0] ? libraryLink(c, token, guid, serverUp) : null;
                if (serverUp[0] && !guid.isEmpty()) {   // the server answered: remember it, found or not
                    kept.put(guid, new JSONObject().put("l", link == null ? "" : link).put("at", now)
                            .put("s", sp(c).getString("serverId", "")));
                }
            }
            if (link == null && !slug.isEmpty()) link = "https://watch.plex.tv/" + (type.equals("show") ? "show/" : "movie/") + slug;
            if (!art.startsWith("https://")) art = "";   // the watchlist's artwork is Plex's own https address
            out.put(new JSONObject().put("title", m.optString("title")).put("picture", art)
                    .put("intent", link == null ? "" : link).put("watchlist", true));
        }
        synchronized (Plex.class) {   // only this watchlist's titles are kept; another account's never are
            if (token.equals(sp(c).getString("token", ""))) sp(c).edit().putString("links", kept.toString()).apply();
        }
        return out;
    }

    private static JSONObject links(Context c) {
        try {
            return new JSONObject(sp(c).getString("links", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
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
     * A card's picture: your server's, made 1280×720 by the server (with its token, at the address Plex named for it),
     * or Plex's artwork, an https address that isn't ours: fetched as any app's is, through NetPicture's checks and
     * without a token. Off the UI thread (WatchArt's network thread).
     */
    static byte[] picture(Context c, String key) throws IOException {
        // The key is PICTURE + server + "|" + the picture (WatchNext.Item.plex).
        int bar = key.startsWith(PICTURE) ? key.indexOf('|') : -1;
        String image = bar > 0 ? key.substring(bar + 1) : "", serverId = bar > 0 ? key.substring(PICTURE.length(), bar) : "";
        if (!signedIn(c) || image.isEmpty()) throw new PlexError("no Plex picture");
        if (image.startsWith("https://")) return NetPicture.get(image);
        HttpURLConnection conn;
        if (serverId.equals(picturesDown) && System.currentTimeMillis() < picturesDownUntil) {
            throw new PlexError("Plex server down");
        }
        try {
            String url = sp(c).getString("serverUri", "") + "/photo/:/transcode?width=1280&height=720&minSize=1&upscale=1&url="
                    + URLEncoder.encode(image, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("X-Plex-Token", sp(c).getString("serverToken", ""));
        } catch (IOException | RuntimeException e) {
            throw new PlexError("no Plex picture (" + e.getClass().getSimpleName() + ")");
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(8000);
        try {
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                picturesDown = serverId;   // no answer: a minute's rest for this server's pictures
                picturesDownUntil = System.currentTimeMillis() + EVERY;
                // WatchArt logs this: without the address (the server's has the home's IP in it).
                throw new PlexError("can't reach Plex (" + e.getClass().getSimpleName() + ")");
            }
            if (status != 200) throw new PlexError("Plex picture: HTTP " + status);
            try (InputStream in = conn.getInputStream()) {
                return readAll(in, MAX_BYTES);
            } catch (PlexError e) {
                throw e;
            } catch (IOException e) {
                throw new PlexError("can't reach Plex (" + e.getClass().getSimpleName() + ")");
            }
        } finally {
            conn.disconnect();
        }
    }

    // MARK: - HTTP

    /**
     * What the log and the Plex row may say about a failure: only this file's own messages. A JSONException's text
     * holds the answer it couldn't read (a pin's or a server list's has tokens in it), and a network error's names the
     * server's address.
     */
    private static String why(Throwable e) {
        return e instanceof PlexError ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** This file's own failures: their text never has an address, a token or a piece of an answer. */
    private static final class PlexError extends IOException {
        PlexError(String message) { super(message); }
    }

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
                throw new PlexError("can't reach Plex (" + e.getClass().getSimpleName() + ")");
            }
            if (status < 200 || status >= 300) throw new PlexError("Plex returned HTTP " + status);
            try (InputStream in = conn.getInputStream()) {
                return new String(readAll(in, MAX_JSON), StandardCharsets.UTF_8);
            } catch (PlexError e) {
                throw e;
            } catch (IOException e) {
                throw new PlexError("can't reach Plex (" + e.getClass().getSimpleName() + ")");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max) throw new PlexError("Plex's answer is too large");
        }
        return out.toByteArray();
    }
}
