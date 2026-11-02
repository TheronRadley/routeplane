package dev.routeplane.routing;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Canonicalises request paths so that what routing <em>matches</em> is exactly what is <em>forwarded</em>.
 *
 * <ol>
 *   <li>percent-escapes of unreserved characters ({@code %2e}, {@code %41}...) are decoded and the hex digits of the
 *       remaining escapes are upper-cased;</li>
 *   <li>NUL, control characters, backslashes, non-ASCII characters and malformed escapes are rejected;</li>
 *   <li>encoded slashes ({@code %2f}, {@code %5c}) are rejected unless explicitly allowed;</li>
 *   <li>runs of '/' are merged and dot segments are removed (RFC 3986 section 5.2.4), never climbing above the root.</li>
 * </ol>
 */
public final class PathNormalizer {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PathNormalizer() {
    }

    /** Either a normalised path or a reason it was rejected. */
    public record Result(String path, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Result normalize(String raw, boolean allowEncodedSlashes) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '/') {
            return fail("request path must start with '/'");
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    return fail("malformed percent-escape in path");
                }
                int hi = hex(raw.charAt(i + 1));
                int lo = hex(raw.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    return fail("malformed percent-escape in path");
                }
                int v = (hi << 4) | lo;
                if (v == 0 || v < 0x20 || v == 0x7F) {
                    return fail("control character in path");
                }
                if (v == '/' || v == '\\') {
                    if (!allowEncodedSlashes) {
                        return fail("encoded slash or backslash in path");
                    }
                    sb.append('%').append(HEX[hi]).append(HEX[lo]);
                } else if (isUnreserved(v)) {
                    sb.append((char) v);
                } else {
                    sb.append('%').append(HEX[hi]).append(HEX[lo]);
                }
                i += 2;
            } else if (c == '\\') {
                return fail("backslash in path");
            } else if (c < 0x21 || c > 0x7E) {
                return fail("illegal character in path");
            } else {
                sb.append(c);
            }
        }
        return new Result(removeDotSegments(sb.toString()), null);
    }

    static String removeDotSegments(String path) {
        String[] parts = path.split("/", -1);
        Deque<String> out = new ArrayDeque<>();
        boolean trailingSlash = false;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            boolean last = i == parts.length - 1;
            if (p.equals(".")) {
                trailingSlash |= last;
            } else if (p.equals("..")) {
                if (!out.isEmpty()) {
                    out.removeLast();
                }
                trailingSlash |= last;
            } else if (p.isEmpty()) {
                trailingSlash |= last; // merged slash; a trailing one is preserved
            } else {
                out.addLast(p);
            }
        }
        StringBuilder sb = new StringBuilder("/");
        boolean first = true;
        for (String s : out) {
            if (!first) {
                sb.append('/');
            }
            sb.append(s);
            first = false;
        }
        if (trailingSlash && !out.isEmpty()) {
            sb.append('/');
        }
        return sb.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static Result fail(String why) {
        return new Result(null, why);
    }
}
