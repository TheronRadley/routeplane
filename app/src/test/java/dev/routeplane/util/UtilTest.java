package dev.routeplane.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void parsesAndFormatsDurations() {
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals(Duration.ofSeconds(10), Durations.parse("10s"));
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d"));
        assertEquals("1500ms", Durations.format(Duration.ofMillis(1500)));
        assertEquals("10s", Durations.format(Duration.ofSeconds(10)));
        assertEquals("2m", Durations.format(Duration.ofMinutes(2)));
    }

    @Test
    void rejectsAmbiguousDurations() {
        for (String bad : new String[] {"10", "1.5s", "-1s", "s", "10 s", "10sec", "PT10S", ""}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(bad), bad);
        }
    }

    @Test
    void cidrMatchesIpv4AndIpv6() throws Exception {
        Cidr net = Cidr.parse("10.1.0.0/16");
        assertTrue(net.contains(InetAddress.getByName("10.1.200.3")));
        assertFalse(net.contains(InetAddress.getByName("10.2.0.1")));
        assertTrue(Cidr.parse("127.0.0.1").contains(InetAddress.getByName("127.0.0.1")));
        assertTrue(Cidr.parse("::1/128").contains(InetAddress.getByName("::1")));
        assertFalse(Cidr.parse("fd00::/8").contains(InetAddress.getByName("10.0.0.1")));
        assertTrue(Cidr.parse("0.0.0.0/0").contains(InetAddress.getByName("203.0.113.9")));
    }

    @Test
    void cidrRejectsHostNamesAndBadPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("example.com"));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/x"));
    }

    @Test
    void hashingIsStableAndSpreads() {
        assertEquals(Hashing.hash64("user-42"), Hashing.hash64("user-42"));
        assertTrue(Hashing.hash64("a") != Hashing.hash64("b"));
        for (int i = 0; i < 1000; i++) {
            double u = Hashing.toUnitInterval(Hashing.hash64("k" + i));
            assertTrue(u > 0 && u < 1);
        }
    }
}
