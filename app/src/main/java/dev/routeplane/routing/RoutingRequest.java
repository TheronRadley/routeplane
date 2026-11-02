package dev.routeplane.routing;

/**
 * Read-only description of an incoming request as seen by routing. Deliberately independent of any HTTP library so
 * that policies (including third-party ones) can be unit-tested without a network.
 */
public interface RoutingRequest {
    /** Upper-case HTTP method. */
    String method();

    /** Normalised request path (dot segments removed, never contains the query). */
    String path();

    /** Raw query string without the leading '?', or null. */
    String query();

    /** First value of a request header (case-insensitive name), or null. */
    String header(String name);

    /** First URL-decoded value of a query parameter, or null. */
    String queryParam(String name);

    /** First value of a cookie from the Cookie header, or null. */
    String cookie(String name);

    /** Address of the direct TCP peer (never taken from a header). */
    String clientAddress();

    /** The correlation id assigned to this request. */
    String requestId();
}
