package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The firmware's own TV API: the cn.cvte.tv.api library, which the manifest declares as a library that may be
 * absent. On CVTE boards it is the only way to list and switch sources — those boards register no TvInputService,
 * so TvInputManager sees nothing at all (#577), while this API answers with EntityInputSource and switches with
 * eventSystemInputSourceSetInputSource(id), which is what the TV's own source menu calls.
 *
 * Every method binds, calls and unbinds, and waits while it does, so every one of them runs off the UI thread.
 */
final class CvteApi {
    private static final String ACTION = "com.cvte.tv.api.TV_API_SERVICE";
    private static final String PACKAGE = "com.cvte.tv.api.impl";
    private static final String MANAGER = "com.cvte.tv.api.aidl.ITvApiManager";
    private static final long CONNECT_MS = 5000;

    private CvteApi() {}

    /** False on a TV without the library: the caller keeps today's behaviour (AC6). */
    static boolean available() {
        return CvteSources.firmwarePresent();
    }

    /** The sources the firmware offers, in its order, or null when it will not answer. */
    static List<CvteSources.Source> sources(Context c) {
        Link link = connect(c);
        if (link == null) return null;
        try {
            Object list = link.source.getClass().getMethod("eventSystemInputSourceGetList").invoke(link.source);
            return list instanceof List ? CvteSources.list((List<?>) list) : null;
        } catch (Throwable t) {
            AppLog.w("CVTE sources unreadable: " + t);
            return null;
        } finally {
            link.close();
        }
    }

    /** Switches to one of the firmware's sources; false when it refuses (busy, unknown id, no library). */
    static boolean select(Context c, int id) {
        Link link = connect(c);
        if (link == null) return false;
        try {
            Object ok = link.source.getClass()
                    .getMethod("eventSystemInputSourceSetInputSource", int.class).invoke(link.source, id);
            return ok instanceof Boolean && ((Boolean) ok).booleanValue();
        } catch (Throwable t) {
            AppLog.w("CVTE source " + id + " refused: " + t);
            return false;
        } finally {
            link.close();
        }
    }

    /** A bound service, held for the one call it makes. */
    private static final class Link implements Closeable {
        final Context context;
        final ServiceConnection connection;
        final Object source;

        Link(Context context, ServiceConnection connection, Object source) {
            this.context = context;
            this.connection = connection;
            this.source = source;
        }

        @Override public void close() {
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
                // already gone: nothing to release
            }
        }
    }

    /** Binds the TV API and takes its input-source interface, or null when any step fails. */
    private static Link connect(Context c) {
        if (!available()) return null;
        final CountDownLatch answered = new CountDownLatch(1);
        final Object[] holder = new Object[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    Class<?> stub = Class.forName(MANAGER + "$Stub");
                    Object manager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                    holder[0] = manager.getClass().getMethod("getTVApiSystemInputSource").invoke(manager);
                } catch (Throwable t) {
                    AppLog.w("CVTE API unusable: " + t);
                    holder[0] = null;
                } finally {
                    answered.countDown();
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                answered.countDown();
            }
        };
        boolean bound;
        try {
            bound = c.bindService(new Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            AppLog.w("CVTE API unreachable: " + t);
            return null;
        }
        if (!bound) return null;
        try {
            if (!answered.await(CONNECT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w("CVTE API silent for " + CONNECT_MS + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (holder[0] == null) {
            try {
                c.unbindService(connection);
            } catch (Throwable ignored) {
                // never connected: nothing to release
            }
            return null;
        }
        return new Link(c, connection, holder[0]);
    }
}
