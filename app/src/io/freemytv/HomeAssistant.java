package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Buttons for your own Home Assistant in the bottom drawer. The address and the entities come with the build
 * (config.properties: home_assistant_url, _entities); the token never does, since any app on the TV can read an
 * installed APK: `tools/send_key.py <IP> home_assistant_token` sends it over adb into the app's private files (Secrets).
 * Nothing runs until Home Settings › Home Assistant is on: then the drawer reads the entities' states while it is open
 * (when it opens, then every 30 s) and OK calls the entity's service. The only host is the server you name. Locks, alarms, covers
 * outside SAFE_COVERS (garage doors and gates, and those without a class), and every other domain outside DOMAINS, are
 * never offered: anyone in the room can press a TV remote.
 */
final class HomeAssistant {
    private HomeAssistant() {}

    /** What a tile can be, and what OK does with it (see service()). */
    static final List<String> DOMAINS = Collections.unmodifiableList(Arrays.asList(
            "light", "switch", "fan", "input_boolean", "cover", "scene", "script", "automation", "button", "input_button"));
    /** At most this many tiles: the drawer's row stays readable next to the inputs and shortcuts. */
    static final int MAX = 8;
    /** While the drawer is open, the states are read again this often. */
    static final long REFRESH_MS = 30_000;

    /**
     * The only covers offered, by Home Assistant's device class: what shades a window. Garage doors, gates and doors open a
     * way into the house, like locks, and many template and MQTT garage doors have no class: those are left out.
     */
    static final List<String> SAFE_COVERS = Collections.unmodifiableList(
            Arrays.asList("awning", "blind", "curtain", "shade", "shutter"));

    private static final String SERVER = trimSlash(Config.HA_URL.trim());

    /** One entity from home_assistant_entities. Its name and state change as the server answers. */
    static final class Control {
        final String entity, domain;
        volatile String name;
        volatile String state = "";   // as Home Assistant says it ("on", "off", "open", "unavailable"…); "" = not read
        volatile String deviceClass;   // a cover's, once read ("" when it has none); null = not read yet

        Control(String entity) {
            this.entity = entity;
            this.domain = entity.substring(0, entity.indexOf('.'));
            this.name = fallbackName(entity);
        }

        /** Lit tile: a light, switch or fan that is on, a cover that is open. Scenes, scripts and buttons stay lit. */
        boolean on() {
            if (state.equals("unavailable") || state.equals("unknown")) return false;
            switch (domain) {
                case "light": case "switch": case "fan": case "input_boolean": case "automation":
                    return state.equals("on");
                case "cover":
                    return state.equals("open") || state.equals("opening");
                default:
                    return true;
            }
        }

        /** Something that is on or off (TalkBack says which); scenes, scripts and buttons are only pressed. */
        boolean hasOnOff() {
            return !domain.equals("scene") && !domain.equals("script") && !domain.equals("button")
                    && !domain.equals("input_button");
        }

        boolean unavailable() {
            return state.equals("unavailable");
        }

        /** A tile can be shown: a cover only once its class is read, and only one of SAFE_COVERS. */
        boolean offered() {
            if (!domain.equals("cover")) return true;
            String dc = deviceClass;
            return dc != null && SAFE_COVERS.contains(dc);
        }
    }

    private static final List<Control> CONTROLS = parse(Config.HA_ENTITIES);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-assistant");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean names, fetching;
    /** Whether the token file was there at the last look (hasToken, token()): what the UI thread goes by. */
    private static volatile boolean tokenThere;
    private static volatile long readAt;

    /** Built with an address and entities (Config.HOME_ASSISTANT): only then is there a Home Settings row. */
    static boolean offered() {
        return Config.HOME_ASSISTANT && !SERVER.isEmpty();
    }

    /** At least one entity it can show, out of home_assistant_entities. */
    static boolean hasEntities() {
        return !CONTROLS.isEmpty();
    }

    /** Built with a server and entities, and its token came from a computer: without them, nothing here runs. */
    static boolean configured(Context c) {
        return offered() && hasEntities() && hasToken(c);
    }

    /** Configured and turned on in Home Settings. Looks at the token file: not for the UI thread (onNow). */
    static boolean on(Context c) {
        return Prefs.homeAssistant() && configured(c);
    }

    /** on(), with the token as last seen: no file read, for the UI thread. A token taken away shows at the next read. */
    private static boolean onNow() {
        return Prefs.homeAssistant() && offered() && hasEntities() && tokenThere;
    }

    static List<Control> controls() {
        return CONTROLS;
    }

    /** The drawer's tiles, one per entity, with the names last read from the server. Off the UI thread (it reads files). */
    static List<HomeApps.Item> items(Context c) {
        return on(c) ? tiles(c) : new ArrayList<>();
    }

    /** items(), with the token as last seen: for the UI thread, once items() or a read has run. */
    static List<HomeApps.Item> itemsNow(Context c) {
        return onNow() ? tiles(c) : new ArrayList<>();
    }

    private static List<HomeApps.Item> tiles(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        loadNames(c);
        for (Control ctl : CONTROLS) {
            if (ctl.offered()) out.add(HomeApps.homeAssistant(ctl, symbol(ctl.domain)));
        }
        return out;
    }

    /** The symbol for a tile (app/assets/symbols). */
    static String symbol(String domain) {
        switch (domain) {
            case "light": return "lightbulb";
            case "switch": case "input_boolean": return "toggle";
            case "fan": return "fan";
            case "cover": return "blinds";
            case "scene": return "sparkles";
            case "script": return "play";
            case "automation": return "bolt";
            default: return "smart_button";
        }
    }

    // MARK: - Reading the states

    /**
     * Reads every entity's state and name (and a cover's class), off the main thread; `done` runs on the main thread
     * when they're in, and then items() may have changed (a cover shows once its class is known, or goes).
     */
    static void refresh(Context c, Runnable done) {
        if (!onNow() || fetching) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            boolean changed = false;
            try {
                String token = token(app);
                for (Control ctl : CONTROLS) changed |= read(ctl, token);
                readAt = System.currentTimeMillis();
            } catch (IOException e) {
                AppLog.w("home assistant: " + e.getMessage());
                for (Control ctl : CONTROLS) {
                    changed |= !ctl.state.isEmpty();
                    ctl.state = "";   // shown dimmed, as not read, until the server answers again
                }
            } finally {
                fetching = false;
            }
            if (changed) saveNames(app);
            if (done != null) MAIN.post(done);
        });
    }

    /** True if the name, the state or the class changed. A missing entity reads as unavailable; any other error stops the round. */
    private static boolean read(Control ctl, String token) throws IOException {
        String before = ctl.name + "\n" + ctl.state + "\n" + ctl.deviceClass;
        try {
            JSONObject o = new JSONObject(request(token, "GET", "/api/states/" + ctl.entity, null));
            ctl.state = o.optString("state", "");
            JSONObject attributes = o.optJSONObject("attributes");
            String name = attributes == null ? "" : attributes.optString("friendly_name", "").trim();
            if (!name.isEmpty()) ctl.name = name;
            if (ctl.domain.equals("cover")) {
                String dc = attributes == null ? "" : attributes.optString("device_class", "").trim().toLowerCase(Locale.ROOT);
                if (!SAFE_COVERS.contains(dc) && !dc.equals(ctl.deviceClass)) {
                    AppLog.w("home assistant: " + ctl.entity + " left out (" + (dc.isEmpty() ? "a cover without a class" : "a " + dc) + ")");
                }
                ctl.deviceClass = dc;
            }
        } catch (NotFound e) {
            ctl.state = "unavailable";
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer for " + ctl.entity);
        }
        return !before.equals(ctl.name + "\n" + ctl.state + "\n" + ctl.deviceClass);
    }

    /** When the states were last read (0: never, or the server didn't answer). */
    static long readAt() {
        return readAt;
    }

    // MARK: - OK on a tile

    interface Result {
        void done(boolean ok);
    }

    /**
     * Calls the tile's service: a light, switch, fan or cover toggles, a scene or script turns on, an automation runs,
     * a button is pressed. The tile shows its new state at once; it is read again a moment later, and put back if the
     * call failed. `result` runs on the main thread.
     */
    static void activate(Context c, Control ctl, Result result) {
        if (!onNow() || ctl.unavailable() || !ctl.offered()) {
            if (result != null) result.done(false);
            return;
        }
        final Context app = c.getApplicationContext();
        final String before = ctl.state;
        if (toggles(ctl.domain)) ctl.state = flipped(ctl);
        IO.execute(() -> {
            boolean ok;
            String token = "";
            try {
                token = token(app);
                request(token, "POST", "/api/services/" + service(ctl.domain), "{\"entity_id\":" + JSONObject.quote(ctl.entity) + "}");
                ok = true;
            } catch (IOException e) {
                AppLog.w("home assistant: " + ctl.entity + ": " + e.getMessage());
                ctl.state = before;
                ok = false;
            }
            final boolean worked = ok;
            MAIN.post(() -> {
                if (result != null) result.done(worked);
            });
            if (ok) {
                // Home Assistant answers before the device has changed: read it once it has.
                try {
                    Thread.sleep(1200);
                    read(ctl, token);
                } catch (InterruptedException | IOException ignored) {
                }
                if (result != null) MAIN.post(() -> result.done(true));
            }
        });
    }

    private static boolean toggles(String domain) {
        return domain.equals("light") || domain.equals("switch") || domain.equals("fan")
                || domain.equals("input_boolean") || domain.equals("cover");
    }

    private static String flipped(Control ctl) {
        if (ctl.domain.equals("cover")) return ctl.on() ? "closed" : "open";
        return ctl.on() ? "off" : "on";
    }

    /** Domain/service for OK, as /api/services takes it. */
    static String service(String domain) {
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean": case "cover":
                return "homeassistant/toggle";
            case "scene": return "scene/turn_on";
            case "script": return "script/turn_on";
            case "automation": return "automation/trigger";
            default: return domain + "/press";   // button, input_button
        }
    }

    // MARK: - Turning it off

    /** Home Settings › Home Assistant off: the names it read are forgotten, and nothing runs until it's on again. */
    static void forget(Context c) {
        prefs(c).edit().clear().apply();
        names = false;
        readAt = 0;
        for (Control ctl : CONTROLS) {
            ctl.state = "";
            ctl.name = fallbackName(ctl.entity);
            ctl.deviceClass = null;
        }
    }

    // MARK: - The token, sent from a computer (tools/send_key.py → Secrets)

    /** A file read: not for the UI thread (onNow keeps what the last look saw). */
    static boolean hasToken(Context c) {
        return tokenThere = !Secrets.get(c, Secrets.HOME_ASSISTANT_TOKEN).isEmpty();
    }

    /** Read at each round, so a token sent (or taken away) while the home screen runs counts at once. Never logged. */
    private static String token(Context c) throws IOException {
        String t = Secrets.get(c, Secrets.HOME_ASSISTANT_TOKEN);
        tokenThere = !t.isEmpty();
        if (t.isEmpty()) throw new IOException("no token: send it with tools/send_key.py <IP> home_assistant_token");
        return t;
    }

    // MARK: - Names, kept so the tiles have them before the server answers

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("home-assistant", Context.MODE_PRIVATE);
    }

    private static void loadNames(Context c) {
        if (names) return;
        names = true;
        SharedPreferences p = prefs(c);
        for (Control ctl : CONTROLS) {
            String name = p.getString("name:" + ctl.entity, "");
            if (!name.isEmpty()) ctl.name = name;
            if (ctl.deviceClass == null) ctl.deviceClass = p.getString("class:" + ctl.entity, null);
        }
    }

    private static void saveNames(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (Control ctl : CONTROLS) {
            e.putString("name:" + ctl.entity, ctl.name);
            if (ctl.deviceClass != null) e.putString("class:" + ctl.entity, ctl.deviceClass);
        }
        e.apply();
    }

    /** "light.living_room" → "Living room", until the server gives its name. */
    private static String fallbackName(String entity) {
        String id = entity.substring(entity.indexOf('.') + 1).replace('_', ' ').trim();
        return id.isEmpty() ? entity : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    // MARK: - The settings and the server

    /** home_assistant_entities: comma separated, in order; anything outside DOMAINS (locks, alarms…) is left out. */
    static List<Control> parse(String setting) {
        List<Control> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String raw : setting.split(",")) {
            String entity = raw.trim().toLowerCase(Locale.ROOT);
            int dot = entity.indexOf('.');
            if (dot <= 0 || dot == entity.length() - 1 || seen.contains(entity)) continue;
            if (!entity.matches("[a-z0-9_]+\\.[a-z0-9_]+")) continue;
            if (!DOMAINS.contains(entity.substring(0, dot))) {
                AppLog.w("home assistant: " + entity + " left out (only " + DOMAINS + ")");
                continue;
            }
            if (out.size() == MAX) {
                AppLog.w("home assistant: more than " + MAX + " entities, " + entity + " left out");
                break;
            }
            seen.add(entity);
            out.add(new Control(entity));
        }
        return Collections.unmodifiableList(out);
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static final class NotFound extends IOException {
        NotFound() {
            super("HTTP 404");
        }
    }

    private static String request(String token, String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + path).openConnection();
        // A redirect would carry the token to wherever it points, and past lan_server's check: none is followed.
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 404) throw new NotFound();
            if (code == 401) throw new IOException("the token was refused (HTTP 401)");
            if (code >= 300 && code < 400) throw new IOException("HTTP " + code + ": redirects aren't followed");
            if (code != 200 && code != 201) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
