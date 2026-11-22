package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Lists, connects and disconnects the paired Bluetooth audio devices. Not run by the app: BluetoothAudio starts it
 * through the TV's own ADB (`CLASSPATH=<this apk> app_process /system/bin io.freemytv.BtTool list`), because connecting
 * is a hidden call that needs BLUETOOTH_PRIVILEGED, which only the shell has. So it has no Context of its own and
 * touches nothing else of the app.
 *
 * Output, one line each: `bt|<address>|<1 or 0: connected>|<name>` for `list`, then `bt|end` (no `bt|end`: the read
 * failed, so an empty list can be told from a failure); `bt|ok|<1 or 0>` or `bt|failed` after `connect` and
 * `disconnect <address>`.
 */
public final class BtTool {
    private static final long WAIT_MS = 7000;
    private static Context ctx;
    private static BluetoothAdapter adapter;
    private static String command, address;

    public static void main(String[] args) throws Exception {
        command = args.length > 0 ? args[0] : "list";
        address = args.length > 1 ? args[1].toUpperCase(Locale.US) : null;
        Looper.prepareMainLooper();
        try {   // Android 12 and newer: the Bluetooth service wrappers a bare process has not registered yet
            Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null);
        } catch (Throwable ignored) {}
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object t = thread.getMethod("systemMain").invoke(null);
        ctx = (Context) thread.getMethod("getSystemContext").invoke(t);
        BluetoothManager manager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            System.out.println("bt|failed");
            System.exit(1);
        }
        new Thread(() -> {   // never leave a process behind on the TV
            try { Thread.sleep(WAIT_MS * 2); } catch (InterruptedException ignored) {}
            System.out.println("bt|failed");
            System.exit(2);
        }).start();
        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    run(proxy);
                } catch (Throwable e) {
                    System.out.println("bt|failed");
                }
                System.exit(0);
            }
            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
        Looper.loop();
    }

    private static void run(BluetoothProfile a2dp) throws Exception {
        if (address == null) {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                int state = a2dp.getConnectionState(d);
                BluetoothClass cls = d.getBluetoothClass();
                boolean audio = cls != null && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
                if (!audio && state == BluetoothProfile.STATE_DISCONNECTED) continue;   // remotes, phones, keyboards
                String name = d.getName() == null ? d.getAddress() : d.getName().replace('\n', ' ');
                System.out.println("bt|" + d.getAddress() + "|" + (state == BluetoothProfile.STATE_CONNECTED ? 1 : 0) + "|" + name);
            }
            System.out.println("bt|end");
            return;
        }
        boolean connect = command.equals("connect");
        if (!connect && !command.equals("disconnect")) throw new IllegalArgumentException(command);
        BluetoothDevice d = adapter.getRemoteDevice(address);
        if (!adapter.getBondedDevices().contains(d)) throw new IllegalArgumentException("not paired");   // never reaches an unpaired one
        Method call = a2dp.getClass().getMethod(command, BluetoothDevice.class);
        boolean accepted = Boolean.TRUE.equals(call.invoke(a2dp, d));
        long end = System.currentTimeMillis() + WAIT_MS;
        boolean connected = a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
        while (accepted && connected != connect && System.currentTimeMillis() < end) {
            Thread.sleep(250);
            connected = a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
        }
        System.out.println(connected == connect ? "bt|ok|" + (connected ? 1 : 0) : "bt|failed");
    }
}
