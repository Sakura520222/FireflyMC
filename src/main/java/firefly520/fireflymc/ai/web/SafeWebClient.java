package firefly520.fireflymc.ai.web;

import firefly520.fireflymc.ai.task.TaskControl;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/**
 * Public HTTP(S) reader. TCP connects to the validated InetAddress, never resolves the hostname a
 * second time. HTTPS still verifies the original hostname and sends SNI. Redirects repeat validation.
 */
public final class SafeWebClient implements AutoCloseable {
    public record Response(URI uri, int status, Map<String, String> headers, byte[] body) {}
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws Exception; }
    @FunctionalInterface public interface Exchange {
        Response get(URI uri, InetAddress address, Map<String, String> headers, TaskControl control) throws Exception;
    }
    private final Resolver resolver;
    private final Exchange exchange;
    private final ExecutorService dns = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon(true).name("fireflymc-ai-dns-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public SafeWebClient() { this(InetAddress::getAllByName, SafeWebClient::socketGet); }
    public SafeWebClient(Resolver resolver, Exchange exchange) { this.resolver = resolver; this.exchange = exchange; }

    public Response get(URI target, Map<String, String> headers, TaskControl control) throws Exception {
        URI uri = target;
        Set<URI> visited = new HashSet<>();
        String authenticationOrigin = origin(uri);
        for (int redirects = 0; redirects <= 4; redirects++) {
            control.check();
            validateUri(uri);
            if (!visited.add(uri)) throw new IOException("WEB_REDIRECT_LOOP");
            String host = uri.getHost().replace("[", "").replace("]", "");
            CompletableFuture<InetAddress[]> lookup = CompletableFuture.supplyAsync(() -> {
                try { return resolver.resolve(host); }
                catch (Exception e) { throw new CompletionException(new IOException("WEB_DNS_FAILED")); }
            }, dns);
            InetAddress[] addresses = control.await(lookup, 2500);
            if (addresses.length == 0 || addresses.length > 16) throw new IOException("WEB_DNS_LIMIT");
            for (InetAddress address : addresses)
                if (!isPublic(address)) throw new SecurityException("WEB_PRIVATE_ADDRESS");
            Map<String, String> outbound = headers;
            if (!origin(uri).equals(authenticationOrigin) && !headers.isEmpty())
                throw new SecurityException("WEB_AUTHENTICATED_REDIRECT");
            Response response = exchange.get(uri, addresses[0], outbound, control);
            if (Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                String location = response.headers().get("location");
                if (location == null) throw new IOException("WEB_REDIRECT_WITHOUT_LOCATION");
                uri = uri.resolve(location);
                continue;
            }
            if (response.status() != 200) throw new IOException("WEB_HTTP_" + response.status());
            return response;
        }
        throw new IOException("WEB_REDIRECT_LIMIT");
    }
    public static void validateUri(URI uri) {
        if (uri == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.toASCIIString().length() > 4096
                || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443
                || uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".localhost")
                || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".local"))
            throw new SecurityException("WEB_URL_NOT_ALLOWED");
    }
    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + ":" + port(uri);
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? "https".equals(uri.getScheme()) ? 443 : 80 : uri.getPort(); }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && c >= 64 && c <= 127
                    || a == 169 && c == 254 || a == 172 && c >= 16 && c <= 31
                    || a == 192 && (c == 168 || c == 0 || c == 88 && d == 99)
                    || a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)
                    || a == 203 && c == 0 && d == 113);
        }
        if (b.length != 16) return false;
        if ((b[0] & 0xe0) != 0x20) return false; // Currently allocated global unicast only.
        if ((b[0] & 0xfe) == 0xfc || (b[0] & 255) == 0xff) return false;
        // Reject transition mechanisms that could encode a private IPv4 destination.
        if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02) return false;
        if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                && (b[2] == 0 && b[3] == 0 || (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)) return false;
        if (b[0] == 0 && (b[1] & 255) == 0x64 && (b[2] & 255) == 0xff && (b[3] & 255) == 0x9b) return false;
        boolean firstTwelveZero = true;
        for (int i = 0; i < 12; i++) firstTwelveZero &= b[i] == 0;
        return !firstTwelveZero;
    }

    static Response socketGet(URI uri, InetAddress address, Map<String, String> headers, TaskControl control) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        Socket raw = new Socket();
        Socket socket = raw;
        try {
            raw.connect(new InetSocketAddress(address, port(uri)), 4000);
            raw.setSoTimeout(500);
            if ("https".equals(uri.getScheme())) {
                String hostname = uri.getHost().replace("[", "").replace("]", "");
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, hostname, port(uri), true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!hostname.contains(":")) parameters.setServerNames(List.of(new SNIHostName(hostname)));
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(5000);
                tls.startHandshake();
                tls.setSoTimeout(500);
                socket = tls;
            }
            control.check();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(host)
                    .append("\r\nUser-Agent: FireflyMC-AI-Research/1\r\nAccept: text/html,application/json,text/plain\r\nAccept-Encoding: gzip\r\nConnection: close\r\n");
            for (var header : headers.entrySet()) {
                if (!header.getKey().matches("[A-Za-z-]{1,40}") || header.getValue().contains("\r") || header.getValue().contains("\n"))
                    throw new SecurityException("WEB_HEADER_INVALID");
                if (!"https".equals(uri.getScheme())) throw new SecurityException("WEB_CREDENTIALS_REQUIRE_TLS");
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = line(input, control, deadline, 4096);
            if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("WEB_INVALID_STATUS");
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, String> responseHeaders = new HashMap<>();
            int bytes = statusLine.length();
            while (true) {
                String line = line(input, control, deadline, 8192);
                bytes += line.length();
                if (bytes > 32768) throw new IOException("WEB_HEADERS_TOO_LARGE");
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("WEB_INVALID_HEADER");
                responseHeaders.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            if (status != 200) return new Response(uri, status, Map.copyOf(responseHeaders), new byte[0]);
            final int maximum = 1024 * 1024;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (responseHeaders.getOrDefault("transfer-encoding", "").equalsIgnoreCase("chunked")) {
                while (true) {
                    String length = line(input, control, deadline, 128).split(";", 2)[0].trim();
                    long chunk;
                    try { chunk = Long.parseLong(length, 16); } catch (NumberFormatException e) { throw new IOException("WEB_INVALID_CHUNK"); }
                    if (chunk < 0 || chunk > maximum - body.size()) throw new IOException("WEB_BODY_TOO_LARGE");
                    if (chunk == 0) break;
                    readExactly(input, body, (int) chunk, control, deadline);
                    if (!line(input, control, deadline, 2).isEmpty()) throw new IOException("WEB_INVALID_CHUNK");
                }
            } else if (responseHeaders.containsKey("content-length")) {
                long length;
                try { length = Long.parseLong(responseHeaders.get("content-length")); }
                catch (NumberFormatException e) { throw new IOException("WEB_INVALID_LENGTH"); }
                if (length < 0 || length > maximum) throw new IOException("WEB_BODY_TOO_LARGE");
                readExactly(input, body, (int) length, control, deadline);
            } else {
                byte[] buffer = new byte[8192];
                while (true) {
                    int count = read(input, buffer, buffer.length, control, deadline);
                    if (count < 0) break;
                    if (count > maximum - body.size()) throw new IOException("WEB_BODY_TOO_LARGE");
                    body.write(buffer, 0, count);
                }
            }
            byte[] data = body.toByteArray();
            if ("gzip".equalsIgnoreCase(responseHeaders.get("content-encoding"))) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    data = gzip.readNBytes(maximum + 1);
                    if (data.length > maximum) throw new IOException("WEB_DECOMPRESSED_BODY_TOO_LARGE");
                }
            } else if (responseHeaders.containsKey("content-encoding")
                    && !"identity".equalsIgnoreCase(responseHeaders.get("content-encoding")))
                throw new IOException("WEB_ENCODING_UNSUPPORTED");
            return new Response(uri, status, Map.copyOf(responseHeaders), data);
        } finally {
            try { socket.close(); } finally { if (socket != raw) raw.close(); }
        }
    }
    private static void readExactly(InputStream input, ByteArrayOutputStream output, int size, TaskControl control, long deadline) throws Exception {
        byte[] buffer = new byte[Math.min(8192, Math.max(1, size))];
        int remaining = size;
        while (remaining > 0) {
            int count = read(input, buffer, Math.min(buffer.length, remaining), control, deadline);
            if (count < 0) throw new EOFException("WEB_TRUNCATED_BODY");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }
    private static int read(InputStream input, byte[] buffer, int length, TaskControl control, long deadline) throws Exception {
        while (true) {
            control.check();
            if (System.nanoTime() > deadline) throw new IOException("WEB_TIMEOUT");
            try { return input.read(buffer, 0, length); }
            catch (SocketTimeoutException ignored) {}
        }
    }
    private static String line(InputStream input, TaskControl control, long deadline, int maximum) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        while (true) {
            if (read(input, one, 1, control, deadline) < 0) throw new EOFException("WEB_TRUNCATED_HEADER");
            int value = one[0] & 255;
            if (value == '\n') break;
            if (value != '\r') line.write(value);
            if (line.size() > maximum) throw new IOException("WEB_HEADER_TOO_LARGE");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }
    @Override public void close() { dns.shutdownNow(); }
}
