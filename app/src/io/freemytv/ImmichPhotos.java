package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Photos from an Immich server (immich_server in config.properties, its key sent to each TV by tools/send_key.py
 * into Secrets, never in the APK): every 6 hours, a random choice of its photos goes through the same import as the
 * ones a computer sends, one at a time, so at most one original is on disk at once. Nothing goes before something new
 * has come: at Photos.MAX the oldest of Immich's make room, and after a change of source the previous source's photos
 * go one for each new one. Photos sent with tools/photos.py are never touched. A TV without the key shows My Photos
 * as before, even in a build with a server set: the key sent by hand is what says this TV shows Immich.
 */
final class ImmichPhotos {
    private ImmichPhotos() {}

    private static final long EVERY_MS = 6 * 3_600_000L;
    /** After a failure (the server down), tried again this much later. */
    private static final long RETRY_MS = 30 * 60_000L;
    /** A claim this old was left by a process that died while syncing: another one may take it. */
    private static final long CLAIM_MS = 3_600_000L;
    /** Photos per sync; the import keeps Photos.MAX in all. */
    private static final int COUNT = 40;
    /** Smaller ones would be blown up on the screen (Photos also refuses under 640). */
    private static final int MIN_SIDE = 1080;
    /** Originals bigger than this (RAW, TIFF exports…) come as Immich's preview instead. */
    private static final long MAX_ORIGINAL = 25L << 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private static volatile String key = "";
    private static volatile long checkedAt = -1;

    /** A server in config.properties and its key sent to this TV, read again every 30 s at most (Settings asks every frame). */
    static boolean configured(Context c) {
        if (Config.IMMICH_SERVER.trim().isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        if (checkedAt < 0 || now - checkedAt > 30_000) {
            key = Secrets.get(c, Secrets.IMMICH_KEY);
            checkedAt = now;
        }
        return !key.isEmpty();
    }

    /** An album with photos in it: what My Photos can show. */
    static final class Album {
        final String id, name;

        Album(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** The server's albums with photos, once loadAlbums has read them (empty before). */
    static volatile java.util.List<Album> albums = new java.util.ArrayList<>();

    /** Reads the albums in the background; `done` runs on the main thread when they're in. */
    static void loadAlbums(Context c, Runnable done) {
        if (!configured(c)) return;
        WORKER.execute(() -> {
            try {
                JSONArray all = new JSONArray(request("/api/albums", null));
                java.util.List<Album> out = new java.util.ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject a = all.getJSONObject(i);
                    if (a.optInt("assetCount") > 0) out.add(new Album(a.optString("id"), a.optString("albumName")));
                }
                java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
                albums = out;
                if (done != null) MAIN.post(done);
            } catch (Exception e) {
                AppLog.w("immich albums: " + e.getMessage());
            }
        });
    }

    /**
     * My Photos' source changed (Home Settings): a new choice comes at the next home screen, and Immich's photos from
     * before go as it comes. Default (none of yours) takes Immich's away now; tools/photos.py's always stay.
     */
    static void sourceChanged(Context c) {
        Context app = c.getApplicationContext();
        //noinspection ResultOfMethodCallIgnored
        new File(app.getFilesDir(), "immich-sync").delete();
        boolean mine = !"default".equals(Prefs.photoSource());
        long at = System.currentTimeMillis();
        // On the worker, after a sync under way: what that one brings is the previous source's too
        WORKER.execute(() -> {
            if (mine) touch(changed(app), at);
            else Photos.removeImmich(app);
        });
        Prefs.setPhotos(mine);
    }

    /** Its time is when the source last changed (absent: no previous source's photos to replace). */
    private static File changed(Context app) {
        return new File(app.getFilesDir(), "immich-source");
    }

    /** One sync at a time across processes: the screensaver (main process) and the home screen (:home) both ask. */
    private static File claim(Context app) {
        return new File(app.getFilesDir(), "immich-sync.lock");
    }

    /** Takes the claim atomically; false while another process is syncing, or when the file can't be written. */
    private static boolean take(Context app) {
        File lock = claim(app);
        if (lock.exists() && System.currentTimeMillis() - lock.lastModified() > CLAIM_MS) lock.delete();
        try {
            return lock.createNewFile();
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts a sync in the background when one is due (and My Photos is on); `ready` runs on the main thread after. */
    static void syncIfDue(Context c, Runnable ready) {
        if (!configured(c) || !Prefs.photos() || "default".equals(Prefs.photoSource()) || running) return;
        Context app = c.getApplicationContext();
        File stamp = new File(app.getFilesDir(), "immich-sync");
        if (Math.abs(System.currentTimeMillis() - stamp.lastModified()) < EVERY_MS) return;   // 0 when never
        if (!take(app)) return;   // the other process is syncing
        running = true;
        WORKER.execute(() -> {
            int n = 0;
            try {
                n = sync(app);
                AppLog.w("immich: " + n + " photos, " + Photos.files(app).size() + " in all");
            } catch (Exception e) {
                AppLog.w("immich: " + e.getMessage());
            } finally {
                touch(stamp, n > 0 ? System.currentTimeMillis() : System.currentTimeMillis() - EVERY_MS + RETRY_MS);
                claim(app).delete();
                running = false;
            }
            if (n > 0 && ready != null) MAIN.post(ready);
        });
    }

    private static int sync(Context app) throws Exception {
        JSONObject query = new JSONObject().put("size", COUNT * 2).put("type", "IMAGE").put("withExif", true);
        if ("album".equals(Prefs.photoSource()) && !Prefs.photoAlbum().isEmpty()) {
            query.put("albumIds", new JSONArray().put(Prefs.photoAlbum()));
        }
        JSONArray found = new JSONArray(request("/api/search/random", query.toString()));
        File inbox = Photos.privateInbox(app);
        if (!inbox.isDirectory() && !inbox.mkdirs()) throw new IOException("can't create " + inbox);
        // The previous source's photos, oldest first: one goes for each new one; the rest wait for the next syncs
        File changed = changed(app);
        List<File> previous = new ArrayList<>();
        if (changed.exists()) {
            for (File f : Photos.fromImmich(app)) if (Photos.importedAt(f) < changed.lastModified()) previous.add(f);
        }
        // Low-memory and 720p TVs take Immich's preview (about 1440 px) rather than decode a 24 MP original
        boolean small = Backdrop.W <= 1280 || HomeApps.lowRam(app);
        int got = 0;
        for (int i = 0; i < found.length() && got < COUNT; i++) {
            JSONObject a = found.getJSONObject(i);
            String id = a.optString("id");
            if (!"IMAGE".equals(a.optString("type")) || !id.matches("[0-9a-fA-F-]{8,64}")) continue;
            JSONObject exif = a.optJSONObject("exifInfo");
            if (exif != null) {
                int w = exif.optInt("exifImageWidth"), h = exif.optInt("exifImageHeight");
                if (w > 0 && h > 0 && Math.min(w, h) < MIN_SIDE) continue;
            }
            long size = exif == null ? 0 : exif.optLong("fileSizeInByte");
            boolean original = !small && "image/jpeg".equals(a.optString("originalMimeType")) && size < MAX_ORIGINAL;
            File part = new File(inbox, "." + Photos.IMMICH + id), done = new File(inbox, Photos.IMMICH + id + ".jpg");
            // All 100 are tools/photos.py's, so nothing could make room: stop before a download that would be thrown
            // away (and, with nothing imported, made again every 30 minutes)
            if (Photos.count(app) >= Photos.MAX && Photos.fromImmich(app).isEmpty()) break;
            try {
                download("/api/assets/" + id + (original ? "/original" : "/thumbnail?size=preview"), part);
                // Room only now that the new one is here: a download that fails takes nothing away
                boolean full = Photos.count(app) >= Photos.MAX;
                if (full && !makeRoom(app, previous)) break;   // all of them are tools/photos.py's
                if (!part.renameTo(done)) continue;
                // Right away: the next original doesn't wait beside this one. One of the previous source's goes for
                // each new one (already done when it made room).
                if (Photos.importWaiting(app) > 0) {
                    got++;
                    if (!full && !previous.isEmpty()) previous.remove(0).delete();
                }
            } catch (IOException e) {
                AppLog.w("immich: photo skipped: " + e.getMessage());
            } finally {
                part.delete();
            }
        }
        // The previous source's go one for each new one (above), never the rest: a sync that brought only a few, or
        // stopped on the network, would otherwise take the whole library with it. The mark stays until the last of
        // them went, so the next syncs carry on replacing them.
        if (got > 0 && previous.isEmpty()) changed.delete();
        return got;
    }

    /** Room for one more at Photos.MAX: the previous source's oldest, else Immich's oldest; never tools/photos.py's. */
    private static boolean makeRoom(Context app, List<File> previous) {
        if (!previous.isEmpty()) return previous.remove(0).delete();
        List<File> immich = Photos.fromImmich(app);
        return !immich.isEmpty() && immich.get(0).delete();
    }

    private static HttpURLConnection open(String path) throws IOException {
        String server = Config.IMMICH_SERVER.trim();
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        HttpURLConnection http = (HttpURLConnection) new URL(server + path).openConnection();
        // Never followed: on a redirect to another host only Authorization is dropped, and x-api-key would go along
        http.setInstanceFollowRedirects(false);
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("x-api-key", key);
        http.setRequestProperty("Accept", "application/json, image/*");
        return http;
    }

    /** A JSON request: POST with `json`, or GET without it. */
    private static String request(String path, String json) throws IOException {
        HttpURLConnection http = open(path);
        try {
            if (json != null) {
                http.setRequestMethod("POST");
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " on " + path);
            try (InputStream in = http.getInputStream()) {
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                return body.toString("UTF-8");
            }
        } finally {
            http.disconnect();
        }
    }

    private static void download(String path, File to) throws IOException {
        HttpURLConnection http = open(path);
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long left = MAX_ORIGINAL;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if ((left -= n) < 0) throw new IOException("too big");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            http.disconnect();
        }
    }

    private static void touch(File f, long when) {
        try {
            if (!f.exists()) new FileOutputStream(f).close();
            f.setLastModified(Math.max(1, when));
        } catch (IOException ignored) {
            // asked again at the next resume
        }
    }
}
