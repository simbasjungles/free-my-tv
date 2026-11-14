package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What you were watching in your apps, from Android TV's own lists (TvContract, needs READ_TV_LISTINGS): the system
 * Watch Next list, where Prime Video, Disney+, HBO Max, Plex and others note what you left half-way, and the home
 * screen channels some apps publish under the name "Continue Watching" instead (Stremio). Newest first, a few per app,
 * so one show doesn't fill the row. Signed in to Plex (Plex.java), what Plex has in progress joins in by when it was
 * last watched, and the Plex Watchlist comes last.
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Declared by apps that write the TV's lists (apps can also write their own rows and Watch Next without it). */
    private static final String WRITE_EPG = "com.android.providers.tv.permission.WRITE_EPG_DATA";
    static final int PER_APP = 2, MAX = 12;
    /** Channel names that mean "continue watching" (apps name their own channel). */
    private static final Set<String> CHANNEL_NAMES = new HashSet<>(java.util.Arrays.asList(
            "continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo"));

    static final class Item {
        String pkg, title, episode;     // episode: "S2 E5" (in the TV's language) or the episode's title, or null
        String app;                     // the app's name, for the card's details line
        long duration, position, engaged;
        String intent;
        final Uri pictureUri;
        final String pictureKey, bannerKey;
        /** Read from Plex itself (Plex.java), not from what Plex shares on the TV: its own limit per app. */
        boolean fromPlex;
        private final String picture;

        Item(String pkg, String poster, String thumbnail) {
            this.pkg = pkg;
            picture = thumbnail != null ? thumbnail : poster;
            Uri uri = picture == null ? null : Uri.parse(picture);
            pictureUri = uri != null && ("content".equalsIgnoreCase(uri.getScheme())
                    || "android.resource".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) ? uri : null;
            pictureKey = pictureUri == null ? null : picture;
            bannerKey = "banner:" + pkg;
        }

        /**
         * From Plex (Plex.java): the picture is Plex's (a server path or Plex's https artwork), "" for the banner.
         * `server` scopes its key: a path on one server isn't the same picture on another account's.
         */
        static Item plex(String image, String server) {
            return new Item(image, server);
        }

        private Item(String image, String server) {
            pkg = Plex.PKG;
            fromPlex = true;
            picture = image.isEmpty() ? null : image;
            pictureUri = null;
            pictureKey = image.isEmpty() ? null : Plex.PICTURE + server + "|" + image;
            bannerKey = "banner:" + pkg;
        }

        /** The picture for a 16:9 card: the thumbnail (usually 16:9) before the poster. */
        String picture() {
            return picture;
        }

        /** 0 to 1, or -1 when there is no position worth showing (not started, or no length). */
        float progress() {
            if (duration <= 0 || position < 1000 || position >= duration) return -1;
            return (float) position / duration;
        }
    }

    /** How a hidden item is remembered: its app and title (apps can't be asked to drop another app's entry). */
    static String hideKey(Item it) {
        return it.pkg + "|" + it.title;
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Everything to show, newest first (off the main thread: these are content provider queries). */
    static List<Item> load(Context c) {
        Plex.Lists plex = Plex.lists(c);   // signed in to Plex, with or without the TV's lists
        if (!allowed(c) && plex.watching.isEmpty() && plex.watchlist.isEmpty()) return Collections.emptyList();
        List<Item> all = new ArrayList<>();
        if (allowed(c)) {
            ContentResolver cr = c.getContentResolver();
            all.addAll(read(cr, TvContract.WatchNextPrograms.CONTENT_URI));
            for (long channel : continueChannels(cr, c.getPackageName())) {
                all.addAll(read(cr, TvContract.buildPreviewProgramsUriForChannel(channel)));
            }
        }
        Set<String> off = Prefs.watchOff(), hidden = Prefs.watchHidden();
        if (!plex.watching.isEmpty()) {
            // Plex's own list replaces what Plex shares on the TV (Google TV), which would only repeat it. Until it has
            // something (signed out, the first fetch, no server), the TV's Plex rows stay.
            for (java.util.Iterator<Item> i = all.iterator(); i.hasNext(); ) if (Plex.PKG.equals(i.next().pkg)) i.remove();
            // Mixed in with the other apps' titles by when each was last watched, as theirs are.
            all.addAll(plex.watching);
        }
        for (java.util.Iterator<Item> i = all.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (off.contains(it.pkg) || hidden.contains(hideKey(it))) i.remove();
        }
        Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Item it : all) {
            String key = it.pkg + "|" + (it.intent != null ? it.intent : it.title);
            int n = perApp.containsKey(it.pkg) ? perApp.get(it.pkg) : 0;
            // Plex's own list is in-progress titles, one per show: more of it than of an app's shared rows.
            if (n >= (it.fromPlex ? Plex.CONTINUE_MAX : PER_APP) || !seen.add(key)) continue;
            perApp.put(it.pkg, n + 1);
            it.app = appName(c, it.pkg);
            out.add(it);
            if (out.size() >= MAX) break;
        }
        // The watchlist after what you're in the middle of, in every app.
        if (!off.contains(Plex.PKG)) {
            String name = appName(c, Plex.PKG);
            for (Item it : plex.watchlist) {
                if (out.size() >= MAX) break;
                if (hidden.contains(hideKey(it)) || !seen.add(it.pkg + "|" + it.title)) continue;
                it.app = name;
                out.add(it);
            }
        }
        return out;
    }

    /**
     * The apps that can feed Continue Watching (Settings, off the main thread): those allowed to write Android TV's
     * lists, plus any publishing right now. As {package, name}, sorted by name, without Free My TV.
     */
    static List<String[]> sources(Context c) {
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = c.getPackageManager();
        try {
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (p.requestedPermissions == null) continue;
                for (String perm : p.requestedPermissions) {
                    if (WRITE_EPG.equals(perm)) { pkgs.add(p.packageName); break; }
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps: " + e.getMessage());
        }
        if (allowed(c)) {
            ContentResolver cr = c.getContentResolver();
            packages(cr, TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, null, pkgs);
            // Home screen rows (not the tuner's channels): apps write these, and Watch Next, without any permission.
            packages(cr, TvContract.Channels.CONTENT_URI, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE, pkgs);
        }
        if (Plex.available()) pkgs.add(Plex.PKG);   // its row signs in to Plex too
        pkgs.remove(c.getPackageName());
        // Only apps you can open from the TV: leaves out system pieces (Shell) and home screens (other launchers),
        // which ask for the permission but have nothing to resume.
        Set<String> homes = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        List<String[]> out = new ArrayList<>();
        // Plex stays while signed in, even uninstalled: its row is where the account is signed out.
        boolean plexAccount = Plex.signedIn(c);
        for (String p : pkgs) {
            boolean keep = plexAccount && Plex.PKG.equals(p);
            if (!keep && (homes.contains(p) || HomeApps.launchIntent(pm, p) == null)) continue;
            String name = keep && appName(c, p).isEmpty() ? "Plex" : appName(c, p);
            out.add(new String[] {p, name.isEmpty() ? p : name});
        }
        Collections.sort(out, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The packages in a TvContract table (only preview channels when typeColumn is given). */
    private static void packages(ContentResolver cr, Uri uri, String column, String typeColumn, Set<String> out) {
        String[] projection = typeColumn == null ? new String[] {column} : new String[] {column, typeColumn};
        try (Cursor cur = cr.query(uri, projection, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                if (typeColumn != null && !TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(1))) continue;
                String p = cur.isNull(0) ? null : cur.getString(0);
                if (p != null) out.add(p);
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps " + uri + ": " + e.getMessage());
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static List<Long> continueChannels(ContentResolver cr, String self) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (cur == null) return ids;
            int id = cur.getColumnIndex(TvContract.Channels._ID);
            int name = cur.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int pkg = cur.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            while (cur.moveToNext()) {
                String n = name < 0 ? null : cur.getString(name), p = pkg < 0 ? null : cur.getString(pkg);
                if (n != null && CHANNEL_NAMES.contains(n.trim().toLowerCase(Locale.ROOT)) && !self.equals(p)) {
                    ids.add(cur.getLong(id));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching channels: " + e.getMessage());
        }
        return ids;
    }

    private static List<Item> read(ContentResolver cr, Uri uri) {
        List<Item> out = new ArrayList<>();
        List<String> projection = new ArrayList<>(java.util.Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI));
        // Preview programs have no engagement time or Watch Next type.
        if (TvContract.WatchNextPrograms.CONTENT_URI.equals(uri)) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                // Saved for later, not something you're in the middle of.
                if (number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
                        == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                String title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || pkg == null) continue;
                Item it = new Item(pkg, text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                        text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI));
                it.title = title;
                String season = text(cur, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String episode = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                // Season and episode as each language writes them: "S2 E5", "T2 E5" (temporada), "St. 2 F. 5".
                it.episode = season != null && episode != null
                        ? Ui.trf("S{season} E{episode}", "season", season, "episode", episode)
                        : text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                if (it.episode != null && (it.episode.isEmpty() || it.title.contains(it.episode))) it.episode = null;
                it.duration = number(cur, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                it.position = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                it.engaged = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                it.intent = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                out.add(it);
            }
        } catch (RuntimeException e) {   // a provider that refuses (no permission on some firmware) means nothing to show
            AppLog.w("continue watching " + uri + ": " + e.getMessage());
        }
        return out;
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
