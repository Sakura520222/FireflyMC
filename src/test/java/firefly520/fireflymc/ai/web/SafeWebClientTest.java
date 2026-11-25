package firefly520.fireflymc.ai.web;

import com.sun.net.httpserver.HttpServer;
import firefly520.fireflymc.ai.task.TaskControl;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SafeWebClientTest {
    @Test void privateLoopbackMetadataReservedAndTransitionAddressesAreRejected() throws Exception {
        for (String value : List.of("127.0.0.1", "10.1.2.3", "172.16.4.2", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "198.18.0.1", "192.0.2.1", "203.0.113.1", "::1", "fc00::1",
                "fe80::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::1", "64:ff9b::7f00:1"))
            assertFalse(SafeWebClient.isPublic(InetAddress.getByName(value)), value);
        assertTrue(SafeWebClient.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeWebClient.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
        for (String value : List.of("file:///etc/passwd", "http://localhost/x", "http://user:secret@example.org",
                "ftp://example.org", "https://example.org:22/", "http://machine.local/"))
            assertThrows(SecurityException.class, () -> SafeWebClient.validateUri(URI.create(value)));
    }

    @Test void validatedAddressIsPinnedAndRedirectRebindingFailsBeforeSecondConnection() throws Exception {
        AtomicInteger resolutions = new AtomicInteger(), exchanges = new AtomicInteger();
        InetAddress publicIp = InetAddress.getByName("8.8.8.8"), privateIp = InetAddress.getByName("127.0.0.1");
        try (var web = new SafeWebClient(host -> resolutions.incrementAndGet() == 1
                ? new InetAddress[]{publicIp} : new InetAddress[]{privateIp}, (uri, ip, headers, control) -> {
            assertEquals(publicIp, ip);
            exchanges.incrementAndGet();
            return new SafeWebClient.Response(uri, 302, Map.of("location", "/next"), new byte[0]);
        })) {
            assertThrows(SecurityException.class, () -> web.get(URI.create("https://source.example/"),
                    Map.of(), new TaskControl(5000)));
        }
        assertEquals(2, resolutions.get());
        assertEquals(1, exchanges.get());
    }

    @Test void mixedDnsAndCredentialRedirectAreRejectedWithoutLeakingHeaders() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        try (var web = new SafeWebClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")}, (uri, address, headers, control) -> {
            sent.incrementAndGet(); return new SafeWebClient.Response(uri, 200, Map.of(), new byte[0]);
        })) {
            assertThrows(SecurityException.class, () -> web.get(URI.create("https://source.example"), Map.of(), new TaskControl(5000)));
        }
        assertEquals(0, sent.get());
        try (var web = new SafeWebClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
                (uri, address, headers, control) -> {
                    sent.incrementAndGet();
                    return new SafeWebClient.Response(uri, 302, Map.of("location", "https://other.example/"), new byte[0]);
                })) {
            assertThrows(SecurityException.class, () -> web.get(URI.create("https://search.example"),
                    Map.of("X-Subscription-Token", "test-only"), new TaskControl(5000)));
        }
        assertEquals(1, sent.get());
    }

    @Test void realBoundedTransportReadsChunkedBodyAndRejectsCompressedExpansion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chunked", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("<main>verified content</main>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext("/bomb", exchange -> {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(new byte[2 * 1024 * 1024]); }
            byte[] bytes = compressed.toByteArray();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            // Test the HTTP parser directly against an isolated local fixture. Production get() still
            // rejects this address/port; the public-address and pinning tests above cover that gate.
            URI base = URI.create("http://fixture.example:" + server.getAddress().getPort());
            var response = SafeWebClient.socketGet(base.resolve("/chunked"), InetAddress.getLoopbackAddress(),
                    Map.of(), new TaskControl(5000));
            assertTrue(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8).contains("verified content"));
            var failure = assertThrows(IOException.class, () -> SafeWebClient.socketGet(base.resolve("/bomb"),
                    InetAddress.getLoopbackAddress(), Map.of(), new TaskControl(5000)));
            assertEquals("WEB_DECOMPRESSED_BODY_TOO_LARGE", failure.getMessage());
        } finally { server.stop(0); }
    }
}
