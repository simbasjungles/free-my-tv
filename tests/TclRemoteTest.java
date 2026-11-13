package io.freemytv;

public final class TclRemoteTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(TclRemote.liveTv(4001, 240, 0x0417, 0x0304), "C7L BT_RC813A_B4 TV button");
        check(!TclRemote.liveTv(4001, 240, 0x0417, 0x0305), "other remote model untouched");
        check(!TclRemote.liveTv(4001, 240, 0x0000, 0x0304), "other vendor untouched");
        check(!TclRemote.liveTv(4001, 0, 0x0417, 0x0304), "synthetic event untouched");
        check(!TclRemote.liveTv(4001, 241, 0x0417, 0x0304), "different scan code untouched");
        check(!TclRemote.liveTv(170, 240, 0x0417, 0x0304), "standard TV key untouched");
        check(!TclRemote.liveTv(4065, 240, 0x0417, 0x0304), "TCL YouTube key untouched");
        System.out.println("TclRemote: " + assertions + " assertions passed");
    }
}
