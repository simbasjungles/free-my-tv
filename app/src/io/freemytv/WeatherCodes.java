package io.freemytv;

import java.util.HashMap;
import java.util.Map;

/**
 * MET Norway's {@code symbol_code} → Open-Meteo's WMO weather code, which is what the app's symbols are drawn from.
 *
 * <p>Kept apart from {@link WeatherSource} so it can be tested on a computer: no JSON, no Android.
 */
final class WeatherCodes {
    private WeatherCodes() {}

    /** The WMO code for a MET Norway symbol, day or night; {@code -1} when the symbol is unknown. */
    static int code(String symbol) {
        if (symbol == null || symbol.isEmpty()) return -1;
        String s = symbol.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
        // Thunder has a dozen names (rainandthunder, lightsnowshowersandthunder…): one rule covers them all.
        if (s.contains("andthunder")) return s.startsWith("heavy") ? 99 : 95;
        Integer c = SYMBOL.get(s);
        if (c == null) c = SYMBOL.get(s.replace("showers", ""));   // sleetshowers → sleet, and so on
        return c == null ? -1 : c;
    }

    private static final Map<String, Integer> SYMBOL = new HashMap<>();

    static {
        SYMBOL.put("clearsky", 0);
        SYMBOL.put("fair", 1);
        SYMBOL.put("partlycloudy", 2);
        SYMBOL.put("cloudy", 3);
        SYMBOL.put("fog", 45);
        SYMBOL.put("lightrain", 61);
        SYMBOL.put("lightrainshowers", 80);
        SYMBOL.put("rain", 63);
        SYMBOL.put("rainshowers", 81);
        SYMBOL.put("heavyrain", 65);
        SYMBOL.put("heavyrainshowers", 82);
        SYMBOL.put("lightsleet", 66);
        SYMBOL.put("sleet", 67);
        SYMBOL.put("heavysleet", 67);
        SYMBOL.put("lightsnow", 71);
        SYMBOL.put("lightsnowshowers", 85);
        SYMBOL.put("snow", 73);
        SYMBOL.put("snowshowers", 86);
        SYMBOL.put("heavysnow", 75);
        SYMBOL.put("heavysnowshowers", 86);
    }
}
