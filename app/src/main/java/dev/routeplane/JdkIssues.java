package dev.routeplane;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Known defects in specific JDK builds that matter to a virtual-thread based proxy.
 *
 * <p><b>JDK-8369227 "Virtual thread stuck in PARKED state"</b>: on JDK 25.0.0 to 25.0.2 and 26.0.0 an untimed park of a
 * virtual thread that recently did a timed park can be stranded by the earlier park's stale timeout task (introduced by
 * the ForkJoinPool delayed-task rewrite in JDK 25 b18). Symptoms: a handler or a {@code Thread.sleep} that should take
 * milliseconds takes many seconds (observed here as upstream responses that arrive only after the 10 s response
 * timeout). JDK 21 and 24 do not have the bug; it is fixed in 25.0.3, 26.0.1 and 27.
 */
public final class JdkIssues {
    private static final Pattern AFFECTED = Pattern.compile("^(25(\\.0\\.[0-2])?|26(\\.0\\.0)?)([^0-9.].*)?$");

    private JdkIssues() {
    }

    /** @return human-readable warnings for the given {@code java.version} value (empty when nothing is known) */
    public static List<String> warningsFor(String javaVersion) {
        List<String> out = new ArrayList<>();
        if (javaVersion != null && AFFECTED.matcher(javaVersion).matches()) {
            out.add("JDK " + javaVersion + " is affected by JDK-8369227 (virtual threads can stay parked for seconds after a timed park); "
                    + "requests may stall intermittently. Use JDK 21, 24, 25.0.3 or newer, or 26.0.1 or newer.");
        }
        return out;
    }

    public static List<String> warnings() {
        return warningsFor(System.getProperty("java.version"));
    }
}
