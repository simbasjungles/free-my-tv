package io.freemytv;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The console card on the shelf: which HDMI input it is, and how the apps share the shelf with it. */
public final class ConsoleShelfTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> symbols(String... s) {
        return Arrays.asList(s);
    }

    public static void main(String[] args) {
        // The TV's own labels: "Console de jogos" here, "PlayStation 5" over HDMI-CEC on another set.
        check(ConsoleShelf.consoleIndex(symbols("hdmi", "gamepad", "tv")) == 1, "console found by its icon");
        check(ConsoleShelf.consoleIndex(symbols("gamepad", "hdmi")) == 0, "first console wins");
        check(ConsoleShelf.consoleIndex(symbols("hdmi", "play", "speaker")) == -1, "no console on the ports");
        check(ConsoleShelf.consoleIndex(Collections.<String>emptyList()) == -1, "no inputs at all");
        check(ConsoleShelf.consoleIndex(symbols("hdmi", null)) == -1, "a missing icon is not a console");

        check(ConsoleShelf.appsWithConsole(8) == 7, "one slot kept for the console");
        check(ConsoleShelf.appsWithConsole(10) == 9, "a wider shelf keeps one more app");
        check(ConsoleShelf.appsWithConsole(1) == 0, "a single slot is the console's");
        check(ConsoleShelf.appsWithConsole(0) == 0, "no room, no apps");
        check(ConsoleShelf.appsWithConsole(Integer.MAX_VALUE) == Integer.MAX_VALUE - 1, "an endless shelf still fits");

        check(ConsoleShelf.packages(Arrays.asList("com.netflix.ninja", null, "com.google.android.youtube.tv"))
                .equals(Arrays.asList("com.netflix.ninja", "com.google.android.youtube.tv")), "console left out of a saved order");
        check(ConsoleShelf.packages(Arrays.asList((String) null)).isEmpty(), "only the console: nothing to save");
        check(ConsoleShelf.packages(Collections.<String>emptyList()).isEmpty(), "empty shelf");

        System.out.println("ConsoleShelf: " + assertions + " assertions passed");
    }
}
