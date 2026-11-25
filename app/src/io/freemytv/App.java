package io.freemytv;

import android.app.Application;
import android.content.Context;

/**
 * Gives Prefs a context in every process. Before the first unlock (direct boot) only the boot home screen runs, and
 * Prefs reads credential-protected storage, so it waits for the unlock (Unlock).
 */
public class App extends Application {
    private static boolean initialized;

    /** Once, after the unlock. Android doesn't order two receivers of ACTION_USER_UNLOCKED, so the home screen calls it too. */
    static synchronized void initUnlocked(Context c) {
        if (initialized) return;
        Prefs.init(c);
        Place.init(c);
        initialized = true;   // only after both: a failure is tried again
    }

    @Override public void onCreate() {
        super.onCreate();
        Unlock.whenUnlocked(this, () -> initUnlocked(this));
    }
}
