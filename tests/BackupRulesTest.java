package io.freemytv;

import java.util.Arrays;
import java.util.List;

public final class BackupRulesTest {
    private static int assertions;
    private static final List<String> LANGUAGES = Arrays.asList("en", "de", "hu");

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static boolean ok(String key, Object value) {
        return BackupRules.ok(key, value, LANGUAGES, 6, 9);
    }

    public static void main(String[] args) {
        check(ok("theme", "dark") && !ok("theme", "banana") && !ok("theme", 3), "theme takes its three words");
        check(ok("clock", "12") && ok("clock", "24") && !ok("clock", 12), "clock is a word, not a number");
        check(ok("language", "auto") && ok("language", "hu") && !ok("language", "xx") && !ok("language", ""),
                "language is auto or a catalogue");
        check(ok("cards", 3) && ok("cards", 6) && !ok("cards", 2) && !ok("cards", 7) && !ok("cards", "4"), "cards stay in range");
        check(ok("sleepPlace", 0) && ok("sleepPlace", 8) && !ok("sleepPlace", 9) && !ok("sleepPlace", -1),
                "sleep place stays in range");
        check(ok("sounds", true) && !ok("sounds", "true") && !ok("sounds", 1), "booleans are booleans");
        check(ok("coin0", false) && ok("coin9", true) && !ok("coin10", true) && !ok("coin", true), "coins are coin0 to coin9");
        check(ok("shelf", "netflix,youtube") && ok("shelf", "") && !ok("shelf", "a b") && !ok("shelf", "a;rm"),
                "lists are plain words");
        check(!ok("shelf", new String(new char[2001]).replace((char) 0, 'a')), "a list is cut at 2000 characters");
        check(ok("topContent", "continue") && !ok("topContent", "all") && ok("forecast", "days") && !ok("forecast", "weeks"),
                "words of top content and forecast");
        check(ok("recents", "home") && ok("powerOn", "live") && !ok("powerOn", "off"), "words of recents and power-on");
        for (String secret : new String[] {"tmdbKey", "watchmodeKey", "access", "refresh", "nowPlaying", "homeAssistant",
                "liveButton", "photoSource", "photoAlbum", "sleepAt", "sleepFrom", "watchHidden", "key"}) {
            check(!ok(secret, "x") && !ok(secret, true) && !ok(secret, 1), secret + " must never be in a file");
        }
        check(BackupRules.managed().contains("shelf") && BackupRules.managed().contains("watchOff")
                && BackupRules.managed().size() == 10 + 6 + 10 + 10 + 1, "the managed list");
        check(BackupRules.city("Harka", "47.6", "16.6") && !BackupRules.city("Harka", "95", "16.6")
                && !BackupRules.city("Harka", "47.6", "181") && !BackupRules.city("Harka", "NaN", "16.6")
                && !BackupRules.city("Harka", "x", "16.6") && !BackupRules.city(null, "47.6", "16.6")
                && !BackupRules.city("a\nb", "47.6", "16.6"), "a city comes with real coordinates");
        check(BackupRules.packageName("com.netflix.ninja") && !BackupRules.packageName("a b") && !BackupRules.packageName(""),
                "package names");
        check(BackupRules.keyApp("") && BackupRules.keyApp("com.netflix.ninja") && BackupRules.keyApp("io.freemytv#panel")
                && !BackupRules.keyApp("a b") && !BackupRules.keyApp(null), "what a remote button may open");
        System.out.println("BackupRules: " + assertions + " assertions passed");
    }
}
