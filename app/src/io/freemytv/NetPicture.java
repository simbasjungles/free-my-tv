package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * A picture another app left as an address in the TV's Watch Next list (Continue Watching), fetched carefully, since
 * that address is not ours: https only, never a host on this TV or on the home network (so an address can't make the TV
 * call the router or another device), redirects followed by hand and checked the same way, pictures only, with a size
 * and a time limit, and no cookies or cache. Errors never carry the address: it says what someone watches. Kept free
 * of Android types so tests/NetPictureTest runs it on a plain JVM (#174).
 */
final class NetPicture {
    private NetPicture() {}

    static final int MAX_BYTES = 4 << 20;
    private static final int MAX_REDIRECTS = 3, CONNECT_MS = 6000, READ_MS = 6000, TOTAL_MS = 10_000;

    /** The picture's bytes. Throws when anything about the address or the answer isn't as it should be. */
    static byte[] get(String address) throws IOException {
        long deadline = System.currentTimeMillis() + TOTAL_MS;
        String at = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url;
            try {
                url = new URL(at);
            } catch (IOException e) {
                throw new IOException("not an address");
            }
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("not https");
            InetAddress[] hosts;
            try {
                hosts = InetAddress.getAllByName(url.getHost());
            } catch (IOException | RuntimeException e) {
                throw new IOException("host not found");
            }
            if (hosts.length == 0) throw new IOException("host not found");
            for (InetAddress host : hosts) if (!isPublic(host.getAddress())) throw new IOException("not a public host");
            HttpURLConnection c;
            try {
                c = (HttpURLConnection) url.openConnection();
            } catch (IOException | RuntimeException e) {
                throw new IOException("no connection");
            }
            if (!(c instanceof HttpsURLConnection)) throw new IOException("not https");
            try {
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "FreeMyTV");
                c.setRequestProperty("Accept", "image/*");
                int code;
                try {
                    code = c.getResponseCode();
                } catch (IOException | RuntimeException e) {
                    throw new IOException("no answer (" + e.getClass().getSimpleName() + ")");
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String to = c.getHeaderField("Location");
                    if (to == null) throw new IOException("redirect to nowhere");
                    try {
                        at = new URL(url, to).toString();
                    } catch (IOException e) {
                        throw new IOException("redirect to nowhere");
                    }
                    continue;
                }
                if (code != 200) throw new IOException("answer " + code);
                String type = c.getContentType();
                if (type == null || !type.trim().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                    throw new IOException("not a picture");
                }
                if (c.getContentLength() > MAX_BYTES) throw new IOException("picture too large");
                try (InputStream in = c.getInputStream()) {
                    return readAll(in, MAX_BYTES, deadline);
                } catch (IOException | RuntimeException e) {
                    String why = e.getMessage();
                    throw new IOException("picture too large".equals(why) || "picture took too long".equals(why) ? why
                            : "picture not read (" + e.getClass().getSimpleName() + ")");
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** Reads it all, up to a size and until a moment (a server that trickles bytes can't hold a card's place). */
    static byte[] readAll(InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max) throw new IOException("picture too large");
            if (System.currentTimeMillis() > deadline) throw new IOException("picture took too long");
        }
        return out.toByteArray();
    }

    /**
     * False for this device, the home network and the other ranges that aren't the internet: loopback, private
     * (10/8, 172.16/12, 192.168/16), link-local, carrier NAT (100.64/10), multicast, reserved and "this network" in
     * IPv4; loopback, unspecified, unique local (fc00::/7), link-local, multicast and IPv4 written inside IPv6 (mapped,
     * NAT64, 6to4: checked as that IPv4) in IPv6.
     */
    static boolean isPublic(byte[] a) {
        if (a == null) return false;
        if (a.length == 4) {
            int b0 = a[0] & 255, b1 = a[1] & 255;
            if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return false;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;
            if (b0 == 169 && b1 == 254) return false;
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return false;
            if (b0 == 192 && b1 == 168) return false;
            if (b0 == 192 && b1 == 0 && (a[2] & 255) == 0) return false;
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;
            return true;
        }
        if (a.length != 16) return false;
        boolean zeros = true;
        for (int i = 0; i < 10; i++) zeros &= a[i] == 0;
        if (zeros) {
            boolean mapped = (a[10] & 255) == 255 && (a[11] & 255) == 255, compat = a[10] == 0 && a[11] == 0;
            if (mapped) return isPublic(new byte[] {a[12], a[13], a[14], a[15]});
            if (compat) return false;   // ::, ::1 and the old IPv4-compatible form
        }
        int b0 = a[0] & 255, b1 = a[1] & 255;
        if (b0 == 0xff || (b0 & 0xfe) == 0xfc) return false;                 // multicast, unique local
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return false;                 // link-local
        if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) return false;                 // the old site-local
        if (b0 == 0x00 && b1 == 0x64 && (a[2] & 255) == 0xff && (a[3] & 255) == 0x9b) {   // NAT64 64:ff9b::/96
            return isPublic(new byte[] {a[12], a[13], a[14], a[15]});
        }
        if (b0 == 0x20 && b1 == 0x02) return isPublic(new byte[] {a[2], a[3], a[4], a[5]});   // 6to4
        return true;
    }
}
