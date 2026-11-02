package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/**
 * The game console's card on the home shelf (HomeApps): the TV reports it as an HDMI input whose name says "console" or
 * "PlayStation", and it keeps the shelf's last slot, so it stays put while the apps scroll. Kept free of Android types
 * so tests/ConsoleShelfTest can run it on a plain JVM.
 */
final class ConsoleShelf {
    private ConsoleShelf() {}

    /** The icon that marks a game console (HomeApps.deviceSymbol). */
    static final String SYMBOL = "gamepad";

    /** The console's index in `symbols` (the icon picked for each input's name), or -1 when no input is a console. */
    static int consoleIndex(List<String> symbols) {
        for (int i = 0; i < symbols.size(); i++) if (SYMBOL.equals(symbols.get(i))) return i;
        return -1;
    }

    /**
     * How many apps fit on the shelf when one slot is kept for the console. With a single slot, the console is the one
     * card (the shelf shows the thing the person asked for); otherwise every app that fit before still fits, minus one.
     */
    static int appsWithConsole(int max) {
        return max <= 1 ? 0 : max - 1;
    }

    /** The packages of a shelf order, leaving out cards that aren't apps (the console tile has no package). */
    static List<String> packages(List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) if (p != null) out.add(p);
        return out;
    }
}
