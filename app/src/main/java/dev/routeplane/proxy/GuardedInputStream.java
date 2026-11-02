package dev.routeplane.proxy;

import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps the client's request body. It enforces the maximum body size while streaming (chunked bodies have no declared
 * length to check up front), counts bytes, and remembers <em>which side</em> failed: an {@link IOException} thrown by
 * the underlying client stream means the client went away or stalled, which must not be blamed on the upstream.
 */
final class GuardedInputStream extends InputStream {
    /** Thrown when the body exceeds the configured limit. */
    static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException(long limit) {
            super("request body exceeds " + limit + " bytes");
        }
    }

    private final InputStream in;
    private final long limit;
    private final long declaredLength;
    private volatile boolean eof;
    private volatile Thread reader;
    private volatile long count;
    private volatile IOException clientFailure;
    private volatile boolean limitExceeded;

    /** @param declaredLength the Content-Length, or -1 for chunked bodies */
    GuardedInputStream(InputStream in, long limit, long declaredLength) {
        this.in = in;
        this.limit = limit;
        this.declaredLength = declaredLength;
    }

    /**
     * Interrupts the thread currently blocked reading the client's body, if any. The body is often read by the JDK
     * client's publisher thread, not the handler thread; that thread holds the channel's (uninterruptible) read lock,
     * so the handler cannot finish the exchange until it is released - and interrupting it closes the client channel.
     */
    void interruptReader() {
        Thread t = reader;
        if (t != null) {
            t.interrupt();
        }
    }

    /** True once the whole body has been read (end of stream seen, or the declared length reached). */
    boolean exhausted() {
        return eof || (declaredLength >= 0 && count >= declaredLength);
    }

    long bytesRead() {
        return count;
    }

    IOException clientFailure() {
        return clientFailure;
    }

    boolean limitExceeded() {
        return limitExceeded;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n;
        reader = Thread.currentThread();
        try {
            n = in.read(b, off, len);
        } catch (IOException e) {
            clientFailure = e;
            throw e;
        } finally {
            reader = null;
        }
        if (n < 0) {
            eof = true;
        }
        if (n > 0) {
            count += n;
            if (count > limit) {
                limitExceeded = true;
                throw new BodyTooLargeException(limit);
            }
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
