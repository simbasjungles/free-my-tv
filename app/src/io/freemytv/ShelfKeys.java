package io.freemytv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which saved keys the home shelf shows: an app that still launches, or an input the TV lists (an HDMI port or
 * Live TV). Settings > Shelf offers inputs like any app and a person picks them (off until picked), so a key that
 * neither launches nor names a listed input holds no place: an uninstalled app, a port the TV no longer has.
 */
final class ShelfKeys {
    private ShelfKeys() {}

    /** Whether the shelf shows this saved key. */
    static boolean holds(String key, boolean launchable, Set<String> inputs) {
        return launchable || inputs.contains(key);
    }

    /** The saved keys the shelf shows, in order, up to `max`: what counts toward the limit. */
    static List<String> resolve(List<String> saved, Set<String> launchable, Set<String> inputs, int max) {
        List<String> out = new ArrayList<>();
        for (String key : saved) {
            if (out.size() >= max) break;
            if (holds(key, launchable.contains(key), inputs)) out.add(key);
        }
        return out;
    }

    /** What a reorder writes back for an item: its package, or its key when it names an input. */
    static String stored(String pkg, String key) {
        return pkg != null ? pkg : key;
    }
}
