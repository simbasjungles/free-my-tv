package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Reads the TV platform's shared Continue Watching entries when the device grants access. */
final class WatchNext {
    private static final int CONTINUE = 0;
    private static final int MAX_ARTWORK_BYTES = 16 * 1024 * 1024;
    private static final int ARTWORK_CACHE_BYTES = (int) Math.min(6 * 1024 * 1024,
            Runtime.getRuntime().maxMemory() / 16);
    private static final LruCache<String, Bitmap> ARTWORK = new LruCache<String, Bitmap>(ARTWORK_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static boolean warnedPermission;
    private static boolean warnedMissingArtwork;
    private static boolean warnedUnreadableArtwork;

    private WatchNext() {}

    static List<Recs.Pick> read(Context context) {
        List<Recs.Pick> items = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            return items;
        }
        String[] columns = {
                "title", "package_name", "intent_uri", "poster_art_uri", "thumbnail_uri",
                "last_playback_position_millis", "duration_millis", "watch_next_type",
                "last_engagement_time_utc_millis", "season_display_number",
                "episode_display_number", "episode_title"
        };
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, columns,
                    null, null, null);
        } catch (SecurityException e) {
            warnDenied(e);
            return items;
        }
        if (cursor == null) return items;
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = cursor) {
            int typeColumn = c.getColumnIndex("watch_next_type");
            int titleColumn = c.getColumnIndex("title");
            int packageColumn = c.getColumnIndex("package_name");
            int intentColumn = c.getColumnIndex("intent_uri");
            int posterColumn = c.getColumnIndex("poster_art_uri");
            int thumbnailColumn = c.getColumnIndex("thumbnail_uri");
            int positionColumn = c.getColumnIndex("last_playback_position_millis");
            int durationColumn = c.getColumnIndex("duration_millis");
            int engagementColumn = c.getColumnIndex("last_engagement_time_utc_millis");
            int seasonColumn = c.getColumnIndex("season_display_number");
            int episodeColumn = c.getColumnIndex("episode_display_number");
            int episodeTitleColumn = c.getColumnIndex("episode_title");
            if (typeColumn < 0 || titleColumn < 0 || packageColumn < 0 || intentColumn < 0) return items;
            while (c.moveToNext()) {
                if (c.getInt(typeColumn) != CONTINUE) continue;
                Entry entry = new Entry();
                entry.title = c.getString(titleColumn);
                entry.pkg = c.getString(packageColumn);
                entry.intent = c.getString(intentColumn);
                entry.art = value(c, posterColumn);
                if (TextUtils.isEmpty(entry.art)) entry.art = value(c, thumbnailColumn);
                entry.position = number(c, positionColumn);
                entry.duration = number(c, durationColumn);
                entry.engagement = number(c, engagementColumn);
                entry.season = value(c, seasonColumn);
                entry.episode = value(c, episodeColumn);
                entry.episodeTitle = value(c, episodeTitleColumn);
                if (!TextUtils.isEmpty(entry.title) && !TextUtils.isEmpty(entry.pkg)
                        && !TextUtils.isEmpty(entry.intent)) entries.add(entry);
            }
        } catch (SecurityException e) {
            warnDenied(e);
            return items;
        }
        Collections.sort(entries, Comparator.comparingLong((Entry entry) -> entry.engagement).reversed());
        for (Entry entry : entries) {
            if (items.size() >= Prefs.watchNextCount()) break;
            if (HomeApps.launchIntent(context.getPackageManager(), entry.pkg) == null) continue;
            Recs.Pick item = new Recs.Pick();
            item.watchNext = true;
            item.kind = "watch-next";
            item.pkg = entry.pkg;
            item.title = entry.title;
            item.link = entry.intent;
            item.app = appName(context, entry.pkg);
            item.episodeTitle = entry.episodeTitle;
            item.progressPercent = entry.duration > 0
                    ? Math.max(0, Math.min(100, (int) (100L * entry.position / entry.duration))) : 0;
            StringBuilder details = new StringBuilder(item.app);
            if (!TextUtils.isEmpty(entry.season)) details.append(" · S").append(entry.season);
            if (!TextUtils.isEmpty(entry.episode)) details.append(" E").append(entry.episode);
            if (entry.duration > 0) details.append(" · ").append(time(entry.position)).append('/').append(time(entry.duration));
            item.meta = details.toString();
            item.artwork = artwork(context, entry.art);
            items.add(item);
        }
        return items;
    }

    private static final class Entry {
        String title, pkg, intent, art, season, episode, episodeTitle;
        long position, duration, engagement;
    }

    private static String value(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? null : cursor.getString(column);
    }

    private static long number(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? 0 : cursor.getLong(column);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, remainder);
    }

    private static void warnDenied(SecurityException e) {
        if (warnedPermission) return;
        AppLog.w("Continue Watching isn't readable on this TV: " + e.getMessage());
        warnedPermission = true;
    }

    private static String appName(Context context, String pkg) {
        try {
            return String.valueOf(context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static Bitmap artwork(Context context, String address) {
        if (TextUtils.isEmpty(address)) {
            if (!warnedMissingArtwork) {
                AppLog.w("Continue Watching entry has no poster or thumbnail URI");
                warnedMissingArtwork = true;
            }
            return null;
        }
        Uri uri = Uri.parse(address);
        synchronized (ARTWORK) {
            Bitmap cached = ARTWORK.get(address);
            if (cached != null) return cached;
        }
        Bitmap bitmap;
        if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            bitmap = downloadArtwork(uri);
        } else {
            bitmap = readArtwork(context, uri);
        }
        if (bitmap != null) {
            synchronized (ARTWORK) {
                ARTWORK.put(address, bitmap);
            }
        }
        return bitmap;
    }

    private static Bitmap readArtwork(Context context, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | SecurityException e) {
            AppLog.w("Continue Watching artwork failed (" + uri.getScheme() + "): " + e.getMessage());
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            warnUnreadableArtwork(uri, bounds.outWidth, bounds.outHeight);
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.min(bounds.outWidth / 960, bounds.outHeight / 540));
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = in == null ? null : BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) warnUnreadableArtwork(uri, bounds.outWidth, bounds.outHeight);
            return bitmap;
        } catch (IOException | SecurityException e) {
            AppLog.w("Continue Watching artwork failed (" + uri.getScheme() + "): " + e.getMessage());
            return null;
        }
    }

    private static void warnUnreadableArtwork(Uri uri, int width, int height) {
        if (warnedUnreadableArtwork) return;
        AppLog.w("Continue Watching artwork couldn't be decoded (" + uri.getScheme() + ", "
                + width + "x" + height + ")");
        warnedUnreadableArtwork = true;
    }

    private static Bitmap downloadArtwork(Uri uri) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                AppLog.w("Continue Watching artwork returned HTTP " + status);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = readLimited(in);
                if (bytes == null) {
                    AppLog.w("Continue Watching artwork exceeded the 16 MB download limit");
                    return null;
                }
                Bitmap bitmap = decodeArtwork(bytes);
                if (bitmap == null) AppLog.w("Continue Watching artwork download returned an unreadable image");
                return bitmap;
            }
        } catch (IOException e) {
            AppLog.w("Continue Watching artwork download failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > MAX_ARTWORK_BYTES) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Bitmap decodeArtwork(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.min(bounds.outWidth / 960, bounds.outHeight / 540));
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    static boolean open(Activity activity, Recs.Pick item) {
        PackageManager pm = activity.getPackageManager();
        try {
            Intent intent = Intent.parseUri(item.link, 0).setPackage(item.pkg);
            if ((intent.getComponent() == null || item.pkg.equals(intent.getComponent().getPackageName()))
                    && pm.resolveActivity(intent, 0) != null) {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                return true;
            }
        } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't resume " + item.title + ": " + e.getMessage());
        }
        Intent home = HomeApps.launchIntent(pm, item.pkg);
        if (home == null) return false;
        try {
            activity.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + item.app + ": " + e.getMessage());
            return false;
        }
    }
}
