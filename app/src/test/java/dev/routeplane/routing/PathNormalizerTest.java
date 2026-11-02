package dev.routeplane.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/|/",
            "/a/b|/a/b",
            "/a/b/|/a/b/",
            "/a//b|/a/b",
            "/a/./b|/a/b",
            "/a/../b|/b",
            "/a/b/..|/a/",
            "/../../etc/passwd|/etc/passwd",
            "/api/%2e%2e/admin|/admin",
            "/api/%2E./admin|/admin",
            "/%61pi|/api",
            "/a%20b|/a%20b",
            "/a%2fb|ERR",
            "/a%5Cb|ERR",
            "/a%00b|ERR",
            "/a%0ab|ERR",
            "/a%zzb|ERR",
            "/a%2|ERR",
            "/a\\b|ERR",
            "api|ERR",
    })
    void normalises(String raw, String expected) {
        var r = PathNormalizer.normalize(raw, false);
        if (expected.equals("ERR")) {
            assertFalse(r.ok(), raw + " should be rejected but became " + r.path());
        } else {
            assertTrue(r.ok(), raw + ": " + r.error());
            assertEquals(expected, r.path());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a b", "/caf\u00e9", "/a\tb"})
    void rejectsRawSpacesControlAndNonAscii(String raw) {
        assertFalse(PathNormalizer.normalize(raw, false).ok());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a%2fb", "/a%2Fb", "/a%5cb"})
    void encodedSlashesCanBeAllowedExplicitly(String raw) {
        var r = PathNormalizer.normalize(raw, true);
        assertTrue(r.ok());
        assertTrue(r.path().contains("%2F") || r.path().contains("%5C"), "stays encoded, never decoded: " + r.path());
    }

    @org.junit.jupiter.api.Test
    void matchedPathAndForwardedPathAreTheSameString() {
        // A request that tries to climb out of /public must be matched (and forwarded) as /admin, not as /public/...
        assertEquals("/admin/users", PathNormalizer.normalize("/public/%2e%2e/admin/users", false).path());
    }
}
