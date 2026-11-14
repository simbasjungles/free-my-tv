package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VisibleOrderTest {
    private static int assertions;

    private static List<String> list(String... keys) { return Arrays.asList(keys); }
    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void arrange(List<String> saved, List<String> hidden, List<String> current, List<String> expected) {
        List<String> currentOriginal = new ArrayList<>(current);
        check(VisibleOrder.arrange(saved, hidden, current).equals(expected), "wrong arranged order");
        check(current.equals(currentOriginal), "the current list must not change");
    }

    private static void order(List<String> saved, List<String> moved, List<String> expected) {
        List<String> original = new ArrayList<>(saved), moveOriginal = new ArrayList<>(moved);
        check(VisibleOrder.replace(saved, moved).equals(expected), "wrong saved order");
        check(saved.equals(original) && moved.equals(moveOriginal), "input lists must not change");
    }

    public static void main(String[] args) {
        order(list("live", "store", "inputs", "settings"), list("settings", "live", "inputs"),
                list("settings", "store", "live", "inputs"));
        order(list("store", "live", "inputs", "recents", "settings", "future"), list("settings", "inputs", "live"),
                list("store", "settings", "inputs", "recents", "live", "future"));
        order(list("live", "inputs", "settings"), list("settings", "inputs", "live"), list("settings", "inputs", "live"));
        order(list("live", "store", "settings"), list("live", "settings"), list("live", "store", "settings"));
        order(list("store", "settings"), list("settings"), list("store", "settings"));
        order(list("store", "recents"), list(), list("store", "recents"));
        order(list(), list("live", "settings"), list("live", "settings"));
        order(list("live", "store"), list("settings", "live"), list("settings", "store", "live"));
        order(list("store", "settings"), list("live", "settings", "inputs"), list("store", "live", "settings", "inputs"));
        // With every visibility subset and permutation, hidden keys retain their exact indices.
        List<String> saved = list("live", "store", "inputs", "settings", "recents");
        for (int mask = 0; mask < (1 << saved.size()); mask++) {
            List<String> visible = new ArrayList<>();
            for (int n = 0; n < saved.size(); n++) if ((mask & (1 << n)) != 0) visible.add(saved.get(n));
            permutations(saved, visible, 0);
        }
        List<String> moved = VisibleOrder.replace(list("live", "store", "inputs", "settings"), list("settings", "live", "inputs"));
        order(moved, list("store", "settings", "inputs", "live"), list("store", "settings", "inputs", "live"));
        // arrange: saved order first, unknown keys appended, hidden keys out, gone keys skipped
        arrange(null, list(), list("live", "hdmi1"), list("live", "hdmi1"));
        arrange(list("hdmi2", "live"), list(), list("live", "hdmi1", "hdmi2"), list("hdmi2", "live", "hdmi1"));
        arrange(list("live", "hdmi3", "hdmi2"), list(), list("live", "hdmi1", "hdmi2"), list("live", "hdmi2", "hdmi1"));
        arrange(list("live", "hdmi1", "hdmi2"), list("hdmi1"), list("live", "hdmi1", "hdmi2"), list("live", "hdmi2"));
        arrange(null, list("hdmi1"), list("live", "hdmi1"), list("live"));
        arrange(list("hdmi3", "live"), list("hdmi3"), list("live", "hdmi1"), list("live", "hdmi1"));
        System.out.println("VisibleOrder: " + assertions + " assertions passed");
    }

    private static void permutations(List<String> saved, List<String> visible, int at) {
        if (at == visible.size()) {
            List<String> actual = VisibleOrder.replace(saved, visible), shown = new ArrayList<>();
            check(actual.size() == saved.size(), "no saved button lost or added");
            for (int i = 0; i < saved.size(); i++) {
                if (visible.contains(saved.get(i))) shown.add(actual.get(i));
                else check(actual.get(i).equals(saved.get(i)), "hidden button changed position");
            }
            check(shown.equals(visible), "visible order must match the move");
            return;
        }
        for (int i = at; i < visible.size(); i++) {
            Collections.swap(visible, at, i);
            permutations(saved, visible, at + 1);
            Collections.swap(visible, at, i);
        }
    }
}
