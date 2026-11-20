package io.freemytv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reorders the shown keys without moving or discarding saved keys absent from the screen. */
final class VisibleOrder {
    private VisibleOrder() {}

    static List<String> replace(List<String> saved, List<String> ordered) {
        List<String> out = new ArrayList<>(saved);
        int next = 0;
        for (int slot = 0; slot < out.size() && next < ordered.size(); slot++) {
            if (ordered.contains(out.get(slot))) out.set(slot, ordered.get(next++));
        }
        // A button newly discovered since the saved snapshot has no slot yet.
        while (next < ordered.size()) out.add(ordered.get(next++));
        return out;
    }

    /** The keys to show, in the person's order: the saved order first (a key the list no longer has is just
     *  skipped, its saved place kept), then the ones never ordered in the list's own order; hidden ones stay out. */
    static List<String> arrange(List<String> saved, List<String> hidden, List<String> current) {
        List<String> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        if (saved != null) {
            for (String key : saved) {
                if (hidden.contains(key) || !placed.add(key)) continue;
                if (current.contains(key)) out.add(key);
            }
        }
        for (String key : current) {
            if (!placed.contains(key) && !hidden.contains(key)) out.add(key);
        }
        return out;
    }
}
