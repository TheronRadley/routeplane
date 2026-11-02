package dev.routeplane;

/** Version information from the jar manifest ({@code Implementation-Version}); "dev" when running from class files. */
public final class BuildInfo {
    private BuildInfo() {
    }

    public static String version() {
        String v = BuildInfo.class.getPackage() == null ? null : BuildInfo.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
