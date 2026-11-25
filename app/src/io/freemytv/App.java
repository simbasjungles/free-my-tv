package io.freemytv;

import android.app.Application;

/**
 * Gives Prefs a context in every process. Before the first unlock (direct boot) only the boot home screen runs, and
 * Prefs reads credential-protected storage, so it waits for the unlock (Unlock).
 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Unlock.whenUnlocked(this, () -> {
            Prefs.init(this);
            Place.init(this);
        });
    }
}
