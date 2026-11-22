package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The paired Bluetooth speakers and headphones as a group of tiles in the bottom drawer: dimmed when not connected, OK
 * connects or disconnects. Off by default (Home Settings › Bluetooth audio); no discovery and no pairing, that stays in TV
 * Settings. Android only lets the shell connect a paired device (a hidden call that needs BLUETOOTH_PRIVILEGED), so
 * everything goes through the TV's own ADB, like Recent Apps: BtTool runs from this app's own APK with `app_process`.
 * Free My TV mode only (Capabilities.adb). Nothing runs while the drawer is closed, and nothing leaves the TV.
 * A2DP only (speakers, headphones, soundbars): earbuds that connect over LE Audio alone show as not connected.
 */
final class BluetoothAudio {
    private BluetoothAudio() {}

    /** While the drawer is open, the list is read again this often (a speaker may be switched on or off beside the TV). */
    static final long REFRESH_MS = 10_000;
    /** At most this many tiles: the drawer's row stays readable next to the inputs and shortcuts. */
    static final int MAX = 6;
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-F]{2}(:[0-9A-F]{2}){5}");

    private static final class Device {
        final String address, name;
        volatile boolean connected;

        Device(String address, String name, boolean connected) {
            this.address = address;
            this.name = name;
            this.connected = connected;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bluetooth-audio");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<Device> devices = Collections.emptyList();
    private static volatile boolean busy;
    /** A connect or disconnect is under way: a read now would bring back the state it is changing. */
    private static volatile boolean toggling;

    interface Result {
        void done(boolean worked);
    }

    /** The tiles from the last read (empty until the drawer has read once, and while the row is off). Cheap: any thread. */
    static List<HomeApps.Item> items() {
        List<HomeApps.Item> list = new ArrayList<>();
        if (!Prefs.bluetoothAudio()) {   // off in Home Settings (another process): what was read is forgotten, here where it is kept
            if (!devices.isEmpty()) devices = Collections.emptyList();
            return list;
        }
        for (Device d : devices) {
            HomeApps.Item it = HomeApps.bluetooth(d.address, d.name, d.connected);
            list.add(it);
        }
        return list;
    }

    /** Reads the paired audio devices now (off the UI thread); `done` runs on it when the list changed. */
    static void refresh(Context c, Runnable done) {
        if (!Prefs.bluetoothAudio() || busy || toggling || !Capabilities.adb(c)) return;
        Context app = c.getApplicationContext();
        busy = true;
        IO.execute(() -> {
            boolean changed = false;
            try {
                String out = run(app, "list");
                if (!out.contains("bt|end")) throw new IOException("no complete list");   // a failed read keeps what is there
                List<Device> read = parse(out);
                changed = !toggling && !same(read, devices);   // a press came during the read: its answer would bring back the old state
                if (changed) devices = read;
            } catch (IOException | RuntimeException e) {
                AppLog.w("bluetooth list: " + e);
            } finally {
                busy = false;
            }
            if (changed && done != null) MAIN.post(done);
        });
    }

    /** OK on a tile: connects it if it isn't, else disconnects it. The tile shows the new state at once and goes back if it failed. */
    static void toggle(Context c, String address, Result result) {
        Device target = null;
        for (Device d : devices) if (d.address.equals(address)) target = d;
        if (target == null || !ADDRESS.matcher(address).matches() || !Capabilities.adb(c)) {
            if (result != null) result.done(false);
            return;
        }
        Context app = c.getApplicationContext();
        Device dev = target;
        boolean connect = !dev.connected;
        dev.connected = connect;
        toggling = true;
        IO.execute(() -> {
            boolean worked = false;
            try {
                String out = run(app, (connect ? "connect " : "disconnect ") + address);
                worked = out.contains("bt|ok|" + (connect ? "1" : "0"));
            } catch (IOException | RuntimeException e) {
                AppLog.w("bluetooth " + (connect ? "connect" : "disconnect") + ": " + e);
            }
            if (!worked) dev.connected = !connect;
            toggling = false;
            boolean ok = worked;
            if (result != null) MAIN.post(() -> result.done(ok));
        });
    }

    /** BtTool's output. It runs as the shell, from this app's APK (the same file Android runs the app from). */
    private static String run(Context c, String args) throws IOException {
        String apk = c.getPackageCodePath();
        if (apk.indexOf('\'') >= 0) throw new IOException("odd path");
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            return adb.shell("CLASSPATH='" + apk + "' app_process /system/bin io.freemytv.BtTool " + args + " 2>&1");
        }
    }

    private static List<Device> parse(String out) {
        List<Device> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\|", 4);
            if (f.length < 4 || !f[0].equals("bt") || !ADDRESS.matcher(f[1]).matches()) continue;
            String name = f[3].trim();
            list.add(new Device(f[1], name.isEmpty() ? f[1] : name, f[2].equals("1")));
            if (list.size() == MAX) break;
        }
        return list;
    }

    private static boolean same(List<Device> a, List<Device> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).address.equals(b.get(i).address) || !a.get(i).name.equals(b.get(i).name)
                    || a.get(i).connected != b.get(i).connected) return false;
        }
        return true;
    }
}
