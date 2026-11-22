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
 * The settings file for tools/settings.py where adb's shell can't open the app's folder in Android/data (some Android 11
 * builds, Fire OS 8): `adb exec-out content read --uri content://io.freemytv.backup/file` reads it, and
 * `adb exec-in content write --uri content://io.freemytv.backup/file` replaces it. Only adb's shell (or root) may, and a
 * write only counts once it reads as a settings file (Backup.read): a send cut short keeps the old one.
 */
public class BackupFile extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb");
        File f = Backup.file(getContext());
        if (!mode.contains("w")) {
            if (!f.isFile()) throw new FileNotFoundException("no settings file: Export settings on the TV first");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        File part = new File(f.getParentFile(), Backup.FILE + ".part");
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean ok = Backup.read(part) != null && part.renameTo(f);
                        if (!ok) part.delete();
                        AppLog.w("settings file: " + (ok ? "received" : "not received"));
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
