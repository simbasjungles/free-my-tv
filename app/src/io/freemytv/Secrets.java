package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Keys that must not be in the APK (any app on the TV can read an installed APK): tools/send_key.py writes one from
 * the computer with `adb exec-in content write --uri content://io.freemytv.secrets/<name>`, and `content delete` takes
 * it away; `content query` answers whether one is there and since when, never the value, so the tool can tell it
 * arrived (`exec-in` brings back nothing the TV says). Only adb's shell (or root) may, and only the names in NAMES; each
 * lands in the app's private files. A new feature with a secret adds its name here and reads it with get().
 */
public class Secrets extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;
    static final String IMMICH_KEY = "immich_api_key", HOME_ASSISTANT_TOKEN = "home_assistant_token";
    private static final List<String> NAMES = Arrays.asList(IMMICH_KEY, HOME_ASSISTANT_TOKEN);

    private static File file(Context c, String name) {
        return new File(new File(c.getFilesDir(), "secrets"), name);
    }

    /** The key sent for `name`, or "" (none yet). A small file read: callers keep it rather than ask every frame. */
    static String get(Context c, String name) {
        try {
            return new String(Files.readAllBytes(file(c, name).toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    @Override public boolean onCreate() {
        return true;
    }

    private static String name(Uri uri) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends keys");
        String name = uri.getLastPathSegment();
        if (!NAMES.contains(name)) throw new FileNotFoundException("one of " + NAMES + ": content://io.freemytv.secrets/<name>");
        return name;
    }

    /** One line of printable ASCII, as API keys and tokens are; anything else is a send cut short or a wrong file. */
    private static boolean whole(File f) {
        if (f.length() == 0 || f.length() > 4096) return false;
        try {
            String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return v.matches("[\\x21-\\x7E]{8,4000}");
        } catch (IOException e) {
            return false;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if (!mode.contains("w")) throw new FileNotFoundException("write only");
        File done = file(getContext(), name), dir = done.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("can't create " + dir);
        // Written under a hidden name and renamed once whole, so a send cut short keeps the old key. Whole means it
        // reads as a key: through `adb exec-in` the close comes as "Remote side is dead" even when everything arrived.
        File part = new File(dir, "." + name);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean ok = whole(part) && part.renameTo(done);
                        if (!ok) part.delete();
                        AppLog.w("secrets: " + name + (ok ? " received" : " not received"));
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try {
            return file(getContext(), name(uri)).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    /** One row: present (1 or 0) and when it was received (ms, 0 if absent). Never the value. */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String name;
        try {
            name = name(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        File f = file(getContext(), name);
        MatrixCursor row = new MatrixCursor(new String[] {"present", "received"});
        row.addRow(new Object[] {f.isFile() ? 1 : 0, f.isFile() ? f.lastModified() : 0});
        return row;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
