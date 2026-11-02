package dev.routeplane.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** An IPv4 or IPv6 network in CIDR notation. Only literal addresses are accepted; no DNS lookups happen. */
public final class Cidr {
    private final byte[] network;
    private final int prefix;
    private final String text;

    private Cidr(byte[] network, int prefix, String text) {
        this.network = network;
        this.prefix = prefix;
        this.text = text;
    }

    /** @throws IllegalArgumentException for anything that is not {@code <ip>} or {@code <ip>/<prefix>} */
    public static Cidr parse(String text) {
        String t = text.trim();
        String addr = t;
        Integer bits = null;
        int slash = t.indexOf('/');
        if (slash >= 0) {
            addr = t.substring(0, slash);
            try {
                bits = Integer.parseInt(t.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix length in '" + text + "'");
            }
        }
        if (!isIpLiteral(addr)) {
            throw new IllegalArgumentException("'" + text + "' is not an IP address or CIDR block (host names are not allowed)");
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid address in '" + text + "'");
        }
        int max = bytes.length * 8;
        int prefix = bits == null ? max : bits;
        if (prefix < 0 || prefix > max) {
            throw new IllegalArgumentException("prefix length in '" + text + "' must be between 0 and " + max);
        }
        return new Cidr(mask(bytes, prefix), prefix, t);
    }

    /** True for dotted-quad IPv4 or colon-containing IPv6 literals (never triggers DNS). */
    public static boolean isIpLiteral(String s) {
        if (s.indexOf(':') >= 0) {
            return s.matches("[0-9A-Fa-f:.%a-zA-Z0-9]+");
        }
        return s.matches("([0-9]{1,3})(\\.[0-9]{1,3}){3}");
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
            int m = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
            out[i] = (byte) (out[i] & m);
        }
        return out;
    }

    public boolean contains(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != network.length) {
            return false;
        }
        return Arrays.equals(mask(b, prefix), network);
    }

    @Override
    public String toString() {
        return text;
    }
}
