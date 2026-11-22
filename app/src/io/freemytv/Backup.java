package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Home Settings › Export settings and Import settings: what the person chose, as one JSON file, so a reinstall, a
 * factory reset or another TV doesn't mean setting it all again. The file lives in the app's own external folder
 * (`Android/data/io.freemytv/files/freemytv-settings.json`: no permission, and adb can pull and push it with
 * `tools/settings.py`; where the shell can't reach that folder, BackupFile hands it over). Only choices go in: never a
 * key or token (TMDB, Watchmode, Trakt, Home Assistant, Immich), a running sleep timer, a cache, or anything tied to
 * this one TV (its input ids, the Immich album, hidden Continue Watching titles). BackupRules says what may be in the
 * file and what a good value is: import skips what it doesn't know or doesn't like, so a file from another version, or
 * edited by hand, can't break the home screen. Import makes the TV match the file: a setting the file doesn't name goes
 * back to its default (the ones that aren't choices, such as the keys, stay).
 */
final class Backup {
    private Backup() {}

    static final String FILE = "freemytv-settings.json";
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 64 * 1024;

    /** The file's place: the app's external folder, or where the TV has none, its private files (BackupFile reads it there). */
    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        return new File(dir == null ? c.getFilesDir() : dir, FILE);
    }

    /** Writes the file. Returns how many settings it holds, or -1 if it couldn't be written. */
    static int export(Context c) {
        File f = file(c);
        try {
            JSONObject settings = new JSONObject();
            SharedPreferences sp = Prefs.store();
            Map<String, ?> all = sp.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                Object v = e.getValue();
                if (key.equals("watchOff") && v instanceof Set) {
                    settings.put(key, new JSONArray(new TreeSet<Object>((Set<?>) v)));
                } else if (BackupRules.ok(key, v, Arrays.asList(Ui.languages()), Recs.MAX_CARDS, SleepOverlay.PLACES)) {
                    settings.put(key, v);
                }
            }
            Object city = all.get("city"), lat = all.get("latitude"), lon = all.get("longitude");
            if (city instanceof String && lat instanceof String && lon instanceof String) {   // chosen together, or not at all
                settings.put("city", city).put("latitude", lat).put("longitude", lon);
            }
            JSONObject remote = new JSONObject();
            if (Prefs.hasSavedKeys()) for (int code : Prefs.KEYS) remote.put(String.valueOf(code), Prefs.keyApp(code));
            JSONObject out = new JSONObject().put("freemytv", VERSION).put("settings", settings);
            if (remote.length() > 0) out.put("remoteKeys", remote);
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                stream.write(out.toString(1).getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            if (!tmp.renameTo(f)) return -1;
            return settings.length() + remote.length();
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("export settings: " + e);
            return -1;
        }
    }

    /** The file as a settings file, or null when it isn't one (too big, not JSON, no version, no settings). */
    static JSONObject read(File f) {
        if (f == null || !f.isFile() || f.length() > MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONObject all = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            int version = all.optInt("freemytv", 0);
            return version >= 1 && version <= VERSION && all.optJSONObject("settings") != null ? all : null;
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("read settings file: " + e);
            return null;
        }
    }

    /** Reads the file and applies it. Returns how many settings it changed, or -1 if there is no usable file. */
    static int restore(Context c) {
        JSONObject all = read(file(c));
        if (all == null) return -1;
        JSONObject settings = all.optJSONObject("settings");
        int changed = 0;
        SharedPreferences.Editor ed = Prefs.store().edit();
        String language = null;
        for (String key : BackupRules.managed()) {   // what the file doesn't name goes back to its default
            if (!settings.has(key)) ed.remove(key);
        }
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Object v = settings.opt(key);
            if (settings.isNull(key) || BackupRules.city().contains(key)) {
                continue;   // a null is no value; the city is checked together, below
            } else if (key.equals("watchOff")) {
                Set<String> set = packages(v);
                if (set != null) { ed.putStringSet(key, set); changed++; } else ed.remove(key);
            } else if (BackupRules.ok(key, v, Arrays.asList(Ui.languages()), Recs.MAX_CARDS, SleepOverlay.PLACES)) {
                if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) ed.putInt(key, (Integer) v);
                else ed.putString(key, (String) v);
                if (key.equals("language")) language = (String) v;
                changed++;
            }
        }
        String city = text(settings, "city"), lat = text(settings, "latitude"), lon = text(settings, "longitude");
        if (BackupRules.city(city, lat, lon)) {
            ed.putString("city", city).putString("latitude", lat).putString("longitude", lon);
            changed += 3;
        } else if (!settings.has("city") && !settings.has("latitude") && !settings.has("longitude")) {
            for (String key : BackupRules.city()) ed.remove(key);
        }
        ed.apply();
        JSONObject remote = all.optJSONObject("remoteKeys");
        if (remote == null) {
            Prefs.clearKeys();   // none saved when the file was made: every button does its default
        } else {
            for (int code : Prefs.KEYS) {   // only the buttons this TV's remote has
                String name = String.valueOf(code);
                String app = remote.has(name) && !remote.isNull(name) ? remote.optString(name, null) : null;
                if (BackupRules.keyApp(app) && Prefs.setKeyApp(code, app)) changed++;
            }
        }
        Prefs.changed();
        if (language != null) Ui.applyLanguage();
        return changed;
    }

    private static String text(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key) ? o.optString(key, null) : null;
    }

    private static Set<String> packages(Object v) {
        if (!(v instanceof JSONArray)) return null;
        JSONArray a = (JSONArray) v;
        Set<String> set = new HashSet<>();
        for (int i = 0; i < a.length() && i < 500; i++) {
            String s = a.optString(i, "");
            if (!BackupRules.packageName(s)) return null;
            set.add(s);
        }
        return set;
    }
}
