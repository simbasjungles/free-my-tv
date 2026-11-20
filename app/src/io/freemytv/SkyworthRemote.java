package io.freemytv;

/** The shortcut keys verified on the JVC G24's Skyworth firmware. Other remotes stay with the TV. */
final class SkyworthRemote {
    private SkyworthRemote() {}

    static boolean supported(String manufacturer, String brand, String model, int sdk) {
        return sdk == 30 && "JVC G24 TV".equals(model)
                && ("skyworth".equalsIgnoreCase(manufacturer) || "skyworth".equalsIgnoreCase(brand));
    }

    static boolean shortcut(int key, int scan) {
        // BUTTON_16 is shared with many app buttons: match only DailyShortcut's IR/Bluetooth scan codes.
        // BUTTON_14/240 is another DailyShortcut mapping in this firmware's global-key table.
        return (key == 203 && (scan == 383 || scan == 79)) || (key == 201 && scan == 240);
    }

    static boolean settings(int key) {
        // KEYCODE_NOTIFICATION: SystemUI otherwise requests Google TV's disabled notification panel.
        return key == 83;
    }
}
