package io.freemytv;

/** TCL C7L's BT_RC813A_B4 remote: the TV button sends vendor key 4001, HID usage 0x000c005b. */
final class TclRemote {
    private TclRemote() {}

    static final int KEY_TV = 4001;

    static boolean liveTv(int keyCode, int scanCode, int vendorId, int productId) {
        return keyCode == KEY_TV && scanCode == 240 && vendorId == 0x0417 && productId == 0x0304;
    }
}
