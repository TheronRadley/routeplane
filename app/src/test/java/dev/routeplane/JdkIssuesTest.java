package dev.routeplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdkIssuesTest {
    @Test
    void flagsOnlyTheBuildsAffectedByJdk8369227() {
        for (String affected : new String[] {"25", "25.0.0", "25.0.1", "25.0.2", "26", "26.0.0", "25.0.2-ea", "25-ea"}) {
            assertEquals(1, JdkIssues.warningsFor(affected).size(), affected);
        }
        for (String fine : new String[] {"21", "21.0.8", "17.0.8", "24", "24.0.2", "25.0.3", "25.0.4", "25.0.10", "26.0.1", "27", "26.1"}) {
            assertTrue(JdkIssues.warningsFor(fine).isEmpty(), fine);
        }
        assertTrue(JdkIssues.warningsFor(null).isEmpty());
        assertTrue(JdkIssues.warningsFor("25.0.2").get(0).contains("JDK-8369227"));
    }
}
