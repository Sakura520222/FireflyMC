package firefly520.fireflymc.client.relay.ipv6;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
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

    @Test
    void performProbe_http200IsAvailable() throws Exception {
        Ipv6ConnectivityChecker c = newCheckerWithTransport(req -> 200, 5);
        Ipv6ProbeResult r = c.performProbeForTest();
        assertEquals(Ipv6ProbeStatus.AVAILABLE, r.status());
        assertEquals(200, r.httpStatus());
    }

    @Test
    void performProbe_http300IsHttpFailed() {
        Ipv6ConnectivityChecker c = newCheckerWithTransport(req -> 300, 5);
        assertEquals(Ipv6ProbeStatus.HTTP_FAILED, c.performProbeForTest().status());
    }

    @Test
    void performProbe_http199IsHttpFailed() {
        Ipv6ConnectivityChecker c = newCheckerWithTransport(req -> 199, 5);
        assertEquals(Ipv6ProbeStatus.HTTP_FAILED, c.performProbeForTest().status());
    }

    @Test
    void performProbe_unknownHostMapsDnsFailed() {
        Ipv6ConnectivityChecker c = newCheckerWithTransportFailing(req -> { throw new UnknownHostException("nx"); }, 5);
        Ipv6ProbeResult r = c.performProbeForTest();
        assertEquals(Ipv6ProbeStatus.DNS_FAILED, r.status());
        assertNull(r.httpStatus());
    }

    @Test
    void performProbe_interruptedMapsUnknownAndRestoresFlag() throws Exception {
        Ipv6ConnectivityChecker c = newCheckerWithTransportFailing(req -> { throw new InterruptedException("t"); }, 5);
        Ipv6ProbeResult r = c.performProbeForTest();
        assertEquals(Ipv6ProbeStatus.UNKNOWN, r.status());
        // 当前测试线程被 performProbe 内 Thread.currentThread().interrupt() 恢复标志
        assertTrue(Thread.interrupted());
    }

    @Test
    void performProbe_runtimeExceptionMapsUnknown() {
        Ipv6ConnectivityChecker c = newCheckerWithTransportFailing(req -> { throw new RuntimeException("boom"); }, 5);
        assertEquals(Ipv6ProbeStatus.UNKNOWN, c.performProbeForTest().status());
    }

    interface FailingTransport { int send(HttpRequest req) throws Exception; }

    private static Ipv6ConnectivityChecker newCheckerWithTransport(Ipv6ConnectivityChecker.ProbeTransport t, int timeout) {
        return new Ipv6ConnectivityChecker(t, java.time.Clock.systemUTC(), settings(timeout), Runnable::run);
    }

    private static Ipv6ConnectivityChecker newCheckerWithTransportFailing(FailingTransport t, int timeout) {
        return new Ipv6ConnectivityChecker(req -> {
            try { return t.send(req); }
            catch (IOException | InterruptedException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }, java.time.Clock.systemUTC(), settings(timeout), Runnable::run);
    }

    private static Ipv6ConnectivityChecker.ProbeSettings settings(int timeout) {
        return new Ipv6ConnectivityChecker.ProbeSettings() {
            public boolean enabled() { return true; }
            public int timeoutSeconds() { return timeout; }
            public int cacheMinutes() { return 15; }
        };
    }

    @Test
    void runProbe_clockRuntimeExceptionCompletesExceptionallyAndRevertsSnapshot() {
        java.time.Clock brokenClock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { throw new RuntimeException("clock broken"); }
        };
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> 204, brokenClock, settings(5), Runnable::run);
        Ipv6ProbeResult previous = new Ipv6ProbeResult(Ipv6ProbeStatus.AVAILABLE,
                java.time.Instant.now(), 10, 204);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.done(previous));

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> future = new java.util.concurrent.CompletableFuture<>();
        c.runProbeForTest(future, previous);

        assertTrue(future.isCompletedExceptionally());
        assertFalse(c.snapshot().probing());
        assertEquals(previous, c.snapshot().lastResult());
    }

    @Test
    void runProbe_errorRethrownAndRevertsSnapshot() {
        Ipv6ConnectivityChecker c = newCheckerWithTransportFailing(req -> { throw new StackOverflowError("oom"); }, 5);
        Ipv6ProbeResult previous = new Ipv6ProbeResult(Ipv6ProbeStatus.AVAILABLE, java.time.Instant.now(), 10, 204);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.done(previous));

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> future = new java.util.concurrent.CompletableFuture<>();
        org.junit.jupiter.api.Assertions.assertThrows(StackOverflowError.class, () -> c.runProbeForTest(future, previous));
        assertTrue(future.isCompletedExceptionally());
        assertFalse(c.snapshot().probing());
    }

    @Test
    void checkAsync_disabledReturnsFailedFuture() {
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> 204, java.time.Clock.systemUTC(),
                new Ipv6ConnectivityChecker.ProbeSettings() {
                    public boolean enabled() { return false; }
                    public int timeoutSeconds() { return 5; }
                    public int cacheMinutes() { return 15; }
                },
                Runnable::run);
        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(false);
        assertTrue(f.isCompletedExceptionally());
        assertFalse(c.snapshot().probing());
    }

    @Test
    void checkAsync_singleFlight_reusesInFlightFuture() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> { calls.incrementAndGet(); entered.countDown(); release.await(); return 204; },
                java.time.Clock.systemUTC(), settings(5), probeExecutor());

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f1 = c.checkAsync(false);
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "probe not entered in time");
        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f2 = c.checkAsync(false);
        assertSame(f1, f2);
        assertEquals(1, calls.get());
        release.countDown();
        Ipv6ProbeResult r1 = f1.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Ipv6ProbeStatus.AVAILABLE, r1.status());
    }

    @Test
    void checkAsync_cacheHitReturnsCompletedFutureWithCached() {
        java.time.Instant now = java.time.Clock.systemUTC().instant();
        java.time.Clock fixedNow = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        Ipv6ConnectivityChecker c = newChecker(15, fixedNow);
        Ipv6ProbeResult cached = new Ipv6ProbeResult(Ipv6ProbeStatus.DNS_FAILED, now.minusSeconds(60), 50, null);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.done(cached));

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(false);
        assertTrue(f.isDone());
        assertSame(cached, f.getNow(null));
    }

    @Test
    void checkAsync_forceSkipsCache() {
        java.time.Instant now = java.time.Clock.systemUTC().instant();
        java.time.Clock fixedNow = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> { calls.incrementAndGet(); return 204; }, fixedNow, settings(5), Runnable::run);
        Ipv6ProbeResult cached = new Ipv6ProbeResult(Ipv6ProbeStatus.DNS_FAILED, now.minusSeconds(60), 50, null);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.done(cached));

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(true);
        assertTrue(f.isDone());
        assertEquals(Ipv6ProbeStatus.AVAILABLE, f.getNow(null).status());
        assertEquals(1, calls.get());
    }

    @Test
    void checkAsync_probingKeepsLastResult() {
        Ipv6ProbeResult prev = new Ipv6ProbeResult(Ipv6ProbeStatus.AVAILABLE, java.time.Instant.now(), 10, 204);
        Ipv6ConnectivityChecker c = newCheckerWithTransport(req -> 204, 5);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.probing(prev));
        // 不实际触发,只验证 snapshot 字段语义:probing(true) 保留 lastResult
        assertTrue(c.snapshot().probing());
        assertSame(prev, c.snapshot().lastResult());
    }

    @Test
    void checkAsync_executorRejectedReturnsExceptionallyCompletedFuture() {
        java.util.concurrent.Executor failing = task -> { throw new java.util.concurrent.RejectedExecutionException("test"); };
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> 204, java.time.Clock.systemUTC(), settings(5), failing);

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(true);
        assertTrue(f.isCompletedExceptionally());
        assertFalse(c.snapshot().probing());
        assertNull(c.snapshot().lastResult());
        // 后续可重启
        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f2 = c.checkAsync(true);
        assertTrue(f2.isCompletedExceptionally());
    }

    @Test
    void checkAsync_interruptedMapsUnknownEndToEnd() throws Exception {
        // 端到端验证 checkAsync + InterruptedException → result UNKNOWN + 状态正确。
        // 中断标志的恢复已由 performProbe_interruptedMapsUnknownAndRestoresFlag 直接验证
        // (不在此用 whenComplete 验证回调线程标志 —— CompletableFuture 回调执行时机/线程
        //  随 JDK 实现而异,在 complete 线程与 join 线程之间可能异步,会引入 flaky)。
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> { entered.countDown(); release.await(); throw new InterruptedException("t"); },
                java.time.Clock.systemUTC(), settings(5), probeExecutor());

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(false);
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "probe not entered in time");
        release.countDown();
        Ipv6ProbeResult result = f.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(Ipv6ProbeStatus.UNKNOWN, result.status());
        assertNull(result.httpStatus());
        assertFalse(c.snapshot().probing());
        assertNotNull(c.snapshot().lastResult());
    }

    /** 测试用 daemon 线程 Executor:避免非 daemon 线程在测试结束后阻止 JVM 退出。 */
    private static java.util.concurrent.Executor probeExecutor() {
        return r -> {
            Thread t = new Thread(r, "ipv6-test-probe");
            t.setDaemon(true);
            t.start();
        };
    }
}
