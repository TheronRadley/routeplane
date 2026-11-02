package dev.routeplane.config;

import java.util.function.Function;

/**
 * Environment substitution for scalar configuration values.
 *
 * <ul>
 *   <li>{@code ${NAME}} - value of environment variable NAME; it is an error if NAME is unset or empty.</li>
 *   <li>{@code ${NAME:-default}} - value of NAME, or {@code default} when NAME is unset or empty.</li>
 *   <li>{@code $$} - a literal dollar sign. A lone {@code $} not followed by {@code {} or {@code $} is literal.</li>
 *   <li>Names match {@code [A-Za-z_][A-Za-z0-9_]*}. Substituted values are never expanded again.</li>
 * </ul>
 *
 * Substitution is applied to scalar <em>values</em> after YAML parsing - never to keys and never to raw YAML text -
 * so an environment variable cannot inject structure into the document.
 */
public final class EnvSubstitutor {
    private final Function<String, String> lookup;

    public EnvSubstitutor(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    /** @throws IllegalArgumentException when a referenced variable is missing or the syntax is malformed */
    public String substitute(String input) {
        if (input.indexOf('$') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < input.length() && input.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
            } else if (i + 1 < input.length() && input.charAt(i + 1) == '{') {
                int end = input.indexOf('}', i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated '${' in '" + input + "'");
                }
                String body = input.substring(i + 2, end);
                String name = body;
                String def = null;
                int sep = body.indexOf(":-");
                if (sep >= 0) {
                    name = body.substring(0, sep);
                    def = body.substring(sep + 2);
                }
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("invalid environment variable name '" + name + "'");
                }
                String value = lookup.apply(name);
                if (value == null || value.isEmpty()) {
                    if (def == null) {
                        throw new IllegalArgumentException("environment variable " + name + " is not set (use ${" + name + ":-default} to provide a default)");
                    }
                    value = def;
                }
                out.append(value);
                i = end + 1;
            } else {
                out.append('$');
                i++;
            }
        }
        return out.toString();
    }
}
