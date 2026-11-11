package io.freemytv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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

    /** The application id the APK is built under; build.sh --pkg makes a build that lives next to another one. */
    static final String PKG = Config.PACKAGE;
    /** The classes' package: the same as PKG unless --pkg renamed the manifest. */
    static final String JAVA = "io.freemytv";
    static final String HOME_ACTIVITY = PKG + "/" + JAVA + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + JAVA + ".SettingsActivity";
    /** Our screensaver: on Android 11 to 12L the copy that runs in the main process (ScreensaverAlone, #10). */
    private static final boolean DREAM_ALONE = Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT <= 32;
    static final String DREAM = PKG + "/" + JAVA + (DREAM_ALONE ? ".ScreensaverAlone" : ".Screensaver");
    /** The other copy: an update (ours or the TV's) moves the setting from it, without a notice. */
    private static final String OTHER_DREAM = PKG + "/" + JAVA + (DREAM_ALONE ? ".Screensaver" : ".ScreensaverAlone");
    static final String FREE = "free", ORIGINAL = "original";

    /**
     * Fire TV (Fire OS): Amazon's launcher keeps the Home button (`priority=950` on `CATEGORY_HOME`), so the switch
     * refuses Free My TV mode while Original and the watchdog still run.
     */
    static final boolean AMAZON = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) || "Amazon".equalsIgnoreCase(Build.BRAND);

    /**
     * Where switching the home screen kept a TV from starting again, so the switch refuses Free My TV mode and a TV
     * already in it goes back to Original. Kalley 43G200 turns Google TV's setup app back on at every start: without its
     * home screen the next start looped at the logo, and that TV only came back with a USB reflash (CleanYourTV,
     * examples/kalley-43g200). A Huawei TV (HarmonyOS) stopped at its logo at the first restart after the switch, and
     * only a factory reset brought it back (10-29).
     */
    static final boolean BRICKS = "R3_GTV".equals(Build.DEVICE) || huawei();

    /** Huawei's TVs and boxes, and anything on HarmonyOS (Huawei's BuildEx says so). */
    private static boolean huawei() {
        if ("HUAWEI".equalsIgnoreCase(Build.MANUFACTURER) || "HUAWEI".equalsIgnoreCase(Build.BRAND)) return true;
        try {
            Object brand = Class.forName("com.huawei.system.BuildEx").getMethod("getOsBrand").invoke(null);
            return "harmony".equalsIgnoreCase(String.valueOf(brand));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {   // not there: not HarmonyOS
            return false;
        }
    }

    /**
     * A phone, not a TV (telephony, and neither Android TV's features nor the TV mode): the APK was installed in the
     * wrong place. The settings say so, and the switch refuses: a TV home screen on a phone answers no touch.
     */
    static boolean phone(Context c) {
        PackageManager pm = c.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return false;
        UiModeManager mode = c.getSystemService(UiModeManager.class);
        if (mode != null && mode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return false;
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /** What the settings and the refused switch say on a phone. */
    static String phoneText() {
        return Ui.tr("Free My TV is a TV app: install this APK on your Android TV or Google TV (freemytv.io), not on a phone.");
    }

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
        // Sony BRAVIA (BRAVIA_UR3, MediaTek, Android TV 10 tested; CleanYourTV's examples/sony-bravia-4k-ur3 list,
        // reboot-checked there on four sets)
        "tv.samba.ssm",                                                               // Samba TV ACR: watches what's on screen
        "com.qterics.da.product", "com.sony.dtv.da.service",                          // device agents (remote support)
        "com.sonyericsson.idd.agent", "com.sony.dtv.sonybugreportsys",                // usage data, bug-report uploader
        "com.sony.dtv.usercontext",                                                   // viewing context for recommendations
        "com.sony.dtv.promos", "com.sony.dtv.sonyselect",                             // promo apps
        "com.sony.dtv.demosupport", "com.sony.dtv.demosystemsupport", "com.sony.dtv.multiscreendemo",   // store demo
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
        // Hotack X1BR projector (LuminaOS, Android 14): its home, settings, Wi-Fi, Bluetooth and keystone in one (#342)
        "com.htc.luminaos",
        "com.tcl.channelplus",                                                        // the channel source TCL's Live TV tunes (off: black screen)
        "com.tcl.waterfall.overseas",                                                 // TCL Channel: partnercustomizer's entry and key open it
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
        // Xiaomi (MediaTek): system service, remote pairing, live TV, quick settings, child lock, updates, casting, HDMI player (MIUI TV)
        "mitv.service", "com.mi.bluetooth", "com.mitv.livetv.ci_mediatek", "com.xiaomi.tvqs.overseas",
        "com.mitv.tvlock", "com.xiaomi.mitv.updateservice", "com.xiaomi.android.tvsetup.partnercustomizer",
        "com.mitv.overseaservice", "com.xiaomi.mitv.tvmanager", "com.xiaomi.wfdsinkhelperservice",
        "com.mitv.milinkservice", "com.xiaomi.mitv.smartshare", "android.autoinstalls.config.xiaomi.amelie",
        "com.xiaomi.mitv.tvplayer",
        // Skyworth (MediaTek): live TV, remote pairing and keys, setup, service menu, casting, OLED care, boot
        "com.smartdevice.livetv", "com.smartdevice.livetv.overlay_SANLUX", "com.smartdevice.bleconnectservice",
        "com.smartdevice.duplebluetooth", "com.smartdevice.myapp", "com.smartdevice.setupwraith",
        "com.smartdevice.aftersale", "com.smartdevice.factory", "com.smartdevice.dailymanage",
        "com.smartdevice.global.service", "com.smartdevice.dailyshortcut", "com.smartdevice.service", // remote, quick settings, system
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
        // Sony BRAVIA (MediaTek, Android TV 10): inputs and tuner, TV API and live TV, system and vendor services
        // (the remote's keys), settings, Bravia Sync (HDMI-CEC), picture and sound, updates, IP control, AirPlay and
        // HomeKit with the Vewd core (persistent: AirPlay crash-loops without it, CleanYourTV's UR3 list)
        "com.sony.dtv.tvinput.external", "com.sony.dtv.tvinput.hardwaremanager", "com.sony.dtv.tvinput.provider",
        "com.sony.dtv.tvinput.tuner", "com.sony.dtv.tvapi", "com.sony.dtv.tvx", "com.sony.dtv.sonysystemservice",
        "com.sony.dtv.sonyvendorservice", "com.sony.dtv.settingsprovider", "com.sony.dtv.braviasyncmenu",
        "com.sony.dtv.braviasyncservice", "com.sony.dtv.braviasyncsetting", "com.sony.dtv.panelsettings",
        "com.sony.dtv.audiooutput", "com.sony.dtv.acousticautocalibration", "com.sony.dtv.imeproxy",
        "com.sony.dtv.update", "com.sony.dtv.systemupdate", "com.sony.dtv.oadservice", "com.sony.dtv.otaurlprovisioning",
        "com.sony.dtv.upgradesetup", "com.sony.dtv.webapi.core", "com.sony.dtv.airplayapp", "com.sony.dtv.homekit",
        "com.vewd.core.service",
        // Wanbo projectors (T2 Ultra: a Skyworth board, Amlogic, Android TV 11): remote pairing and reconnect, Amlogic
        // overlay, settings and customization, projector service, Skyworth and OEM services, factory and QA tests
        "com.droidlogic.ble", "com.droidlogic.overlay", "com.mft.rcupair", "com.mtf.blereconnect", "com.mtf.customization",
        "com.mtf.tvsettingadapt", "com.sdt.projector.observer", "com.skyworth.superservice", "com.skyworth.smallclient",
        "com.oemservice", "com.oem.qa", "com.mft.producttest", "android.autoinstalls.config.AMLSTBAndroid.device",
        // HY300 projectors (Hotack, Allwinner H713; Amlogic txhd2): keystone and projection settings, the HDMI player,
        // Miracast receiver
        "com.htc.htcsettings", "com.softwinner.awlivetv", "com.softwinner.miracastReceiver", "com.softwinner.tcorrection",
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

    /** A factory or service menu: never turned off, and never listed with the TV's own apps (HomeApps.launchable). */
    static boolean factoryMenu(String pkg) {
        return FACTORY.matcher(pkg).matches();
    }

    /** Amazon's streaming apps, not part of the system on any TV: Recent Apps may close them (mayTurnOff still refuses them). */
    private static final Set<String> AMAZON_APPS = new HashSet<>(Arrays.asList(
            "com.amazon.amazonvideo.livingroom", "com.amazon.music.tv"));

    /** Recent Apps' ▲: what may be turned off, and Amazon's streaming apps, which only stop. */
    static boolean mayStop(String pkg) {
        return mayTurnOff(pkg) || AMAZON_APPS.contains(pkg) && !PROTECTED.contains(pkg);
    }

    /** Checked before every disable: never the protected list, Amazon's packages, Android's own pieces or a factory menu. */
    static boolean mayTurnOff(String pkg) {
        return !PROTECTED.contains(pkg) && !pkg.startsWith("com.amazon.")
                && !CORE.matcher(pkg).matches() && !FACTORY.matcher(pkg).matches();
    }

    /**
     * Of these, the ones the TV still needs, whatever they look like: the software under its inputs (they ask for
     * TV_INPUT_HARDWARE; off, HDMI shows "No signal" and only a full restore brought it back, CleanYourTV #321), and
     * the receiver of the remote's Input key and Bluetooth pairing (fusion.android.tv.demo on several MediaTek boards).
     * null when the check didn't finish: then none of them goes off this time.
     */
    static Set<String> neededByTv(Adb adb, Set<String> pkgs) throws IOException {
        Set<String> keep = new HashSet<>();
        if (pkgs.isEmpty()) return keep;
        // Only the package's own requested permissions: a shared user's would match by mistake. A line per package keeps
        // the connection's read timeout from running out on a slow TV. A dump that doesn't name the package counts as
        // "couldn't check": after a hang Android turns every dumpsys off ("Service dumps disabled due to hung system
        // process.", #246), and an empty answer must not read as "nothing needed".
        String out = adb.shell("for p in " + String.join(" ", sorted(pkgs)) + "; do d=\"$(dumpsys package $p)\"; "
                + "if ! printf '%s\\n' \"$d\" | grep -q -F \"Package [$p]\"; then echo \"unknown $p\"; "
                + "elif printf '%s\\n' \"$d\" | sed -n -r '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p'"
                + " | grep -q TV_INPUT_HARDWARE || printf '%s\\n' \"$d\" | grep -q -E 'TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory';"
                + " then echo \"keep $p\"; else echo \"- $p\"; fi; done; echo checked");
        if (!out.contains("checked") || out.contains("unknown ")) return null;
        Matcher m = Pattern.compile("keep ([\\w.]+)").matcher(out);
        while (m.find()) keep.add(m.group(1));
        if (!keep.isEmpty()) AppLog.w("kept on, the TV's inputs or remote need them: " + String.join(", ", sorted(keep)));
        return keep;
    }

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this); the stamp tells when it started (ModeJob.started). */
    static long request(Context c, String mode) {
        // Back to Free My TV before Original's revoke ran: it would stop the app mid-switch, and isn't wanted any more.
        if (FREE.equals(mode)) ModeJob.cancel(c, ModeJob.REVOKE_WATCH);
        return ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
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
        Prefs.reload();
        if ("home".equals(Prefs.powerOn())) ModeJob.schedule(c, ModeJob.POWER_ON, null, 10_000);
    }

    // MARK: - At power-on (Home Settings, #396 and #346): only after a full start, never when the TV wakes from standby

    private static File homeShownFile(Context c) {
        return new File(c.getFilesDir(), "home-shown");
    }

    private static int bootCount(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    /**
     * The home screen came up (:home, HomeActivity). Keeps the uptime of the first time in this start, the moment a key
     * would have to come after to count as the person's. True when this was that first time.
     */
    static boolean noteHomeShown(Context c) {
        int boot = bootCount(c);
        if (boot < 0 || homeShownAt(c) != 0) return false;
        try (FileOutputStream out = new FileOutputStream(homeShownFile(c))) {
            out.write((boot + " " + SystemClock.uptimeMillis()).getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** When the home screen first came up in this start (uptime), or 0. */
    private static long homeShownAt(Context c) {
        try (FileInputStream in = new FileInputStream(homeShownFile(c))) {
            byte[] data = new byte[48];
            int n = in.read(data);
            String[] saved = n > 0 ? new String(data, 0, n, StandardCharsets.US_ASCII).trim().split(" ") : new String[0];
            return saved.length == 2 && saved[0].equals(String.valueOf(bootCount(c))) ? Long.parseLong(saved[1]) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static final Pattern UPTIME = Pattern.compile("^(\\d+)(\\s|$)");
    private static final Pattern RESUMED = Pattern.compile("u\\d+ ([\\w.]+)/");

    /**
     * The power manager's last user activity (uptime ms, "310962 (5000 ms ago)"; the latest of its display groups), or
     * MAX_VALUE where it reads otherwise: then nothing counts as untouched.
     */
    private static long lastUserActivity(Adb adb) throws IOException {
        long last = -1;
        for (String line : adb.shell("dumpsys power | grep -i 'lastUserActivityTime='").split("\n")) {
            int at = line.indexOf('=');
            if (at < 0) continue;
            Matcher m = UPTIME.matcher(line.substring(at + 1).trim());
            if (!m.find()) return Long.MAX_VALUE;
            last = Math.max(last, Long.parseLong(m.group(1)));
        }
        return last < 0 ? Long.MAX_VALUE : last;
    }

    /**
     * The package of the activity in front, or null. Android 9 to 11 write "mResumedActivity:", 12 and 13
     * "topResumedActivity=", 14 "Resumed:" (top first); all of them "ResumedActivity:" too.
     */
    private static String resumedPackage(Adb adb) throws IOException {
        Matcher m = RESUMED.matcher(adb.shell("dumpsys activity activities | grep -m 3 -E 'ResumedActivity|Resumed:'"));
        return m.find() ? m.group(1) : null;
    }

    /**
     * At power-on "Home screen": some MediaTek TV centers put themselves on top about a second after the home screen
     * comes up at boot ("Please scan channels" where there's no antenna, #396). Gives the screen back to ours, but only
     * while no key has been pressed since our home screen first came up, and only in the first 4 minutes.
     */
    static void powerOnNow(Context app) {
        Prefs.reload();
        if (!"home".equals(Prefs.powerOn())) return;
        List<String> tvApps = Arrays.asList(HomeApps.LIVE_TV_APPS);
        for (int i = 0; i < 6 && SystemClock.elapsedRealtime() < 240_000; i++) {
            if (i > 0) SystemClock.sleep(10_000);
            long shown = homeShownAt(app);
            if (shown == 0) continue;   // our home screen isn't up yet
            synchronized (RUN) {   // never in the middle of a switch or the boot check's changes
                JSONObject s = state(app);
                if (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")) return;
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    if (lastUserActivity(adb) >= shown) return;   // a key since: what's in front is their choice
                    String front = resumedPackage(adb);
                    if (front != null && tvApps.contains(front)) {
                        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                        AppLog.w("at power-on: " + front + " took the screen before any key, back to the home screen");
                        return;
                    }
                } catch (IOException e) {
                    AppLog.w("at power-on: " + e.getMessage());
                }
            }
        }
    }

    /** After an update of this app: complete Free My TV mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    /**
     * The remote's app buttons (RemoteKeys) need an accessibility service that only filters those keys. It is on only
     * on TCL, Vestel, KTC, Sony, Xiaomi and Hisense TVs, the JVC G24, Formovie projectors, Chromecast with Google TV
     * (Sabrina, YouTube only) and the Google TV Streamer in Free My TV mode; Original turns it off. Android turns it
     * off when this app is updated, so the boot check and an update turn it back on. Changing Remote Buttons asks
     * again. It goes off with every button on Nothing (or the brand's default), and on TCL's Android 11
     * (RemoteKeys.serviceWanted).
     */
    static void ensureRemoteService(Context c, long delayMs) {
        if (RemoteKeys.supported()) ModeJob.schedule(c, ModeJob.REMOTE, null, delayMs);
    }

    static void ensureRemoteServiceNow(Context app) {
        if (!RemoteKeys.supported() || !FREE.equals(state(app).optString("mode"))) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            remoteService(app, adb, RemoteKeys.serviceWanted());
        } catch (IOException e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    private static final String REMOTE_SERVICE = PKG + "/" + JAVA + ".RemoteKeyService";
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

    private static final String NOW_PLAYING = PKG + "/" + JAVA + ".NowPlaying";

    /**
     * Home Settings' Now Playing widget reads what apps play through our notification listener, which could read every
     * notification (ours never does): it's allowed only while the widget is on, in Free My TV mode, and taken back when
     * the widget goes off, by Original and by the watchdog's revert. Asked when the widget changes and by a screen that
     * finds it on but not allowed; this process sets it to what the widget says at that moment, so the order the
     * requests run in doesn't matter.
     */
    static void nowPlayingAccess(Context c) {
        ModeJob.schedule(c, ModeJob.PLAYING, null, 0);
    }

    static void nowPlayingAccessNow(Context app) {
        synchronized (RUN) {   // after a switch or a revert, never in the middle of one
            JSONObject s = state(app);
            if (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")) return;   // they took it back
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                nowPlaying(app, adb, nowPlayingOn());
            } catch (IOException e) {
                AppLog.w("now playing: " + e.getMessage());   // ADB didn't answer: asked again next time, not refused
            }
        }
    }

    /** The widget as Home Settings (:home) saved it. */
    private static boolean nowPlayingOn() {
        Prefs.reload();
        return Prefs.nowPlaying();
    }

    /** Allows our listener and proves it took (refused, the widget's row shows Unavailable), or takes it back. */
    private static void nowPlaying(Context c, Adb adb, boolean on) throws IOException {
        String out = adb.shell("cmd notification " + (on ? "allow_listener " : "disallow_listener ") + NOW_PLAYING).trim();
        AppLog.w("now playing " + (on ? "on" : "off") + (out.isEmpty() ? "" : ": " + out));
        if (!on) {
            Capabilities.forget(c, Capabilities.LISTENER);   // tried again when it's next turned on
            return;
        }
        boolean works = NowPlaying.allowed(c);
        for (int i = 0; i < 5 && !works; i++) {   // the system can take a moment to see it
            SystemClock.sleep(200);
            works = NowPlaying.allowed(c);
        }
        Capabilities.prove(c, Capabilities.LISTENER, works);
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
        if (BRICKS) {
            done(c, bricksText());
            return;
        }
        if (phone(c)) {
            done(c, phoneText());
            return;
        }
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                if (found == null) {
                    // No holder to read (Android 8 and 9 have no `cmd role`, and the chooser answers until a default is
                    // set), or it's ours: the TV's own, when it has exactly one (#453).
                    found = stockHomeAtSwitch(adb, s);
                    // Its Home button can't be timed now, nor at a retry: HOME opens the chooser or ours (Original times it).
                    if (found != null && metrics(state(c), ORIGINAL) == null) putMetric(c, ORIGINAL, "at", System.currentTimeMillis());
                }
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
            // back. The TV's first setup usually saved the network (or its home screen did, which goes off too):
            // then stop here, before anything changes.
            Set<String> owners = wifiOwners(adb);
            if (owners == null) {
                // Nobody can tell who saved the networks: on Wi-Fi, stop before anything changes.
                if (!"0".equals(adb.shell("settings get global wifi_on").trim())) {
                    throw new IOException(Ui.tr("I couldn't check which app saved the Wi-Fi, so nothing changed. Try again in a minute."));
                }
                owners = new HashSet<>();
            }
            // By name, for adb logcat: the message only says whether it was the setup app or the home screen (#411).
            if (!owners.isEmpty()) AppLog.w("Wi-Fi networks saved by " + String.join(", ", sorted(owners)));
            boolean setupHome = setupIsHome(adb);
            if (setupHome && owners.contains(GOOGLE_SETUP)) {
                throw new IOException(Ui.tr("the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more."));
            }
            if (owners.contains(packageOf(home))) {
                throw new IOException(Ui.tr("the TV's home screen saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more."));
            }
            if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                putMetric(c, ORIGINAL, "at", System.currentTimeMillis());   // first: a retry after TCL's TGuard killed the job skips it (#277)
                busy(c, Ui.tr("Measuring the original home screen…"));
                measure(c, adb, ORIGINAL, true);
            }
            busy(c, Capabilities.screensaver(c) ? Ui.tr("Switching the home screen and screensaver…")
                    : Ui.tr("Switching the home screen…"));
            Watchdog.clear(c);
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("Android didn't accept the new home screen"));
            if (Config.SCREENSAVER) adb.shell("settings put secure screensaver_components " + DREAM);
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
            Set<String> offNow = packages(adb, "-d");
            // A reinstall starts from a fresh state: only the TV's record knows what an older version turned off that's
            // no longer on the list. One that won't turn on stays recorded, so Original tries again.
            disabled.addAll(offTheList(adb, ours, offNow, packageOf(home)));
            for (String pkg : offNow) {
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
                if (tvHome(pkg, homePkg) && setupOn) {
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
                    remoteService(c, adb, RemoteKeys.serviceWanted());
                } catch (IOException e) {
                    AppLog.w("remote buttons service: " + e.getMessage());
                }
            }
            wakeWatch(adb, true);
            notice(c, Capabilities.screensaver(c)
                    ? Ui.trf("Free My TV is on: new home screen and screensaver, {n} apps turned off.", "n", disabled.size())
                    : Ui.trf("Free My TV is on: new home screen, {n} apps turned off.", "n", disabled.size()));
            busy(c, Ui.tr("Measuring Free My TV…"));
            measure(c, adb, FREE, false);
            done(c, null);
            homeAfter(adb);
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000);
        } catch (Exception e) {
            done(c, Ui.trf("Couldn't turn on Free My TV: {error}", "error", e.getMessage()));
        }
    }

    static String bricksText() {
        return Ui.tr("On this TV model, switching the home screen kept a TV from starting. Free My TV stays an app here: open it from your apps.");
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
            wakeWatch(adb, false);
            SleepTimer.stop(c);
            adb.shell("appops set " + PKG + " SYSTEM_ALERT_WINDOW default");   // SleepOverlay's, if it was allowed
            Capabilities.forget(c, Capabilities.OVERLAY);                       // asked again next time
            try {
                nowPlaying(c, adb, false);
            } catch (IOException e) {
                AppLog.w("now playing: " + e.getMessage());
            }
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
            measure(c, adb, ORIGINAL, false);
            done(c, null);
            homeAfter(adb);
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000);
            if (WatchNext.allowed(c)) ModeJob.schedule(c, ModeJob.REVOKE_WATCH, null, 5_000);
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

    /**
     * {@link #stockHome} for a switch that can't read HOME's holder. Not where Google TV's home is installed (Original
     * takes that one), nor while an earlier switch's work is still off, after a reinstall: the home screen it turned off
     * is no candidate then, and a lone vendor app that opens HOME would be taken for it.
     */
    private static String stockHomeAtSwitch(Adb adb, JSONObject s) throws IOException {
        if (adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) return null;
        Set<String> earlier = recorded(adb);
        earlier.addAll(stringList(s, "disabled"));
        earlier.retainAll(packages(adb, "-d"));
        return earlier.isEmpty() ? stockHome(adb) : null;
    }

    /**
     * {@link #stockHome}, recorded as the original home screen for a switch that couldn't read it; or "". Only Google's
     * launcher here: this check runs by itself, and on a TV switched earlier the real home screen may be off, leaving a
     * lone vendor app that opens HOME. Any other home screen is recorded by going through Original once.
     */
    private static String recordStockHome(Context c, Adb adb) throws IOException, JSONException {
        if (adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) return "";
        String stock = stockHome(adb);
        if (stock == null || !TV_LAUNCHER.equals(packageOf(stock))) return "";
        String home = realHome(stock);
        save(c, state(c).put("originalHome", home));
        AppLog.w("original home screen recorded at the check: " + home);
        return home;
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
                // Google TV's home too, where the switch turned it off along with another launcher (targets).
                String googleHome = packageOf(GOOGLE_HOME);
                if (stringList(s, "disabled").contains(googleHome) && !keptOff.contains(googleHome)) adb.shell("pm enable " + googleHome);
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
                    if (!pkg.equals(GOOGLE_SETUP) && !tvHome(pkg, homePkg) && !pkg.equals(dreamPkg) && !(jm && pkg.equals(HTC_HOME))) {
                        kept.put(pkg);
                        stillOff.add(pkg);
                    }
                }
                record(adb, stillOff);
                // The screensaver first: if that fails, the revert isn't marked done and the next try does it again.
                restoreScreensaver(adb, s);
                wakeWatch(adb, false);
                SleepTimer.stop(app);   // its row shows Unavailable from now on
                try {
                    nowPlaying(app, adb, false);   // and so does Now Playing's
                } catch (IOException e) {
                    AppLog.w("now playing: " + e.getMessage());
                }
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
            if (BRICKS) {
                // Switched by a version that still allowed it here: the TV's own home screen back before the next restart.
                AppLog.w("Free My TV mode where the switch kept a TV from starting: back to Original");
                applyOriginal(app);
                notice(app, bricksText());
                return;
            }
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                try {   // as the widget says: a TV update can take it back, and a take-back can have failed
                    nowPlaying(app, adb, !s.optBoolean("homeReverted") && nowPlayingOn());
                } catch (IOException e) {
                    AppLog.w("now playing: " + e.getMessage());
                }
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                Set<String> owners = wifiOwners(adb);
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                // Switched without the TV's home screen recorded (Android 8 and 9, #453): recorded now, so it goes
                // off here as at the switch, and Original turns it back on.
                if (home.isEmpty() && !reverted) home = recordStockHome(app, adb);
                String homePkg = packageOf(home);
                // Who saved the Wi-Fi unknown: the setup app and the home screen wait for the next check.
                Set<String> wait = new HashSet<>();
                if (owners == null) {
                    owners = new HashSet<>();
                    wait.add(GOOGLE_SETUP);
                    if (homePkg != null) wait.add(homePkg);
                    wait.add(packageOf(GOOGLE_HOME));   // a home screen too (targets)
                }
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                String dream = originalDream(s);
                // In the switch's order (Google TV's setup app before its home screen), then the rest.
                List<String> order = new ArrayList<>();
                for (String pkg : targets(home, dream, s.optBoolean("setupHome", true))) {
                    if (!reverted || !(tvHome(pkg, homePkg) || pkg.equals(GOOGLE_SETUP) || pkg.equals(packageOf(dream)))) {
                        wanted.add(pkg);
                        order.add(pkg);
                    }
                }
                for (String pkg : sorted(wanted)) if (!order.contains(pkg)) order.add(pkg);
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                Set<String> gone = new HashSet<>(disabled);
                offTheList(adb, disabled, disabledNow, homePkg);
                gone.removeAll(disabled);
                wanted.removeAll(gone);
                order.removeAll(gone);
                // screensaver=false after a switch with ours: the TV's own screensaver app comes back on.
                String dreamPkg = packageOf(dream);
                if (!Config.SCREENSAVER && dreamPkg != null && disabled.remove(dreamPkg)) {
                    wanted.remove(dreamPkg);
                    order.remove(dreamPkg);
                    if (disabledNow.remove(dreamPkg)) adb.shell("pm enable " + dreamPkg);
                }
                // Off before Free My TV: turned off again if a TV update brings one back, but never handed to
                // Original to turn on.
                List<String> keptOff = stringList(s, "keptOff");
                // Switched before Google TV's home was a target beside another launcher: off now and not by us, the
                // person turned it off (their launcher needs that), so it's theirs and Original leaves it off.
                String googleHome = packageOf(GOOGLE_HOME);
                boolean theirs = !googleHome.equals(homePkg) && disabledNow.contains(googleHome)
                        && !disabled.contains(googleHome) && !keptOff.contains(googleHome);
                if (theirs) keptOff.add(googleHome);
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
                    if (tvHome(pkg, homePkg) && setupOn) {
                        AppLog.w("kept the TV's home screen on: Google TV's setup app is still on");
                        continue;
                    }
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        if (pkg.equals(GOOGLE_SETUP)) setupOn = false;
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                // The switch's own rule, at every check: the TV's home screen stays on while Google TV's setup app is
                // on. With the home off, the setup app's recovery screen takes Home, and a TV that turns the setup app
                // back on at every start may not start at all (#269). Versions before 1.2 could leave it that way.
                for (String tv : new String[] {homePkg, packageOf(GOOGLE_HOME)}) {
                    if (setupOn && tv != null && !tv.equals(PKG) && disabledNow.contains(tv)
                            && adb.shell("pm enable " + tv).contains("new state: enabled")) {
                        disabled.remove(tv);
                        disabledNow.remove(tv);
                        AppLog.w("turned the TV's home screen back on: Google TV's setup app is on");
                    }
                }
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("the home screen"));
                }
                wakeWatch(adb, !reverted);   // again after a restart, which ends it (running already: left alone)
                // Whole component names: ".ScreensaverAlone" (Android 11 to 12L) contains ".Screensaver" (the others).
                List<String> savers = Arrays.asList(adb.shell("settings get secure screensaver_components").trim().split(","));
                if (Config.SCREENSAVER && !reverted && !savers.contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    if (!savers.contains(OTHER_DREAM)) fixed.add(Ui.tr("the screensaver"));   // ours, moved: not the TV
                } else if (!Config.SCREENSAVER && !reverted && (savers.contains(DREAM) || savers.contains(OTHER_DREAM))) {
                    restoreScreensaver(adb, s);   // screensaver=false since the switch: the TV's own again
                }
                record(adb, disabled);
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                if (theirs) latest.put("keptOff", new JSONArray(keptOff));
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
        // Another launcher was the home screen (Projectivy on a Chromecast): Google TV's own is still there, and its
        // higher priority takes the Home button from ours (packages the TV lacks are skipped).
        String googleHome = packageOf(GOOGLE_HOME);
        if (!googleHome.equals(homePkg)) out.add(googleHome);
        if (TV_LAUNCHER.equals(homePkg)) out.add(TV_RECOMMENDATIONS);
        String dreamPkg = packageOf(dream);
        // screensaver=false keeps the TV's own screensaver: then it stays on.
        if (Config.SCREENSAVER && dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
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

    // MARK: - Hikeen boards: back from standby (#367)

    /**
     * Realtek TVs on Hikeen boards (Romsat, KIVI; Android 9) force-stop the app in front as they go to standby, from a
     * hook in ActivityManager (its `mLastPackageName`): that finishes our home screen, and the TV wakes on Realtek's
     * black FallbackHome until Home is pressed. The force-stop also cancels the app's jobs and alarms, so nothing in the
     * app can answer. This loop runs in adb's shell instead (uid 2000: the hook and com.hikeen.cleanmaster leave it
     * alone): at each "Unblocked screen on" in the system log it looks for FallbackHome in front for 3 seconds, and only
     * then opens the default home screen, so an input or an app the TV wakes into stays. The switch and the boot check
     * start it (a restart ends it); Original and the watchdog's revert stop it. Its name is in its command lines, so
     * pgrep and pkill find only it: the board runs a root logcat of its own, which pkill may not touch.
     */
    private static final String WAKE = "freemytv-wake";
    /** Matches WAKE but not the command line that asks for it ("[e]" isn't "e"). */
    private static final String WAKE_MATCH = "'freemytv-wak[e]'";
    private static final String WAKE_LOOP = "trap \"\" HUP; logcat -b system -T 1 -s DisplayPowerController:I " + WAKE + ":S"
            + " | while read -r line; do case \"$line\" in *\"Unblocked screen on\"*) for i in 1 2 3; do sleep 1;"
            + " if dumpsys activity activities | grep ResumedActivity | grep -q FallbackHome; then"
            + " am start -a android.intent.action.MAIN -c android.intent.category.HOME; break; fi; done;; esac; done";

    /** Starts the loop on a Hikeen board, once, or stops it. Only logs: what called it goes on either way. */
    private static void wakeWatch(Adb adb, boolean on) {
        try {
            if (!on) {
                if (!wakeRunning(adb)) return;
                adb.shell("pkill -f " + WAKE_MATCH);
                AppLog.w("wake watch off");
                return;
            }
            if (!adb.shell("pm list packages com.hikeen.").contains("package:com.hikeen.") || wakeRunning(adb)) return;
            // HUP ignored from the start: when this shell ends, the kernel sends one to what it started, and the loop
            // isn't in a session of its own until setsid runs.
            adb.shell("trap \"\" HUP; setsid sh -c '" + WAKE_LOOP + "' " + WAKE + " </dev/null >/dev/null 2>&1 &");
            AppLog.w(wakeRunning(adb) ? "wake watch on: the home screen comes back after standby (#367)" : "wake watch didn't start");
        } catch (IOException e) {
            AppLog.w("wake watch: " + e.getMessage());
        }
    }

    /** Only process numbers count: a TV without pgrep answers with an error. */
    private static boolean wakeRunning(Adb adb) throws IOException {
        return adb.shell("pgrep -f " + WAKE_MATCH).trim().matches("\\d+(\\s+\\d+)*");
    }

    // MARK: - Continue Watching's permission

    /**
     * Continue Watching reads Android TV's Watch Next list, which needs READ_TV_LISTINGS. It is granted only when you
     * pick Continue Watching (Home Settings asks this from :home), through the TV's own ADB so the TV doesn't ask;
     * if that fails, Home Settings falls back to Android's own permission dialog.
     */
    static void requestWatchGrant(Context c) {
        ModeJob.schedule(c, ModeJob.GRANT_WATCH, null, 0);
    }

    static void grantWatchNow(Context app) {
        if (WatchNext.allowed(app)) return;
        try (Adb adb = Adb.connect(app, 8_000, null)) {
            adb.shell("pm grant " + PKG + " " + WatchNext.PERMISSION);
            AppLog.w("continue watching permission " + (WatchNext.allowed(app) ? "granted" : "not granted"));
        } catch (IOException e) {
            AppLog.w("continue watching permission: " + e.getMessage());
        }
    }

    /**
     * Original takes the permission back. Android stops the app's processes when a permission is revoked, so this runs
     * as its own job once the switch is over (if the job is run again after that, it finds nothing left to revoke). It
     * holds the switch lock, so a switch never runs under it, and does nothing if Free My TV is back by then.
     */
    static void revokeWatchNow(Context app) {
        synchronized (RUN) {
            if (!WatchNext.allowed(app) || !ORIGINAL.equals(state(app).optString("mode", ORIGINAL))) return;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                AppLog.w("continue watching permission: revoking");
                adb.shell("pm revoke " + PKG + " " + WatchNext.PERMISSION);
            } catch (IOException e) {
                AppLog.w("continue watching permission: " + e.getMessage());
            }
        }
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
        // screensaver=false: the TV kept its own, so only ours (set before that) gives way, not one picked meanwhile.
        if (!Config.SCREENSAVER && !adb.shell("settings get secure screensaver_components").contains(PKG + "/")) return;
        String saver = safeComponents(s.optString("originalScreensaver", ""));
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components");
        else adb.shell("settings put secure screensaver_components '" + saver + "'");
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? safeComponent(d) : "";
    }

    /** The recorded home screen or Google TV's: both stay on while Google TV's setup app is on, and after a revert. */
    private static boolean tvHome(String pkg, String homePkg) {
        return pkg.equals(homePkg) || pkg.equals(packageOf(GOOGLE_HOME));
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

    /** A failed measurement only logs: the switch itself went through (or is about to), and a TV may cut it short
     *  (a TCL's TGuard kills the job once the original launcher is in front, and the error read as a failed switch, #277).
     *  The memory after the timing (the home screen just in front, as Free My TV's is when it's measured), but on its
     *  own: a timing that fails no longer leaves both memory rows empty. */
    private static void measure(Context c, Adb adb, String mode, boolean memory) {
        try {
            measureHomeTime(c, adb, mode);
        } catch (Exception e) {
            AppLog.w("measurement of " + mode + " failed: " + e.getMessage());
        }
        if (!memory) return;
        try {
            measureMemory(c, adb, mode);
        } catch (Exception e) {
            AppLog.w("memory measurement of " + mode + " failed: " + e.getMessage());
        }
    }

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

    /**
     * Two minutes after a switch (the home screen settled, caches trimmed), and again when the settings close without
     * it. Only with that home screen in front: behind another app it's trimmed, and with the settings in front their
     * memory would count as our home screen's. Not in front within ten minutes of the switch: again in a minute.
     */
    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            JSONObject s = state(c);
            if (!mode.equals(s.optString("mode"))) return;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                String homePkg = packageOf(originalHome(s));   // dumpsys names an activity as "package/.Class"
                String front = FREE.equals(mode) ? PKG + "/" + JAVA + ".HomeActivity" : homePkg == null ? null : homePkg + "/";
                if (front != null && !adb.shell("dumpsys activity activities | grep ResumedActivity").contains(front)) {
                    boolean again = System.currentTimeMillis() - s.optLong("since") < 600_000;
                    AppLog.w("memory of " + mode + " not measured: its home screen isn't in front" + (again ? ", again in a minute" : ""));
                    if (again) ModeJob.schedule(c, ModeJob.MEMORY, mode, 60_000);
                    return;
                }
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
        // "TOTAL PSS:" from Android 10, plain "TOTAL:" before (never "TOTAL SWAP PSS:" nor the table's "TOTAL" row).
        Pattern total = Pattern.compile("TOTAL(?: PSS)?:\\s+(\\d+)");
        for (String p : processes) {
            Matcher m = total.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) pss += Long.parseLong(m.group(1));
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024);
    }

    /** The home screen after a finished switch: if ADB went away meanwhile, the switch still reads as done. */
    private static void homeAfter(Adb adb) {
        try {
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        } catch (IOException e) {
            AppLog.w("home after the switch: " + e.getMessage());
        }
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

    /**
     * Turned off by an earlier version and protected now (TCL's Live TV source and TCL Channel): back on, and out of
     * {@code record} (with {@code offNow}). Only those: one that is merely not a target this time (the old home screen
     * after a reinstall that recorded another) stays as it is, since turned on here it could take the Home button.
     * Never Google TV's setup app or the TV's home screen here. Returns the ones still off that wouldn't turn on; they
     * stay in {@code record}.
     */
    private static Set<String> offTheList(Adb adb, Set<String> record, Set<String> offNow, String homePkg)
            throws IOException {
        Set<String> stuck = new HashSet<>();
        for (String pkg : sorted(record)) {
            if (pkg.equals(GOOGLE_SETUP) || pkg.equals(homePkg) || mayTurnOff(pkg)) continue;
            if (!offNow.contains(pkg)) {
                AppLog.w("dropped " + pkg + " from the record: no longer on the list");
            } else if (adb.shell("pm enable " + pkg).contains("new state: enabled")) {
                offNow.remove(pkg);
                AppLog.w("turned back on " + pkg + ": no longer on the list");
            } else {
                AppLog.w("could not turn back on " + pkg);
                stuck.add(pkg);
                continue;
            }
            record.remove(pkg);
        }
        return stuck;
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
