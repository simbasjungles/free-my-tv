package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * On Fire TV it also keeps Free My TV on screen: Amazon's launcher starts itself on every Home press (an explicit
 * intent, so the default home screen is never asked), and the app comes back over it.
 */
public class RemoteKeyService extends AccessibilityService {
    /** The last time the home screen was shown: one Home press can report its window more than once. */
    private long lastHome;
    /** Right after a power-on this service may start after Amazon's launcher is already up: no window change to see. */
    private static final long BOOT_SETTLE = 180_000;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (!HomeMode.AMAZON) return;
        // At a power-on the launcher can be up before this service (so its window change is never seen), and a task
        // left from before may be there but behind it: bring the home screen up once, after the TV has settled.
        handler.postDelayed(() -> {
            if (SystemClock.elapsedRealtime() < BOOT_SETTLE || !hasTask()) showHome();
        }, 3000);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!HomeMode.AMAZON || event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.amazon.tv.launcher".contentEquals(pkg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastHome < 1000) return;
        lastHome = now;
        showHome();
    }

    /**
     * Brings Free My TV's own task back to the front. Fire OS stops a background app's activity starts for a few
     * seconds after Home, but moving a task the app owns is not an activity start, so this is immediate.
     */
    private void showHome() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.AppTask> tasks = am == null ? null : am.getAppTasks();
        if (tasks != null) {
            for (ActivityManager.AppTask task : tasks) {
                if (task.getTaskInfo().id < 0) continue;   // one still being created
                try {
                    task.moveToFront();
                    AppLog.w("Home: task " + task.getTaskInfo().id + " back to the front");
                    return;
                } catch (RuntimeException e) {
                    AppLog.w("Home: couldn't bring task " + task.getTaskInfo().id + " back: " + e);
                }
            }
        }
        AppLog.w("Home: no task of ours, starting the home screen");
        startActivity(new Intent(this, FireHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private boolean hasTask() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.AppTask> tasks = am == null ? null : am.getAppTasks();
        if (tasks == null) return false;
        for (ActivityManager.AppTask task : tasks) if (task.getTaskInfo().id >= 0) return true;
        return false;
    }

    @Override public void onInterrupt() {}
}
