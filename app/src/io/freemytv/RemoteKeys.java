package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 * On Xiaomi TVs whose remote has no Home button, its Mi button is BUTTON_4, a global key that Xiaomi's GlobalKeyReceiver
 * (partnercustomizer) turns into PatchWall. The filter sees it first, and by default it opens the home screen.
 * On Xiaomi TVs the YouTube button is ours too: BUTTON_3, a global key for the stock home screen (launcherx), which
 * Free My TV turns off. So is the PatchWall button (BUTTON_5, which that receiver would open PatchWall with); Netflix
 * goes to the receiver, which stays on. Their remote sends one press as a burst of presses: the app opens once.
 * On Xiaomi TVs sold in Russia, the Okko, VK, Rutube, KION, Wink and IVI buttons are global keys too, for Xiaomi's
 * hot-key receiver (partnercustomizer's GlobalKeyReceiver).
 * On Hisense TVs, the Prime Video, YouTube, Disney+, All Apps, Settings (gear) and profile buttons go to Google TV's
 * home app, which Free My TV mode turns off: without the filter they do nothing. Netflix stays with the system.
 * On Google TV Streamer, YouTube and the custom star button go to that home app too; Netflix goes to Google's service.
 * On the JVC G24 (Skyworth Android 11), the input shortcut's vendor receiver crashes without Google TV's content
 * provider, and Quick Settings targets its disabled notification panel. Open the native TV menus directly.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie"),
            HISENSE = is("Hisense");
    static final boolean CHROMECAST_SABRINA = SabrinaRemote.supported(Build.DEVICE, Build.MODEL);
    static final boolean GOOGLE_STREAMER = is("Google") && Build.MODEL.equals("Google TV Streamer");
    static final boolean JVC_SKYWORTH = SkyworthRemote.supported(Build.MANUFACTURER, Build.BRAND,
            Build.MODEL, Build.VERSION.SDK_INT);
    /**
     * Xiaomi TVs whose remote has a Home button and hands every app button to Xiaomi's receiver, which stays on
     * (MiTV-AXSO2, Amlogic, Android TV 11 tested): only the PatchWall button (BUTTON_5) is ours, left to the TV until an
     * app is picked. By model name, since the other buttons' codes were not checked on this remote.
     */
    static final boolean XIAOMI_PATCHWALL_ONLY = is("Xiaomi") && Build.MODEL.equals("MiTV-AXSO2");
    /**
     * Xiaomi TVs (models MiTV-M…; MiTV-MSSP3, MediaTek, Android TV 9 tested). Not the Mi Box or Mi TV Stick, the slowest
     * devices we run on, whose remotes have a Home button; Formovie's Xiaomi projectors have their own remote.
     */
    static final boolean XIAOMI = is("Xiaomi") && !FORMOVIE && (Build.MODEL.startsWith("MiTV-M") || XIAOMI_PATCHWALL_ONLY);
    /**
     * Xiaomi's MiTV-AFKR0 (jaws, Amlogic, Android TV 11 tested), whose remote (Xiaomi RC,
     * Vendor_2717_Product_32b9.kl) has the buttons marked "Mi TV+", Netflix, Prime Video and YouTube. YouTube is
     * BUTTON_16 here, not BUTTON_3 as on the MiTV-M… remotes, and there is no Rutube button to clash with it. Its
     * presses come one at a time, so no burst as on the other Xiaomi remotes.
     */
    static final boolean XIAOMI_AFKR0 = is("Xiaomi") && Build.MODEL.equals("MiTV-AFKR0");
    /**
     * Philips' Google TV models (TP Vision; 65OLED910, "Philips Google TV TA5", Android 14, #437), whose remote sends TP
     * Vision's own key codes. org.droidtv.GlobalKey hands the Settings button to Google TV's home app (its dashboard),
     * which Free My TV turns off; Netflix, YouTube and Prime Video keep working through that receiver until an app is
     * picked. Their older Android TV sets have another remote.
     */
    static final boolean PHILIPS_GTV = (is("Philips") || is("TPV") || Build.MODEL.startsWith("Philips"))
            && Build.VERSION.SDK_INT >= 33;
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;
    /**
     * With a key filter on, TCL's Android 11 changes the volume twice for one press, and a held key gets stuck (#325).
     * There the service stays off: the buttons work on our home screen, whose window gets them (BUTTON_3 never does).
     */
    static final boolean FILTER_SAFE = !(TCL && Build.VERSION.SDK_INT == Build.VERSION_CODES.R);

    /** Xiaomi's remote sends one press as a burst of separate presses: a press this soon after the last one is the same. */
    private static final long BURST_MS = 1000;
    private static int lastKey;
    private static long lastDown;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || XIAOMI || XIAOMI_AFKR0 || HISENSE || CHROMECAST_SABRINA
                || GOOGLE_STREAMER || JVC_SKYWORTH || PHILIPS_GTV;
    }

    /** The key service is on only where the filter is safe, and while a button opens something (else it has no work). */
    static boolean serviceWanted() {
        if (!supported() || !FILTER_SAFE) return false;
        for (int key : Prefs.KEYS) if (!Prefs.keyApp(key).isEmpty()) return true;
        return false;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (CHROMECAST_SABRINA) {
            InputDevice device = InputDevice.getDevice(e.getDeviceId());
            if (device == null || !SabrinaRemote.isRemote(device.getVendorId(), device.getProductId())) return false;
        }
        int keyCode = TCL && e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        if (JVC_SKYWORTH) {
            if (SkyworthRemote.settings(keyCode)) keyCode = Prefs.KEY_JVC_SETTINGS;
            else if (SkyworthRemote.shortcut(keyCode, e.getScanCode())) keyCode = Prefs.KEY_JVC_SHORTCUT;
            else return false;
        }
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart. The BRAVIA VU31's remote sends BUTTON_2 and BUTTON_15 too (Prefs.sonyKey).
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9 || keyCode == KeyEvent.KEYCODE_BUTTON_2
                || keyCode == KeyEvent.KEYCODE_BUTTON_15)) {
            keyCode = Prefs.sonyKey(e.getScanCode());
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            // Only on Xiaomi, where the bursts are: everywhere else a second press opens the app again, as before.
            boolean burst = XIAOMI && keyCode == lastKey && e.getEventTime() - lastDown < BURST_MS;
            lastKey = keyCode;
            lastDown = e.getEventTime();
            if (burst) return true;
            // Our own package means the home screen in use (Xiaomi's Mi button): ours, or the TV's own after the
            // watchdog handed it back. Not the settings app our launcher icon opens.
            Intent intent = pkg.equals(HomeMode.PKG) || pkg.equals(Prefs.ALL_APPS)
                    ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    : JVC_SKYWORTH && pkg.equals(Prefs.QUICK_SETTINGS)
                    ? new Intent(c, QuickSettingsActivity.class)
                    : JVC_SKYWORTH && pkg.equals(Prefs.TV_SHORTCUTS)
                    ? new Intent(Intent.ACTION_MAIN).setClassName("com.smartdevice.dailyshortcut",
                            "com.smartdevice.dailyshortcut.ui.MainActivity")
                    : (TCL || PHILIPS_GTV) && pkg.equals(Prefs.QUICK_PANEL)
                    ? new Intent(c, QuickPanelActivity.class)
                    : (JVC_SKYWORTH || TCL || PHILIPS_GTV) && pkg.equals("com.android.tv.settings")
                    ? new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage(pkg)
                    : launchIntent(c.getPackageManager(), pkg);
            if (pkg.equals(Prefs.ALL_APPS)) intent.putExtra(HomeActivity.EXTRA_ALL_APPS, true);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            // Consumed even when it doesn't open: its key-up is ours too, and a key-down alone would reach the TV.
            if (intent != null) {
                try {
                    c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (android.content.ActivityNotFoundException | SecurityException ex) {
                    AppLog.w("remote target unavailable: " + pkg);
                }
            }
        }
        return true;
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
