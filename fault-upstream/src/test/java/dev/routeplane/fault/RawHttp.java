package dev.routeplane.fault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Tiny blocking HTTP/1.1 client over a raw socket for tests that must observe wire-level behaviour. */
public final class RawHttp implements AutoCloseable {
    public record Response(int status, Map<String, String> headers, byte[] body, boolean truncated) {
        public String text() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RawHttp(String host, int port, int timeoutMs) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(timeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public Socket socket() {
        return socket;
    }

    public void send(String raw) throws IOException {
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    public Response request(String method, String target, String extraHeaders, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: test\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        if (body != null) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) {
            out.write(body);
        }
        out.flush();
        return readResponse(method.equals("HEAD"));
    }

    public Response get(String target) throws IOException {
        return request("GET", target, null, null);
    }

    /** Reads one response; {@code truncated} is true if the connection ended before the declared body was complete. */
    public Response readResponse(boolean head) throws IOException {
        String statusLine = readLine();
        if (statusLine == null) {
            throw new IOException("connection closed before a status line");
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean truncated = false;
        String te = headers.get("transfer-encoding");
        if (head || status == 204 || status == 304 || (status >= 100 && status < 200)) {
            return new Response(status, headers, new byte[0], false);
        }
        try {
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
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
                        break;
                    }
                    body.write(in.readNBytes(n));
                    readLine();
                }
            } else if (headers.containsKey("content-length")) {
                long n = Long.parseLong(headers.get("content-length"));
                byte[] b = in.readNBytes((int) n);
                body.write(b);
                truncated = b.length < n;
            }
        } catch (IOException e) {
            truncated = true;
        }
        return new Response(status, headers, body.toByteArray(), truncated);
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
