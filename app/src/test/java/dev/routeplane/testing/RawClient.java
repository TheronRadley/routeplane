package dev.routeplane.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Blocking HTTP/1.1 client over a raw socket, for tests that must control or observe exact bytes on the wire. */
public final class RawClient implements AutoCloseable {
    public record Response(int status, Map<String, List<String>> headers, byte[] body, boolean truncated, boolean sawTerminator) {
        public String text() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RawClient(int port, int timeoutMs) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public Socket socket() {
        return socket;
    }

    public OutputStream out() {
        return out;
    }

    public InputStream in() {
        return in;
    }

    public void send(String raw) throws IOException {
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    public void send(byte[] raw) throws IOException {
        out.write(raw);
        out.flush();
    }

    /** Sends one request and reads one response. */
    public Response request(String method, String target, String headers, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: test.local\r\n");
        if (headers != null) {
            sb.append(headers);
        }
        if (body != null) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("\r\n");
        send(sb.toString());
        if (body != null && body.length > 0) {
            send(body);
        }
        return read(method.equals("HEAD"));
    }

    public Response get(String target) throws IOException {
        return request("GET", target, null, null);
    }

    /** Reads one response (status line, headers, body by Content-Length or chunking). */
    public Response read(boolean head) throws IOException {
        String statusLine = readLine();
        if (statusLine == null) {
            throw new IOException("connection closed before a status line");
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            headers.computeIfAbsent(line.substring(0, c).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(line.substring(c + 1).trim());
        }
        if (head || status == 204 || status == 304 || (status >= 100 && status < 200)) {
            return new Response(status, headers, new byte[0], false, true);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean truncated = false;
        boolean terminator = false;
        List<String> te = headers.get("transfer-encoding");
        try {
            if (te != null && te.get(0).toLowerCase(Locale.ROOT).contains("chunked")) {
                while (true) {
                    String size = readLine();
                    if (size == null) {
                        truncated = true;
                        break;
                    }
                    int n = Integer.parseInt(size.trim(), 16);
                    if (n == 0) {
                        while ((line = readLine()) != null && !line.isEmpty()) {
                            // trailers
                        }
                        terminator = true;
                        break;
                    }
                    byte[] chunk = in.readNBytes(n);
                    body.write(chunk);
                    if (chunk.length < n) {
                        truncated = true;
                        break;
                    }
                    readLine();
                }
            } else if (headers.containsKey("content-length")) {
                long n = Long.parseLong(headers.get("content-length").get(0));
                byte[] b = in.readNBytes((int) n);
                body.write(b);
                truncated = b.length < n;
                terminator = !truncated;
            }
        } catch (IOException e) {
            truncated = true;
        }
        return new Response(status, headers, body.toByteArray(), truncated, terminator);
    }

    /** Reads until the peer closes or the timeout fires; returns what arrived (for tests about raw framing). */
    public byte[] readUntilClosed() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
        } catch (IOException e) {
            // reset or timeout: return what we have
        }
        return bo.toByteArray();
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
