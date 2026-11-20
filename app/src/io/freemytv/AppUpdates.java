package io.freemytv;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Keeps Free My TV and the apps installed from GitHub up to date (Home Settings > Updates). One setting, one schedule
 * and one check for all of them; by default only Free My TV is picked, as it updated itself before. Free My TV's own
 * release goes through Updates (its repository, the project's key), the other apps through KNOWN, each from its own
 * repository and release file. A new release is downloaded, checked (GitHub's SHA-256,
 * the same package, a higher version, signed by the key of the copy already on the TV) and only then installed through
 * the TV's own ADB, which keeps the app's data and asks nothing on screen. It installs only while Free My TV's own
 * screens are in front, so never under a film; otherwise it waits and tries again. Needs Free My TV mode (ADB allowed).
 * The settings are a file (the :home settings write it, UpdateJob in the main process reads it), like the state's.
 */
final class AppUpdates {
    private AppUpdates() {}

    static final String OFF = "off", ALL = "all", CUSTOM = "custom";

    /** An app this knows how to update: its releases, and which file of a release holds its APK. */
    static final class Source {
        final String pkg, name, repo;
        /** Release file names, tried in order; "{abi}" is the ABI the installed copy runs as (arm64-v8a, armeabi-v7a…). */
        final String[] files;
        /** Also looks at pre-releases (the app's beta is published that way). */
        final boolean prerelease;

        Source(String pkg, String name, String repo, boolean prerelease, String... files) {
            this.pkg = pkg;
            this.name = name;
            this.repo = repo;
            this.prerelease = prerelease;
            this.files = files;
        }
    }

    /**
     * The apps it updates. A file pattern only picks the download: whatever it finds must still be the same package,
     * newer and signed with the same key as the copy on the TV, or nothing is installed.
     */
    static final Source[] KNOWN = {
        // Its releases since 32.56 are org.smarttube.*: the older com.teamsmart.* copies can't take them.
        new Source("org.smarttube.stable", "SmartTube", "yuliskov/SmartTube", false,
                "SmartTube_stable_[0-9.]+_{abi}\\.apk", "SmartTube_stable_[0-9.]+_universal\\.apk"),
        new Source("org.smarttube.beta", "SmartTube Beta", "yuliskov/SmartTube", true,
                "SmartTube_beta_[0-9.]+_{abi}\\.apk", "SmartTube_beta_[0-9.]+_universal\\.apk"),
        // Its Android builds come as an archive (plezy.apk inside), one per ABI.
        new Source("com.edde746.plezy", "Plezy", "edde746/plezy", false, "plezy-android-{abi}\\.tar\\.gz"),
        new Source("com.fgl27.twitch", "SmartTwitchTV", "fgl27/SmartTwitchTV", false, "(?i)(?!.*debug).*\\.apk"),
        new Source("org.jellyfin.androidtv", "Jellyfin", "jellyfin/jellyfin-androidtv", false,
                "(?i)(?!.*debug).*{abi}.*\\.apk", "(?i)(?!.*debug)(?!.*(arm|x86)).*\\.apk"),
        new Source("nl.giejay.android.tv.immich", "Immich", "giejay/Immich-Android-TV", false, "ImmichTV-[0-9.]+\\.apk"),
        // Home Assistant's phone app, in its two flavors (minimal: without Google Play services).
        new Source("io.homeassistant.companion.android", "Home Assistant", "home-assistant/android", false,
                "app-full-release\\.apk"),
        new Source("io.homeassistant.companion.android.minimal", "Home Assistant (minimal)", "home-assistant/android",
                false, "app-minimal-release\\.apk"),
        // Not Free My TV itself: Updates does that (Home Settings > Updates).
    };

    private static final Object LOCK = new Object();
    private static final long MAX_DOWNLOAD = 400L * 1024 * 1024;
    /** Release files tried per check before giving up (the named one, then guesses: see pickAssets). */
    private static final int MAX_TRIES = 3;
    private static final int JOB_PERIODIC = 2100, JOB_SOON = 2101;
    private static final long PERIOD = 6 * 3600_000L, RETRY = 20 * 60_000L;

    // MARK: - Settings (app-updates.json)

    /** Custom until the person chooses, with Free My TV picked (picked): it updated itself before this setting. */
    static String mode(Context c) {
        String m = read(c, "app-updates.json").optString("mode", CUSTOM);
        return ALL.equals(m) || CUSTOM.equals(m) ? m : OFF;
    }

    /** In Custom: the apps picked (none until the person picks some). */
    static Set<String> picked(Context c) {
        JSONObject s = read(c, "app-updates.json");
        if (!s.has("apps")) return new HashSet<>(java.util.Collections.singleton(HomeMode.PKG));
        return new HashSet<>(HomeMode.stringList(s, "apps"));
    }

    static void setMode(Context c, String mode) {
        JSONObject s = read(c, "app-updates.json");
        put(s, "mode", mode);
        write(c, "app-updates.json", s);
        schedule(c);
    }

    static void setPicked(Context c, String pkg, boolean on) {
        Set<String> apps = picked(c);
        if (on) apps.add(pkg); else apps.remove(pkg);
        JSONObject s = read(c, "app-updates.json");
        put(s, "apps", new JSONArray(apps));
        write(c, "app-updates.json", s);
    }

    static boolean enabled(Context c, String pkg) {
        String m = mode(c);
        return ALL.equals(m) || (CUSTOM.equals(m) && picked(c).contains(pkg));
    }

    /**
     * Stores that update what they installed, with their own key (Play, Amazon, F-Droid): an app from them is theirs to
     * update, and GitHub's release wouldn't install over it anyway.
     */
    private static final Set<String> STORES = new HashSet<>(java.util.Arrays.asList("com.android.vending",
            "com.amazon.venezia", "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store"));

    /** The known apps installed on this TV, in KNOWN's order, but not those a store installed (it updates them). */
    static List<Source> installed(Context c) {
        List<Source> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (Source s : KNOWN) {
            try {
                pm.getPackageInfo(s.pkg, 0);
                if (!STORES.contains(installer(pm, s.pkg))) out.add(s);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return out;
    }

    /** Who installed a package ("" for adb, a file manager or unknown). */
    @SuppressWarnings("deprecation")
    private static String installer(PackageManager pm, String pkg) {
        try {
            String name = Build.VERSION.SDK_INT >= 30 ? Android11.installer(pm, pkg) : pm.getInstallerPackageName(pkg);
            return name == null ? "" : name;
        } catch (IllegalArgumentException | PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Android 11's install source (getInstallerPackageName is deprecated there); only called there. */
    private static final class Android11 {
        static String installer(PackageManager pm, String pkg) throws PackageManager.NameNotFoundException {
            android.content.pm.InstallSourceInfo info = pm.getInstallSourceInfo(pkg);
            return info.getInstallingPackageName();
        }
    }

    // MARK: - Status (app-updates-status.json, written by the job, read by the settings)

    static JSONObject status(Context c) {
        return read(c, "app-updates-status.json");
    }

    /** What the last check said about an app, for its row ("" before the first check). */
    static String result(Context c, String pkg) {
        JSONObject apps = status(c).optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(pkg);
        return app == null ? "" : app.optString("result");
    }

    // MARK: - Jobs

    /** Every 6 hours while it's on (kept across restarts); nothing while it's off. */
    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.cancel(Updates.JOB);   // Free My TV's own weekly check, from before: this job checks it now
        if (OFF.equals(mode(c))) {
            scheduler.cancel(JOB_PERIODIC);
            scheduler.cancel(JOB_SOON);
            return;
        }
        if (scheduler.getPendingJob(JOB_PERIODIC) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, UpdateJob.class))
                .setPeriodic(PERIOD)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    /** A check after `delayMs` ("Check Now", or an install waiting for the home screen). */
    static void checkSoon(Context c, long delayMs) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo.Builder job = new JobInfo.Builder(JOB_SOON, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        scheduler.schedule(job.build());
    }

    // MARK: - The check (UpdateJob, main process)

    static void run(Context c) {
        synchronized (LOCK) {
            JSONObject status = status(c);
            put(status, "checkedAt", System.currentTimeMillis());
            put(status, "error", "");
            if (OFF.equals(mode(c))) {
                write(c, "app-updates-status.json", status);
                return;
            }
            boolean waiting = false, failed = false;
            // The other apps install through the TV's ADB, which only Free My TV mode allows.
            List<Source> apps0 = new ArrayList<>();
            for (Source s : installed(c)) if (enabled(c, s.pkg)) apps0.add(s);
            if (!apps0.isEmpty() && !Capabilities.adb(c)) {
                put(status, "error", Ui.tr("Needs Free My TV mode: turn it on in Home Screen."));
                apps0.clear();
            }
            JSONObject apps = status.optJSONObject("apps");
            if (apps == null) apps = new JSONObject();
            for (Source s : apps0) {
                JSONObject app = apps.optJSONObject(s.pkg);
                if (app == null) app = new JSONObject();
                try {
                    waiting |= update(c, s, app);
                } catch (Exception e) {
                    boolean offline = offline(e);
                    failed |= offline || e instanceof Temporary;
                    put(app, "result", offline ? Ui.tr("No connection")
                            : Ui.trf("Error: {error}.", "error", String.valueOf(e.getMessage())));
                    AppLog.w("app update " + s.pkg + ": " + e);
                }
                put(apps, s.pkg, app);
                put(status, "apps", apps);
                write(c, "app-updates-status.json", status);
            }
            put(status, "apps", apps);
            write(c, "app-updates-status.json", status);
            // Free My TV last: installing it ends this process.
            if (enabled(c, HomeMode.PKG)) {
                try {
                    int self = updateSelf(c);
                    waiting |= self == WAIT;
                    failed |= self == AGAIN;
                } catch (Exception e) {
                    AppLog.w("app update " + HomeMode.PKG + ": " + e);
                }
            }
            // No network, or GitHub busy or over its hourly limit (an address many homes share): again in 30 minutes,
            // then 1 hour, 2…, up to the usual 6, as Free My TV's own check did.
            int retries = status.optInt("retries");
            long later = failed ? Math.min(PERIOD, (30 * 60_000L) << Math.min(retries, 4)) : 0;
            put(status, "retries", failed ? retries + 1 : 0);
            if (waiting) later = later == 0 ? RETRY : Math.min(later, RETRY);
            write(c, "app-updates-status.json", status);
            if (later > 0) checkSoon(c, later);
        }
    }

    private static final int DONE = 0, WAIT = 1, AGAIN = 2;
    /** Left by Free My TV just before it installs its own update through ADB: the new copy reopens the home screen. */
    private static final String SELF_UPDATE = "self-update.json";

    /**
     * Free My TV itself: Updates checks its latest release and downloads it (only on the APK from the releases, only
     * signed with the project's key). In Free My TV mode a ready one installs as the other apps do, through ADB and
     * only on the home screen or settings; in Original there's no ADB, so it waits for OK on its row and Android's
     * installer, which asks. DONE, WAIT (for the home screen) or AGAIN (the check failed on the network or GitHub).
     */
    private static int updateSelf(Context c) throws IOException {
        boolean again = Updates.check(c);
        JSONObject state = Updates.state(c);
        if (!Updates.ready(state, Updates.installedVersion(c))) return again ? AGAIN : DONE;
        if (!Capabilities.adb(c)) return DONE;
        File apk = Updates.readyFile(c);
        if (!apk.isFile()) return DONE;
        try (Adb adb = Adb.connectAllowed(c)) {
            if (!inFront(adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))) return WAIT;
            // The package manager ends this process once it takes the new copy, often before "Success" comes back.
            write(c, SELF_UPDATE, new JSONObject());
            AppLog.w("app update: installing Free My TV " + state.optString("version"));
            String out = adb.install(apk).trim();
            if (!out.contains("Success")) throw new IOException(out.isEmpty() ? Ui.tr("the TV didn't install it") : out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            new File(c.getFilesDir(), SELF_UPDATE).delete();
            throw e;
        }
        return DONE;
    }

    /**
     * After Free My TV updated itself through ADB (MY_PACKAGE_REPLACED): the install ended the home screen with the old
     * process, and nothing starts it again, so the TV stays on a black screen. Home through ADB brings it back.
     */
    static void reopenHome(Context c) {
        File mark = new File(c.getFilesDir(), SELF_UPDATE);
        if (!mark.exists()) return;
        boolean recent = System.currentTimeMillis() - mark.lastModified() < 10 * 60_000L;
        //noinspection ResultOfMethodCallIgnored
        mark.delete();
        if (!recent) return;
        try (Adb adb = Adb.connectAllowed(c)) {
            adb.shell("input keyevent KEYCODE_HOME");
        } catch (IOException e) {
            AppLog.w("home after the update: " + e.getMessage());
        }
    }

    /** A failure worth trying again soon: GitHub busy or over its limit, a download cut short. */
    static final class Temporary extends IOException {
        Temporary(String message) {
            super(message);
        }
    }

    private static boolean offline(Exception e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException || e instanceof javax.net.ssl.SSLException;
    }

    /** One app: checks its latest release and installs it if it's newer. True when an install waits for the home screen. */
    private static boolean update(Context c, Source s, JSONObject app) throws IOException, JSONException {
        PackageManager pm = c.getPackageManager();
        PackageInfo installed = packageInfo(pm, s.pkg);
        // Not the cache, which Android empties first when storage runs low (as Updates keeps its download).
        File dir = new File(c.getNoBackupFilesDir(), "app-updates");
        File apk = new File(dir, s.pkg + ".apk");

        // Downloaded and checked earlier, waiting for the home screen: install it unless the TV got a newer copy since.
        if (apk.exists() && app.optLong("versionCode") > versionCode(installed)) return install(c, s, apk, app);
        //noinspection ResultOfMethodCallIgnored
        apk.delete();

        List<JSONObject> assets = latestAssets(s, abiOf(installed.applicationInfo));
        if (assets.isEmpty()) {
            put(app, "result", Ui.tr("No APK for this TV in the latest release."));
            return false;
        }
        String seen = assets.get(0).optLong("id") + ":" + assets.get(0).optString("updated_at");
        if (seen.equals(app.optString("seen")) && app.optLong("versionCode") <= versionCode(installed)) {
            // The same file was refused before (another key, another app): say so again, not "Up to date".
            if (!app.optString("refused").isEmpty()) throw new IOException(app.optString("refused"));
            put(app, "result", Ui.tr("Up to date."));
            return false;
        }
        app.remove("refused");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder for the downloads");

        // The file the patterns name, else the release's other likely files (a project that renamed its files): the
        // first that is this app goes on to the checks below. At most MAX_TRIES downloads.
        PackageInfo archive = null;
        String problem = null;
        for (int i = 0; i < assets.size() && i < MAX_TRIES; i++) {
            JSONObject asset = assets.get(i);
            File download = new File(dir, s.pkg + ".download");
            boolean unpacked = true;
            try {
                download(asset, download);
                if (isArchive(asset.optString("name"))) {
                    try {
                        extractApk(download, apk);
                    } catch (IOException e) {
                        unpacked = false;   // no APK in that archive: not the file, try the next
                    }
                } else if (!download.renameTo(apk)) {
                    throw new IOException("could not keep the download");
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                download.delete();
            }
            archive = unpacked ? archiveInfo(pm, apk) : null;
            problem = archive == null ? Ui.tr("the download isn't an app")
                    : !s.pkg.equals(archive.packageName) ? Ui.trf("the download isn't {app}", "app", s.name)
                    : null;
            if (problem == null) break;
            AppLog.w("app update " + s.pkg + ": " + asset.optString("name") + " isn't it, trying the next file");
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
        if (problem == null && versionCode(archive) <= versionCode(installed)) {
            put(app, "seen", seen);
            put(app, "versionCode", versionCode(archive));
            put(app, "result", Ui.tr("Up to date."));
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return false;
        }
        if (problem == null && !sameSigner(installed, archive)) {
            problem = Ui.tr("it's signed with another key than the copy on the TV");
        }
        if (problem != null) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            put(app, "seen", seen);
            put(app, "versionCode", 0);
            put(app, "refused", problem);
            throw new IOException(problem);
        }
        put(app, "seen", seen);
        put(app, "versionCode", versionCode(archive));
        put(app, "version", archive.versionName);
        return install(c, s, apk, app);
    }

    /** Installs a checked APK if Free My TV's screens are in front; else leaves it for the next try (true). */
    private static boolean install(Context c, Source s, File apk, JSONObject app) throws IOException {
        String version = app.optString("version");
        try (Adb adb = Adb.connectAllowed(c)) {
            if (!inFront(adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))) {
                put(app, "result", Ui.trf("{version} is ready: it installs on the home screen.", "version", version));
                return true;
            }
            String out = adb.install(apk).trim();
            if (!out.contains("Success")) throw new IOException(out.isEmpty() ? Ui.tr("the TV didn't install it") : out);
        }
        //noinspection ResultOfMethodCallIgnored
        apk.delete();
        put(app, "result", Ui.trf("Updated to {version}.", "version", version));
        put(app, "updatedAt", System.currentTimeMillis());
        HomeMode.notice(c, Ui.trf("{app} updated to {version}.", "app", s.name, "version", version));
        return false;
    }

    /** Free My TV's home screen or settings has the focus: no film or app the install could interrupt. */
    static boolean inFront(String dumpsys) {
        for (String line : dumpsys.split("\n")) {
            if ((line.contains("mCurrentFocus") || line.contains("mFocusedApp")) && !line.contains(HomeMode.PKG + "/")) return false;
        }
        return dumpsys.contains(HomeMode.PKG + "/");
    }

    // MARK: - GitHub

    /** The release files worth trying, from the newest release (or pre-release, for a beta) that has any. */
    private static List<JSONObject> latestAssets(Source s, String abi) throws IOException, JSONException {
        String api = "https://api.github.com/repos/" + s.repo + "/releases";
        JSONArray releases = s.prerelease ? new JSONArray(get(api + "?per_page=10"))
                : new JSONArray().put(new JSONObject(get(api + "/latest")));
        for (int r = 0; r < releases.length(); r++) {
            JSONObject release = releases.getJSONObject(r);
            if (release.optBoolean("draft")) continue;
            List<JSONObject> assets = pickAssets(release.optJSONArray("assets"), s.files, abi, s.repo);
            if (!assets.isEmpty()) return assets;
        }
        return new ArrayList<>();
    }

    /**
     * The files to try, in order: those the patterns name (pattern by pattern), then, in case the project renamed its
     * files, the other APKs and archives that look like this TV's build. Only from that repository's releases (as
     * Updates does for Free My TV; github.com then redirects to its download host). What's downloaded must still be
     * the app, newer and signed with its key, so a wrong guess costs a download, never an install.
     */
    static List<JSONObject> pickAssets(JSONArray assets, String[] files, String abi, String repo) {
        List<JSONObject> out = new ArrayList<>();
        if (assets == null) return out;
        String from = ("https://github.com/" + repo + "/releases/download/").toLowerCase(Locale.ROOT);
        List<JSONObject> fromRepo = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("browser_download_url").toLowerCase(Locale.ROOT).startsWith(from)) fromRepo.add(a);
        }
        for (String file : files) {
            Pattern p = Pattern.compile(file.replace("{abi}", Pattern.quote(abi)));
            for (JSONObject a : fromRepo) {
                if (!out.contains(a) && p.matcher(a.optString("name")).matches()) out.add(a);
            }
        }
        for (int rank = 0; rank < 2; rank++) {
            for (JSONObject a : fromRepo) {
                if (!out.contains(a) && guess(a.optString("name"), abi) == rank) out.add(a);
            }
        }
        return out;
    }

    /** ABIs as release files name them, most specific first. */
    private static final String[][] ABI_NAMES = {
        {"arm64-v8a", "arm64", "aarch64"},
        {"armeabi-v7a", "armeabi", "armv7", "arm32"},
        {"x86_64", "x64", "amd64"},
        {"x86", "i686"},
    };

    /**
     * How much a file looks like an APK for this ABI: 0 named for it, 1 for every ABI (universal, or none named), -1
     * not one to try (another ABI, a debug, Wear OS, car or test build, not an APK or archive).
     */
    static int guess(String name, String abi) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!(n.endsWith(".apk") || isArchive(n))) return -1;
        if (n.matches(".*(debug|wear|automotive|androidtest|unsigned|linux|windows|macos|darwin|ios).*")) return -1;
        n = n.replace("x86_64", "amd64").replace("x86-64", "amd64");   // so "x86" below doesn't find it in "x86_64"
        Set<String> named = new HashSet<>();
        for (String[] names : ABI_NAMES) {
            for (String token : names) {
                if (n.matches(".*(^|[^a-z0-9])" + Pattern.quote(token) + "($|[^a-z0-9]).*")) named.add(names[0]);
            }
        }
        if (named.isEmpty() || n.contains("universal")) return 1;
        return named.size() == 1 && named.contains(abi) ? 0 : -1;
    }

    private static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".tar.gz") || n.endsWith(".tgz");
    }

    /** The ABI an installed app runs as (its native library folder: lib/arm, lib/arm64…), else the TV's first. */
    static String abiOf(ApplicationInfo info) {
        String dir = info == null || info.nativeLibraryDir == null ? "" : new File(info.nativeLibraryDir).getName();
        switch (dir) {
            case "arm64": return "arm64-v8a";
            case "arm": return "armeabi-v7a";
            case "x86_64": return "x86_64";
            case "x86": return "x86";
            default: return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "armeabi-v7a";
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String why = "GitHub: HTTP " + code;
                throw code == 403 || code == 429 || code >= 500 ? new Temporary(why) : new IOException(why);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** Downloads a release file, checking its size and the SHA-256 GitHub publishes (when it does). */
    private static void download(JSONObject asset, File to) throws IOException {
        long size = asset.optLong("size", -1);
        if (size > MAX_DOWNLOAD) throw new IOException("the file is too large");
        HttpURLConnection c = (HttpURLConnection) new URL(asset.optString("browser_download_url")).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        long total = 0;
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String why = "download: HTTP " + code;
                throw code == 429 || code >= 500 ? new Temporary(why) : new IOException(why);
            }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD) throw new IOException("the file is too large");
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
        if (size >= 0 && total != size) throw new Temporary("the download was cut short");
        String digest = asset.optString("digest");
        if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(hex(sha.digest()))) {
            throw new IOException("the download doesn't match GitHub's SHA-256");
        }
    }

    /** The APK inside a release archive (.tar.gz): the first regular file whose name ends in .apk. */
    static void extractApk(File archive, File to) throws IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(archive), 64 * 1024)) {
            byte[] header = new byte[512];
            String longName = null;
            while (readFully(in, header)) {
                if (isZero(header)) break;
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                String name = longName != null ? longName : string(header, 0, 100);
                longName = null;
                if (type == 'L') {   // GNU long name: the next entry's name
                    byte[] data = new byte[(int) Math.min(size, 4096)];
                    if (!readFully(in, data)) break;
                    skip(in, padded(size) - data.length);
                    longName = string(data, 0, data.length);
                    continue;
                }
                if ((type == '0' || type == 0) && name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    try (OutputStream out = new FileOutputStream(to)) {
                        byte[] buffer = new byte[64 * 1024];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n < 0) throw new IOException("the archive was cut short");
                            out.write(buffer, 0, n);
                            left -= n;
                        }
                    }
                    return;
                }
                skip(in, padded(size));
            }
        }
        throw new IOException("no APK in the archive");
    }

    private static long padded(long size) {
        return (size + 511) / 512 * 512;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int read = 0;
        while (read < b.length) {
            int n = in.read(b, read, b.length - read);
            if (n < 0) {
                if (read == 0) return false;
                throw new IOException("the archive was cut short");
            }
            read += n;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buffer = new byte[8192];
        while (n > 0) {
            int r = in.read(buffer, 0, (int) Math.min(buffer.length, n));
            if (r < 0) throw new IOException("the archive was cut short");
            n -= r;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long octal(byte[] b, int at, int length) throws IOException {
        long v = 0;
        for (int i = at; i < at + length; i++) {
            if (b[i] == 0 || b[i] == ' ') {
                if (v > 0) break;
                continue;
            }
            if (b[i] < '0' || b[i] > '7') throw new IOException("not a tar archive");
            v = v * 8 + (b[i] - '0');
        }
        return v;
    }

    private static String string(byte[] b, int at, int length) {
        int end = at;
        while (end < at + length && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }

    // MARK: - Packages

    @SuppressWarnings("deprecation")
    private static int signersFlag() {
        return Build.VERSION.SDK_INT >= 28 ? Android9.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static PackageInfo packageInfo(PackageManager pm, String pkg) throws IOException {
        try {
            return pm.getPackageInfo(pkg, signersFlag());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("not installed");
        }
    }

    private static PackageInfo archiveInfo(PackageManager pm, File apk) {
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), signersFlag());
        if (info != null && info.applicationInfo != null) {
            // Before Android 9, an archive's signatures are read only with its path set.
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
        }
        return info;
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? Android9.versionCode(info) : info.versionCode;
    }

    /**
     * Signed with a key of the installed copy (its whole lineage, so a release signed after a key rotation still
     * matches). The package manager enforces the real rule; this only stops a download that can't install anyway.
     */
    static boolean sameSigner(PackageInfo installed, PackageInfo archive) {
        Set<String> mine = signers(installed), theirs = signers(archive);
        for (String s : theirs) if (mine.contains(s)) return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) {
        Signature[] list = Build.VERSION.SDK_INT >= 28 ? Android9.signers(info) : info.signatures;
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Signature s : list) out.add(hex(sha.digest(s.toByteArray())));
        } catch (NoSuchAlgorithmException ignored) {
        }
        return out;
    }

    /** Android 9's signing certificates (with the key's lineage) and long version codes; only called there. */
    private static final class Android9 {
        static final int GET_SIGNING_CERTIFICATES = PackageManager.GET_SIGNING_CERTIFICATES;

        static long versionCode(PackageInfo info) {
            return info.getLongVersionCode();
        }

        static Signature[] signers(PackageInfo info) {
            if (info.signingInfo == null) return null;
            return info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    // MARK: - Files (atomic writes, as the state's)

    private static JSONObject read(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void write(Context c, String name, JSONObject value) {
        File dir = c.getFilesDir(), tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            AppLog.w("could not save " + name + ": " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(new File(dir, name))) AppLog.w("could not replace " + name);
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }
}
