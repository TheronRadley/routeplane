package dev.routeplane.proxy;

import java.io.InputStream;

/** The request body as the forwarding logic sees it. Only {@link Empty} and {@link Buffered} can be replayed. */
sealed interface BodySource {

    boolean replayable();

    /** No body (GET, HEAD, or a zero-length body). */
    record Empty() implements BodySource {
        @Override
        public boolean replayable() {
            return true;
        }
    }

    /** A body that was read completely into memory (bounded by {@code maxBufferedBodyBytes}): safe to retry and to mirror. */
    record Buffered(byte[] bytes) implements BodySource {
        @Override
        public boolean replayable() {
            return true;
        }
    }

    /** A body that is piped from the client to the upstream while it arrives. Single use; length -1 means chunked. */
    record Streaming(InputStream stream, long length) implements BodySource {
        @Override
        public boolean replayable() {
            return false;
        }
    }
}
