package dev.routeplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.routing.PolicyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The shipped configurations are documentation; they must keep validating. */
class ExampleConfigsTest {
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("config/examples"))) {
            dir = dir.getParent();
        }
        Assumptions.assumeTrue(dir != null, "repository layout not found from the working directory");
        return dir;
    }

    @Test
    void everyExampleAndTheDefaultConfigurationValidate() throws Exception {
        Path root = repoRoot();
        Path keystore = Files.createTempFile("admin", ".p12"); // validation only requires the key store file to be readable
        Map<String, String> env = Map.of("ADMIN_TOKEN", "0123456789abcdef-example", "ADMIN_KEYSTORE", keystore.toString(), "KS_PASS", "changeit");
        ConfigLoader loader = new ConfigLoader(PolicyRegistry.load());
        List<Path> files = new ArrayList<>();
        files.add(root.resolve("config/routeplane.yaml"));
        try (var s = Files.list(root.resolve("config/examples"))) {
            s.filter(p -> p.toString().endsWith(".yaml") && !p.getFileName().toString().equals("custom-policy.yaml")).sorted().forEach(files::add);
        }
        assertTrue(files.size() >= 7, "expected the default configuration and the examples: " + files);
        for (Path f : files) {
            RouteplaneConfig c = loader.loadFile(f, env::get);
            assertFalse(c.routes().isEmpty(), f.toString());
        }
        Files.deleteIfExists(keystore);
    }

    @Test
    void theCustomPolicyExampleNeedsItsPolicyInstalled() throws Exception {
        Path f = repoRoot().resolve("config/examples/custom-policy.yaml");
        ConfigException e = org.junit.jupiter.api.Assertions.assertThrows(ConfigException.class, () -> new ConfigLoader(PolicyRegistry.load()).loadFile(f, Map.<String, String>of()::get));
        assertEquals(1, e.issues().size());
        assertTrue(e.getMessage().contains("unknown routing policy 'tenant-sticky'"));
    }

    @Test
    void theAdminTlsExampleIsRejectedWithoutItsSecrets() throws Exception {
        Path f = repoRoot().resolve("config/examples/remote-admin-tls.yaml");
        ConfigException e = org.junit.jupiter.api.Assertions.assertThrows(ConfigException.class, () -> new ConfigLoader(PolicyRegistry.load()).loadFile(f, Map.<String, String>of()::get));
        assertTrue(e.getMessage().contains("environment variable ADMIN_TOKEN is not set"), e.getMessage());
    }
}
