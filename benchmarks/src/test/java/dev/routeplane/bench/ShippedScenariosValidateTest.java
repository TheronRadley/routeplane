package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.config.ConfigLoader;
import dev.routeplane.routing.PolicyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A campaign takes an hour; a scenario whose proxy configuration the proxy refuses to load must be caught in seconds. This runs the
 * real configuration validator over every variant of every shipped scenario (a campaign once aborted because a budget value exceeded
 * the documented maximum).
 */
class ShippedScenariosValidateTest {
    @Test
    void everyVariantOfEveryScenarioIsAValidProxyConfiguration() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("experiments/scenarios"))) {
            dir = dir.getParent();
        }
        Assumptions.assumeTrue(dir != null, "repository layout not found from the working directory");
        ConfigLoader loader = new ConfigLoader(PolicyRegistry.load());
        int checked = 0;
        for (Scenario s : ScenarioLoader.loadAll(dir.resolve("experiments/scenarios"))) {
            if (s.direct) {
                continue;
            }
            Map<String, String> env = new HashMap<>();
            env.put("PROXY_PORT", "18080");
            env.put("ADMIN_PORT", "19090");
            int port = 20000;
            for (Scenario.UpstreamSpec u : s.upstreams) {
                env.put("UP_" + u.id().toUpperCase(Locale.ROOT).replace('-', '_'), Integer.toString(port++));
            }
            for (Map.Entry<String, String> v : s.variants.entrySet()) {
                try {
                    loader.parse(v.getValue(), s.id + "@" + v.getKey(), env::get);
                } catch (RuntimeException e) {
                    throw new AssertionError("scenario " + s.id + ", variant " + v.getKey() + ": " + e.getMessage(), e);
                }
                checked++;
            }
        }
        assertTrue(checked >= 20, "expected to validate every proxied variant, validated " + checked);
    }
}
