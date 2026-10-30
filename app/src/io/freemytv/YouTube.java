package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The YouTube tab: the newest videos of the channels in config.properties "youtube_channels" (channel ids), or else of
 * the categories picked on the TV (Home Settings > Picks > YouTube tab; none picked, no tab), from YouTube's public
 * RSS feeds only (no key, no account, no pages read). The channels opened most from here come first and a video opened
 * here doesn't come back. They open in the YouTube app on the shelf: YouTube or SmartTube.
 */
final class YouTube {
    /** YouTube itself, then SmartTube's builds (stable first). */
    private static final String[][] APPS = {
        {"com.google.android.youtube.tv", "youtube"},
        {"org.smarttube.stable", "smarttube"},
        {"org.smarttube.beta", "smarttube"},
        {"com.teamsmart.videomanager.tv", "smarttube"},
        {"com.liskovsoft.smarttubetv.beta", "smarttube"},
    };
    /**
     * The categories to pick from: key, name (pt, en, es, de, ru), then their channels. Ids, not handles: no page
     * lookup. YouTube sometimes answers 404 for a channel's feed for a while: that channel keeps its last videos.
     */
    static final String[][] CATEGORIES = {
        {"tech",
            "UCBJycsmduvYEL83R_U4JriQ",     // Marques Brownlee
            "UCXuqSBlHAE6Xw-yeJA0Tunw",     // Linus Tech Tips
            "UCMiJRAwDNSNzuYeN2uWa0pA",     // Mrwhosetheboss
            "UCsTcErHg8oDvUnTzoqsYeNw"},    // Unbox Therapy
        {"science",
            "UCHnyfMqiRRG1u-2MsSQLbXA",     // Veritasium
            "UCsXVk37bltHxD1rDPwtNM8Q",     // Kurzgesagt
            "UCY1kMZp36IQSyNx_9h4mpCg",     // Mark Rober
            "UC6107grRI4m0o2-emgoDnAA",     // SmarterEveryDay
            "UC6nSFpj9HTCZ5t-N3Rm3-HA"},    // Vsauce
        {"gaming",
            "UC7_YxT-KID8kRbqZo7MyscQ",     // Markiplier
            "UCYzPXprvl5Y-Sf0g4vX-m6g",     // jacksepticeye
            "UCKy1dAqELo0zrOtPkf0eTMw"},    // IGN
        {"cars",
            "UCjOl2AUblVmg2rA_cRgZkFg",     // Top Gear
            "UCsqjHFMB_JYTaEnf_vmTNqg",     // Doug DeMuro
            "UCUhFaUpnq31m6TNX2VKVSVA"},    // carwow
        {"sport",
            "UCB_qr75-ydFVKSF9Dmo6izg",     // Formula 1
            "UCWJ2lWNubArHWmf3FIHbfcQ"},    // NBA
        {"news",
            "UC16niRr50-MSBwiO3YDb3RA",     // BBC News
            "UCNye-wNBqNL5ZzHSJj3l8Bg",     // Al Jazeera English
            "UChqUTb7kYRX8-EiaN3XFrSQ"},    // Reuters
        {"food",
            "UCMyOj6fhvKFMjxUCp3b_3gA",     // Nick DiGiovanni
            "UChBEbMKI1eCcejTtmI32UEw"},    // Joshua Weissman
        {"music",
            "UC2Qw1dzXDBAZPwS7zm37g8g",     // COLORS
            "UC4eYXhJI4-7wSWc8UNRwD4A",     // NPR Music (Tiny Desk)
            "UC3I2GFN_F8WudD_2jUZbojA"},    // KEXP
        {"fun",
            "UCX6OQ3DkcsbYNE6H8uQQuVA",     // MrBeast
            "UC3XTzVzaHQEd30rQbuvCtTQ",     // Last Week Tonight
            "UC8-Th83bH_thdKZDJCrn88g"},    // The Tonight Show
        {"nature",
            "UCpVm7bg6pXKo1Pr6k5kxG9A",     // National Geographic
            "UCW39zufHfsuGgpLviKh297Q",     // DW Documentary
            "UCwmZiChSryoWQCZMIQezgTg"},    // BBC Earth
        {"kids",
            "UCoookXUzPciGrEZEXmh4Jjg",     // Sesame Street
            "UCAOtE1V7Ots4DjM8JLlrYgg",     // Peppa Pig
            "UCbCmjCuTUZos6Inko4u57UQ"},    // Cocomelon
    };
    /**
     * "Hide AI videos" (Settings > YouTube): titles about AI, in the TV's languages ("AI", ChatGPT, OpenAI, "KI",
     * "inteligência artificial"...). By the title only: YouTube's own label for made-with-AI videos isn't public.
     */
    private static final Pattern AI = Pattern.compile("(?iu)\\b(A\\.?I\\.?|AGI|ChatGPT|GPT-?\\d*|OpenAI|LLMs?|Gemini AI|"
            + "Copilot|Midjourney|Sora|DeepSeek|Grok|deepfakes?|chatbots?|artificial intelligence|machine learning|"
            + "inteligência artificial|inteligencia artificial|künstliche intelligenz|KI|kunstmatige intelligentie|"
            + "искусственн\\w* интеллект\\w*|ИИ|нейросет\\w*)\\b");
    /** Where each category's channels start in its row (after the key). */
    private static final int FIRST_CHANNEL = 1;
    private static final long EVERY = 3600_000L, MAX_AGE = 30L * 24 * 3600_000L;
    private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    /** Thumbnails come only from YouTube's image server, for an id that is a video id. */
    static final String THUMBS = "https://i.ytimg.com/vi/";
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    /** The tab's backdrops kept on disk beside the picks' (Recs.prune). */
    static final int BACKDROPS = 20;

    private static final class Video {
        String id, title, channel, channelId;
        long published;
    }

    private static YouTube instance;

    static synchronized YouTube get(Context c) {
        if (instance == null) instance = new YouTube(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Video> videos = new ArrayList<>();
    private volatile long fetchedAt, failedAt;
    private volatile String fetchedFor = "";    // the channels the videos were fetched for
    private volatile boolean complete;          // every channel answered last time (else: again in 10 minutes)
    private volatile boolean fetching;

    private YouTube(Context c) {
        context = c;
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mem = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        maxThumbs = am.isLowRamDevice() || mem.totalMem < 3L << 30 ? 30 : 60;
        store = c.getSharedPreferences("youtube", Context.MODE_PRIVATE);
        try {
            videos = parse(new JSONArray(store.getString("videos", "[]")));
            fetchedAt = store.getLong("at", 0);
            fetchedFor = store.getString("channels", "");
            complete = store.getBoolean("complete", false);
        } catch (Exception ignored) {
        }
    }

    /** The categories' name in the TV's language. */
    static String name(String[] category) {
        switch (category[0]) {
            case "tech": return Ui.tr("Tech");
            case "science": return Ui.tr("Science");
            case "gaming": return Ui.tr("Gaming");
            case "cars": return Ui.tr("Cars");
            case "sport": return Ui.tr("Sports");
            case "news": return Ui.tr("News");
            case "food": return Ui.tr("Food");
            case "music": return Ui.tr("Music");
            case "fun": return Ui.tr("Entertainment");
            case "nature": return Ui.tr("Nature & documentaries");
            case "kids": return Ui.tr("Kids");
            default: return category[0];
        }
    }

    /** config.properties "youtube_channels" set: they win, and the categories aren't asked. */
    static boolean configured() {
        return !Config.YOUTUBE_CHANNELS.trim().isEmpty();
    }

    /** A channel to show: a valid id in config.properties, or a category picked on the TV. Without one, no tab. */
    private static boolean hasChannels() {
        if (!configured()) {
            List<String> picked = Prefs.youtubeCategories();
            return picked != null && !picked.isEmpty();
        }
        for (String s : Config.YOUTUBE_CHANNELS.split("[,\\s]+")) if (CHANNEL_ID.matcher(s.trim()).matches()) return true;
        return false;
    }

    /** The channels to show: from config.properties, or from the picked categories. */
    private static List<String> channels() {
        List<String> out = new ArrayList<>();
        if (configured()) {
            for (String s : Config.YOUTUBE_CHANNELS.split("[,\\s]+")) {
                if (CHANNEL_ID.matcher(s.trim()).matches()) out.add(s.trim());
                else if (!s.trim().isEmpty()) AppLog.w("youtube: not a channel id (UC…), left out: " + s.trim());
            }
            return out;
        }
        List<String> picked = Prefs.youtubeCategories();
        if (picked == null) return out;
        for (String[] c : CATEGORIES) {
            if (!picked.contains(c[0])) continue;
            for (int i = FIRST_CHANNEL; i < c.length; i++) if (!out.contains(c[i])) out.add(c[i]);
        }
        return out;
    }

    /**
     * Turned on (Home Settings > Picks), a channel to show and YouTube or SmartTube on the TV: the home screen shows the
     * YouTube tab.
     */
    boolean on() {
        return Prefs.youtubeOn() && hasChannels() && app() != null;
    }

    /** The app the videos open in: the first YouTube app on the shelf, else the first one installed (YouTube first). */
    private String[] app() {
        for (String pkg : HomeApps.shelfPackages(context)) for (String[] a : APPS) if (a[0].equals(pkg)) return a;
        for (String[] a : APPS) if (HomeApps.launchIntent(context.getPackageManager(), a[0]) != null) return a;
        return null;
    }

    /** Only YouTube's thumbnails and Deezer's covers, over HTTPS: an address from a feed or a chart goes nowhere else. */
    private static boolean imageHost(String url) {
        if (url.startsWith(THUMBS)) return true;
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && u.getHost().endsWith(".dzcdn.net");
        } catch (IOException e) {
            return false;
        }
    }

    /** The shader of an image that thumb() returned. */
    android.graphics.BitmapShader shader(String url) {
        synchronized (thumbs) {
            return shaders.get(url);
        }
    }

    /** A row of the YouTube tab: a category (or "YouTube" for the channels in config.properties) and its videos. */
    static final class Row {
        final String title;
        final List<Recs.Pick> picks = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }

    /**
     * The tab's rows: one per picked category, each with its best-scored videos (newest first, the channels
     * opened most moved up), one per channel while there are other channels to pick from. A row short of recent videos
     * (channels that post monthly) fills up with older ones from the feeds, newest first, so a video opened here makes
     * room for another. Rows with no videos yet are left out; the first time, the first row starts with the "choose your
     * categories" card.
     */
    List<Row> rows() {
        List<Row> out = new ArrayList<>();
        String[] app = app();
        if (app == null) return out;
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>(store.getStringSet("seen", Collections.<String>emptySet()));
        List<Video> fresh = new ArrayList<>(), older = new ArrayList<>();
        boolean hideAi = Prefs.youtubeHideAi();
        for (Video v : videos) {
            if (seen.contains(v.id) || (hideAi && AI.matcher(v.title).find())) continue;
            (now - v.published < MAX_AGE ? fresh : older).add(v);
        }
        Map<String, Double> score = new HashMap<>();
        for (Video v : fresh) score.put(v.id, score(v, now));
        Collections.sort(fresh, (a, b) -> Double.compare(score.get(b.id), score.get(a.id)));
        Collections.sort(older, (a, b) -> Long.compare(b.published, a.published));
        if (configured()) {
            fill(out, "YouTube", channels(), fresh, older, app, now);
        } else {
            List<String> picked = Prefs.youtubeCategories();
            for (String[] c : CATEGORIES) {
                if (picked != null && picked.contains(c[0])) fill(out, name(c), java.util.Arrays.asList(c).subList(FIRST_CHANNEL, c.length), fresh, older, app, now);
            }
        }
        return out;
    }

    /** Adds a row with the videos of these channels, if it has any: the recent ones, then older ones while it's short. */
    private void fill(List<Row> rows, String title, List<String> channels, List<Video> fresh, List<Video> older,
            String[] app, long now) {
        Set<String> wanted = new HashSet<>(channels);
        Row row = new Row(title);
        add(row, wanted, fresh, app, now);
        add(row, wanted, older, app, now);
        if (!row.picks.isEmpty()) rows.add(row);
    }

    /** One per channel first, then a second from each, then any: a channel that posts all day doesn't fill the row. */
    private static void add(Row row, Set<String> wanted, List<Video> sorted, String[] app, long now) {
        Map<String, Integer> perChannel = new HashMap<>();
        for (int pass = 1; pass <= 3 && row.picks.size() < Recs.MAX_CARDS; pass++) {
            for (Video v : sorted) {
                if (row.picks.size() >= Recs.MAX_CARDS) break;
                if (!wanted.contains(v.channelId)) continue;
                int count = perChannel.containsKey(v.channelId) ? perChannel.get(v.channelId) : 0;
                if ((pass < 3 && count >= pass) || contains(row.picks, v.id)) continue;
                perChannel.put(v.channelId, count + 1);
                row.picks.add(pick(v, app, now));
            }
        }
    }

    /** The first row's videos (Recs.now("yt"): the home screen preloads the backdrops of the first ones). */
    List<Recs.Pick> now() {
        List<Row> rows = rows();
        return rows.isEmpty() ? new ArrayList<>() : rows.get(0).picks;
    }

    // MARK: - Images for the rows: YouTube's 320×180 thumbnails and Deezer's 250×250 covers, kept in memory (~120 KB each)

    /** 60 kept (~7 MB), or 30 under 3 GB: still more than the cards on screen, so scrolling back doesn't refetch. */
    private final int maxThumbs;
    private final Map<String, android.graphics.Bitmap> thumbs = new java.util.LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, android.graphics.Bitmap> eldest) {
            if (size() <= maxThumbs) return false;
            shaders.remove(eldest.getKey());
            return true;
        }
    };
    // Each thumbnail's shader for HomeView.coverImage, made with it and dropped with it (nothing made while drawing).
    private final Map<String, android.graphics.BitmapShader> shaders = new HashMap<>();
    private final Set<String> thumbPending = new HashSet<>();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * A row card's image (Pick.thumb: a video's thumbnail, an album cover), or null: it is fetched in the background and
     * `ready` runs on the main thread. The Music tab shares this cache.
     */
    android.graphics.Bitmap thumb(String url, Runnable ready) {
        if (!imageHost(url)) return null;
        synchronized (thumbs) {
            android.graphics.Bitmap b = thumbs.get(url);
            if (b != null || !thumbPending.add(url)) return b;
        }
        thumbIo.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                HttpURLConnection h = open(url);
                try (InputStream in = h.getInputStream()) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                    // On a 720p UI a card is ~113 px high: Deezer's 250 px covers at half size are still sharp there.
                    if (Backdrop.W <= 1280 && url.contains("dzcdn.net")) o.inSampleSize = 2;
                    b = android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    h.disconnect();
                }
            } catch (Exception e) {
                AppLog.w("thumbnail " + url + ": " + e.getMessage());
            }
            final android.graphics.Bitmap done = b;
            synchronized (thumbs) {
                if (done != null) {
                    shaders.put(url, new android.graphics.BitmapShader(done, android.graphics.Shader.TileMode.CLAMP,
                            android.graphics.Shader.TileMode.CLAMP));
                    thumbs.put(url, done);
                }
            }
            // A failed one stays pending until a minute has passed: not fetched again on every frame.
            main.postDelayed(() -> { synchronized (thumbs) { thumbPending.remove(url); } }, done != null ? 0 : 60_000L);
            if (done != null) main.post(ready);
        });
        return null;
    }

    private static boolean contains(List<Recs.Pick> picks, String id) {
        for (Recs.Pick p : picks) if (id.equals(p.video)) return true;
        return false;
    }

    /** Newer is better (half after two days); each open of the channel from here adds about a day and a half. */
    private double score(Video v, long now) {
        double hours = Math.max(0, now - v.published) / 3600_000.0;
        int opens = store.getInt("opens:" + v.channelId, 0);
        return 1 / (1 + hours / 48) + 0.35 * Math.log(1 + opens);
    }

    private static Recs.Pick pick(Video v, String[] app, long now) {
        Recs.Pick p = new Recs.Pick();
        p.kind = "yt";
        p.video = v.id;
        p.thumb = THUMBS + v.id + "/mqdefault.jpg";   // 320×180, always there
        p.title = v.title;
        p.backdrop = THUMBS + v.id + "/maxresdefault.jpg";
        p.link = "https://www.youtube.com/watch?v=" + v.id;
        // YouTube plays it in the channel's uploads (UU…): what comes next is the same channel's, not YouTube's
        // suggestions. SmartTube would only show that list, without playing the video.
        if (app[1].equals("youtube") && v.channelId != null && CHANNEL_ID.matcher(v.channelId).matches()) {
            p.link += "&list=UU" + v.channelId.substring(2);
        }
        p.pkg = app[0];
        p.app = app[1];
        p.meta = v.channel + " · " + age(now - v.published) + " · " + Recs.serviceName(p);
        p.detailed = true;
        return p;
    }

    private static String age(long ms) {
        long minutes = Math.max(1, ms / 60_000L);
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 24 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }

    /** A video was opened from the tab: its channel moves up and the video leaves the tab. */
    void opened(Recs.Pick p) {
        if (p == null || !"yt".equals(p.kind)) return;
        String channel = null;
        for (Video v : videos) if (v.id.equals(p.video)) channel = v.channelId;
        Set<String> seen = new HashSet<>(store.getStringSet("seen", Collections.<String>emptySet()));
        seen.add(p.video);
        if (seen.size() > 300) {     // only the ones still in the feeds matter (older videos fill short rows)
            Set<String> current = new HashSet<>();
            for (Video v : videos) current.add(v.id);
            seen.retainAll(current);
        }
        SharedPreferences.Editor e = store.edit().putStringSet("seen", seen);
        if (channel != null) e.putInt("opens:" + channel, store.getInt("opens:" + channel, 0) + 1);
        e.apply();
    }

    /** Fetches the feeds once an hour (after a failure, not again for 10 minutes); `done` runs when there's news. */
    void refreshIfStale(Runnable done) {
        if (!on() || fetching) return;
        List<String> channels = channels();
        String key = String.join(",", channels);
        boolean picked = !key.equals(fetchedFor);     // other categories: right away
        long now = System.currentTimeMillis();
        long every = complete ? EVERY : 10 * 60_000L;
        if (!picked && now - fetchedAt < every && now >= fetchedAt && !videos.isEmpty()) return;
        if (!picked && failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Video> all = new ArrayList<>(), before = videos;
                String last = "";
                int failed = 0;
                for (String c : channels) {
                    try {
                        all.addAll(feed(c));
                    } catch (Exception e) {
                        // YouTube answers 404 for a feed now and then: the channel keeps the videos it had.
                        failed++;
                        last = c + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                        for (Video v : before) if (v.channelId.equals(c)) all.add(v);
                    }
                }
                if (failed > 0) AppLog.w("youtube: " + failed + " of " + channels.size() + " feeds failed (" + last + ")");
                if (all.isEmpty() && failed > 0) throw new IOException("no videos");
                videos = all;
                fetchedFor = key;
                fetchedAt = System.currentTimeMillis();
                complete = failed == 0;
                failedAt = failed == channels.size() ? fetchedAt : 0;
                save();
                done.run();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("youtube: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** The channel's 15 newest uploads, without Shorts. */
    private static List<Video> feed(String channelId) throws Exception {
        HttpURLConnection h = open("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
        List<Video> out = new ArrayList<>();
        try (InputStream in = h.getInputStream()) {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String channel = "";
            Video v = null;
            boolean shorts = false;
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String tag = x.getName();
                    if (tag.equals("entry")) {
                        v = new Video();
                        v.channelId = channelId;
                        shorts = false;
                    } else if (v == null) {
                        if (tag.equals("title")) channel = x.nextText();
                    } else if (tag.equals("videoId") || tag.equals("yt:videoId")) {   // without namespaces, the prefix stays
                        v.id = x.nextText();
                    } else if (tag.equals("title") && v.title == null) {
                        v.title = x.nextText();
                    } else if (tag.equals("link")) {
                        shorts |= String.valueOf(x.getAttributeValue(null, "href")).contains("/shorts/");
                    } else if (tag.equals("published")) {
                        String t = x.nextText();
                        v.published = iso.parse(t.length() >= 19 ? t.substring(0, 19) : t).getTime();
                    }
                } else if (e == XmlPullParser.END_TAG && x.getName().equals("entry") && v != null) {
                    if (!shorts && v.id != null && VIDEO_ID.matcher(v.id).matches() && v.title != null) {
                        v.channel = channel;
                        out.add(v);
                    }
                    v = null;
                }
            }
        } finally {
            h.disconnect();
        }
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0");
        h.setInstanceFollowRedirects(false);   // youtube.com and i.ytimg.com only, the hosts the README lists
        return h;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Video v : videos) {
                a.put(new JSONObject().put("id", v.id).put("title", v.title).put("channel", v.channel)
                        .put("channelId", v.channelId).put("published", v.published));
            }
            store.edit().putString("videos", a.toString()).putLong("at", fetchedAt).putString("channels", fetchedFor)
                    .putBoolean("complete", complete).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Video> parse(JSONArray a) {
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Video v = new Video();
            v.id = o.optString("id");
            v.title = o.optString("title");
            v.channel = o.optString("channel");
            v.channelId = o.optString("channelId");
            v.published = o.optLong("published");
            if (VIDEO_ID.matcher(v.id).matches()) out.add(v);
        }
        return out;
    }
}
