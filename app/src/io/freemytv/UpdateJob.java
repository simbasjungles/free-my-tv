package io.freemytv;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** AppUpdates' checks, as a job in the main process: every 6 hours while it's on, and after "Check Now". */
public class UpdateJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                AppUpdates.run(app);
            } catch (Throwable t) {
                AppLog.w("app updates failed: " + t);
            }
            jobFinished(params, false);
        }, "free-my-tv-updates").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
