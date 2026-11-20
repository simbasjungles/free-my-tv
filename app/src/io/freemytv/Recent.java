package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The apps last opened from Free My TV (the shelf, All Apps, the picks, Continue Watching, the screensaver), newest
 * first, for the shelf's "Last opened first" order (Prefs.shelfRecent). Noted only while that's on, and deleted when it
 * goes off. Apps opened some other way (the TV's own buttons, another app) aren't seen: that would need an accessibility
 * service, which only some TVs run. No times: a TV whose clock starts wrong after a power cut can't misorder it. A small
 * file (a package a line), replaced whole on each save so a crash never leaves half of it, and read again when another
 * process changed it (on Android 11 to 12L the screensaver runs in the main process).
 */
final class Recent {
    private Recent() {}

    private static final String FILE = "recent-apps";
    private static final int MAX = 40;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    // As last read or written, under the lock; `known` is the same for the UI thread, which never waits on the file.
    private static List<String> keys;
    private static long stamp, size;   // the file's lastModified() and length() then
    private static volatile List<String> known;
    /** Goes up whenever the list changes: the home screen orders its shelf again only then. */
    static volatile int version;

    /** The app was just opened (saved in the background, only while the shelf follows it). */
    static void note(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || !Prefs.shelfRecent()) return;
        final Context app = c.getApplicationContext();
        WRITER.execute(() -> {
            synchronized (Recent.class) {
                fresh(app);
                if (!keys.isEmpty() && keys.get(0).equals(pkg)) return;
                keys.remove(pkg);
                keys.add(0, pkg);
                while (keys.size() > MAX) keys.remove(keys.size() - 1);
                save(app);
                changed();
            }
        });
    }

    /** "My order" again: nothing is kept. */
    static void clear(Context c) {
        final Context app = c.getApplicationContext();
        WRITER.execute(() -> {
            synchronized (Recent.class) {
                File f = new File(app.getFilesDir(), FILE);
                f.delete();
                keys = new ArrayList<>();
                stamp = f.lastModified();
                size = f.length();
                changed();
            }
        });
    }

    /** Off the UI thread: the items opened before first, the newest first, the others after them in their own order. */
    static List<HomeApps.Item> order(Context c, List<HomeApps.Item> items) {
        List<String> k;
        synchronized (Recent.class) {
            fresh(c);
            k = known;
        }
        return sorted(k, items);
    }

    /** The same on the UI thread, with the list as last read (no file access; as they are until it's been read). */
    static List<HomeApps.Item> orderKnown(List<HomeApps.Item> items) {
        List<String> k = known;
        return k == null ? items : sorted(k, items);
    }

    private static List<HomeApps.Item> sorted(List<String> k, List<HomeApps.Item> items) {
        List<HomeApps.Item> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> Integer.compare(rank(k, a), rank(k, b)));   // stable: the rest keep their order
        return out;
    }

    private static int rank(List<String> k, HomeApps.Item it) {
        int i = it.pkg == null ? -1 : k.indexOf(it.pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Lock held. Reads the file if it changed since (a missing or damaged one is an empty list). */
    private static void fresh(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        long m = f.lastModified(), n = f.length();
        if (keys != null && m == stamp && n == size) return;
        List<String> read = new ArrayList<>();
        if (m != 0) {
            try {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                for (String line : s.split("\n")) if (!line.isEmpty() && !read.contains(line)) read.add(line);
            } catch (IOException | RuntimeException e) {
                // unreadable: start again
            }
        }
        stamp = m;
        size = n;
        boolean first = keys == null;
        if (!first && read.equals(keys)) return;
        keys = read;
        if (first) known = Collections.unmodifiableList(new ArrayList<>(read)); else changed();
    }

    /** Lock held. */
    private static void save(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('\n');
        File dir = c.getFilesDir(), f = new File(dir, FILE), tmp = new File(dir, FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
        stamp = f.lastModified();
        size = f.length();
    }

    /** Lock held. */
    private static void changed() {
        known = Collections.unmodifiableList(new ArrayList<>(keys));
        version++;
    }
}
