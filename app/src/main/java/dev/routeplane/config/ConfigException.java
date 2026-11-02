package dev.routeplane.config;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when a configuration cannot be activated. Carries every issue found, not just the first. */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<ConfigIssue> issues;

    public ConfigException(List<ConfigIssue> issues) {
        super(render(issues));
        this.issues = List.copyOf(issues);
    }

    public List<ConfigIssue> issues() {
        return issues;
    }

    private static String render(List<ConfigIssue> issues) {
        String body = issues.stream().map(ConfigIssue::toString).collect(Collectors.joining("\n  - ", "\n  - ", ""));
        return issues.size() + " configuration problem" + (issues.size() == 1 ? "" : "s") + " found:" + body;
    }
}
