package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The two weather sources, and the one forecast model the rest of the app draws ({@link HomeData.Weather}).
 *
 * <p>Open-Meteo is the default: keyless, and it answers everywhere but on networks that block its host — some Russian
 * ISPs do, which is issue #450. MET Norway is the fallback: also keyless, and reachable there. Both are parsed into the
 * same model, so the screen, the screensaver and the night look don't know which one answered.
 */
final class WeatherSource {
    private WeatherSource() {}

    static final String OPEN_METEO_HOST = "api.open-meteo.com";
    static final String GEOCODING_HOST = "geocoding-api.open-meteo.com";

    /** Open-Meteo's forecast URL; {@code host} is the override from Home Settings (empty: the official host). */
    static String openMeteoUrl(String lat, String lon, String host, String query) {
        return "https://" + (host == null || host.trim().isEmpty() ? OPEN_METEO_HOST : host.trim())
                + "/v1/forecast?latitude=" + lat + "&longitude=" + lon + query;
    }

    /** The same, for the city search: the override swaps the host, keeping its {@code geocoding-} prefix. */
    static String geocodingUrl(String name, String language, String host) {
        String h = host == null || host.trim().isEmpty() ? GEOCODING_HOST : "geocoding-" + host.trim();
        return "https://" + h + "/v1/search?count=10&format=json&language=" + language + "&name=" + name;
    }

    /** MET Norway's compact forecast for a place: no key, one request, ~4 days of hourly points. */
    static String metNoUrl(String lat, String lon) {
        return "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + lat + "&lon=" + lon;
    }

    // MARK: - Open-Meteo

    static HomeData.Weather parseOpenMeteo(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        // Open-Meteo writes every time at the place's offset of now, even past a DST change (Berlin's sunrise after the
        // change still reads 07:51 at GMT+2): read them at that offset, as LG and Samsung do, not by the zone's rules.
        TimeZone zone = o.has("utc_offset_seconds") ? new SimpleTimeZoneUtc(o.getInt("utc_offset_seconds"))
                : TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        HomeData.Weather w = new HomeData.Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray night = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(night == null || night.optInt(i, 1) == 1);
            }
        }
        JSONArray dates = daily.getJSONArray("time"), highs = daily.getJSONArray("temperature_2m_max"),
                lows = daily.getJSONArray("temperature_2m_min"), codes = daily.optJSONArray("weather_code");
        long now = System.currentTimeMillis();
        for (int i = 0; codes != null && i < dates.length() && w.days.size() < 5; i++) {
            String date = dates.getString(i);
            // Only days that haven't started at the city: today, and past days in a forecast kept offline, are left out.
            if (localTime(date + "T12:00", zone) - 12 * 3600_000L <= now) continue;
            w.days.add(localTime(date + "T12:00", TimeZone.getDefault()));   // its noon on the TV's clock: named right anywhere
            w.dayMax.add(highs.optDouble(i));
            w.dayMin.add(lows.optDouble(i));
            w.dayCode.add(codes.optInt(i, -1));
        }
        return w;
    }

    // MARK: - MET Norway

    static HomeData.Weather parseMetNo(String body, double lat, double lon) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray series = root.getJSONObject("properties").getJSONArray("timeseries");
        HomeData.Weather w = new HomeData.Weather();
        TimeZone zone = TimeZone.getDefault();
        long now = System.currentTimeMillis();

        JSONObject first = series.getJSONObject(0).getJSONObject("data");
        JSONObject details = first.getJSONObject("instant").getJSONObject("details");
        w.temp = details.optDouble("air_temperature", Double.NaN);
        // The current condition, whatever the hourly loop below finds: "now" is what the card shows first.
        w.code = WeatherCodes.code(symbolOf(first));

        // Next hours: the series is hourly for the first days, then steps to 6 hours — the first point without a
        // next_1_hours block is where the switch happens.
        for (int i = 0; i < series.length() && w.hours.size() < 6; i++) {
            JSONObject point = series.getJSONObject(i);
            long at = iso(point.getString("time"));
            JSONObject data = point.getJSONObject("data");
            JSONObject next = data.optJSONObject("next_1_hours");
            if (next == null) break;
            if (at <= now) continue;
            w.hours.add(at);
            w.hourTemp.add(data.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", Double.NaN));
            String symbol = next.getJSONObject("summary").optString("symbol_code", "");
            w.hourCode.add(WeatherCodes.code(symbol));
            w.hourDay.add(!symbol.endsWith("_night"));
        }
        if (!w.hourCode.isEmpty()) {
            String symbol0 = first.optJSONObject("next_1_hours") == null ? ""
                    : first.getJSONObject("next_1_hours").getJSONObject("summary").optString("symbol_code", "");
            if (w.code < 0) w.code = WeatherCodes.code(symbol0);
        }

        // Days: highs and lows per local date, the symbol from the point nearest noon. Today is left out (the app
        // shows today's high and low on the card itself), and so is anything already past.
        Map<String, double[]> bounds = new HashMap<>();
        Map<String, Integer> noonCode = new HashMap<>();
        Map<String, Integer> noonDistance = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < series.length(); i++) {
            JSONObject point = series.getJSONObject(i);
            long at = iso(point.getString("time"));
            if (at <= now) continue;
            String date = localDate(at, zone);
            double temp = point.getJSONObject("data").getJSONObject("instant").getJSONObject("details")
                    .optDouble("air_temperature", Double.NaN);
            double[] b = bounds.get(date);
            if (b == null) {
                b = new double[] {temp, temp};
                bounds.put(date, b);
                order.add(date);
            } else {
                if (temp < b[0]) b[0] = temp;
                if (temp > b[1]) b[1] = temp;
            }
            int hour = localHour(at, zone);
            int distance = Math.abs(hour - 12);
            Integer best = noonDistance.get(date);
            if (best == null || distance < best) {
                JSONObject data = point.getJSONObject("data");
                JSONObject summary = data.optJSONObject("next_1_hours") != null
                        ? data.getJSONObject("next_1_hours").getJSONObject("summary")
                        : data.optJSONObject("next_6_hours") != null
                        ? data.getJSONObject("next_6_hours").getJSONObject("summary") : null;
                if (summary != null) {
                    noonDistance.put(date, distance);
                    noonCode.put(date, WeatherCodes.code(summary.optString("symbol_code", "")));
                }
            }
        }
        String today = localDate(now, zone);
        for (String date : order) {
            if (w.days.size() >= 5) break;
            if (date.equals(today)) continue;
            double[] b = bounds.get(date);
            w.days.add(noon(date, zone));
            w.dayMax.add(b[1]);
            w.dayMin.add(b[0]);
            Integer c = noonCode.get(date);
            w.dayCode.add(c == null ? -1 : c);
        }
        double[] todayBounds = bounds.get(today);
        if (todayBounds != null) {
            w.max = todayBounds[1];
            w.min = todayBounds[0];
        }
        if (Double.isNaN(w.max)) {   // no points left for today (late evening): the card shows what it has
            w.max = w.temp;
            w.min = w.temp;
        }

        // MET Norway sends no sunrise or sunset: they're computed here, without another request.
        long[] sun = Sun.times(now, lat, lon);
        w.sunrise = sun[0];
        w.sunset = sun[1];
        w.isDay = w.sunrise == 0 || w.sunset == 0 || (now >= w.sunrise && now < w.sunset);
        return w;
    }

    /** The symbol of a timeseries point: the next hour when it's there, otherwise the next six. */
    private static String symbolOf(JSONObject data) throws Exception {
        if (data == null) return "";
        JSONObject h1 = data.optJSONObject("next_1_hours");
        if (h1 != null) return h1.getJSONObject("summary").optString("symbol_code", "");
        JSONObject h6 = data.optJSONObject("next_6_hours");
        return h6 == null ? "" : h6.getJSONObject("summary").optString("symbol_code", "");
    }

    // MARK: - Time

    static long iso(String time) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(time.replace("Z", "").substring(0, 16)).getTime();
    }

    static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String localDate(long at, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(at));
    }

    private static int localHour(long at, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("H", Locale.US);
        f.setTimeZone(zone);
        return Integer.parseInt(f.format(new Date(at)));
    }

    /** Noon of that local date on the TV's clock: the day is named right wherever the city is. */
    private static long noon(String date, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(date + "T12:00").getTime();
    }

    /** A fixed offset, as Open-Meteo reports the place's (a DST change doesn't move its times). */
    private static final class SimpleTimeZoneUtc extends TimeZone {
        private final int offsetMillis;

        SimpleTimeZoneUtc(int offsetSeconds) {
            offsetMillis = offsetSeconds * 1000;
        }

        @Override public int getOffset(int era, int year, int month, int day, int dayOfWeek, int millis) {
            return offsetMillis;
        }

        @Override public int getOffset(long date) { return offsetMillis; }

        @Override public void setRawOffset(int offsetMillis) { throw new UnsupportedOperationException(); }

        @Override public int getRawOffset() { return offsetMillis; }

        @Override public boolean useDaylightTime() { return false; }

        @Override public boolean inDaylightTime(Date date) { return false; }
    }
}
