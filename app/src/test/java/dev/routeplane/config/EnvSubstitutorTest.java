package dev.routeplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvSubstitutorTest {
    private final EnvSubstitutor env = new EnvSubstitutor(Map.of("PORT", "9000", "EMPTY", "", "TRICKY", "${PORT}")::get);

    @Test
    void substitutesVariablesAndDefaults() {
        assertEquals("9000", env.substitute("${PORT}"));
        assertEquals("http://h:9000/x", env.substitute("http://h:${PORT}/x"));
        assertEquals("8080", env.substitute("${MISSING:-8080}"));
        assertEquals("fallback", env.substitute("${EMPTY:-fallback}"));
        assertEquals("", env.substitute("${MISSING:-}"));
    }

    @Test
    void unsetOrEmptyWithoutDefaultIsAnError() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> env.substitute("${MISSING}")).getMessage().contains("MISSING"));
        assertThrows(IllegalArgumentException.class, () -> env.substitute("${EMPTY}"));
    }

    @Test
    void dollarEscapesAndLiterals() {
        assertEquals("cost $5", env.substitute("cost $5"));
        assertEquals("${PORT}", env.substitute("$${PORT}"));
        assertEquals("a$b", env.substitute("a$$b"));
        assertEquals("$", env.substitute("$"));
    }

    @Test
    void valuesAreNotExpandedTwiceAndSyntaxIsStrict() {
        assertEquals("${PORT}", env.substitute("${TRICKY}"));
        assertThrows(IllegalArgumentException.class, () -> env.substitute("${PORT"));
        assertThrows(IllegalArgumentException.class, () -> env.substitute("${1BAD}"));
        assertThrows(IllegalArgumentException.class, () -> env.substitute("${A B}"));
    }
}
