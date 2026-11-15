package io.freemytv;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Place.init(this);
        fireHome();
    }

    /**
     * FireHomeActivity answers HOME on Fire TV. Everywhere else it stays off: with it on, the app would have two home
     * screens and Android could be left without a default one (a ZTE Google TV box stayed on FallbackHome, #467).
     */
    private void fireHome() {
        ComponentName fire = new ComponentName(this, FireHomeActivity.class);
        int want = HomeMode.AMAZON
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm = getPackageManager();
        if (pm.getComponentEnabledSetting(fire) != want) {
            pm.setComponentEnabledSetting(fire, want, PackageManager.DONT_KILL_APP);
        }
    }
}
