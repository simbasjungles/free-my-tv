package io.freemytv;

public final class SabrinaRemoteTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(SabrinaRemote.supported("sabrina", "Chromecast"), "Chromecast with Google TV");
        check(!SabrinaRemote.supported("kirkwood", "Google TV Streamer"), "streamer uses its own mapping");
        check(!SabrinaRemote.supported("sabrina", "another model"), "other Sabrina model untouched");
        check(!SabrinaRemote.supported("another device", "Chromecast"), "other Chromecast untouched");
        check(SabrinaRemote.isRemote(0x18d1, 0x9450), "Sabrina Bluetooth remote");
        check(!SabrinaRemote.isRemote(0x18d1, 0x9451), "Google TV Streamer remote untouched");
        check(!SabrinaRemote.isRemote(0x18d1, 0x0100), "virtual remote untouched");
        check(!SabrinaRemote.isRemote(0x0000, 0x9450), "other vendor untouched");
        check(SabrinaRemote.KEY_YOUTUBE == 190, "YouTube maps to BUTTON_3");
        System.out.println("SabrinaRemote: " + assertions + " assertions passed");
    }
}
