package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original ⇄ Free My TV. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.freemytv";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity";
    /** Our screensaver: on Android 11 the copy that runs in the main process (ScreensaverAlone, #10). */
    static final String DREAM = PKG + "/" + PKG + (Build.VERSION.SDK_INT == 30 ? ".ScreensaverAlone" : ".Screensaver");
    static final String FREE = "free", ORIGINAL = "original";

    /**
     * Fire TV (Fire OS): Amazon's launcher keeps the Home button (`priority=950` on `CATEGORY_HOME`), so the switch
     * refuses Free My TV mode while Original and the watchdog still run.
     */
    static final boolean AMAZON = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) || "Amazon".equalsIgnoreCase(Build.BRAND);

    /** Classic Android TV's home screen; its recommendations service crashes on the screensaver while it's off (#11). */
    static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    /**
     * Google TV has a RecoveryActivity that would take over the home screen without its launcher. Older Android TV
     * (Android 9) has the same setup app without a home screen: there it is left alone.
     */
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";
    /**
     * Some HY300 projectors (Amlogic txhd2, Android 13) answer the Home button with JmLauncher, not the default home:
     * it opens the package in JM_HOME, else the HTC home screen, and with that one off and the property empty, a black
     * screen. Their adb shell runs as root, so the switch can set the property.
     */
    static final String JM_LAUNCHER = "com.jm.jmlauncher", JM_HOME = "persist.sys.home.selectpkg";
    static final String HTC_HOME = "com.htc.htclauncherhighenglishd08";

    /**
     * Turned off in Free My TV mode, when the TV has them. Google extras first, then TV-maker extras (TCL here; add
     * your brand's, see the README). The TV's own home screen and screensaver are added at switch time.
     */
    static final String[] DEBLOAT = {
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
        // TCL
        "com.tcl.magiconnectfree", "com.tcl.miracast",                                // screen mirroring apps
        "com.tcl.waterfall.overseas", "com.tcl.channelplus",                          // TCL Channel and its Live TV
        "com.tcl.messagebox", "com.tcl.usercenter",                                   // TCL messages and account
        "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",                   // store demo, hotel menu
        "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",        // repair guide, manual, logs
        // Multilaser (Expressluck / CVTE board)
        "tv.anoki.acr.anokiacroptin",                                                 // Anoki ACR: watches what's on screen
        "fusion.android.tv.demo", "com.expressluck.tvhelpmanual",                     // store demo (also Xiaomi projectors), manual
        // Philco (Homwee/Changhong)
        "com.homwee.aipont",                                                          // AI PONT: promo channels, app downloads
        "com.homwee.help",                                                            // user manual
        // Xiaomi
        "com.miui.tv.analytics", "com.xiaomi.statistic",                              // telemetry
        "com.mitv.tvhome.mitvplus", "com.xm.webcontent",                              // Mi TV+ promo channels, promo web content
        // Skyworth (VEON and other badges; MediaTek)
        "com.smartdevice.epopplayer", "com.coocaa.demonow.lightining",               // store demos (start on screen on)
        "com.smartdevice.recommendation",                                             // Coolita promo channels
        // KTC (NextStar; MediaTek)
        "tv.anoki.acr.controller",                                                    // Anoki ACR: watches what's on screen
        "com.mediatek.tv.retaildemo", "com.electronic.manual",                        // store demo, manual
        // HY300 projectors (Amlogic txhd2, Android 13): JmLauncher opens it from the Home button (JM_HOME); off only
        // once JM_HOME is ours
        HTC_HOME,                                                                     // the stock home (and an ad SDK)
    };

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /** Never turned off, not even by mistake (picture, sound, inputs, remote, store). Checked before every disable. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
        "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
        "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config",
        "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service", "com.tcl.initsetup",
        "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        // TCL on Google TV (C825, Android 11): system server, Bluetooth remote and mic, keys, settings and quick menu,
        // keyboard, webview, Wi-Fi overlay, partner setup
        "com.tcl.system.server", "com.tcl.tcl_bt_rcu_service", "com.tcl.micmanager", "com.tcl.keyhelp",
        "com.tcl.settings", "com.tcl.common.shortcutmenu", "com.tcl.inputmethod.international", "com.tcl.android.webview",
        "com.tcl.wifi.resources.overlay", "com.tcl.partnercustomizer", "com.tcl.tv.tclhome_passive",
        "com.tcl.keycustomfunctionservice",                                           // remote keys; a fallback home too
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.inputmethod.latin", "com.google.android.webview",
        "com.android.tv.settings", "com.android.systemui", "android", PKG,
        // Philips (TP Vision) and MediaTek: system, Ambilight, live TV and inputs, setup, factory menu (#11)
        "org.droidtv", "org.droidtv.ambisvc", "org.droidtv.ui.strings", "org.droidtv.tv.packageinfo", "org.droidtv.icp",
        "org.droidtv.gel", "org.droidtv.eum", "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.network",
        "com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.setupwizard", "com.mediatek.tv.factory",
        "mediatek.factorymenu.ui", "com.tpv.xmic.ots.cms.client",
        // Multilaser (Expressluck / CVTE): remote pairing and keys, live TV, media player, factory menus
        "com.express.bt.autopair", "com.mediatek.autopair", "com.mediatek.hotkey.dispatcher",
        "com.mediatek.android.tv.globalkeyoverlay", "com.cltv.fast", "com.mediatek.wwtv.mediaplayer",
        "com.mediatek.androidbox", "com.expressluck.facmenu", "com.express.at.factory.factorymanager",
        "com.at.factory.androidtvaidlservice", "com.cvte.tv.at", "com.cvte.tv.fac.aging", "com.express.fac.test",
        // Philco (Homwee/Changhong, MediaTek): picture tests, overlays, updates, factory menu (pairing is above)
        "com.mediatek.tv.factorykey", "com.mediatek.testpq", "com.mediatek.overlay.tvcenter",
        "com.mediatek.overlay.tvsettings", "com.homwee.overlay.tvservice", "com.homwee.synckey", "com.homwee.upgrade",
        "com.homwee.dmt.upgrade", "com.homwee.factory", "com.homwee.chinstallapk", "com.homwee.chtvmanager",
        "com.mstar.android.tv.disclaimercustomization",
        // Xiaomi (MediaTek): system service, remote pairing, live TV, quick settings, child lock, updates, casting
        "mitv.service", "com.mi.bluetooth", "com.mitv.livetv.ci_mediatek", "com.xiaomi.tvqs.overseas",
        "com.mitv.tvlock", "com.xiaomi.mitv.updateservice", "com.xiaomi.android.tvsetup.partnercustomizer",
        "com.mitv.overseaservice", "com.xiaomi.mitv.tvmanager", "com.xiaomi.wfdsinkhelperservice",
        "com.mitv.milinkservice", "com.xiaomi.mitv.smartshare", "android.autoinstalls.config.xiaomi.amelie",
        // Skyworth (MediaTek): live TV, remote pairing and keys, setup, service menu, casting, OLED care, boot
        "com.smartdevice.livetv", "com.smartdevice.livetv.overlay_SANLUX", "com.smartdevice.bleconnectservice",
        "com.smartdevice.duplebluetooth", "com.smartdevice.myapp", "com.smartdevice.setupwraith",
        "com.smartdevice.aftersale", "com.smartdevice.factory", "com.smartdevice.dailymanage",
        "com.smartdevice.dtv.icast", "com.smartdevice.leanbacklauncher.customizer", "com.mediatek.OledFuctionService",
        "com.mediatek.android.boot.appsplashscreen", "com.mediatek.android.leanbacklauncher.partnercustomizer",
        // KTC (NextStar; MediaTek): live TV and tuners, setup, settings, updates, casting, remote pairing, factory menus
        "com.mediatek.tv.oneworld.tvcenter", "com.mediatek.tis", "com.mediatek.tv.agent",
        "com.mediatek.dtv.tvinput.dvbtuner", "com.mediatek.tv.oneworld.wizardkt", "com.mediatek.tv.settings",
        "com.mediatek.tv.systemupdater", "com.mediatek.extservice", "com.mediatek.dialservice", "com.mediatek.miracast",
        "com.mediatek.tv.launcher.partner", "com.google.android.tv.googletvremotepairer", "com.ktc.serialport",
        "com.ktc.tv.factory", "ktc.factorymenu.ui",
        // Xiaomi projectors (Fengmi/Formovie, MediaTek): keystone, focus, picture and sound, system service, remote
        // pairing, settings provider, factory tests, launcher config, Freeview live TV
        "com.fengmi.projector.imaging", "com.fengmi.platform", "com.fengmi.Autopair", "com.fengmi.fmsettings",
        "com.fengmi.testsuite", "android.autoinstalls.config.fomovie",
        "uk.co.freeview.tifbridge",
        "uk.co.freeview.mdsclient", "uk.co.freeview.fvpconfigauth",
        // onn (Walmart; Amlogic S905Y4, 4K Streaming Box, Google TV 14): remote pairing, update notices, the remote's
        // app buttons (GLOBAL_BUTTON), hardware info, Amlogic system, settings, mic switch and launcher provider
        "com.onn.bluetoothconnect", "com.onn.btpairoverlay", "com.onn.updatenotification", "com.gretzky.GlobalKey",
        "com.gretzky.hwinfo", "com.droidlogic", "com.droidlogic.tv.settings", "com.droidlogic.mictoggle",
        "com.droidlogic.launcher.provider", "com.droidlogic.android.tv.connecteddevices",
        // HY300 projectors (Hotack, Allwinner H713): keystone and projection settings, the HDMI player, Miracast receiver
        "com.htc.htcsettings", "com.softwinner.awlivetv", "com.softwinner.miracastReceiver",
        // Amazon Fire TV: launcher, settings, setup, remote pairing, inputs, live TV, channel scan, OTA updates, Appstore
        // (every com.amazon.* is refused by mayTurnOff too)
        "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.tv.settings.v2", "com.amazon.tv.settings.core",
        "com.amazon.tv.settings.mediatek", "com.amazon.tv.settingsprovider.configuration", "com.amazon.android.settings",
        "com.amazon.tv.quicksettings", "com.amazon.tv.quicksettings.ui", "com.amazon.tv.oobe", "com.amazon.device.blepa",
        "com.amazon.device.bluetoothpa", "com.amazon.device.bluetoothdfu", "com.amazon.autopairservice",
        "com.amazon.fireinputdevices", "com.amazon.tv.inputpreference.service", "com.amazon.franktvinput",
        "com.amazon.tv.livetv", "com.amazon.mediatek.livetv", "com.amazon.tv.channelscan",
        "com.amazon.tv.conditionalaccess", "com.amazon.tv.devicecontrol", "com.amazon.tv.devicecontrolsettings",
        "com.amazon.tv.routing", "com.amazon.tv.ime", "com.amazon.tv.keypolicymanager", "com.amazon.ftv.screensaver",
        "com.amazon.device.software.ota", "com.amazon.webview.chromium", "com.amazon.venezia", "amazon.fireos"));

    /**
     * Android's own pieces: with one of them off, a TV may not start again (a list that took the permission
     * controller and the package installer left an Android TV stick dead, CleanYourTV #884).
     */
    private static final Pattern CORE = Pattern.compile("android|com\\.android\\.(systemui|shell|phone|settings|tv\\.settings)"
            + "|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)"
            + "|com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*");
    /** Factory and service menus, on any brand ("com.tcl.factory.view", "com.cvte.tv.fac.aging", "mediatek.factorymenu.ui"). */
    private static final Pattern FACTORY = Pattern.compile("(.*\\.)?(factory|factorymenu|facmenu|fac|hkcfac|testpq)(\\..*)?");

    /** Checked before every disable: never the protected list, Amazon's packages, Android's own pieces or a factory menu. */
    private static boolean mayTurnOff(String pkg) {
        return !PROTECTED.contains(pkg) && !pkg.startsWith("com.amazon.")
                && !CORE.matcher(pkg).matches() && !FACTORY.matcher(pkg).matches();
    }

    /**
     * Of these, the ones the TV still needs, whatever they look like: the software under its inputs (they ask for
     * TV_INPUT_HARDWARE; off, HDMI shows "No signal" and only a full restore brought it back, CleanYourTV #321), and
     * the receiver of the remote's Input key and Bluetooth pairing (fusion.android.tv.demo on several MediaTek boards).
     * null when the check didn't finish: then none of them goes off this time.
     */
    private static Set<String> neededByTv(Adb adb, Set<String> pkgs) throws IOException {
        Set<String> keep = new HashSet<>();
        if (pkgs.isEmpty()) return keep;
        // Only the package's own requested permissions: a shared user's would match by mistake. A line per package keeps
        // the connection's read timeout from running out on a slow TV.
        String out = adb.shell("for p in " + String.join(" ", sorted(pkgs)) + "; do d=\"$(dumpsys package $p)\"; "
                + "if printf '%s\\n' \"$d\" | sed -n -r '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p'"
                + " | grep -q TV_INPUT_HARDWARE || printf '%s\\n' \"$d\" | grep -q -E 'TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory';"
                + " then echo \"keep $p\"; else echo \"- $p\"; fi; done; echo checked");
        if (!out.contains("checked")) return null;
        Matcher m = Pattern.compile("keep ([\\w.]+)").matcher(out);
        while (m.find()) keep.add(m.group(1));
        if (!keep.isEmpty()) AppLog.w("kept on, the TV's inputs or remote need them: " + String.join(", ", sorted(keep)));
        return keep;
    }

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check Free My TV mode. */
    static void bootCheck(Context c) {
        if (AMAZON) return;
        ensureRemoteService(c, 30_000);
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000);
    }

    /** After an update of this app: complete Free My TV mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    /**
     * The remote's app buttons (RemoteKeys) need an accessibility service that only filters those keys. It is on only
     * on TCL, Vestel, KTC, Sony, Xiaomi and Hisense TVs (and Formovie projectors) in Free My TV mode, and goes off with Original. Android turns it off when this app is updated, so the
     * boot check and an update turn it back on.
     */
    static void ensureRemoteService(Context c, long delayMs) {
        if (RemoteKeys.supported()) ModeJob.schedule(c, ModeJob.REMOTE, null, delayMs);
    }

    static void ensureRemoteServiceNow(Context app) {
        if (!RemoteKeys.supported() || !FREE.equals(state(app).optString("mode"))) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            remoteService(app, adb, true);
        } catch (IOException e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    private static final String REMOTE_SERVICE = PKG + "/" + PKG + ".RemoteKeyService";
    private static final Pattern SERVICE = Pattern.compile("[\\w.]+/[\\w.$]+");

    /** Adds ours to the enabled accessibility services (keeping the others, like TalkBack), or takes it out. */
    private static void remoteService(Context c, Adb adb, boolean on) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> others = new ArrayList<>();
        for (String one : current.split(":")) {
            if (one.isEmpty() || one.equals(REMOTE_SERVICE)) continue;
            // Only plain component names go back into the shell command; anything else is left alone.
            if (!SERVICE.matcher(one).matches()) {
                AppLog.w("accessibility services left as they are: unexpected entry");
                return;
            }
            others.add(one);
        }
        if (on == current.contains(REMOTE_SERVICE)) return;
        List<String> list = new ArrayList<>(others);
        if (on) {
            JSONObject s = state(c);
            try { s.put("accessibilityWasOff", !"1".equals(adb.shell("settings get secure accessibility_enabled").trim())); }
            catch (JSONException ignored) {}
            save(c, s);
            list.add(REMOTE_SERVICE);
        }
        adb.shell(list.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + String.join(":", list) + "'");
        if (on) adb.shell("settings put secure accessibility_enabled 1");
        else if (list.isEmpty() && state(c).optBoolean("accessibilityWasOff")) adb.shell("settings put secure accessibility_enabled 0");
        AppLog.w("remote buttons service " + (on ? "on" : "off"));
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static void applyFree(Context c) {
        JSONObject s = state(c);
        if (AMAZON) {
            done(c, Ui.tr("On Fire TV the Home button belongs to Amazon's home screen. Free My TV stays an app here: open it from your apps."));
            return;
        }
        // Kalley 43G200 turns Google TV's setup app back on at every start: without its home screen the next start
        // looped at the logo, and that TV only came back with a USB reflash (CleanYourTV, examples/kalley-43g200).
        if ("R3_GTV".equals(Build.DEVICE)) {
            done(c, Ui.tr("On this TV model, switching the home screen kept a TV from starting. Free My TV stays an app here: open it from your apps."));
            return;
        }
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                home = found != null ? realHome(found) : "";
                if (home.isEmpty()) AppLog.w("original home screen not recorded (HOME resolves to \"" + homeHolder(adb) + "\"): Original will pick the TV's own");
            }
            String dream = originalDream(s);
            if (dream.isEmpty()) dream = defaultDream(adb);
            JSONObject latest = state(c);
            latest.put("originalHome", home).put("originalDream", dream);
            String saver = adb.shell("settings get secure screensaver_components").trim();
            if (!saver.contains(PKG + "/")) latest.put("originalScreensaver", safeComponents(saver));
            boolean jm = hasJm(adb);
            String jmBefore = jm ? jmHome(adb) : "";
            if (jm && !jmBefore.equals(PKG)) latest.put("originalJmHome", jmBefore.matches("[\\w.]*") ? jmBefore : "");
            save(c, latest);
            // Turning off an app makes Android delete the Wi-Fi networks it saved (#14), and Original can't bring them
            // back. The TV's first setup usually saved the network: then stop here, before anything changes.
            Set<String> owners = wifiOwners(adb);
            if (owners == null) {
                // Nobody can tell who saved the networks: on Wi-Fi, stop before anything changes.
                if (!"0".equals(adb.shell("settings get global wifi_on").trim())) {
                    throw new IOException(Ui.tr("I couldn't check which app saved the Wi-Fi, so nothing changed. Try again in a minute."));
                }
                owners = new HashSet<>();
            }
            boolean setupHome = setupIsHome(adb);
            if ((setupHome && owners.contains(GOOGLE_SETUP)) || owners.contains(packageOf(home))) {
                throw new IOException(Ui.tr("the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more."));
            }
            if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                busy(c, Ui.tr("Measuring the original home screen…"));
                measureHomeTime(c, adb, ORIGINAL);
                measureMemory(c, adb, ORIGINAL);
            }
            busy(c, Ui.tr("Switching the home screen and screensaver…"));
            Watchdog.clear(c);
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("Android didn't accept the new home screen"));
            adb.shell("settings put secure screensaver_components " + DREAM);
            boolean jmOurs = !jm || setJmHome(adb, PKG);
            if (!jmOurs) AppLog.w("JmLauncher keeps the Home button: " + JM_HOME + " didn't take");
            JSONObject started = state(c);
            started.put("mode", FREE).put("homeReverted", false).put("setupHome", setupHome);
            save(c, started);
            List<String> targets = targets(home, dream, setupHome);
            Set<String> disabled = new HashSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            Set<String> keptOff = new HashSet<>(stringList(state(c), "keptOff"));
            // Off now and not on our list: the person turned it off, unless an earlier install of Free My TV did (its
            // list lives in the TV's settings too, since an uninstall erases our state file).
            Set<String> ours = recorded(adb);
            for (String pkg : packages(adb, "-d")) {
                if (targets(home, dream, true).contains(pkg) && !disabled.contains(pkg) && !ours.contains(pkg)) keptOff.add(pkg);
            }
            keptOff.removeAll(ours);
            JSONObject before = state(c);
            before.put("keptOff", new JSONArray(sorted(keptOff)));
            save(c, before);
            Set<String> candidates = new HashSet<>();
            for (String pkg : targets) if (present.contains(pkg) && mayTurnOff(pkg)) candidates.add(pkg);
            Set<String> needed = neededByTv(adb, candidates);
            if (needed == null) {
                AppLog.w("couldn't check what the TV's inputs need: nothing goes off this time");
                needed = candidates;
            }
            String homePkg = packageOf(home);
            boolean setupOn = setupHome && present.contains(GOOGLE_SETUP) && !keptOff.contains(GOOGLE_SETUP);
            int done = 0;
            for (String pkg : targets) {
                done++;
                busy(c, Ui.trf("Turning off apps ({done}/{total})…", "done", done, "total", targets.size()));
                if (!candidates.contains(pkg) || needed.contains(pkg) || owners.contains(pkg) || keptOff.contains(pkg)) continue;
                // The TV's home screen only once Google TV's setup app is off: its recovery screen would take Home.
                if (pkg.equals(homePkg) && setupOn) {
                    AppLog.w("kept the TV's home screen on: Google TV's setup app is still on");
                    continue;
                }
                if (pkg.equals(HTC_HOME) && !jmOurs) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) {
                    if (pkg.equals(GOOGLE_SETUP)) setupOn = false;
                    disabled.add(pkg);
                    JSONObject now = state(c);
                    now.put("disabled", new JSONArray(sorted(disabled)));
                    save(c, now);
                } else {
                    AppLog.w("could not turn off " + pkg + ": " + out.trim());
                }
            }
            record(adb, disabled);
            s = state(c);
            s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(sorted(disabled))).put("setupHome", setupHome);
            save(c, s);
            if (RemoteKeys.supported()) {
                try {
                    remoteService(c, adb, true);
                } catch (IOException e) {
                    AppLog.w("remote buttons service: " + e.getMessage());
                }
            }
            notice(c, Ui.trf("Free My TV is on: new home screen and screensaver, {n} apps turned off.", "n", disabled.size()));
            busy(c, Ui.tr("Measuring Free My TV…"));
            measureHomeTime(c, adb, FREE);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000);
        } catch (Exception e) {
            done(c, Ui.trf("Couldn't turn on Free My TV: {error}", "error", e.getMessage()));
        }
    }

    private static void applyOriginal(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            busy(c, Ui.tr("Turning the apps back on…"));
            String home = originalHome(s);
            Set<String> toEnable = new HashSet<>(stringList(s, "disabled"));
            for (String pkg : targets(home, originalDream(s), true)) {
                if (!Arrays.asList(DEBLOAT).contains(pkg) && !Arrays.asList(ASSISTANT).contains(pkg)) toEnable.add(pkg);
            }
            toEnable.removeAll(stringList(s, "keptOff"));
            Set<String> present = packages(adb, "");
            // The TV's home screen first and Google TV's setup app last, after the home screen is back: alone, its
            // recovery screen would take the Home button.
            boolean setupBack = toEnable.remove(GOOGLE_SETUP) && packages(adb, "-d").contains(GOOGLE_SETUP);
            List<String> order = sorted(toEnable);
            String homePkg = packageOf(home);
            if (order.remove(homePkg)) order.add(0, homePkg);
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (String pkg : order) {
                done++;
                busy(c, Ui.trf("Turning apps back on ({done}/{total})…", "done", done, "total", order.size()));
                if (!present.contains(pkg)) continue;
                // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears silently.
                String out = adb.shell("pm enable " + pkg);
                if (!out.contains("new state: enabled")) failed.add(pkg);
            }
            busy(c, Ui.tr("Restoring the original home screen…"));
            boolean homeOk = restoreHome(adb, home);
            if (hasJm(adb) && !setJmHome(adb, s.optString("originalJmHome", ""))) homeOk = false;
            if (setupBack) {
                if (homeOk && adb.shell("pm enable " + GOOGLE_SETUP).contains("new state: enabled")) {
                    homeOk = restoreHome(adb, home);    // a new home screen candidate can drop the default one
                } else {
                    failed.add(GOOGLE_SETUP);
                }
            }
            record(adb, new HashSet<>(failed));
            restoreScreensaver(adb, s);
            try {
                remoteService(c, adb, false);
            } catch (IOException e) {
                AppLog.w("remote buttons service: " + e.getMessage());
            }
            SleepTimer.stop(c);
            adb.shell("appops set " + PKG + " SYSTEM_ALERT_WINDOW default");   // SleepOverlay's, if it was allowed
            Capabilities.forget(c, Capabilities.OVERLAY);                       // asked again next time
            s = state(c);
            s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(failed)).remove("keptOff");
            save(c, s);
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> problems = new ArrayList<>(failed);
                if (!homeOk) problems.add(Ui.tr("the original home screen"));
                notice(c, Ui.trf("Original mode, but I couldn't turn back on: {list}.", "list", String.join(", ", problems)));
            }
            busy(c, Ui.tr("Measuring the original home screen…"));
            measureHomeTime(c, adb, ORIGINAL);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000);
        } catch (Exception e) {
            done(c, Ui.trf("Couldn't go back to the original: {error}", "error", e.getMessage()));
        }
    }

    /**
     * The recorded home screen; without one, Google TV's; without that, the TV's own (stockHome); and as the last
     * resort the system picks (we leave the role, which Android 9 and older can't do: they have no `cmd role`).
     */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity '" + home + "'").contains("Success")) return true;
        }
        String stock = stockHome(adb);
        if (stock != null && !stock.equals(home)) {
            adb.shell("pm enable " + packageOf(stock));
            if (adb.shell("cmd package set-home-activity '" + stock + "'").contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    /**
     * The TV's own home screen when none was recorded (seen on a Philips TPM171E, Android 8, at the first switch): a
     * system app that opens HOME, other than ours, Google TV's setup app (it opens HOME through its recovery activity)
     * and Android's bare "fallback home". Never a launcher the person installed. null when there isn't exactly one.
     */
    private static String stockHome(Adb adb) throws IOException {
        Set<String> system = new HashSet<>();
        for (String line : adb.shell("pm list packages -s").split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) system.add(line.substring(8));
        }
        String found = null;
        for (String line : adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").split("\n")) {
            line = line.trim();
            if (!COMPONENT.matcher(line).matches() || line.startsWith(PKG + "/") || line.startsWith(GOOGLE_SETUP + "/")
                    || line.contains("FallbackHome")) continue;
            if (!system.contains(packageOf(line))) continue;
            if (found != null) return null;      // two system home screens: don't guess
            found = line;
        }
        return found;
    }

    /** Safety net: our home screen crashed 3 times in a row. The home screen and the screensaver go back; the rest stays. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                boolean ours = homeHolder(adb).contains(PKG);
                if (!ours && (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted"))) return;
                String home = originalHome(s);
                List<String> keptOff = stringList(s, "keptOff");
                if (!restoreHome(adb, home)) {
                    AppLog.w("could not give the home screen back");
                    return;
                }
                // Google TV's setup app only now, with its home screen back (alone, its recovery screen takes Home).
                if (!keptOff.contains(GOOGLE_SETUP) && adb.shell("pm path " + GOOGLE_SETUP).contains("package:")) {
                    adb.shell("pm enable " + GOOGLE_SETUP);
                    restoreHome(adb, home);
                }
                String homePkg = packageOf(home), dreamPkg = packageOf(originalDream(s));
                if (dreamPkg != null && !keptOff.contains(dreamPkg)) adb.shell("pm enable " + dreamPkg);
                // With JmLauncher, Home opens the HTC home screen once the property is back: both, or a black screen.
                boolean jm = hasJm(adb);
                if (jm) {
                    adb.shell("pm enable " + HTC_HOME);
                    setJmHome(adb, s.optString("originalJmHome", ""));
                }
                JSONArray kept = new JSONArray();
                Set<String> stillOff = new HashSet<>();
                for (String pkg : stringList(s, "disabled")) {
                    if (!pkg.equals(GOOGLE_SETUP) && !pkg.equals(homePkg) && !pkg.equals(dreamPkg) && !(jm && pkg.equals(HTC_HOME))) {
                        kept.put(pkg);
                        stillOff.add(pkg);
                    }
                }
                record(adb, stillOff);
                // The screensaver first: if that fails, the revert isn't marked done and the next try does it again.
                restoreScreensaver(adb, s);
                SleepTimer.stop(app);   // its row shows Unavailable from now on
                s.put("homeReverted", true).put("disabled", kept);
                save(app, s);
                notice(app, Ui.tr("Free My TV's home screen crashed 3 times in a row; I switched back to the original one."));
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            } catch (Exception e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    /**
     * In Free My TV mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            String fingerprint = Build.FINGERPRINT;
            boolean updated = !fingerprint.equals(s.optString("fingerprint", fingerprint));
            try {
                s.put("fingerprint", fingerprint);
                s.remove("busy");
                save(app, s);
            } catch (JSONException ignored) {}
            if (!FREE.equals(s.optString("mode")) || AMAZON) return;
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                Set<String> owners = wifiOwners(adb);
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                String homePkg = packageOf(home);
                // Who saved the Wi-Fi unknown: the setup app and the home screen wait for the next check.
                Set<String> wait = new HashSet<>();
                if (owners == null) {
                    owners = new HashSet<>();
                    wait.add(GOOGLE_SETUP);
                    if (homePkg != null) wait.add(homePkg);
                }
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                String dream = originalDream(s);
                // In the switch's order (Google TV's setup app before its home screen), then the rest.
                List<String> order = new ArrayList<>();
                for (String pkg : targets(home, dream, s.optBoolean("setupHome", true))) {
                    if (!reverted || !(pkg.equals(homePkg) || pkg.equals(GOOGLE_SETUP) || pkg.equals(packageOf(dream)))) {
                        wanted.add(pkg);
                        order.add(pkg);
                    }
                }
                for (String pkg : sorted(wanted)) if (!order.contains(pkg)) order.add(pkg);
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                // Off before Free My TV: turned off again if a TV update brings one back, but never handed to
                // Original to turn on.
                List<String> keptOff = stringList(s, "keptOff");
                Set<String> candidates = new HashSet<>();
                for (String pkg : wanted) {
                    if (present.contains(pkg) && !keptOff.contains(pkg) && mayTurnOff(pkg)) candidates.add(pkg);
                }
                Set<String> found = neededByTv(adb, candidates);
                Set<String> needed = found == null ? candidates : found;
                // One an earlier version turned off, that the TV turns out to need: back on (never Google TV's setup
                // app here: with its home screen off, its recovery screen would take Home).
                List<String> back = new ArrayList<>();
                if (found != null) {
                    for (String pkg : sorted(found)) {
                        if (!pkg.equals(GOOGLE_SETUP) && disabledNow.contains(pkg) && disabled.contains(pkg)
                                && adb.shell("pm enable " + pkg).contains("new state: enabled")) {
                            disabled.remove(pkg);
                            disabledNow.remove(pkg);
                            back.add(pkg);
                        }
                    }
                }
                if (!back.isEmpty()) notice(app, Ui.trf("Turned back on {list}: the TV's inputs or remote need it.", "list", String.join(", ", back)));
                // JmLauncher: the HTC home screen goes off only while the Home button opens ours.
                boolean jmOurs = true;
                if (hasJm(adb)) {
                    jmOurs = !reverted && (PKG.equals(jmHome(adb)) || setJmHome(adb, PKG));
                    if (!jmOurs && disabledNow.contains(HTC_HOME) && adb.shell("pm enable " + HTC_HOME).contains("new state: enabled")) {
                        disabled.remove(HTC_HOME);
                        disabledNow.remove(HTC_HOME);
                    }
                }
                boolean setupOn = present.contains(GOOGLE_SETUP) && !disabledNow.contains(GOOGLE_SETUP)
                        && s.optBoolean("setupHome", true);
                for (String pkg : order) {
                    if (!mayTurnOff(pkg) || !present.contains(pkg) || owners.contains(pkg) || wait.contains(pkg)) continue;
                    if (pkg.equals(HTC_HOME) && !jmOurs) continue;
                    if (disabledNow.contains(pkg)) {
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        continue;
                    }
                    if (needed.contains(pkg)) continue;
                    if (pkg.equals(homePkg) && setupOn) {
                        AppLog.w("kept the TV's home screen on: Google TV's setup app is still on");
                        continue;
                    }
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        if (pkg.equals(GOOGLE_SETUP)) setupOn = false;
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("the home screen"));
                }
                // Whole component names: ".ScreensaverAlone" (Android 11) contains ".Screensaver" (12 and newer).
                if (!reverted && !Arrays.asList(adb.shell("settings get secure screensaver_components").trim().split(",")).contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    fixed.add(Ui.tr("the screensaver"));
                }
                record(adb, disabled);
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                save(app, latest);
            } catch (Exception e) {
                notice(app, Ui.trf("Couldn't check Free My TV: {error}", "error", e.getMessage()));
                return;
            }
            if (!fixed.isEmpty()) {
                notice(app, updated ? Ui.trf("The TV update turned back on {list}.", "list", String.join(", ", fixed))
                        : Ui.trf("Turned off what was missing: {list}.", "list", String.join(", ", fixed)));
            } else if (updated) {
                notice(app, Ui.tr("The TV was updated; Free My TV is still on."));
            }
        }
    }

    /**
     * What to turn off, in this order: Google TV's recovery home (when setupHome: the setup app is one of the TV's home
     * screens), the TV's own home screen and screensaver, and the lists above. The setup app goes first: with the home
     * screen off and it still on, its RecoveryActivity takes the Home button, and a restart there factory-reset a Xiaomi
     * (CleanYourTV, MiTV-MOSR4). Android's bare "fallback home" is never turned off.
     */
    private static List<String> targets(String home, String dream, boolean setupHome) {
        List<String> out = new ArrayList<>();
        String homePkg = home.contains("FallbackHome") ? null : packageOf(home);
        if (setupHome) out.add(GOOGLE_SETUP);
        if (homePkg != null && !homePkg.equals(PKG)) out.add(homePkg);
        if (TV_LAUNCHER.equals(homePkg)) out.add(TV_RECOMMENDATIONS);
        String dreamPkg = packageOf(dream);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
        out.addAll(Arrays.asList(DEBLOAT));
        if (Config.DISABLE_ASSISTANT) out.addAll(Arrays.asList(ASSISTANT));
        return out;
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return null;
        String list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return null;
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (holder.matches("[\\w.]+(;[\\w.]+)*")) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        String component = lines[lines.length - 1].trim();
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return component.contains("/") && !component.startsWith("android/") ? packageOf(component) : "";
    }

    /** Whether the Home button goes through JmLauncher (see JM_HOME). */
    private static boolean hasJm(Adb adb) throws IOException {
        return adb.shell("pm path " + JM_LAUNCHER).contains("package:");
    }

    private static String jmHome(Adb adb) throws IOException {
        return adb.shell("getprop " + JM_HOME).trim();
    }

    /** Sets JM_HOME ("" empties it); whether it took. */
    private static boolean setJmHome(Adb adb, String pkg) throws IOException {
        if (!pkg.matches("[\\w.]*")) pkg = "";
        adb.shell("setprop " + JM_HOME + " '" + pkg + "'");
        return pkg.equals(jmHome(adb));
    }

    /** Whether Google TV's setup app is one of the home screens (its RecoveryActivity), so it must go off too. */
    private static boolean setupIsHome(Adb adb) throws IOException {
        return adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").contains(GOOGLE_SETUP + "/");
    }

    /**
     * Apps that saved a Wi-Fi network ("cname" in the Wi-Fi service's list of saved networks), or null when the Wi-Fi
     * service didn't answer (some firmware turns its dumps off): then nobody can tell.
     */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        String out = adb.shell("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'");
        if (!out.contains("cname=") && !out.contains("Wi-Fi is")) return null;
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(out);
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    /** The recorded home screen of the TV, as {@link #realHome}. */
    private static String originalHome(JSONObject s) {
        return realHome(s.optString("originalHome", ""));
    }

    /**
     * Some TVs (TCL, Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private static String realHome(String home) {
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : safeComponent(home);
    }

    private static String originalDream(JSONObject s) {
        return safeComponent(s.optString("originalDream", ""));
    }

    // Nested classes ("Outer$Inner") are valid; every shell command that takes one quotes it, so $ stays literal.
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][\\w.]*/[\\w.$]+");

    private static String safeComponent(String component) {
        return COMPONENT.matcher(component).matches() ? component : "";
    }

    private static String safeComponents(String components) {
        for (String one : components.split(",")) {
            if (safeComponent(one).isEmpty()) return "";
        }
        return components;
    }

    private static void restoreScreensaver(Adb adb, JSONObject s) throws IOException {
        String saver = safeComponents(s.optString("originalScreensaver", ""));
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components");
        else adb.shell("settings put secure screensaver_components '" + saver + "'");
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? safeComponent(d) : "";
    }

    private static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static Adb connect(Context c, JSONObject s) throws IOException {
        busy(c, Ui.tr("Connecting…"));
        Adb adb = Adb.connect(c, 120_000, () -> busy(c, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!s.optBoolean("adbAuthorized")) {
            JSONObject latest = state(c);
            try { latest.put("adbAuthorized", true); } catch (JSONException ignored) {}
            save(c, latest);
        }
        return adb;
    }

    private static void check(String output, String expected, String error) throws IOException {
        if (!output.contains(expected)) throw new IOException(error + " (" + output.trim() + ")");
    }

    // MARK: - Measurements (without opening apps: memory and the home button)

    private static void measureHomeTime(Context c, Adb adb, String mode) throws IOException, JSONException {
        List<Integer> samples = new ArrayList<>();
        Pattern total = Pattern.compile("TotalTime: (\\d+)");
        for (int i = 0; i < 3; i++) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
            SystemClock.sleep(500);
            Matcher m = total.matcher(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"));
            if (m.find()) samples.add(Integer.parseInt(m.group(1)));
            SystemClock.sleep(600);
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
        if (samples.isEmpty()) return;
        Collections.sort(samples);
        putMetric(c, mode, "homeMs", samples.get(samples.size() / 2));
    }

    /** Two minutes after a switch (the home screen settled, caches trimmed). */
    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            if (!mode.equals(state(c).optString("mode"))) return;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                measureMemory(c, adb, mode);
            } catch (Exception e) {
                AppLog.w("memory measurement failed: " + e.getMessage());
            }
        }
    }

    private static void measureMemory(Context c, Adb adb, String mode) throws IOException, JSONException {
        Matcher avail = Pattern.compile("MemAvailable:\\s+(\\d+) kB").matcher(adb.shell("cat /proc/meminfo"));
        if (avail.find()) putMetric(c, mode, "memAvailableMB", Long.parseLong(avail.group(1)) / 1024);
        long pss = 0;
        String home = packageOf(originalHome(state(c)));
        String[] processes = FREE.equals(mode) ? new String[] {PKG + ":home"}
                : home == null ? new String[0] : new String[] {home, home + ":coreservices"};
        Pattern total = Pattern.compile("TOTAL PSS:\\s+(\\d+)");
        for (String p : processes) {
            Matcher m = total.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) pss += Long.parseLong(m.group(1));
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024);
    }

    private static void putMetric(Context c, String mode, String key, long value) throws JSONException {
        JSONObject s = state(c);
        JSONObject metrics = s.optJSONObject("metrics");
        if (metrics == null) metrics = new JSONObject();
        JSONObject m = metrics.optJSONObject(mode);
        if (m == null) m = new JSONObject();
        m.put(key, value).put("at", System.currentTimeMillis());
        metrics.put(mode, m);
        s.put("metrics", metrics);
        save(c, s);
    }

    private static JSONObject metrics(JSONObject s, String mode) {
        JSONObject metrics = s.optJSONObject("metrics");
        return metrics == null ? null : metrics.optJSONObject(mode);
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void busy(Context c, String text) {
        JSONObject s = state(c);
        try { s.put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).put("error", ""); } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }

    // MARK: - Helpers

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /**
     * What Free My TV turned off, kept in the TV's own settings too: an uninstall or Clear data erases our state file,
     * and a new install must still tell its own work from what the person had turned off (#142).
     */
    private static final String RECORD = "free_my_tv_disabled";

    private static Set<String> recorded(Adb adb) {
        Set<String> out = new HashSet<>();
        try {
            String v = adb.shell("settings get global " + RECORD).trim();
            if (!v.isEmpty() && !v.equals("null")) for (String pkg : v.split(",")) if (pkg.matches("[\\w.]+")) out.add(pkg);
        } catch (IOException e) {
            AppLog.w("could not read the record of what we turned off: " + e.getMessage());
        }
        return out;
    }

    private static void record(Adb adb, Set<String> disabled) {
        List<String> list = new ArrayList<>();
        for (String pkg : sorted(disabled)) if (pkg.matches("[\\w.]+")) list.add(pkg);
        try {
            adb.shell(list.isEmpty() ? "settings delete global " + RECORD
                    : "settings put global " + RECORD + " " + String.join(",", list));
        } catch (IOException e) {
            AppLog.w("could not save the record of what we turned off: " + e.getMessage());
        }
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}
