package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The shelf's keys: which saved keys show (an app that launches, or an input the TV lists), and what a reorder saves. */
public final class ShelfKeysTest {
    private static int assertions;

    private static List<String> list(String... keys) { return Arrays.asList(keys); }
    private static Set<String> set(String... keys) { return new HashSet<>(Arrays.asList(keys)); }
    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void shows(List<String> saved, Set<String> launchable, Set<String> inputs, int max, List<String> expected) {
        Set<String> launchableOriginal = new HashSet<>(launchable), inputsOriginal = new HashSet<>(inputs);
        List<String> savedOriginal = new ArrayList<>(saved);
        check(ShelfKeys.resolve(saved, launchable, inputs, max).equals(expected), "wrong shown keys");
        check(launchable.equals(launchableOriginal) && inputs.equals(inputsOriginal) && saved.equals(savedOriginal),
                "the saved list and the sets must not change");
    }

    public static void main(String[] args) {
        // An app that launches shows; an uninstalled one holds no place (and no row counts it toward the limit).
        shows(list("netflix", "gone", "youtube"), set("netflix", "youtube"), set(), 9, list("netflix", "youtube"));
        // An input has no launch intent, but the TV lists it: it shows, like in the drawer.
        shows(list("hdmi1", "netflix"), set("netflix"), set("hdmi1", "live"), 9, list("hdmi1", "netflix"));
        shows(list("live"), set(), set("live"), 9, list("live"));
        // A port the TV no longer lists is out, as an uninstalled app is.
        shows(list("hdmi2", "netflix"), set("netflix"), set("hdmi1"), 9, list("netflix"));
        // The saved order is kept, and the limit counts only what shows.
        shows(list("a", "x", "b", "c"), set("a", "b", "c"), set(), 2, list("a", "b"));
        shows(list("hdmi1", "a", "b"), set("a", "b"), set("hdmi1"), 2, list("hdmi1", "a"));
        shows(list("a", "b", "c"), set("a", "b", "c"), set(), 0, list());
        shows(list(), set(), set(), 9, list());
        shows(list("gone"), set(), set(), 9, list());
        // Every subset of launchable and listed inputs, every limit: the shown keys are the holding ones, in the
        // saved order, none skipped while there is room, none over the limit.
        String[] keys = {"a", "b", "c"};
        for (int mask = 0; mask < 1 << (keys.length * 2); mask++) {
            Set<String> launchable = new HashSet<>(), inputs = new HashSet<>();
            for (int n = 0; n < keys.length; n++) {
                if ((mask & (1 << n)) != 0) launchable.add(keys[n]);
                if ((mask & (1 << (keys.length + n))) != 0) inputs.add(keys[n]);
            }
            for (int max = 0; max <= keys.length + 1; max++) {
                List<String> saved = list(keys), shown = ShelfKeys.resolve(saved, launchable, inputs, max);
                check(shown.size() <= max, "over the limit");
                int at = 0;
                for (String k : shown) {   // a subsequence of the saved list, in its order
                    while (at < saved.size() && !saved.get(at).equals(k)) at++;
                    check(at < saved.size(), "a key that isn't saved was shown");
                    at++;
                }
                for (String k : saved) {
                    boolean holds = ShelfKeys.holds(k, launchable.contains(k), inputs);
                    if (shown.contains(k)) check(holds, "a key that doesn't hold was shown");
                    else check(!holds || shown.size() == max, "a holding key was skipped with room left");
                }
            }
        }
        check(ShelfKeys.holds("netflix", true, set()), "a launchable app holds");
        check(!ShelfKeys.holds("gone", false, set()), "an uninstalled app doesn't hold");
        check(ShelfKeys.holds("hdmi1", false, set("hdmi1")), "a listed input holds");
        check(!ShelfKeys.holds("hdmi3", false, set("hdmi1")), "a port the TV doesn't list doesn't hold");
        // What a reorder writes back: an app by its package, an input by its key.
        check(ShelfKeys.stored("com.netflix.ninja", "netflix").equals("com.netflix.ninja"), "an app saves its package");
        check(ShelfKeys.stored(null, "hdmi1").equals("hdmi1"), "an input saves its key");
        check(ShelfKeys.stored(null, "live").equals("live"), "Live TV saves its key");
        System.out.println("ShelfKeys: " + assertions + " assertions passed");
    }
}
