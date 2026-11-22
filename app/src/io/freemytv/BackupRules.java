package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a settings file may carry (Backup): which settings, and what counts as a good value for each. Plain Java, so
 * tests/BackupRulesTest.java can run it on a computer. Anything not named here (a key, a token, a cache, something tied
 * to one TV) is never exported and never imported.
 */
final class BackupRules {
    private BackupRules() {}

    static final List<String> BOOLEANS = Collections.unmodifiableList(Arrays.asList("hideAnimation", "statusicons",
            "sounds", "saverWatch", "weather", "quotes", "photos", "androidapps", "shelfRecent", "sleepShown"));
    /** Comma lists (shelf, top buttons, inputs, services): not set in Home Settings until the person changes them. */
    static final List<String> LISTS = Collections.unmodifiableList(Arrays.asList("servicesOff", "servicesOn", "shelf",
            "topButtons", "inputsOrder", "inputsHidden"));
    static final List<String> CHOICES = Collections.unmodifiableList(Arrays.asList("language", "temperature", "theme",
            "clock", "topContent", "recents", "powerOn", "forecast", "cards", "sleepPlace"));
    static final int COINS = 10;

    private static final Pattern LIST = Pattern.compile("[A-Za-z0-9_.,:#-]{0,2000}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]{1,200}");
    private static final Pattern KEY_APP = Pattern.compile("[A-Za-z0-9_.#]{0,200}");   // a package, "" (nothing) or "pkg#panel"
    private static final Pattern COIN = Pattern.compile("coin[0-9]");

    /** Every setting the file may carry, one by one (the three of the city are named by city()). */
    static List<String> managed() {
        List<String> all = new ArrayList<>(BOOLEANS);
        all.addAll(LISTS);
        all.addAll(CHOICES);
        for (int i = 0; i < COINS; i++) all.add("coin" + i);
        all.add("watchOff");
        return all;
    }

    static List<String> city() {
        return Arrays.asList("city", "latitude", "longitude");
    }

    /** Whether `value` is a good value for the setting `key`; false for any key the file may not carry. */
    static boolean ok(String key, Object value, List<String> languages, int maxCards, int places) {
        if (BOOLEANS.contains(key) || COIN.matcher(key).matches()) return value instanceof Boolean;
        if (LISTS.contains(key)) return value instanceof String && LIST.matcher((String) value).matches();
        switch (key) {
            case "language": return value instanceof String && (value.equals("auto") || languages.contains(value));
            case "temperature": return one(value, "auto", "C", "F");
            case "theme": return one(value, "auto", "light", "dark");
            case "clock": return one(value, "auto", "24", "12");
            case "topContent": return one(value, "picks", "continue", "none");
            case "recents": return one(value, "off", "drawer", "home");
            case "powerOn": return one(value, "tv", "home", "live");
            case "forecast": return one(value, "hours", "days");
            case "cards": return value instanceof Integer && (Integer) value >= 3 && (Integer) value <= maxCards;
            case "sleepPlace": return value instanceof Integer && (Integer) value >= 0 && (Integer) value < places;
            default: return false;
        }
    }

    private static boolean one(Object value, String... allowed) {
        return value instanceof String && Arrays.asList(allowed).contains(value);
    }

    private static boolean degrees(String s, double limit) {
        try {
            double d = Double.parseDouble(s);
            return !Double.isNaN(d) && Math.abs(d) <= limit;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A city comes with real coordinates, or not at all. */
    static boolean city(String name, String latitude, String longitude) {
        return name != null && name.length() <= 80 && name.indexOf('\n') < 0 && latitude != null && longitude != null
                && degrees(latitude, 90) && degrees(longitude, 180);
    }

    static boolean packageName(String s) {
        return s != null && PACKAGE.matcher(s).matches();
    }

    /** What a Remote Buttons key may open: nothing (""), a package, or one of Free My TV's own screens ("pkg#panel"). */
    static boolean keyApp(String s) {
        return s != null && KEY_APP.matcher(s).matches();
    }
}
