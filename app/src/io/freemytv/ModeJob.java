package io.freemytv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs the mode switches in the background (HomeMode). As a job, the system keeps the app's main process running until
 * the switch finishes, even with the screen showing something else.
 */
public class ModeJob extends JobService {
    static final String APPLY = "apply", VERIFY = "verify", REVERT = "revert", MEMORY = "memory", REMOTE = "remote",
            PLAYING = "playing", UPDATE = "update", INSTALL = "install", GRANT_WATCH = "watchGrant",
            REVOKE_WATCH = "watchRevoke", POWER_ON = "powerOn";   // job ids come from these names: keep them apart

    @Override public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        final String action = extras.getString("action", "");
        final String mode = extras.getString("mode", "");
        final long at = extras.getLong("at", 0);
        final Context app = getApplicationContext();
        new Thread(() -> {
            if (APPLY.equals(action)) noteStarted(app, at);
            boolean again = false;   // the week's update check failed: the system tries again soon (Updates.schedule)
            try {
                switch (action) {
                    case APPLY: HomeMode.applyNow(app, mode); break;
                    case VERIFY: HomeMode.verifyNow(app); break;
                    case REVERT: HomeMode.revertHomeNow(app); break;
                    case MEMORY: HomeMode.measureMemoryNow(app, mode); break;
                    case REMOTE: HomeMode.ensureRemoteServiceNow(app); break;
                    case PLAYING: HomeMode.nowPlayingAccessNow(app); break;
                    case UPDATE: again = Updates.check(app) && params.getJobId() == Updates.JOB; break;   // the week's, or OK
                    case INSTALL: Updates.install(app); break;
                    case GRANT_WATCH: HomeMode.grantWatchNow(app); break;
                    case REVOKE_WATCH: HomeMode.revokeWatchNow(app); break;
                    case POWER_ON: HomeMode.powerOnNow(app); break;
                    default: break;
                }
            } catch (Throwable t) {
                AppLog.w("job " + action + " failed: " + t);
            }
            jobFinished(params, again);
        }, "free-my-tv-" + action).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }

    private static int id(String action) {
        return 1000 + Math.abs(action.hashCode() % 1000);
    }

    /** Drops an action's pending job (a running one goes on). */
    static void cancel(Context c, String action) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(id(action));
    }

    /**
     * Schedules a job (one pending per action: a new request replaces the old one). Returns the request's stamp
     * (elapsedRealtime, the same in every process), which a switch's job leaves behind once it runs ({@link #started}).
     */
    static long schedule(Context c, String action, String mode, long delayMs) {
        long at = SystemClock.elapsedRealtime();
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return at;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", action);
        extras.putString("mode", mode == null ? "" : mode);
        extras.putLong("at", at);
        JobInfo.Builder job = new JobInfo.Builder(id(action),
                new ComponentName(c, ModeJob.class)).setExtras(extras);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        job.setOverrideDeadline(delayMs + 2_000);
        scheduler.schedule(job.build());
        return at;
    }

    // MARK: - Did the switch start?

    // A TV maker's background blocker can keep the job from ever starting (TCL's TclAppBoot, #364, #375): nothing runs,
    // nothing is logged in the app. The job leaves the stamp of the request it serves; the settings screen compares.
    private static File startedFile(Context c) {
        return new File(c.getFilesDir(), "switch-started");
    }

    private static void noteStarted(Context c, long at) {
        try (FileOutputStream out = new FileOutputStream(startedFile(c))) {
            out.write(Long.toString(at).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("could not note that the switch started: " + e.getMessage());
        }
    }

    /** Whether the switch requested at `at` (from {@link #schedule}) has started. */
    static boolean started(Context c, long at) {
        try (FileInputStream in = new FileInputStream(startedFile(c))) {
            byte[] data = new byte[32];
            int n = in.read(data);
            return n > 0 && Long.parseLong(new String(data, 0, n, StandardCharsets.US_ASCII).trim()) == at;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}
