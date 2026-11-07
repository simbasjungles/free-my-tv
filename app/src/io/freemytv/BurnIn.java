package io.freemytv;

import android.os.SystemClock;

/**
 * Burn-in protection for the screens that stay still: the text, icons and cards step a few pixels every minute and a
 * half, around where they rest, so no bright edge stays on the same pixels of an OLED or plasma panel for hours. The
 * backgrounds don't move: they are gradients and photos, which are large and change on their own.
 */
final class BurnIn {
    /** How long each position holds. */
    private static final long STEP_MS = 90_000;
    /** The positions, in 1080p pixels (scaled with the screen): a small ring around the resting place, then the next
     *  step goes round it rather than back and forth. */
    private static final int[][] STEPS = {
            {0, 0}, {5, 0}, {5, 5}, {0, 5}, {-5, 5}, {-5, 0}, {-5, -5}, {0, -5}, {5, -5}
    };

    private BurnIn() {}

    /** Which position holds now. */
    static int step() {
        return (int) ((SystemClock.uptimeMillis() / STEP_MS) % STEPS.length);
    }

    static float x(int step) {
        return STEPS[step][0];
    }

    static float y(int step) {
        return STEPS[step][1];
    }
}
