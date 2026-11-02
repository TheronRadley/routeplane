package dev.routeplane.routing;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Query-string helpers: structural validation and lazy parsing of parameters for routing and hashing. */
public final class QueryString {
    private QueryString() {
    }

    /** True if every character is printable ASCII and every '%' starts a valid escape. */
    public static boolean isWellFormed(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x21 || c > 0x7E || c == '\\') {
                return false;
            }
            if (c == '%') {
                if (i + 2 >= raw.length() || !isHex(raw.charAt(i + 1)) || !isHex(raw.charAt(i + 2))) {
                    return false;
                }
                i += 2;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Parses {@code a=1&b=2&a=3} into an ordered multimap; names and values are URL-decoded as UTF-8. */
    public static Map<String, List<String>> parse(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
