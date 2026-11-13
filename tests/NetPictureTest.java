package io.freemytv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;

public final class NetPictureTest {
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static boolean open(String address) throws IOException {
        return NetPicture.isPublic(InetAddress.getByName(address).getAddress());   // literals only: nothing is looked up
    }

    private static String refusal(String address) {
        try {
            NetPicture.get(address);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) throws IOException {
        for (String a : new String[] {"8.8.8.8", "1.1.1.1", "23.246.0.1", "172.15.0.1", "172.32.0.1", "100.63.0.1",
                "100.128.0.1", "192.169.0.1", "2606:4700:4700::1111", "2a00:86c0::1"}) check(open(a), a + " is public");
        for (String a : new String[] {"127.0.0.1", "10.0.0.41", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.251", "255.255.255.255",
                "192.0.0.8", "198.18.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::fb", "fec0::1",
                "::ffff:192.168.1.1", "::ffff:127.0.0.1", "64:ff9b::c0a8:101", "2002:c0a8:101::1", "2002:a00:29::1"}) {
            check(!open(a), a + " is not public");
        }
        check(open("::ffff:8.8.8.8"), "a public IPv4 written inside IPv6");
        check(!NetPicture.isPublic(null) && !NetPicture.isPublic(new byte[3]), "not an address");
        // Refused before any connection: nothing here reaches the network.
        check("not https".equals(refusal("http://example.com/a.jpg")), "http is refused");
        check("not https".equals(refusal("https://user:pass@example.com/a.jpg")), "user info is refused");
        check("not an address".equals(refusal("file:///sdcard/a.jpg")) || "not https".equals(refusal("file:///sdcard/a.jpg")), "file is refused");
        check("not a public host".equals(refusal("https://192.168.1.1/a.jpg")), "the router is refused");
        check("not a public host".equals(refusal("https://127.0.0.1:8443/a.jpg")), "this device is refused");
        check("not a public host".equals(refusal("https://[::1]/a.jpg")), "this device is refused in IPv6");
        check("not a public host".equals(refusal("https://[fd00::1]/a.jpg")), "the home network is refused in IPv6");
        check("not a public host".equals(refusal("https://localhost/a.jpg")), "localhost is refused");
        byte[] data = new byte[100_000];
        check(NetPicture.readAll(new ByteArrayInputStream(data), 200_000, Long.MAX_VALUE).length == data.length, "read whole");
        try {
            NetPicture.readAll(new ByteArrayInputStream(data), 50_000, Long.MAX_VALUE);
            check(false, "the size limit");
        } catch (IOException e) {
            check("picture too large".equals(e.getMessage()), "the size limit's message");
        }
        try {
            NetPicture.readAll(new ByteArrayInputStream(data), 200_000, 0);
            check(false, "the time limit");
        } catch (IOException e) {
            check("picture took too long".equals(e.getMessage()), "the time limit's message");
        }
        System.out.println("NetPicture: " + assertions + " assertions passed");
    }
}
