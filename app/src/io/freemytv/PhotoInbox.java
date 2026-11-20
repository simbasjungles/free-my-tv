package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Where tools/photos.py writes a photo when the TV doesn't let adb into the app's external folder (Android 11, and
 * Fire OS 8 on it): `content write --uri content://io.freemytv.photos/<name>?size=<bytes>`, fed from a copy it pushed
 * to /data/local/tmp. Only adb's shell (or root) may write; the photo lands in Photos' private inbox and is imported
 * (and deleted) the next time the home screen, the screensaver or the settings open.
 */
public class PhotoInbox extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    /** What tools/photos.py says it sends (?size=), or -1 (then only a clean close keeps it). */
    private static long size(Uri uri) {
        try {
            return Long.parseLong(uri.getQueryParameter("size"));
        } catch (NumberFormatException e) {   // also when there's none
            return -1;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends photos");
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.startsWith(".") || !mode.contains("w")) {
            throw new FileNotFoundException("photos only: content://io.freemytv.photos/<name>");
        }
        File in = Photos.privateInbox(getContext());
        if (!in.isDirectory() && !in.mkdirs()) throw new FileNotFoundException("can't create " + in);
        long expected = size(uri);
        // Written under a hidden name (an import meanwhile leaves it alone) and named only once all of it came: a send
        // cut short (Ctrl-C, Wi-Fi) also closes cleanly. Imported like a pushed photo, at the next open: an import here,
        // in the main process, could save its stale copy of the settings over :home's (Prefs.setPhotos).
        File part = new File(in, "." + name), done = new File(in, name);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        // With a size, all of it came when the length matches, whatever the close says: an older
                        // photos.py's `adb exec-in` can end the sender before its close reaches us ("Remote side is
                        // dead"), and the photo was dropped while the computer said Sent (#380).
                        long got = part.length();
                        boolean whole = expected >= 0 ? got == expected : e == null;
                        if (!whole) {
                            AppLog.w("photos: dropped " + name + ", " + got + (expected >= 0 ? " of " + expected : "")
                                    + " bytes" + (e == null ? "" : " (" + e.getMessage() + ")"));
                        }
                        if (!whole || !part.renameTo(done)) part.delete();
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
