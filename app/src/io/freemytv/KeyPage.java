package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The page a phone opens from the QR code in Home Settings > TMDB key or Watchmode key, to send the key instead of typing it with the
 * remote: http://<the TV's address on the home network>:<a port the system picks>/<a one-time random token>. It takes
 * one key (one POST), has the TV check it with its service, answers with the result and stops; after 5 minutes it stops
 * anyway. Plain HTTP, since a phone can't check a certificate for a LAN address; it listens only on that address. The
 * key is never logged.
 */
final class KeyPage {
    /** Called on the page's own threads, never the UI's. */
    interface Listener {
        /** A phone sent a key: the TV checks it now. */
        void checking();
        /** Checks the key and saves it: null when it's saved, else why not (the phone shows it too). */
        String accept(String key, boolean watchmode);
        /** The page stopped: after the key, after 5 minutes (expired) or close(). Called once. */
        void closed(KeyPage page, boolean expired);
    }

    static final long OPEN_FOR = 5 * 60_000L;
    private static final int MAX_HEAD = 8192, MAX_BODY = 4096, MAX_REQUESTS = 100, MAX_ANSWERING = 4;
    /** A whole request (head and body), not each read: a client trickling bytes can't keep a thread for long. */
    private static final long READ_FOR_NS = 15_000_000_000L;

    final String url;
    private final ServerSocket server;
    private final String path;
    private final Listener listener;
    private final boolean watchmode;   // the Watchmode key's page, else TMDB's
    private final AtomicBoolean taken = new AtomicBoolean();      // a key came: the one this page takes
    private final AtomicInteger answering = new AtomicInteger();   // connections being answered
    private volatile boolean closed;

    KeyPage(InetAddress lan, boolean watchmode, Listener listener) throws IOException {
        this.listener = listener;
        this.watchmode = watchmode;
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = new ServerSocket(0, 4, lan);   // port 0: a free one the system picks
        url = "http://" + lan.getHostAddress() + ":" + server.getLocalPort() + path;
        Thread t = new Thread(this::serve, "KeyPage");
        t.setDaemon(true);
        t.start();
    }

    /** Stops listening (leaving the screen); a request under way still gets its answer. */
    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * The TV's private IPv4 address (10/8, 172.16/12, 192.168/16) on its Wi-Fi or Ethernet, the active network first;
     * never a VPN's (a phone on the Wi-Fi can't reach it) nor a public one (anyone could). Null: type the key instead.
     */
    @SuppressWarnings("deprecation")   // getAllNetworks: still there, and API 21
    static InetAddress lanAddress(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            List<Network> networks = new ArrayList<>();
            if (active != null) networks.add(active);
            for (Network n : cm.getAllNetworks()) if (!n.equals(active)) networks.add(n);
            for (Network n : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    continue;
                }
                LinkProperties link = cm.getLinkProperties(n);
                if (link == null) continue;
                for (LinkAddress a : link.getLinkAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip;
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("key page: no address: " + e);
        }
        return null;
    }

    private void serve() {
        long until = System.nanoTime() + OPEN_FOR * 1_000_000L;   // not the wall clock, which NTP may move after a boot
        boolean expired = false;
        int requests = 0;
        try {
            while (!closed) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0 || requests >= MAX_REQUESTS) {   // a flood of requests ends it as time does: New code
                    expired = true;
                    break;
                }
                server.setSoTimeout((int) Math.min(left, 30_000));
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                requests++;
                // Each connection on its own thread, a few at most: a browser's idle preconnection doesn't hold up the page.
                if (answering.incrementAndGet() > MAX_ANSWERING) {
                    answering.decrementAndGet();
                    socket.close();
                    continue;
                }
                Thread t = new Thread(() -> answer(socket), "KeyPage answer");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!closed) AppLog.w("key page: " + e.getMessage());
        } finally {
            close();
            listener.closed(this, expired);
        }
    }

    private void answer(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10_000);
            if (handle(s.getInputStream(), s.getOutputStream(), System.nanoTime() + READ_FOR_NS)) close();
        } catch (IOException | RuntimeException e) {
            // A phone that went away midway: the page stays open for the next request. Nothing may escape this thread:
            // :home's watchdog counts a crash here as the home screen's.
        } finally {
            answering.decrementAndGet();
        }
    }

    /** One request, read before `deadline` (System.nanoTime). True once a key came (whatever TMDB said): the page has done its job. */
    boolean handle(InputStream in, OutputStream out, long deadline) throws IOException {
        String head = readHead(in, deadline);
        if (head == null) return false;
        String[] line = head.substring(0, Math.max(0, head.indexOf("\r\n"))).split(" ");
        String method = line.length > 1 ? line[0] : "", target = line.length > 1 ? line[1] : "";
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (!target.equals(path)) {
            respond(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        if (method.equals("GET") || method.equals("HEAD")) {
            respond(out, "200 OK", "text/html; charset=utf-8", method.equals("HEAD") ? new byte[0] : form());
            return false;
        }
        if (!method.equals("POST")) {
            respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", new byte[0]);
            return false;
        }
        int length = contentLength(head);
        if (length < 0 || length > MAX_BODY) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8", new byte[0]);
            return false;
        }
        byte[] body = new byte[length];
        for (int n = 0; n < length; ) {
            int got = in.read(body, n, length - n);
            if (got < 0 || System.nanoTime() > deadline) throw new IOException("cut short");
            n += got;
        }
        // Stopped meanwhile (the screen was left, 5 minutes), or two at once: only the first, while it shows, counts.
        if (closed || !taken.compareAndSet(false, true)) {
            respond(out, "409 Conflict", "text/html; charset=utf-8", result(Ui.tr("This code has expired.")));
            return false;
        }
        listener.checking();
        String why = listener.accept(field(new String(body, StandardCharsets.US_ASCII), "key"), watchmode);
        respond(out, "200 OK", "text/html; charset=utf-8", result(why));
        return true;
    }

    /** The request line and headers, up to the blank line; null if there are none, too many, or they come too slowly. */
    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0, b;
        while (matched < 4 && (b = in.read()) >= 0) {
            if (head.size() >= MAX_HEAD || System.nanoTime() > deadline) return null;
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1
                    : b == '\r' ? 1 : 0;
        }
        return matched == 4 ? head.toString(StandardCharsets.ISO_8859_1.name()) : null;
    }

    private static int contentLength(String head) {
        for (String h : head.split("\r\n")) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(h.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** One field of a form (application/x-www-form-urlencoded), or "". */
    private static String field(String form, String name) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                    return "";
                }
            }
        }
        return "";
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // MARK: - The pages (in the TV's language)

    /** How to get a key, then the field to paste it in. */
    private byte[] form() {
        StringBuilder b = top();
        if (watchmode) {
            b.append("<p>").append(esc(Ui.tr("The key makes a pick open the title's page in its app, where the app allows it.")))
                    .append("</p><p>").append(esc(Ui.tr("Free, and it takes two minutes:"))).append("</p><ol><li>")
                    .append(esc(Ui.tr("Create an account at api.watchmode.com."))).append("</li><li>")
                    .append(esc(Ui.tr("Copy the API key it gives you."))).append("</li><li>")
                    .append(esc(Ui.tr("Paste the key here:"))).append("</li></ol>")
                    .append("<p><a class=b href=\"https://api.watchmode.com/requestApiKey/\" target=_blank rel=noreferrer>")
                    .append(esc(Ui.tr("Open Watchmode's API page"))).append("</a></p>");
            return bottom(keyField(b, Ui.tr("Watchmode key")));
        }
        b.append("<p>").append(esc(Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.")))
                .append("</p><p>").append(esc(Ui.tr("Free, and it takes two minutes:"))).append("</p><ol><li>")
                .append(esc(Ui.tr("Create an account at themoviedb.org."))).append("</li><li>")
                .append(esc(Ui.tr("Open Settings → API and request a developer key."))).append("</li><li>")
                .append(esc(Ui.tr("Paste the API Read Access Token (or the API Key) here:"))).append("</li></ol>")
                .append("<p><a class=b href=\"https://www.themoviedb.org/settings/api\" target=_blank rel=noreferrer>")
                .append(esc(Ui.tr("Open TMDB's API page"))).append("</a></p>");
        return bottom(keyField(b, Ui.tr("TMDB key")));
    }

    private StringBuilder keyField(StringBuilder b, String name) {
        return b.append("<form method=post action=\"").append(path).append("\">")
                .append("<textarea name=key aria-label=\"").append(esc(name)).append("\" rows=4 required autocomplete=off autocapitalize=off autocorrect=off spellcheck=false></textarea>")
                .append("<button>").append(esc(Ui.tr("Send to the TV"))).append("</button></form><p class=s>")
                .append(esc(Ui.tr("The key stays on your TV. This page works once, for 5 minutes."))).append("</p>");
    }

    private byte[] result(String why) {
        StringBuilder b = top();
        if (why == null) {
            b.append("<p class=ok>").append(esc(Ui.tr("Saved on the TV. You can close this page."))).append("</p>");
        } else {
            b.append("<p class=no>").append(esc(why)).append("</p><p>")
                    .append(esc(Ui.tr("On the TV, choose New code and scan it again."))).append("</p>");
        }
        return bottom(b);
    }

    private StringBuilder top() {
        return new StringBuilder(2048).append("<!doctype html><html lang=\"").append(esc(Ui.language))
                .append("\"><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>Free My TV</title><style>")
                .append("body{margin:0 auto;max-width:34em;padding:24px;font:17px/1.5 system-ui,-apple-system,sans-serif;")
                .append("background:#1c1c1e;color:#f5f5f7}h1{font-size:24px}li{margin:.3em 0}a{color:#9fd3ff}")
                .append("textarea{box-sizing:border-box;width:100%;font:15px monospace;padding:10px;border-radius:10px;border:0}")
                .append("button,.b{display:block;box-sizing:border-box;width:100%;margin:14px 0;padding:14px;border:0;")
                .append("border-radius:12px;background:#f5f5f7;color:#1c1c1e;font:600 17px system-ui,sans-serif;text-align:center;")
                .append("text-decoration:none}.b{background:#3a3a3c;color:#f5f5f7}.s{color:#98989d;font-size:15px}")
                .append(".ok{color:#7ee08a;font-size:20px}.no{color:#ffc266;font-size:20px}</style>")
                .append("<h1>").append(esc(watchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key"))).append(" · Free My TV</h1>");
    }

    private static byte[] bottom(StringBuilder b) {
        return b.append("</html>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "KeyPage(port %d)", server.getLocalPort());   // never the token
    }
}
