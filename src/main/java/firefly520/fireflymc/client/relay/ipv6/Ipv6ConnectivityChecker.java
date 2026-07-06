package firefly520.fireflymc.client.relay.ipv6;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.FireflyMCMod;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IPv6 出站能力检测器（唯一状态源）。
 *
 * <p><b>红线</b>：本检测器仅证明<b>客户端 → 外部 HTTPS 服务的 IPv6 出站能力</b>。
 * 不证明房主 IPv6 游戏端口可被公网入站直连。UI 不得据此宣称"可被 IPv6 玩家直连"
 * 或"公网可达"，也不得声称检测结果会驱动 P2P / 中继路径选择（P2P 核心不读取本检测结果）。
 */
public final class Ipv6ConnectivityChecker {
    private static final Ipv6ConnectivityChecker INSTANCE = new Ipv6ConnectivityChecker();
    private static final String ENDPOINT = "https://ipv6.test-ipv6.com/images/hires_ok.png";
    private static final String USER_AGENT =
            "FireflyMC-Launcher/" + FireflyMCMod.VERSION + " IPv6ConnectivityCheck";

    private final AtomicReference<Ipv6ProbeSnapshot> snapshot =
            new AtomicReference<>(Ipv6ProbeSnapshot.idle());
    private final AtomicReference<CompletableFuture<Ipv6ProbeResult>> inFlight = new AtomicReference<>();
    private final ProbeTransport transport;
    private final Clock clock;
    private final ProbeSettings settings;
    private final Executor probeExecutor;

    /** 公开快照,供 UI 原子读取。 */
    public record Ipv6ProbeSnapshot(boolean probing, @Nullable Ipv6ProbeResult lastResult) {
        public static Ipv6ProbeSnapshot idle() { return new Ipv6ProbeSnapshot(false, null); }
        public static Ipv6ProbeSnapshot probing(@Nullable Ipv6ProbeResult previous) { return new Ipv6ProbeSnapshot(true, previous); }
        public static Ipv6ProbeSnapshot done(Ipv6ProbeResult result) { return new Ipv6ProbeSnapshot(false, result); }
    }

    interface ProbeTransport {
        int send(HttpRequest request) throws java.io.IOException, InterruptedException;
    }

    interface ProbeSettings {
        boolean enabled();
        int timeoutSeconds();
        int cacheMinutes();
    }

    private Ipv6ConnectivityChecker() {
        HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.transport = request -> client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        this.clock = Clock.systemUTC();
        this.settings = new ProbeSettings() {
            public boolean enabled() { return Config.CLIENT.IPV6_PROBE_ENABLED.get(); }
            public int timeoutSeconds() { return Config.CLIENT.IPV6_PROBE_TIMEOUT_SECONDS.get(); }
            public int cacheMinutes() { return Config.CLIENT.IPV6_PROBE_CACHE_MINUTES.get(); }
        };
        this.probeExecutor = task -> Thread.ofVirtual().name("fireflymc-ipv6-probe").start(task);
    }

    /** 测试用构造器(包私有,全注入)。 */
    Ipv6ConnectivityChecker(ProbeTransport transport, Clock clock, ProbeSettings settings, Executor probeExecutor) {
        this.transport = transport;
        this.clock = clock;
        this.settings = settings;
        this.probeExecutor = probeExecutor;
    }

    public static Ipv6ConnectivityChecker getInstance() { return INSTANCE; }

    public Ipv6ProbeSnapshot snapshot() { return snapshot.get(); }

    /** 全链扫描,按语义优先级返回(数值小者优先)。 */
    static Ipv6ProbeStatus classifyForTest(IOException error) {
        return classify(error);
    }

    private static Ipv6ProbeStatus classify(IOException error) {
        int bestRank = Integer.MAX_VALUE;
        Ipv6ProbeStatus best = Ipv6ProbeStatus.UNKNOWN;
        for (Throwable c = error; c != null; c = c.getCause()) {
            Ipv6ProbeStatus s;
            int rank;
            if (c instanceof UnknownHostException) { s = Ipv6ProbeStatus.DNS_FAILED; rank = 1; }
            else if (c instanceof java.net.http.HttpTimeoutException || c instanceof SocketTimeoutException) { s = Ipv6ProbeStatus.CONNECT_TIMEOUT; rank = 2; }
            else if (c instanceof javax.net.ssl.SSLException) { s = Ipv6ProbeStatus.TLS_FAILED; rank = 3; }
            else if (c instanceof ConnectException || c instanceof NoRouteToHostException) { s = Ipv6ProbeStatus.CONNECT_FAILED; rank = 4; }
            else continue;
            if (rank < bestRank) { bestRank = rank; best = s; }
        }
        return best;
    }

    boolean cacheValidForTest(@Nullable Ipv6ProbeResult result) { return isCacheValid(result); }

    private boolean isCacheValid(@Nullable Ipv6ProbeResult result) {
        if (result == null) return false;
        int cm = settings.cacheMinutes();
        if (cm <= 0) return false;
        return java.time.Duration.between(result.checkedAt(), clock.instant()).toMinutes() < cm;
    }

    Ipv6ProbeResult performProbeForTest() { return performProbe(); }

    private Ipv6ProbeResult performProbe() {
        long startedNanos = System.nanoTime();
        Ipv6ProbeStatus status;
        @Nullable Integer httpStatus;
        try {
            int code = transport.send(buildRequest());
            status = (code >= 200 && code < 300) ? Ipv6ProbeStatus.AVAILABLE : Ipv6ProbeStatus.HTTP_FAILED;
            httpStatus = code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Ipv6ProbeStatus.UNKNOWN;
            httpStatus = null;
        } catch (IOException e) {
            status = classify(e);
            httpStatus = null;
        } catch (RuntimeException e) {
            status = Ipv6ProbeStatus.UNKNOWN;
            httpStatus = null;
        }
        java.time.Instant checkedAt = clock.instant();
        long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return new Ipv6ProbeResult(status, checkedAt, durationMs, httpStatus);
    }

    private HttpRequest buildRequest() {
        return HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + "?cb=" + java.util.UUID.randomUUID()))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }
}
