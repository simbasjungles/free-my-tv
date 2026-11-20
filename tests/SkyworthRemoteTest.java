package io.freemytv;

public final class SkyworthRemoteTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(SkyworthRemote.supported("skyworth", "JVC", "JVC G24 TV", 30), "verified TV");
        check(SkyworthRemote.supported("SKYWORTH", "JVC", "JVC G24 TV", 30), "maker casing");
        check(!SkyworthRemote.supported("skyworth", "JVC", "another TV", 30), "other model untouched");
        check(!SkyworthRemote.supported("TCL", "TCL", "JVC G24 TV", 30), "other manufacturer untouched");
        check(!SkyworthRemote.supported("skyworth", "JVC", "JVC G24 TV", 29), "other firmware untouched");
        check(!SkyworthRemote.supported("skyworth", "JVC", "JVC G24 TV", 31), "newer firmware untouched");
        check(SkyworthRemote.shortcut(203, 383), "Bluetooth shortcut");
        check(SkyworthRemote.shortcut(203, 79), "IR shortcut");
        check(SkyworthRemote.shortcut(201, 240), "alternate shortcut mapping");
        for (int scan : new int[] {0, 73, 176, 240, 387, 750, 592, 609}) {
            check(!SkyworthRemote.shortcut(203, scan), "shared app key stays native: " + scan);
        }
        for (int key : new int[] {3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 176, 178, 202}) {
            check(!SkyworthRemote.shortcut(key, 383), "unrelated key stays native: " + key);
        }
        check(!SkyworthRemote.shortcut(201, 383), "wrong scan for alternate mapping");
        check(!SkyworthRemote.shortcut(83, 240), "settings does not open the input panel");
        check(SkyworthRemote.settings(83), "notification key opens TV settings");
        for (int key : new int[] {3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 176, 178, 201, 202, 203}) {
            check(!SkyworthRemote.settings(key), "settings mapping leaves unrelated key native: " + key);
        }
        System.out.println("SkyworthRemote: " + assertions + " assertions passed");
    }
}
