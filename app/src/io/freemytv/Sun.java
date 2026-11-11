package io.freemytv;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise and sunset for a place, from its latitude and longitude alone: no request, no key.
 *
 * <p>Used when the weather source doesn't send them (MET Norway's compact forecast has no sunrise), and as the
 * fallback for the night look when nothing has been fetched. The NOAA solar position equations, ~1 minute of error.
 */
final class Sun {
    private Sun() {}

    private static final double RAD = Math.PI / 180;
    /** The sun's centre 0.833° below the horizon: refraction plus the disc's radius, the usual "sunrise". */
    private static final double ZENITH = 90.833 * RAD;

    /**
     * Today's sunrise and sunset at this place, in epoch milliseconds. Both are {@code 0} when the sun doesn't rise
     * or doesn't set that day (polar day or night).
     *
     * @param at any moment of the day wanted
     */
    static long[] times(long at, double lat, double lon) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(at);
        int year = cal.get(Calendar.YEAR);
        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;

        // Fractional year, in radians: the only input the equations need (NOAA's simple form).
        double gamma = 2 * Math.PI / (leap ? 366 : 365) * (dayOfYear - 1 + 0.5);

        // Equation of time and solar declination, both from that angle.
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);

        double latRad = lat * RAD;
        double cosHourAngle = (Math.cos(ZENITH) / (Math.cos(latRad) * Math.cos(decl))) - Math.tan(latRad) * Math.tan(decl);
        if (cosHourAngle >= 1 || cosHourAngle <= -1) return new long[] {0, 0};

        double hourAngle = Math.acos(cosHourAngle) / RAD;   // degrees
        // Minutes from 00:00 UTC at this longitude, then moved onto the day's UTC midnight.
        long midnight = utcMidnight(cal);
        long sunrise = midnight + Math.round((720 - 4 * (lon + hourAngle) - eqTime) * 60_000L);
        long sunset = midnight + Math.round((720 - 4 * (lon - hourAngle) - eqTime) * 60_000L);
        return new long[] {sunrise, sunset};
    }

    private static long utcMidnight(Calendar cal) {
        Calendar c = (Calendar) cal.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
