package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, this app was updated, or the sleep timer ended. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            AppUpdates.schedule(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            AppUpdates.schedule(context);   // the periodic job survives restarts, but not always an update of the app
        } else if (SleepTimer.ACTION.equals(action)) {
            long setAt = intent.getLongExtra("setAt", 0);
            PendingResult result = goAsync();   // ADB is network: off the main thread
            new Thread(() -> {
                try {
                    SleepTimer.fire(context, setAt);
                } finally {
                    result.finish();
                }
            }).start();
        }
    }
}
