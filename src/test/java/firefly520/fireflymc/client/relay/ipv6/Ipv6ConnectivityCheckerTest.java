package firefly520.fireflymc.client.relay.ipv6;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

class Ipv6ConnectivityCheckerTest {

    private final Ipv6ConnectivityChecker checker = new Ipv6ConnectivityChecker(
            request -> { throw new UnsupportedOperationException("classify 不应调用 transport"); },
            java.time.Clock.systemUTC(),
            new Ipv6ConnectivityChecker.ProbeSettings() {
                public boolean enabled() { return true; }
                public int timeoutSeconds() { return 5; }
                public int cacheMinutes() { return 15; }
            },
            Runnable::run
    );

    private static Ipv6ProbeStatus classify(IOException e) {
        return Ipv6ConnectivityChecker.classifyForTest(e);
    }

    @Test
    void classify_unknownHost() {
        assertEquals(Ipv6ProbeStatus.DNS_FAILED, classify(new UnknownHostException("nx")));
    }

    @Test
    void classify_wrappedUnknownHost() {
        assertEquals(Ipv6ProbeStatus.DNS_FAILED, classify(new IOException(new UnknownHostException("nx"))));
    }

    @Test
    void classify_httpTimeout() {
        assertEquals(Ipv6ProbeStatus.CONNECT_TIMEOUT, classify(new HttpTimeoutException("t")));
    }

    @Test
    void classify_socketTimeout() {
        assertEquals(Ipv6ProbeStatus.CONNECT_TIMEOUT, classify(new SocketTimeoutException("t")));
    }

    @Test
    void classify_sslHandshake() {
        assertEquals(Ipv6ProbeStatus.TLS_FAILED, classify(new SSLHandshakeException("t")));
    }

    @Test
    void classify_connectException() {
        assertEquals(Ipv6ProbeStatus.CONNECT_FAILED, classify(new ConnectException("refused")));
    }

    @Test
    void classify_noRoute() {
        assertEquals(Ipv6ProbeStatus.CONNECT_FAILED, classify(new NoRouteToHostException("net unreachable")));
    }

    @Test
    void classify_semanticPriority_innerSslOverOuterConnect() {
        // 外层 ConnectException 不应掩盖内层 SSLException（语义优先级）
        IOException outer = new ConnectException("refused");
        outer.initCause(new SSLHandshakeException("tls"));
        assertEquals(Ipv6ProbeStatus.TLS_FAILED, classify(outer));
    }

    @Test
    void classify_unrecognizedFallsToUnknown() {
        assertEquals(Ipv6ProbeStatus.UNKNOWN, classify(new IOException("weird")));
    }

    @Test
    void cacheValid_nullReturnsFalse() {
        assertFalse(checker.cacheValidForTest(null));
    }

    @Test
    void cacheValid_zeroMinutesAlwaysInvalid() {
        Ipv6ConnectivityChecker zeroCache = newChecker(0, java.time.Clock.systemUTC());
        Ipv6ProbeResult r = resultAt(java.time.Clock.systemUTC().instant(), Ipv6ProbeStatus.AVAILABLE);
        assertFalse(zeroCache.cacheValidForTest(r));
    }

    @Test
    void cacheValid_withinWindowValid() {
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock fixedNow = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        Ipv6ConnectivityChecker c = newChecker(15, fixedNow);
        Ipv6ProbeResult r = resultAt(now.minus(java.time.Duration.ofMinutes(10)), Ipv6ProbeStatus.AVAILABLE);
        assertTrue(c.cacheValidForTest(r));
    }

    @Test
    void cacheValid_atBoundaryInvalid() {
        java.time.Instant now = java.time.Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC).instant();
        java.time.Clock fixedNow = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        Ipv6ConnectivityChecker c = newChecker(15, fixedNow);
        Ipv6ProbeResult r = resultAt(now.minus(java.time.Duration.ofMinutes(15)), Ipv6ProbeStatus.AVAILABLE);
        assertFalse(c.cacheValidForTest(r));
    }

    private static Ipv6ProbeResult resultAt(java.time.Instant t, Ipv6ProbeStatus s) {
        return new Ipv6ProbeResult(s, t, 100, null);
    }

    private static Ipv6ConnectivityChecker newChecker(int cacheMinutes, java.time.Clock clock) {
        return new Ipv6ConnectivityChecker(
                request -> 204,
                clock,
                new Ipv6ConnectivityChecker.ProbeSettings() {
                    public boolean enabled() { return true; }
                    public int timeoutSeconds() { return 5; }
                    public int cacheMinutes() { return cacheMinutes; }
                },
                Runnable::run
        );
    }
}
