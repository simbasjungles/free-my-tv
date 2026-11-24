package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own photos. A computer puts them in the app's external files folder (tools/photos.py: adb push to
 * Android/data/io.freemytv/files/photos, no permission needed) or, where adb can't reach it, through PhotoInbox; the
 * next time the home screen, the screensaver or the settings open, each one is imported once into the app's private
 * folder, turned upright (EXIF), kept whole at the screensaver's size (never cropped: see decode) and re-encoded (which
 * also drops its GPS and other tags), and the original is deleted. While they are on (Home Settings > My Photos), the screensaver shows them
 * instead of the picks' photos, in the order they came, and without picks (no TMDB key) one of them is the home
 * screen's background, a new one every hour.
 *
 * My Photos (PhotosActivity) keeps your choices in photos/choices.txt, beside the photos: their order, which ones stay
 * out of the screensaver, the one picked for the home screen, how long each shows and whether they're shuffled. A file
 * in the folder, written whole and renamed into place, so the screensaver's process (main, on Android 11) sees a change
 * as the home screen's does: the rename moves the folder's time, which files() checks.
 */
final class Photos {
    private Photos() {}

    /** The screensaver's photo size (DreamView loads them 10% larger than the screen, for the slow zoom). */
    static final int W = 2112, H = 1188;
    /** At most this many (about 300 KB each); later ones wait in the folder until some are removed. */
    static final int MAX = 100;
    /** Smaller ones are icons and thumbnails, not photos. */
    private static final int MIN_SIDE = 640;
    /**
     * Filling the screen may cut this much of a photo's height or width at most: 3:2 (16%) and 4:3 (25%) fill, as
     * do 21:9 (24%) and 2:1 (11%); a 1:1 (44%), a portrait (3:4: 58%) or a phone's panorama (3:1: 41%) is shown whole.
     */
    private static final float MAX_CUT = 0.30f;

    /** Whether a w × h photo fills the screen (cropped, with the slow zoom) rather than shown whole on black. */
    static boolean fills(int w, int h) {
        if (w <= 0 || h <= 0) return true;
        float photo = (float) w / h, screen = (float) W / H;
        float cut = photo < screen ? 1 - photo / screen : 1 - screen / photo;
        return cut <= MAX_CUT;
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    /** Where tools/photos.py puts them (the app's own external folder: adb can write there, and no permission). */
    private static File inbox(Context c) {
        return c.getExternalFilesDir("photos");
    }

    /**
     * Where PhotoInbox puts what adb writes when it can't reach the external folder: Android 11 keeps adb out of other
     * apps' Android/data folders (on Fire OS 8 too).
     */
    static File privateInbox(Context c) {
        return new File(c.getFilesDir(), "inbox");
    }

    /**
     * What's waiting in both inboxes, in name order: claims (another process importing) included, other hidden names
     * (a photo PhotoInbox is still writing) left out.
     */
    private static File[] sent(Context c) {
        List<File> out = new ArrayList<>();
        for (File in : new File[] {inbox(c), privateInbox(c)}) {
            File[] all = in == null ? null : in.listFiles();
            if (all != null) for (File f : all) if (!f.getName().startsWith(".") || f.getName().startsWith(CLAIM)) out.add(f);
        }
        File[] sorted = out.toArray(new File[0]);
        Arrays.sort(sorted, (a, b) -> original(a.getName()).compareTo(original(b.getName())));
        return sorted;
    }

    // MARK: - The imported photos

    private static long listedStamp = -1;
    private static List<File> listed = new ArrayList<>();

    // What choices.txt says (read with the listing).
    private static final java.util.Set<String> hidden = new java.util.HashSet<>();
    private static String homeName = "";
    private static boolean homeOff, shuffled;
    private static int slideSeconds;

    /** How long each photo can show in the screensaver, in seconds; the first is the default (the picks' 30 s). */
    static final int[] SLIDES = {30, 60, 120, 300, 600, 1800};

    private static final String CHOICES = "choices.txt";

    /** The imported photos, in your order, then the ones that came since (one stat of the folder while nothing changed). */
    static synchronized List<File> files(Context c) {
        File d = dir(c);
        long stamp = d.exists() ? d.lastModified() : 0;
        if (stamp != listedStamp) {
            List<File> out = new ArrayList<>();
            File[] all = d.listFiles();
            if (all != null) for (File f : all) if (f.getName().endsWith(".jpg")) out.add(f);
            Collections.sort(out);
            readChoices(d, out);
            listed = out;
            listedStamp = stamp;
        }
        return listed;
    }

    /** Reads choices.txt into the fields, and puts `files` (sorted by name) in its order. */
    private static void readChoices(File d, List<File> files) {
        hidden.clear();
        homeName = "";
        homeOff = shuffled = false;
        slideSeconds = SLIDES[0];
        List<String> order = new ArrayList<>();
        File f = new File(d, CHOICES);
        if (f.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (line.startsWith("slide=")) {
                        try {
                            slideSeconds = Math.max(10, Integer.parseInt(line.substring(6)));
                        } catch (NumberFormatException ignored) {}
                    } else if (line.equals("shuffle")) {
                        shuffled = true;
                    } else if (line.equals("home=off")) {
                        homeOff = true;
                    } else if (line.startsWith("home=")) {
                        homeName = line.substring(5);
                    } else if (line.startsWith("off ")) {
                        String name = line.substring(4);
                        hidden.add(name);
                        order.add(name);
                    } else if (line.endsWith(".jpg")) {
                        order.add(line);
                    }
                }
            } catch (IOException e) {
                AppLog.w("photos: can't read " + CHOICES + ": " + e.getMessage());
            }
        }
        if (order.isEmpty()) return;
        final java.util.Map<String, Integer> place = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) place.put(order.get(i), i);
        // Listed ones in their place; new ones after them, in the order they came (sort is stable).
        Collections.sort(files, (a, b) -> {
            Integer pa = place.get(a.getName()), pb = place.get(b.getName());
            return Integer.compare(pa == null ? Integer.MAX_VALUE : pa, pb == null ? Integer.MAX_VALUE : pb);
        });
    }

    /** Writes the choices for `order` (the photos as My Photos shows them); the folder's new time tells the others. */
    private static synchronized void writeChoices(Context c, List<File> order, java.util.Set<String> off, String home,
                                                  boolean noHome, int slide, boolean shuffle) {
        File d = dir(c);
        StringBuilder b = new StringBuilder();
        b.append("slide=").append(slide).append('\n');
        if (shuffle) b.append("shuffle\n");
        if (noHome) b.append("home=off\n");
        else if (!home.isEmpty()) b.append("home=").append(home).append('\n');
        for (File f : order) b.append(off.contains(f.getName()) ? "off " : "").append(f.getName()).append('\n');
        File part = new File(d, CHOICES + ".part");
        try (OutputStream os = new FileOutputStream(part)) {
            os.write(b.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            AppLog.w("photos: can't write " + CHOICES + ": " + e.getMessage());
            part.delete();
            return;
        }
        if (!part.renameTo(new File(d, CHOICES))) part.delete();
        listedStamp = -1;   // read again now: the folder's time may not have moved within the same second
        // The home screen's photo may be another one now: built at once (backdrop() compares it with the one built), so
        // it's ready, or nearly, when the home screen comes back.
        if (homeOn(c)) backdrop(c, null);
    }

    /** In the screensaver (not left out in My Photos). */
    static synchronized boolean inSaver(Context c, File f) {
        files(c);
        return !hidden.contains(f.getName());
    }

    /** The photo picked for the home screen, or null: then one of them, a new one every hour (or none: homeOff). */
    static synchronized File homePhoto(Context c) {
        for (File f : files(c)) if (f.getName().equals(homeName)) return f;
        return null;
    }

    /** My Photos > Home background: Off (the plain background behind the home screen). */
    static synchronized boolean homeOff(Context c) {
        files(c);
        return homeOff;
    }

    static synchronized int slideSeconds(Context c) {
        files(c);
        return slideSeconds;
    }

    static synchronized boolean shuffled(Context c) {
        files(c);
        return shuffled;
    }

    private static void save(Context c, List<File> order) {
        writeChoices(c, order, hidden, homeName, homeOff, slideSeconds, shuffled);
    }

    static synchronized void setInSaver(Context c, File f, boolean on) {
        List<File> order = new ArrayList<>(files(c));
        java.util.Set<String> off = new java.util.HashSet<>(hidden);
        if (on) off.remove(f.getName()); else off.add(f.getName());
        writeChoices(c, order, off, homeName, homeOff, slideSeconds, shuffled);
    }

    /** `f` behind the home screen, or null: back to a new one every hour. */
    static synchronized void setHomePhoto(Context c, File f) {
        files(c);
        writeChoices(c, new ArrayList<>(listed), hidden, f == null ? "" : f.getName(), false, slideSeconds, shuffled);
    }

    static synchronized void setHomeOff(Context c, boolean off) {
        files(c);
        writeChoices(c, new ArrayList<>(listed), hidden, off ? "" : homeName, off, slideSeconds, shuffled);
    }

    static synchronized void setSlideSeconds(Context c, int seconds) {
        files(c);
        writeChoices(c, new ArrayList<>(listed), hidden, homeName, homeOff, seconds, shuffled);
    }

    static synchronized void setShuffled(Context c, boolean on) {
        files(c);
        writeChoices(c, new ArrayList<>(listed), hidden, homeName, homeOff, slideSeconds, on);
    }

    /** Saves the order My Photos shows (after a move). */
    static synchronized void setOrder(Context c, List<File> order) {
        files(c);
        save(c, order);
    }

    /** Deletes one photo (and its place in the choices). */
    static synchronized void remove(Context c, File f) {
        List<File> order = new ArrayList<>(files(c));
        order.remove(f);
        f.delete();
        java.util.Set<String> off = new java.util.HashSet<>(hidden);
        off.remove(f.getName());
        writeChoices(c, order, off, homeName.equals(f.getName()) ? "" : homeName, homeOff, slideSeconds, shuffled);
    }

    /** On in Home Settings and something imported. */
    static boolean on(Context c) {
        return Prefs.photos() && !files(c).isEmpty();
    }

    /** Behind the home screen: on, and My Photos' Home background isn't Off. */
    static boolean homeOn(Context c) {
        return on(c) && !homeOff(c);
    }

    /** For the screensaver: the photos in it as picks (no title, no caption), in your order. */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!on(c)) return out;
        for (File f : files(c)) {
            if (!inSaver(c, f)) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = "photo";
            p.id = f.getName().hashCode();   // by name: a photo keeps its key when others move or leave
            p.photo = f;
            out.add(p);
        }
        return out;
    }

    // MARK: - The home screen's background

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File built, building;
    /** Who asked while it was being built: all are told once it's ready (the home screen redraws then). */
    private static final java.util.Set<Runnable> readyWaiters = new java.util.LinkedHashSet<>();
    private static Backdrop backdrop;

    /**
     * This hour's photo, ready to draw as the home screen's background, or null (off, or the first one still being
     * built). When the hour changes, the previous one stays until the new one is ready; then `ready` runs.
     */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        if (!homeOn(c)) {
            backdrop = null;
            built = null;
            return null;
        }
        List<File> list = files(c);
        File chosen = homePhoto(c);
        File want = chosen != null ? chosen : list.get((int) ((System.currentTimeMillis() / 3_600_000L) % list.size()));
        if (ready != null && !want.equals(built)) readyWaiters.add(ready);   // told when it's done, even if built for another
        if (!want.equals(built) && !want.equals(building)) {
            building = want;
            worker.execute(() -> {
                Backdrop b = null;
                long t0 = android.os.SystemClock.uptimeMillis();
                try {
                    Bitmap src = BitmapFactory.decodeFile(want.getPath());
                    if (src != null) b = Backdrop.make(src, true, true);
                    AppLog.w("photos: background " + want.getName() + " ready in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("photos: background from " + want.getName() + ": " + e.getMessage());
                }
                List<Runnable> tell;
                synchronized (Photos.class) {
                    building = null;
                    tell = new ArrayList<>(readyWaiters);
                    readyWaiters.clear();
                    if (b == null) return;
                    backdrop = b;
                    built = want;
                }
                main.post(() -> { for (Runnable r : tell) r.run(); });
            });
        }
        return backdrop;
    }

    // MARK: - Importing what the computer sent

    private static boolean importing;
    private static final List<Runnable> waiting = new ArrayList<>();

    /**
     * Imports what tools/photos.py put in the inbox, in the background, one photo at a time; `done` runs (on the main
     * thread) when something was imported, also when an import already under way (asked by another screen) brings it.
     * Cheap when the inbox is empty: one directory listing.
     */
    static void importInbox(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Photos.class) {
            if (done != null) waiting.add(done);
            if (importing) return;
            importing = true;
        }
        worker.execute(() -> {
            int n = 0;
            try {
                n = importNow(app);
            } catch (RuntimeException e) {
                AppLog.w("photos: import failed: " + e.getMessage());
            }
            List<Runnable> tell;
            synchronized (Photos.class) {
                importing = false;
                tell = new ArrayList<>(waiting);
                waiting.clear();
            }
            if (n > 0) main.post(() -> { for (Runnable r : tell) r.run(); });
        });
    }

    /** A photo one process is importing is named ".claim-<when>-<its name>". */
    private static final String CLAIM = ".claim-";
    /** A claim this old was left by a process that died while importing: another one may take the photo. */
    private static final long CLAIM_MS = 10 * 60_000L;

    private static String original(String name) {
        if (!name.startsWith(CLAIM)) return name;
        int dash = name.indexOf('-', CLAIM.length());
        return dash < 0 ? name : name.substring(dash + 1);
    }

    private static boolean abandoned(String claimed) {
        int dash = claimed.indexOf('-', CLAIM.length());
        if (dash < 0) return false;
        try {
            long when = Long.parseLong(claimed.substring(CLAIM.length(), dash));
            return Math.abs(System.currentTimeMillis() - when) > CLAIM_MS;   // also one dated ahead: the clock went back
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static int importNow(Context c) {
        File[] all = sent(c);
        if (all.length == 0) return 0;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            AppLog.w("photos: can't create " + d);
            return 0;
        }
        // Counted on disk, not from files(): its cache is keyed on the folder's time, which another process's import
        // (or two within the same second) may not move.
        File[] imported = d.listFiles((dir, name) -> name.endsWith(".jpg"));
        int have = imported == null ? 0 : imported.length, count = 0;
        for (File sent : all) {
            if (!sent.isFile()) continue;
            String name = sent.getName();
            if (name.startsWith(CLAIM) && !abandoned(name)) continue;   // another process is importing it
            String original = original(name);
            if (!image(original.toLowerCase(Locale.ROOT))) {
                sent.delete();
                continue;
            }
            if (have + count >= MAX) {
                AppLog.w("photos: " + MAX + " already, " + original + " waits");
                continue;
            }
            // Claimed with a rename before it's read: on Android 11 the screensaver (main process) and the home screen
            // (:home) both import, and without it they could take the same photo twice.
            File src = new File(sent.getParentFile(), CLAIM + System.currentTimeMillis() + "-" + original);
            if (!sent.renameTo(src)) continue;
            // Named by import time (the order they came is the order the screensaver shows them) and by process (the
            // other one may import in the same millisecond); written as .part first, so a crash leaves no half photo.
            File out = new File(d, String.format(Locale.US, "%013d-%d%s", System.currentTimeMillis() + count,
                    android.os.Process.myPid(), original.startsWith(IMMICH) ? IMMICH_END : ".jpg"));
            File part = new File(d, out.getName() + ".part");
            try {
                Bitmap b = decode(src, W, H);
                if (b == null) {
                    AppLog.w("photos: skipped " + original + " (too small or unreadable)");
                } else {
                    try (OutputStream os = new FileOutputStream(part)) {
                        b.compress(Bitmap.CompressFormat.JPEG, 86, os);
                    }
                    b.recycle();
                    if (!part.renameTo(out)) throw new IOException("can't name " + out.getName());
                    count++;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                part.delete();
                AppLog.w("photos: skipped " + original + ": " + e.getMessage());
            }
            src.delete();
        }
        AppLog.w("photos: imported " + count + ", " + (have + count) + " in all");
        if (count > 0) Prefs.setPhotos(true);   // new photos show, even if they had been turned off
        return count;
    }

    // MARK: - Immich's (ImmichPhotos)

    /** What ImmichPhotos leaves in the private inbox starts with this; imported, its name ends with IMMICH_END. */
    static final String IMMICH = "immich-";
    private static final String IMMICH_END = "-immich.jpg";

    /** Imports what's waiting now, on the caller's thread (ImmichPhotos, between two downloads). */
    static int importWaiting(Context c) {
        return importNow(c.getApplicationContext());
    }

    /** Imported photos on disk (not files(): its cache may not have seen an import in this same second). */
    static int count(Context c) {
        File[] all = dir(c).listFiles((d, name) -> name.endsWith(".jpg"));
        return all == null ? 0 : all.length;
    }

    /** The photos ImmichPhotos brought, oldest first; tools/photos.py's are never among them. */
    static List<File> fromImmich(Context c) {
        File[] all = dir(c).listFiles((d, name) -> name.endsWith(IMMICH_END));
        List<File> out = new ArrayList<>(all == null ? Collections.<File>emptyList() : Arrays.asList(all));
        Collections.sort(out);
        return out;
    }

    /** When it was imported (its name starts with that time), or 0. */
    static long importedAt(File f) {
        try {
            return Long.parseLong(f.getName().substring(0, 13));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Takes Immich's away (and what of it still waits), keeping tools/photos.py's. */
    static void removeImmich(Context c) {
        for (File f : fromImmich(c)) f.delete();
        File[] waiting = privateInbox(c).listFiles((d, name) -> name.contains(IMMICH));
        if (waiting != null) for (File f : waiting) f.delete();
    }

    /** Deletes the imported photos and whatever is still waiting, half-sent ones included. */
    static void removeAll(Context c) {
        clear(dir(c));
        clear(privateInbox(c));
        File in = inbox(c);
        File[] queued = in == null ? null : in.listFiles();
        if (queued != null) for (File f : queued) f.delete();
        synchronized (Photos.class) {
            backdrop = null;
            built = null;
        }
    }

    private static boolean image(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || (Build.VERSION.SDK_INT >= 28 && (name.endsWith(".heic") || name.endsWith(".heif")));
    }

    /**
     * The whole photo upright (EXIF), never cropped, so the look can change later without a new import: one that fills()
     * is sized to cover w × h (the screensaver crops it to the screen, with the slow zoom), a portrait or a panorama is
     * fitted inside w × h (shown whole, on black). A smaller photo is enlarged to that size too (a preview, a phone's
     * screenshot). Null if too small or unreadable. Decoded from a FileDescriptor (the path form copies all of the file
     * into memory first) and straight at its final size: halved while it stays larger, then scaled by the decoder itself
     * (inDensity / inTargetDensity): a 24 MP photo is decoded at 3000 × 2000 (24 MB) and scaled into the result in the
     * same call. Only that is turned.
     */
    private static Bitmap decode(File f, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (Math.min(bounds.outWidth, bounds.outHeight) < MIN_SIDE) return null;
        int degrees = rotation(f);
        boolean sideways = degrees == 90 || degrees == 270;
        // The size it ends at, upright, then the same in the file's stored orientation
        int uw = sideways ? bounds.outHeight : bounds.outWidth, uh = sideways ? bounds.outWidth : bounds.outHeight;
        float sx = (float) w / uw, sy = (float) h / uh;
        float scale = fills(uw, uh) ? Math.max(sx, sy) : Math.min(sx, sy);
        int tw = Math.max(1, Math.round(uw * scale)), th = Math.max(1, Math.round(uh * scale));
        int sw = sideways ? th : tw, sh = sideways ? tw : th;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= sw && bounds.outHeight / (opts.inSampleSize * 2) >= sh) {
            opts.inSampleSize *= 2;
        }
        opts.inScaled = true;
        opts.inDensity = Math.max(1, bounds.outWidth / opts.inSampleSize);
        opts.inTargetDensity = sw;
        Bitmap src;
        try (FileInputStream in = new FileInputStream(f)) {
            src = BitmapFactory.decodeFileDescriptor(in.getFD(), null, opts);
        }
        if (src == null) return null;
        src.setDensity(Bitmap.DENSITY_NONE);
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.setRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (turned != src) src.recycle();
        return turned;
    }

    private static int rotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void clear(File d) {
        File[] all = d.listFiles();
        if (all != null) for (File f : all) f.delete();
        d.delete();
    }
}
