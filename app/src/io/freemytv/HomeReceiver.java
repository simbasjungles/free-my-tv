package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, this app was updated, the sleep timer ended, or the installer
 * answered about an update (Updates). Not exported.
 */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            AppUpdates.schedule(context);   // Free My TV's own update with the apps'
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            AppUpdates.schedule(context);   // the periodic job survives restarts, but not always an update of the app
            later(() -> {
                Updates.cleanUp(context);
                AppUpdates.reopenHome(context);   // it updated itself through ADB: the home screen went with the old copy
            });
        } else if (Updates.ACTION.equals(action)) {
            later(() -> Updates.status(context, intent));
        } else if (SleepTimer.ACTION.equals(action)) {
            long setAt = intent.getLongExtra("setAt", 0);
            later(() -> SleepTimer.fire(context, setAt));   // ADB is network
        }
    }

    /** Off the main thread (Updates may wait for a check under way), with the notice held open until it's done. */
    private void later(Runnable work) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                work.run();
            } finally {
                result.finish();
            }
        }).start();
    }
}
