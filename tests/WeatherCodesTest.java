package io.freemytv;

/**
 * MET Norway's symbol codes, including the ones that only appear with a suffix or with thunder: an unmapped code means
 * no icon on the card, which is the kind of thing nobody notices until it rains.
 */
public final class WeatherCodesTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void is(int expected, String symbol) {
        check(WeatherCodes.code(symbol) == expected,
                symbol + ": expected " + expected + ", got " + WeatherCodes.code(symbol));
    }

    public static void main(String[] args) {
        // The plain ones.
        is(0, "clearsky");
        is(1, "fair");
        is(2, "partlycloudy");
        is(3, "cloudy");
        is(45, "fog");
        is(61, "lightrain");
        is(63, "rain");
        is(65, "heavyrain");
        is(80, "lightrainshowers");
        is(81, "rainshowers");
        is(82, "heavyrainshowers");
        is(71, "lightsnow");
        is(73, "snow");
        is(75, "heavysnow");
        is(66, "lightsleet");
        is(67, "sleet");

        // The day and night suffixes are dropped, and the night is not a different kind of weather.
        is(0, "clearsky_day");
        is(0, "clearsky_night");
        is(2, "partlycloudy_polartwilight");

        // Showers of sleet and snow fall back to their base symbol instead of an empty icon.
        is(67, "sleetshowers");
        is(67, "heavysleetshowers");
        is(85, "lightsnowshowers");
        is(86, "snowshowers");

        // Thunder, in all its names.
        is(95, "rainandthunder");
        is(95, "lightrainandthunder");
        is(95, "snowandthunder");
        is(95, "sleetandthunder");
        is(95, "rainshowersandthunder");
        is(95, "lightsnowshowersandthunder");
        is(99, "heavyrainandthunder");
        is(99, "heavysnowandthunder");

        // An unknown symbol is -1 (no icon) rather than a wrong one.
        is(-1, "volcanoash");
        is(-1, "");
        is(-1, null);

        System.out.println("WeatherCodes: " + assertions + " assertions passed");
    }
}
