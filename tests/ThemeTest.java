package io.freemytv;

/** The theme's periods: automatic (the sun), light, sunset, dark, and the fixed schedule through the day. */
public final class ThemeTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static final int H = 60;
    private static final int RISE = 6 * H, SET = 18 * H;

    public static void main(String[] args) {
        check(Theme.valid(Theme.AUTO) && Theme.valid(Theme.LIGHT) && Theme.valid(Theme.SUNSET)
                && Theme.valid(Theme.DARK) && Theme.valid(Theme.SCHEDULE), "the five modes");
        check(!Theme.valid("sepia") && !Theme.valid(null) && !Theme.valid(""), "anything else isn't a mode");
        check(Theme.normalize("sepia").equals(Theme.AUTO), "an unknown mode falls back to automatic");
        check(Theme.normalize(Theme.DARK).equals(Theme.DARK), "a known mode is kept");
        check(Theme.MODES.length == 5, "the quick switch has five stops");

        // Automatic: from sunset to sunrise, and the edges belong to the night at sunset but not at sunrise.
        check(Theme.period(Theme.AUTO, 12 * H, RISE, SET).equals(Theme.PERIOD_LIGHT), "automatic by day");
        check(Theme.period(Theme.AUTO, 23 * H, RISE, SET).equals(Theme.PERIOD_DARK), "automatic at night");
        check(Theme.period(Theme.AUTO, 5 * H, RISE, SET).equals(Theme.PERIOD_DARK), "automatic before sunrise");
        check(Theme.period(Theme.AUTO, SET, RISE, SET).equals(Theme.PERIOD_DARK), "sunset starts the night");
        check(Theme.period(Theme.AUTO, RISE, RISE, SET).equals(Theme.PERIOD_LIGHT), "sunrise ends it");
        check(Theme.isNight(Theme.AUTO, 23 * H, RISE, SET, 0, 0), "automatic at night is night");
        check(!Theme.isNight(Theme.AUTO, 12 * H, RISE, SET, 0, 0), "automatic by day is not night");

        check(Theme.period(Theme.DARK, 12 * H, RISE, SET).equals(Theme.PERIOD_DARK), "dark is dark by day too");
        check(Theme.period(Theme.LIGHT, 23 * H, RISE, SET).equals(Theme.PERIOD_LIGHT), "light is light at night too");
        check(Theme.period(Theme.SUNSET, 3 * H, RISE, SET).equals(Theme.PERIOD_SUNSET), "sunset is always sunset");
        check(Theme.isSunset(Theme.SUNSET, 3 * H, RISE, SET), "sunset ignores the sun");
        check(!Theme.isNight(Theme.SUNSET, 23 * H, RISE, SET, 0, 0), "sunset is not night");
        check(Theme.isNight(Theme.DARK, 0, 0, 0, 0, 0), "dark ignores the sun");

        // The fixed schedule: 05:30–11:59 light, 12:00–17:59 sunset, 18:00–22:00 dark, 22:00–05:29 late.
        check(Theme.period(Theme.SCHEDULE, 0, RISE, SET).equals(Theme.PERIOD_LATE), "midnight is late night");
        check(Theme.period(Theme.SCHEDULE, 5 * H + 29, RISE, SET).equals(Theme.PERIOD_LATE), "05:29 is late night");
        check(Theme.period(Theme.SCHEDULE, 5 * H + 30, RISE, SET).equals(Theme.PERIOD_LIGHT), "05:30 is light");
        check(Theme.period(Theme.SCHEDULE, 11 * H + 59, RISE, SET).equals(Theme.PERIOD_LIGHT), "11:59 is light");
        check(Theme.period(Theme.SCHEDULE, 12 * H, RISE, SET).equals(Theme.PERIOD_SUNSET), "12:00 is sunset");
        check(Theme.period(Theme.SCHEDULE, 17 * H + 59, RISE, SET).equals(Theme.PERIOD_SUNSET), "17:59 is sunset");
        check(Theme.period(Theme.SCHEDULE, 18 * H, RISE, SET).equals(Theme.PERIOD_DARK), "18:00 is dark");
        check(Theme.period(Theme.SCHEDULE, 21 * H + 59, RISE, SET).equals(Theme.PERIOD_DARK), "21:59 is dark");
        check(Theme.period(Theme.SCHEDULE, 22 * H, RISE, SET).equals(Theme.PERIOD_LATE), "22:00 is late night");
        check(Theme.period(Theme.SCHEDULE, 23 * H + 59, RISE, SET).equals(Theme.PERIOD_LATE), "23:59 is late night");
        check(Theme.isNight(Theme.SCHEDULE, 19 * H, RISE, SET, 0, 0), "the evening is night");
        check(Theme.isNight(Theme.SCHEDULE, 23 * H, RISE, SET, 0, 0), "late night is night");
        check(!Theme.isNight(Theme.SCHEDULE, 13 * H, RISE, SET, 0, 0), "the afternoon is not night");
        check(Theme.isSunset(Theme.SCHEDULE, 15 * H, RISE, SET), "the afternoon is sunset");
        check(Theme.isLate(Theme.SCHEDULE, 23 * H, RISE, SET), "late night is late");
        check(!Theme.isLate(Theme.SCHEDULE, 19 * H, RISE, SET), "the evening is not late");
        // The schedule ignores the sun and any old custom times.
        check(Theme.period(Theme.SCHEDULE, 20 * H, 20 * H, 21 * H).equals(Theme.PERIOD_DARK),
                "the schedule isn't the sun");
        check(Theme.isNight(Theme.SCHEDULE, 20 * H, RISE, SET, 6 * H, 8 * H), "old custom times are ignored");
        // A time past a day is the next day's.
        check(Theme.period(Theme.SCHEDULE, 24 * H + 13 * H, RISE, SET).equals(Theme.PERIOD_SUNSET),
                "a time past a day is the next day's");

        // The status icon per period (R2): sun by day, sunset in the afternoon, moon at night, badge at 22:00–05:29.
        check(Theme.periodSymbol(Theme.PERIOD_LIGHT).equals("sunny"), "light is the sun");
        check(Theme.periodSymbol(Theme.PERIOD_SUNSET).equals("sunset"), "the afternoon is the sunset");
        check(Theme.periodSymbol(Theme.PERIOD_DARK).equals("clear_night"), "dark is the moon");
        check(Theme.periodSymbol(Theme.PERIOD_LATE).equals("bedtime"), "late night is the badge");
        check(Theme.periodSymbol("sepia").equals("sunny"), "an unknown period reads as light");
        // Every mode reaches its icon: automatic through the sun, the fixed ones straight away.
        check(Theme.periodSymbol(Theme.period(Theme.AUTO, 12 * H, RISE, SET)).equals("sunny"), "automatic by day");
        check(Theme.periodSymbol(Theme.period(Theme.AUTO, 23 * H, RISE, SET)).equals("clear_night"), "automatic at night");
        check(Theme.periodSymbol(Theme.period(Theme.LIGHT, 23 * H, RISE, SET)).equals("sunny"), "light stays the sun");
        check(Theme.periodSymbol(Theme.period(Theme.SUNSET, 3 * H, RISE, SET)).equals("sunset"), "sunset stays itself");
        check(Theme.periodSymbol(Theme.period(Theme.DARK, 12 * H, RISE, SET)).equals("clear_night"), "dark stays the moon");
        check(Theme.periodSymbol(Theme.period(Theme.SCHEDULE, 8 * H, RISE, SET)).equals("sunny"), "schedule morning");
        check(Theme.periodSymbol(Theme.period(Theme.SCHEDULE, 15 * H, RISE, SET)).equals("sunset"), "schedule afternoon");
        check(Theme.periodSymbol(Theme.period(Theme.SCHEDULE, 20 * H, RISE, SET)).equals("clear_night"), "schedule evening");
        check(Theme.periodSymbol(Theme.period(Theme.SCHEDULE, 23 * H, RISE, SET)).equals("bedtime"), "schedule late night");
        // The warm list has to cover every icon the row can draw (the period changes without a relayout).
        for (String period : new String[] {Theme.PERIOD_LIGHT, Theme.PERIOD_SUNSET, Theme.PERIOD_DARK, Theme.PERIOD_LATE}) {
            boolean warmed = false;
            for (String name : Theme.PERIOD_SYMBOLS) warmed |= name.equals(Theme.periodSymbol(period));
            check(warmed, "the warmed icons cover " + period);
        }

        check(Theme.inWindow(0, 22 * H, 6 * H), "midnight is in 22:00–06:00");
        check(!Theme.inWindow(6 * H, 22 * H, 6 * H), "06:00 is out (the end is exclusive)");
        check(Theme.inWindow(22 * H, 22 * H, 6 * H), "22:00 is in (the start is inclusive)");
        check(!Theme.inWindow(12 * H, 22 * H, 6 * H), "noon is out");
        check(Theme.inWindow(25 * H, 22 * H, 6 * H), "a time past a day is the next day's");
        check(Theme.inWindow(-1, 22 * H, 6 * H), "a time before midnight wraps back");

        check(Theme.dayMinute(25 * H + 30) == 1 * H + 30, "past a day");
        check(Theme.dayMinute(-30) == 23 * H + 30, "before a day");
        check(Theme.dayMinute(18 * H) == 18 * H, "inside a day");

        check(Theme.parseMinutes("18:30") == 18 * H + 30, "a time");
        check(Theme.parseMinutes("6:05") == 6 * H + 5, "a short hour");
        check(Theme.parseMinutes(" 06:05 ") == 6 * H + 5, "spaces around it");
        check(Theme.parseMinutes("24:00") == -1, "no 24:00");
        check(Theme.parseMinutes("12:60") == -1, "no 12:60");
        check(Theme.parseMinutes("-1:00") == -1, "no negative hours");
        check(Theme.parseMinutes("18") == -1, "no bare hour");
        check(Theme.parseMinutes("18:aa") == -1, "not a number");
        check(Theme.parseMinutes(null) == -1, "nothing");

        check(Theme.formatMinutes(18 * H + 30).equals("18:30"), "a time as text");
        check(Theme.formatMinutes(6 * H + 5).equals("06:05"), "the hour is padded");
        check(Theme.formatMinutes(0).equals("00:00"), "midnight");
        check(Theme.formatMinutes(24 * H + 5).equals("00:05"), "past a day");

        check(Theme.MORNING == 5 * H + 30, "the morning starts at 05:30");
        check(Theme.NOON == 12 * H, "the afternoon starts at 12:00");
        check(Theme.EVENING == 18 * H, "the evening starts at 18:00");
        check(Theme.NIGHT == 22 * H, "late night starts at 22:00");

        System.out.println("Theme: " + assertions + " assertions passed");
    }
}
