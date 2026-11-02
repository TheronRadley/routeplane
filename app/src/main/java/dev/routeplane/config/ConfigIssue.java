package dev.routeplane.config;

/** One validation problem, located in the source file where possible. */
public record ConfigIssue(String source, int line, int column, String path, String message) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(source);
        if (line > 0) {
            sb.append(':').append(line).append(':').append(column);
        }
        sb.append(": ");
        if (path != null && !path.isEmpty()) {
            sb.append(path).append(": ");
        }
        return sb.append(message).toString();
    }
}
