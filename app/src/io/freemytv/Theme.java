package io.freemytv;

import java.util.Locale;

/**
 * The home screen's appearance: automatic (dark from sunset to sunrise), always light, always sunset, always dark, or
 * the fixed daily schedule. Times are minutes from midnight, so this is pure logic (tests/ThemeTest.java); HomeData
 * turns the clock and the sun into them.
 *
 * <p>The schedule has four periods, matching the light through the day:
 * 05:30–11:59 light (sunrise), 12:00–17:59 sunset, 18:00–22:00 dark, and 22:00–05:29 late night (the dark design with
 * a night badge; the TV's own brightness and volume are never changed).
 */
final class Theme {
    static final String AUTO = "auto", LIGHT = "light", SUNSET = "sunset", DARK = "dark", SCHEDULE = "schedule";
    static final String[] MODES = {AUTO, LIGHT, SUNSET, DARK, SCHEDULE};

    /** The four periods the schedule walks through. */
    static final String PERIOD_LIGHT = "light", PERIOD_SUNSET = "sunset", PERIOD_DARK = "dark", PERIOD_LATE = "late";

    /** The fixed schedule's boundaries, in minutes from midnight. */
    static final int MORNING = 5 * 60 + 30, NOON = 12 * 60, EVENING = 18 * 60, NIGHT = 22 * 60;

    /** Every icon periodSymbol can pick, so the layout warms them all (the period changes without a relayout). */
    static final String[] PERIOD_SYMBOLS = {"sunny", "sunset", "clear_night", "bedtime"};

    /** Kept for older preferences and callers: the old default evening, now ignored by the fixed schedule. */
    static final int DEFAULT_START = EVENING, DEFAULT_END = 6 * 60;

    private Theme() {}

    static boolean valid(String mode) {
        return AUTO.equals(mode) || LIGHT.equals(mode) || SUNSET.equals(mode) || DARK.equals(mode)
                || SCHEDULE.equals(mode);
    }

    /** The mode, or automatic for anything else (an old or hand-edited preference). */
    static String normalize(String mode) {
        return valid(mode) ? mode : AUTO;
    }

    /**
     * The period now, from the mode and the time of day (minutes from midnight). `sunrise` and `sunset` are automatic's;
     * the fixed schedule ignores them. Automatic stays light by day and dark at night (the sun's own sunset/sunrise).
     */
    static String period(String mode, int now, int sunrise, int sunset) {
        mode = normalize(mode);
        if (LIGHT.equals(mode)) return PERIOD_LIGHT;
        if (SUNSET.equals(mode)) return PERIOD_SUNSET;
        if (DARK.equals(mode)) return PERIOD_DARK;
        now = dayMinute(now);
        if (SCHEDULE.equals(mode)) {
            if (now < MORNING || now >= NIGHT) return PERIOD_LATE;
            if (now < NOON) return PERIOD_LIGHT;
            if (now < EVENING) return PERIOD_SUNSET;
            return PERIOD_DARK;
        }
        return now < dayMinute(sunrise) || now >= dayMinute(sunset) ? PERIOD_DARK : PERIOD_LIGHT;
    }

    /**
     * The status-bar icon for a period (the theme as it looks right now): the sun by day, the sunset in the
     * afternoon, the moon at night and the late-night badge from 22:00–05:29. Unknown periods read as light.
     */
    static String periodSymbol(String period) {
        if (PERIOD_SUNSET.equals(period)) return "sunset";
        if (PERIOD_DARK.equals(period)) return "clear_night";
        if (PERIOD_LATE.equals(period)) return "bedtime";
        return "sunny";
    }

    /** Night now: dark or late night (the schedule's 18:00–05:29). */
    static boolean isNight(String mode, int now, int sunrise, int sunset, int start, int end) {
        String p = period(mode, now, sunrise, sunset);
        return PERIOD_DARK.equals(p) || PERIOD_LATE.equals(p);
    }

    /** The warm sunset period (12:00–17:59 on the schedule). */
    static boolean isSunset(String mode, int now, int sunrise, int sunset) {
        return PERIOD_SUNSET.equals(period(mode, now, sunrise, sunset));
    }

    /** The late-night period (22:00–05:29 on the schedule): dark, with the night badge. */
    static boolean isLate(String mode, int now, int sunrise, int sunset) {
        return PERIOD_LATE.equals(period(mode, now, sunrise, sunset));
    }

    /** Whether `now` is in [start, end), wrapping around midnight when start is later than end. */
    static boolean inWindow(int now, int start, int end) {
        now = dayMinute(now);
        start = dayMinute(start);
        end = dayMinute(end);
        if (start == end) return false;
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** Minutes from midnight, also for a value outside a day (a duration or a bad preference). */
    static int dayMinute(int minutes) {
        int m = minutes % (24 * 60);
        return m < 0 ? m + 24 * 60 : m;
    }

    /** "18:30" (or "6:05") as minutes from midnight, or -1 when it isn't a time. */
    static int parseMinutes(String text) {
        if (text == null) return -1;
        String s = text.trim();
        int colon = s.indexOf(':');
        if (colon < 0) return -1;
        try {
            int hours = Integer.parseInt(s.substring(0, colon).trim());
            int minutes = Integer.parseInt(s.substring(colon + 1).trim());
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return -1;
            return hours * 60 + minutes;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "18:30" for a time of day (and the same clock time for a value outside one). */
    static String formatMinutes(int minutes) {
        int m = dayMinute(minutes);
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
}
