package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;

/**
 * Waits for the first unlock of the user. Before it, Android keeps credential-protected storage closed, so the
 * code that reads it (Prefs, the home screen's state) must wait. The boot home screen is direct-boot-aware: Android
 * starts it before the unlock, and it draws a plain screen until this runs (issue #574, opção C).
 */
final class Unlock {
    private Unlock() {}

    static boolean isUnlocked(Context c) {
        UserManager users = c.getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    /**
     * Runs {@code then} now if the user is unlocked, else once, when ACTION_USER_UNLOCKED arrives. The receiver is
     * registered on the application context and removed after it runs.
     */
    static void whenUnlocked(Context c, Runnable then) {
        Context app = c.getApplicationContext();
        if (isUnlocked(app)) {
            then.run();
            return;
        }
        BroadcastReceiver[] self = new BroadcastReceiver[1];
        self[0] = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) return;
                try {
                    app.unregisterReceiver(self[0]);
                } catch (IllegalArgumentException ignored) {
                    // already gone
                }
                then.run();
            }
        };
        app.registerReceiver(self[0], new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (isUnlocked(app)) {
            try {
                app.unregisterReceiver(self[0]);
            } catch (IllegalArgumentException ignored) {
                // the receiver already ran
            }
            then.run();
        }
    }
}
