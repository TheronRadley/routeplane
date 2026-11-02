package dev.routeplane.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats the duration syntax used in configuration: an integer followed by ms, s, m, h or d. */
public final class Durations {
    private static final Pattern PATTERN = Pattern.compile("^([0-9]{1,9})(ms|s|m|h|d)$");

    private Durations() {
    }

    /**
     * @throws IllegalArgumentException if the text is not {@code <integer><unit>} with unit one of ms, s, m, h, d
     */
    public static Duration parse(String text) {
        String t = text.trim();
        Matcher m = PATTERN.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("expected a duration such as 500ms, 10s, 5m, 1h or 1d but got '" + text + "'");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    public static String format(Duration d) {
        long ms = d.toMillis();
        if (ms % 86_400_000L == 0 && ms > 0) {
            return ms / 86_400_000L + "d";
        }
        if (ms % 3_600_000L == 0 && ms > 0) {
            return ms / 3_600_000L + "h";
        }
        if (ms % 60_000L == 0 && ms > 0) {
            return ms / 60_000L + "m";
        }
        if (ms % 1000L == 0 && ms > 0) {
            return ms / 1000L + "s";
        }
        return ms + "ms";
    }
}
