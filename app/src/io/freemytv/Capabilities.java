package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * What this TV can do, and what an option needs from it. Every option that needs something names it (SettingsActivity's
 * `need`), and the settings show it unavailable, with `missing`'s reason, wherever this TV lacks it: a new option only
 * names its need. What can be read is read here; what can only be seen at work is proven by its feature (a file per
 * proof, as the main process proves and the :home settings read).
 */
final class Capabilities {
    private Capabilities() {}

    /** Drawing over other apps (SleepOverlay): Android TV has no screen to allow it, so it's allowed through ADB. */
    static final String OVERLAY = "overlay";

    /** Reading what apps play (NowPlaying): Android hands media sessions only to an allowed notification listener. */
    static final String LISTENER = "listener";

    /** Ad blocking's DNS (HomeMode.adBlocking): refused where Android couldn't validate AdGuard's server. */
    static final String PRIVATE_DNS = "private-dns";

    /** An option's needs. */
    static final String NEED_ADB = "adb", NEED_REMOTE_BUTTONS = "remote-buttons", NEED_OVERLAY = OVERLAY,
            NEED_LIVE_TV = "live-tv", NEED_LISTENER = LISTENER, NEED_SWITCH = "switch",
            NEED_HOME_ASSISTANT = "home-assistant", NEED_APP_STORE = "app-store", NEED_PRIVATE_DNS = PRIVATE_DNS;

    /** null where this TV has what `need` names; else why not, and what would change it, for the person to read. */
    static String missing(Context c, String need) {
        switch (need) {
            case NEED_ADB:
                return adb(c) ? null : Ui.tr("Needs Free My TV mode: turn it on in Home Screen.");
            case NEED_REMOTE_BUTTONS:
                return remoteButtons() ? null : Ui.tr("This TV's remote has no app buttons that Free My TV knows.");
            case NEED_OVERLAY:
                return overlay(c) ? null : Ui.tr("This TV doesn't let apps show over other apps: the time left shows on the home screen.");
            case NEED_LISTENER:
                return !adb(c) ? Ui.tr("Needs Free My TV mode: turn it on in Home Screen.")
                        : refused(c, LISTENER) ? Ui.tr("This TV doesn't let apps see what's playing.") : null;
            case NEED_PRIVATE_DNS:
                return android.os.Build.VERSION.SDK_INT < 28 ? Ui.tr("Needs Android 9 or newer.")
                        : !adb(c) ? Ui.tr("Needs Free My TV mode: turn it on in Home Screen.")
                        : refused(c, PRIVATE_DNS) ? Ui.tr("AdGuard DNS didn't answer on this network, so the TV kept its own DNS.") : null;
            case NEED_HOME_ASSISTANT:
                return !HomeAssistant.hasEntities()
                        ? Ui.tr("None of home_assistant_entities can be a button (see the README).")
                        : !HomeAssistant.hasToken(c) ? Ui.tr("Send Home Assistant's token from a computer (see the README).") : null;
            case NEED_LIVE_TV:
                return HomeApps.liveTv(c) != null ? null : Ui.tr("This TV has no live TV (antenna or cable) to open.");
            case NEED_APP_STORE:
                return HomeApps.storePackage(c) != null ? null : Ui.tr("This TV has no supported app store to open.");
            case NEED_SWITCH:   // where the switch must not run; a TV already in Free My TV mode can still go back
                return HomeMode.phone(c) ? HomeMode.phoneText() : HomeMode.BRICKS && !adb(c) ? HomeMode.bricksText() : null;
            default:
                return null;
        }
    }

    private static long stateStamp = Long.MIN_VALUE;
    private static boolean adbNow;

    /**
     * Keys and settings through the TV's own ADB: the switch has it allowed, so in Free My TV mode. Read again only when
     * the state file changes, as the settings ask on every frame.
     */
    static synchronized boolean adb(Context c) {
        File f = new File(c.getFilesDir(), "home-state.json");
        long stamp = f.lastModified() * 31 + f.length();
        if (stamp != stateStamp) {
            stateStamp = stamp;
            org.json.JSONObject s = HomeMode.state(c);
            adbNow = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
        }
        return adbNow;
    }

    /** The remote's app buttons, on the brands whose key codes RemoteKeys knows. */
    static boolean remoteButtons() {
        return RemoteKeys.supported();
    }

    /** Over other apps: allowed, or ADB can allow it and hasn't been refused on this TV. */
    static boolean overlay(Context c) {
        return android.provider.Settings.canDrawOverlays(c) || (adb(c) && !refused(c, OVERLAY));
    }

    private static File proof(Context c, String name, boolean works) {
        return new File(c.getFilesDir(), (works ? "can-" : "cannot-") + name);
    }

    /** Tried at work and failed: the option shows unavailable on this TV. */
    static boolean refused(Context c, String name) {
        return proof(c, name, false).exists();
    }

    /** Neither proven nor refused: checked again at work (Original sets back what the check depends on). */
    static void forget(Context c, String name) {
        //noinspection ResultOfMethodCallIgnored
        proof(c, name, true).delete();
        //noinspection ResultOfMethodCallIgnored
        proof(c, name, false).delete();
    }

    /** Records what a feature saw at work: it works, or it doesn't (and then its option shows unavailable). */
    static void prove(Context c, String name, boolean works) {
        try {
            //noinspection ResultOfMethodCallIgnored
            proof(c, name, !works).delete();
            if (proof(c, name, works).createNewFile()) AppLog.w("Capability " + name + ": " + (works ? "works" : "doesn't"));
        } catch (IOException e) {
            AppLog.w("Capability " + name + ": " + e.getMessage());
        }
    }
}
