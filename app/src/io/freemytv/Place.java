package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Where the TV is when config.properties doesn't say (built with --generic, like the APK on GitHub's releases): the
 * TV's time zone gives the weather's city and position (assets/zones.json, the same table as LG and Samsung's
 * web/zones.js), and its country the currency and the TMDB region. A city picked in Home Settings wins over this, and
 * config.properties over both. A zone the table doesn't know leaves New York's position without a name, as on LG.
 */
final class Place {
    private Place() {}

    private static final Pattern EURO = Pattern.compile("AT|BE|BG|CY|DE|EE|ES|FI|FR|GR|HR|IE|IT|LT|LU|LV|MT|NL|PT|SI|SK");

    private static Context app;
    private static boolean loaded;
    private static String country = "US", latitude = "40.7128", longitude = "-74.0060", city = "";

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    /** Read on first use, not in App.onCreate: the processes that never show the weather never pay for it. */
    private static synchronized void load() {
        if (loaded || app == null) return;
        loaded = true;
        String zone = TimeZone.getDefault().getID();
        try (InputStream in = app.getAssets().open("zones.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject all = new JSONObject(out.toString("UTF-8"));
            String v = all.optString(zone, "");
            for (int hops = 0; v.startsWith("=") && hops < 3; hops++) {
                zone = v.substring(1);
                v = all.optString(zone, "");
            }
            String[] p = v.split(",", 4);
            if (p.length >= 3) {
                country = p[0];
                latitude = p[1];
                longitude = p[2];
                city = p.length > 3 ? p[3] : zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ');
            }
        } catch (Exception e) {
            AppLog.w("place: " + zone + ": " + e.getMessage());
        }
    }

    /** Whether config.properties left the place out (a --generic build). */
    static boolean fromZone() {
        return Config.LATITUDE.trim().isEmpty() || Config.LONGITUDE.trim().isEmpty();
    }

    static String city() {
        if (!fromZone()) return Config.CITY;
        load();
        return Config.CITY.trim().isEmpty() ? city : Config.CITY;
    }

    static String latitude() {
        if (!fromZone()) return Config.LATITUDE;
        load();
        return latitude;
    }

    static String longitude() {
        if (!fromZone()) return Config.LONGITUDE;
        load();
        return longitude;
    }

    /** Prices in reais in Brazil, in euros in the euro area, else in dollars (as on LG and Samsung). */
    static String currency() {
        String v = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
        if (!v.isEmpty()) return v;
        load();
        return country.equals("BR") ? "BRL" : EURO.matcher(country).matches() ? "EUR" : "USD";
    }

    /** The picks' streaming region. */
    static String region() {
        String v = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        if (!v.isEmpty()) return v;
        load();
        return country.toUpperCase(Locale.ROOT);
    }
}
