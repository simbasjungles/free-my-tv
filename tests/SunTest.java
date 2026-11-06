package io.freemytv;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sunrise and sunset from the coordinates alone, against published times (sunrise-sunset.org, 2026-11-06). MET Norway's
 * compact forecast has no sunrise, and the night look needs one even before the first forecast arrives.
 */
public final class SunTest {
    private static int assertions;

    /** A few minutes of slack: the published times round to the minute and the equations to about one. */
    private static final double TOLERANCE_MINUTES = 3;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        // Moscow, the three dates that break naive code: midsummer, midwinter, and an ordinary day.
        sun("Moscow, midsummer", 55.7558, 37.6173, "2026-06-21", "2026-06-21T00:44:29Z", "2026-06-21T18:18:10Z");
        sun("Moscow, midwinter", 55.7558, 37.6173, "2026-12-21", "2026-12-21T05:57:17Z", "2026-12-21T12:57:47Z");
        sun("Moscow, November", 55.7558, 37.6173, "2026-11-06", "2026-11-06T04:46:07Z", "2026-11-06T13:40:10Z");
        // The south: a place the northern formulas get wrong when the latitude sign is dropped. Sydney's sunrise on
        // 21 June falls at 06:59 local, which is still the 20th in UTC.
        sun("Sydney, midwinter", -33.8688, 151.2093, "2026-06-21", "2026-06-20T20:59:55Z", "2026-06-21T06:53:52Z");

        // Polar day: no sunrise and no sunset, and the app shows neither instead of a wrong time.
        long[] polar = Sun.times(midday("2026-06-21"), 68.97, 33.08);
        check(polar[0] == 0 && polar[1] == 0, "Murmansk in June: the sun doesn't set, both times must be 0");

        // Sanity: sunrise is before sunset, and both are on the day asked for.
        long[] moscow = Sun.times(midday("2026-11-06"), 55.7558, 37.6173);
        check(moscow[0] < moscow[1], "sunrise must come before sunset");
        check(Math.abs(moscow[0] - midday("2026-11-06")) < 24 * 3600_000L, "sunrise must be on the day asked for");

        System.out.println("Sun: " + assertions + " assertions passed");
    }

    private static void sun(String name, double lat, double lon, String date, String rise, String set) throws Exception {
        long[] times = Sun.times(midday(date), lat, lon);
        check(Math.abs(times[0] - epoch(rise)) <= TOLERANCE_MINUTES * 60_000,
                name + ": sunrise off by " + Math.round((times[0] - epoch(rise)) / 60_000.0) + " min");
        check(Math.abs(times[1] - epoch(set)) <= TOLERANCE_MINUTES * 60_000,
                name + ": sunset off by " + Math.round((times[1] - epoch(set)) / 60_000.0) + " min");
    }

    private static long midday(String date) throws Exception {
        return epoch(date + "T12:00:00Z");
    }

    private static long epoch(String iso) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(iso).getTime();
    }
}
