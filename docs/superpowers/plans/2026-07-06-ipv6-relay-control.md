# IPv6 联机增强与单人 ESC 联机控制 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在单人 ESC 菜单注入"FireflyMC 联机"入口,打开控制面板启停联机;通过 test-ipv6.com 纯 IPv6 端点检测客户端 IPv6 出站能力并展示。

**Architecture:** 新增 `Ipv6ConnectivityChecker`(唯一状态源,全注入可测)+ `SingleplayerRelayControlScreen`(面板);`SingleplayerRelayManager` 加 `HostingState` 四态机(路线 A,主线程串行 + CAS);`SingleplayerRelayClientEvents` 扩展注入 ESC 按钮 + 进世界自动检测。详见 `docs/superpowers/specs/2026-07-06-ipv6-relay-control-design.md`(唯一设计依据)。

**Tech Stack:** Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21 / ModDevGradle / JUnit 5(本次新引入,仅 Checker 用)。

---

## Scope(排除项)

- **路线 B(Relay 异步生命周期收敛)不在本计划内**:Manager 不治理底层 executor/WebSocket/P2P 迟到回调竞态,这是 spec §2.3 既定的独立专项。任何"停止后绝无迟到资源"的验证项均不列入。
- 本计划不重构 P2P / relay 传输核心,对 `SingleplayerRelayManager` 仅做主线程调度对齐 + 状态标记 + 只读 getter。

## File Structure

**新建:**

| 文件 | 责任 |
|---|---|
| `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeStatus.java` | 7 终态公开枚举 |
| `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeResult.java` | 公开 record |
| `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java` | 单例;内含公开嵌套 `Ipv6ProbeSnapshot`、包私有 `ProbeTransport`/`ProbeSettings`;唯一状态源 |
| `src/main/java/firefly520/fireflymc/client/screen/SingleplayerRelayControlScreen.java` | 联机控制面板 |
| `src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java` | Checker JUnit 单测 |

**修改:**

| 文件 | 改动 |
|---|---|
| `build.gradle` | 加 JUnit 5 test 依赖 + `useJUnitPlatform()` |
| `src/main/java/firefly520/fireflymc/Config.java` | 加 `ipv6_probe` 配置组(4 项) |
| `src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayManager.java` | `HostingState` 枚举 + `AtomicReference` + CAS 转换 + 主线程调度对齐 + getter |
| `src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayClientEvents.java` | `GameMenuScreen` 注入 + `onClientLoggedIn` 双检查触发 |
| `src/main/resources/assets/fireflymc/lang/zh_cn.json` | +i18n key |
| `src/main/resources/assets/fireflymc/lang/en_us.json` | +i18n key |

## Testing Strategy

- **Checker**:`src/test` JUnit 5 单测(任务 1 建基建)。Checker 的 `transport`/`clock`/`settings`/`probeExecutor` 全注入,脱离 NeoForge 全局状态,可纯单测。这是本计划唯一的自动化测试层。
- **Manager / Screen / Events**:**不做 JUnit 单测**。Manager 深度依赖 `Minecraft.getInstance()`/integrated server/relay 真实对象,Mock 成本极高且脆弱;NeoForge GameTest 是服务端导向,不适配客户端 Manager 与 GUI。这些模块靠**任务 10 的手动测试矩阵**验证(对应 spec §11.3 + §11.4)。

> 这是 spec §11.2 的第 3 选(白盒/手动)。Manager 状态机不变量用任务 10 第 9 项白盒日志验证。

---

## Task 1: 建立测试基建

**Files:**
- Modify: `build.gradle`
- Create: `src/test/java/firefly520/fireflymc/SmokeTest.java`

- [ ] **Step 1: 加 JUnit 5 依赖到 build.gradle**

在 `build.gradle` 的 `dependencies { ... }` 块末尾(第 148 行 `}` 前)加入:

```groovy
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

在 `dependencies` 块之后(第 150 行 `// This block of code expands...` 注释前)加入:

```groovy
tasks.named('test', Test).configure {
    useJUnitPlatform()
}
```

- [ ] **Step 2: 建 smoke test 验证基建**

Create `src/test/java/firefly520/fireflymc/SmokeTest.java`:

```java
package firefly520.fireflymc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 JUnit 5 基建可用。 */
class SmokeTest {
    @Test
    void passes() {
        assertTrue(true);
    }
}
```

- [ ] **Step 3: 运行测试确认基建可用**

Run: `.\gradlew.bat test`
Expected: `BUILD SUCCESSFUL`,SmokeTest 通过。

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/test/java/firefly520/fireflymc/SmokeTest.java
git commit -m "test: 引入 JUnit 5 测试基建"
```

---

## Task 2: Ipv6ProbeStatus 枚举

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeStatus.java`

- [ ] **Step 1: 创建枚举**

```java
package firefly520.fireflymc.client.relay.ipv6;

/** IPv6 出站能力检测的终态分类。 */
public enum Ipv6ProbeStatus {
    AVAILABLE,
    DNS_FAILED,
    CONNECT_FAILED,
    CONNECT_TIMEOUT,
    TLS_FAILED,
    HTTP_FAILED,
    UNKNOWN
}
```

- [ ] **Step 2: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeStatus.java
git commit -m "feat(ipv6): 新增 Ipv6ProbeStatus 终态枚举"
```

---

## Task 3: Ipv6ProbeResult record

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeResult.java`

- [ ] **Step 1: 创建 record**

```java
package firefly520.fireflymc.client.relay.ipv6;

import javax.annotation.Nullable;
import java.time.Instant;

/** 一次已完成的 IPv6 出站检测的不可变结果。 */
public record Ipv6ProbeResult(
        Ipv6ProbeStatus status,
        Instant checkedAt,
        long durationMs,
        @Nullable Integer httpStatus
) {}
```

> `javax.annotation.Nullable` 来自 NeoForge 已打包的 `guava`/`findbugs` 依赖,项目已有使用(见 `ConnectScreenMixin` 等)。

- [ ] **Step 2: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ProbeResult.java
git commit -m "feat(ipv6): 新增 Ipv6ProbeResult record"
```

---

## Task 4: Config `ipv6_probe` 配置组

**Files:**
- Modify: `src/main/java/firefly520/fireflymc/Config.java`
- Modify: `src/main/resources/assets/fireflymc/lang/zh_cn.json`
- Modify: `src/main/resources/assets/fireflymc/lang/en_us.json`

> **先于 Checker**,因为生产 Checker 的 `ProbeSettings` 匿名实现引用这些配置字段。

- [ ] **Step 1: 在 ClientConfig 加配置字段**

在 `Config.java` 的 `ClientConfig` 类中,`SINGLEPLAYER_RELAY_P2P_IPV6_ENABLED` 字段声明(约第 30 行)之后加入:

```java
    public final ModConfigSpec.BooleanValue IPV6_PROBE_ENABLED;
    public final ModConfigSpec.BooleanValue IPV6_PROBE_AUTO_ON_SP_JOIN;
    public final ModConfigSpec.ConfigValue<Integer> IPV6_PROBE_TIMEOUT_SECONDS;
    public final ModConfigSpec.ConfigValue<Integer> IPV6_PROBE_CACHE_MINUTES;
```

在 `ClientConfig` 构造器的 `builder.pop()` (singleplayer_relay 组结束,约第 114 行)之后、`builder.push("event_notification")` 之前加入:

```java
      builder.push("ipv6_probe")
        .translation("fireflymc.configuration.ipv6_probe");

      IPV6_PROBE_ENABLED = builder
        .comment("Enable IPv6 outbound connectivity probe (test-ipv6.com).")
        .translation("fireflymc.configuration.ipv6_probe.enabled")
        .define("enabled", true);

      IPV6_PROBE_AUTO_ON_SP_JOIN = builder
        .comment("Probe IPv6 outbound silently when entering a singleplayer world.")
        .translation("fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join")
        .define("autoCheckOnSingleplayerJoin", true);

      IPV6_PROBE_TIMEOUT_SECONDS = builder
        .comment("Per-request timeout for the IPv6 probe.")
        .translation("fireflymc.configuration.ipv6_probe.timeout_seconds")
        .defineInRange("timeoutSeconds", 5, 1, 30);

      IPV6_PROBE_CACHE_MINUTES = builder
        .comment("Minutes a completed probe result is cached. 0 = always re-probe.")
        .translation("fireflymc.configuration.ipv6_probe.cache_minutes")
        .defineInRange("cacheMinutes", 15, 0, 1440);

      builder.pop();
```

- [ ] **Step 2: 加配置翻译 key 到 zh_cn.json**

在 `zh_cn.json` 的 `singleplayer_relay` 配置翻译段之后(约第 38 行附近,最后一个 `singleplayer_relay` key 之后)加入:

```json
  "fireflymc.configuration.ipv6_probe": "IPv6 联机检测",
  "fireflymc.configuration.ipv6_probe.enabled": "启用 IPv6 出站检测",
  "fireflymc.configuration.ipv6_probe.enabled.tooltip": "通过 test-ipv6.com 检测客户端的 IPv6 出站能力",
  "fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join": "进入单人世界时自动检测",
  "fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join.tooltip": "进入单人世界后后台静默检测一次 IPv6 出站能力",
  "fireflymc.configuration.ipv6_probe.timeout_seconds": "每次检测超时（秒）",
  "fireflymc.configuration.ipv6_probe.timeout_seconds.tooltip": "单次 IPv6 检测请求的超时时间",
  "fireflymc.configuration.ipv6_probe.cache_minutes": "检测结果缓存时长（分钟）",
  "fireflymc.configuration.ipv6_probe.cache_minutes.tooltip": "已完成检测结果的缓存时长，0 表示不缓存",
```

- [ ] **Step 3: 加配置翻译 key 到 en_us.json**

在 `en_us.json` 对应位置加入英文翻译:

```json
  "fireflymc.configuration.ipv6_probe": "IPv6 Connectivity Probe",
  "fireflymc.configuration.ipv6_probe.enabled": "Enable IPv6 outbound probe",
  "fireflymc.configuration.ipv6_probe.enabled.tooltip": "Probe client IPv6 outbound connectivity via test-ipv6.com",
  "fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join": "Auto-probe on singleplayer join",
  "fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join.tooltip": "Silently probe IPv6 outbound once when entering a singleplayer world",
  "fireflymc.configuration.ipv6_probe.timeout_seconds": "Per-probe timeout (seconds)",
  "fireflymc.configuration.ipv6_probe.timeout_seconds.tooltip": "Timeout for a single IPv6 probe request",
  "fireflymc.configuration.ipv6_probe.cache_minutes": "Result cache minutes",
  "fireflymc.configuration.ipv6_probe.cache_minutes.tooltip": "How long a completed probe result is cached; 0 = never cache",
```

- [ ] **Step 4: 编译确认**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`(JSON 语法正确、配置注册成功)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/Config.java src/main/resources/assets/fireflymc/lang/zh_cn.json src/main/resources/assets/fireflymc/lang/en_us.json
git commit -m "feat(config): 新增 ipv6_probe 配置组及 i18n"
```

---

## Task 5: Ipv6ConnectivityChecker(分 7 个 TDD 子任务)

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java`
- Create: `src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java`

> 这是本计划的核心。完整代码分步给出,每子任务一个 TDD 循环。

### Task 5.1: 接口 + Snapshot + 骨架

- [ ] **Step 1: 创建 Checker 骨架(接口 + 字段 + 构造器 + snapshot getter)**

Create `src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java`:

```java
package firefly520.fireflymc.client.relay.ipv6;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.FireflyMCMod;

import javax.annotation.Nullable;
import java.net.URI;
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
}
```

- [ ] **Step 2: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java
git commit -m "feat(ipv6): Ipv6ConnectivityChecker 骨架与依赖注入"
```

### Task 5.2: classify(IOException) + 单测

- [ ] **Step 1: 写失败测试**

Create `src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java`:

```java
package firefly520.fireflymc.client.relay.ipv6;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(Ipv6ProbeStatus.NO_ROUTE_TO_HOST_EXCEPTION());
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
    void classify_noRouteToHostException() {
        assertEquals(Ipv6ProbeStatus.CONNECT_FAILED, classify(new NoRouteToHostException("net unreachable")));
    }
}
```

> 注:`classify_noRoute` 与 `classify_noRouteToHostException` 是同类重复——保留前者删后者,实际写时合并为一个 `classify_noRoute` 测试即可。修正见 Step 3。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 编译失败(`classifyForTest` 不存在、`NO_ROUTE_TOHostException()` 误写)。

- [ ] **Step 3: 修正测试 + 暴露 classify 包私有方法**

修正 `Ipv6ConnectivityCheckerTest.java`(删除重复/误写的 `classify_noRoute` / `classify_noRouteToHostException`,合并为一个):

```java
    @Test
    void classify_noRoute() {
        assertEquals(Ipv6ProbeStatus.CONNECT_FAILED, classify(new NoRouteToHostException("net unreachable")));
    }
```

在 `Ipv6ConnectivityChecker` 内加 classify 实现 + 包私有测试桥接方法:

```java
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
            else if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) { s = Ipv6ProbeStatus.CONNECT_TIMEOUT; rank = 2; }
            else if (c instanceof javax.net.ssl.SSLException) { s = Ipv6ProbeStatus.TLS_FAILED; rank = 3; }
            else if (c instanceof ConnectException || c instanceof NoRouteToHostException) { s = Ipv6ProbeStatus.CONNECT_FAILED; rank = 4; }
            else continue;
            if (rank < bestRank) { bestRank = rank; best = s; }
        }
        return best;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 所有 classify 测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "feat(ipv6): classify 全链语义优先级分类 + 单测"
```

### Task 5.3: isCacheValid + 单测

- [ ] **Step 1: 写失败测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
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
```

加 import:`import static org.junit.jupiter.api.Assertions.assertFalse;`、`import static org.junit.jupiter.api.Assertions.assertTrue;`。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 编译失败(`cacheValidForTest` 不存在)。

- [ ] **Step 3: 实现 isCacheValid + 暴露测试桥接**

在 `Ipv6ConnectivityChecker` 加:

```java
    boolean cacheValidForTest(@Nullable Ipv6ProbeResult result) { return isCacheValid(result); }

    private boolean isCacheValid(@Nullable Ipv6ProbeResult result) {
        if (result == null) return false;
        int cm = settings.cacheMinutes();
        if (cm <= 0) return false;
        return java.time.Duration.between(result.checkedAt(), clock.instant()).toMinutes() < cm;
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 所有缓存测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "feat(ipv6): isCacheValid 缓存判定 + 边界单测"
```

### Task 5.4: buildRequest + performProbe + 单测

- [ ] **Step 1: 写失败测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
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
        return new Ipv6ConnectivityChecker(req -> t.send(req), java.time.Clock.systemUTC(), settings(timeout), Runnable::run);
    }

    private static Ipv6ConnectivityChecker.ProbeSettings settings(int timeout) {
        return new Ipv6ConnectivityChecker.ProbeSettings() {
            public boolean enabled() { return true; }
            public int timeoutSeconds() { return timeout; }
            public int cacheMinutes() { return 15; }
        };
    }
```

加 import:`import static org.junit.jupiter.api.Assertions.assertNull;`、`import java.net.http.HttpRequest;`。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 编译失败(`performProbeForTest` 不存在)。

- [ ] **Step 3: 实现 buildRequest + performProbe + 测试桥接**

在 `Ipv6ConnectivityChecker` 加:

```java
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
        } catch (java.io.IOException e) {
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
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 所有 performProbe 测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "feat(ipv6): performProbe 检测流程 + HTTP/异常/中断单测"
```

### Task 5.5: runProbe(Error / clock-RuntimeException 安全)+ 单测

- [ ] **Step 1: 写失败测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
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
    void runProbe_errorRe thrownAndRevertsSnapshot() {
        Ipv6ConnectivityChecker c = newCheckerWithTransportFailing(req -> { throw new StackOverflowError("oom"); }, 5);
        Ipv6ProbeResult previous = new Ipv6ProbeResult(Ipv6ProbeStatus.AVAILABLE, java.time.Instant.now(), 10, 204);
        c.setSnapshotForTest(Ipv6ConnectivityChecker.Ipv6ProbeSnapshot.done(previous));

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> future = new java.util.concurrent.CompletableFuture<>();
        org.junit.jupiter.api.Assertions.assertThrows(StackOverflowError.class, () -> c.runProbeForTest(future, previous));
        assertTrue(future.isCompletedExceptionally());
        assertFalse(c.snapshot().probing());
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 编译失败(`runProbeForTest`/`setSnapshotForTest` 不存在)。

- [ ] **Step 3: 实现 runProbe + 测试桥接**

在 `Ipv6ConnectivityChecker` 加:

```java
    void runProbeForTest(java.util.concurrent.CompletableFuture<Ipv6ProbeResult> candidate,
                         @Nullable Ipv6ProbeResult previous) { runProbe(candidate, previous); }

    void setSnapshotForTest(Ipv6ProbeSnapshot s) { snapshot.set(s); }

    private void runProbe(java.util.concurrent.CompletableFuture<Ipv6ProbeResult> candidate,
                          @Nullable Ipv6ProbeResult previous) {
        try {
            Ipv6ProbeResult result = performProbe();
            snapshot.set(Ipv6ProbeSnapshot.done(result));
            candidate.complete(result);
        } catch (RuntimeException error) {
            // clock.instant()、结果构造等基础设施异常(transport/buildRequest 的普通 RE 已在 performProbe 内映射 UNKNOWN)
            snapshot.set(new Ipv6ProbeSnapshot(false, previous));
            candidate.completeExceptionally(error);
        } catch (Error error) {
            snapshot.set(new Ipv6ProbeSnapshot(false, previous));
            candidate.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.compareAndSet(candidate, null);
        }
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: runProbe 测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "feat(ipv6): runProbe RuntimeException/Error 安全网 + 单测"
```

### Task 5.6: checkAsync(single-flight / 在途优先 / enabled / force / 缓存)+ 单测

- [ ] **Step 1: 写失败测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
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
                java.time.Clock.systemUTC(), settings(5),
                java.util.concurrent.Executors.newSingleThreadExecutor());

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f1 = c.checkAsync(false);
        entered.await();
        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f2 = c.checkAsync(false);
        assertSame(f1, f2);
        assertEquals(1, calls.get());
        release.countDown();
        f1.join();
        assertEquals(Ipv6ProbeStatus.AVAILABLE, f1.get().status());
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
```

加 import:`import static org.junit.jupiter.api.Assertions.assertSame;`。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 编译失败(`checkAsync` 不存在)。

- [ ] **Step 3: 实现 checkAsync**

在 `Ipv6ConnectivityChecker` 加:

```java
    public java.util.concurrent.CompletableFuture<Ipv6ProbeResult> checkAsync(boolean force) {
        if (!settings.enabled()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("IPv6 probe is disabled"));
        }
        while (true) {
            java.util.concurrent.CompletableFuture<Ipv6ProbeResult> existing = inFlight.get();
            if (existing != null) {
                if (!existing.isDone()) return existing;
                inFlight.compareAndSet(existing, null);
                continue;
            }
            @Nullable Ipv6ProbeResult cached = snapshot.get().lastResult();
            if (!force && isCacheValid(cached)) {
                return java.util.concurrent.CompletableFuture.completedFuture(cached);
            }
            java.util.concurrent.CompletableFuture<Ipv6ProbeResult> candidate = new java.util.concurrent.CompletableFuture<>();
            if (!inFlight.compareAndSet(null, candidate)) continue;
            @Nullable Ipv6ProbeResult previous = snapshot.get().lastResult();
            snapshot.updateAndGet(s -> Ipv6ProbeSnapshot.probing(previous));
            try {
                probeExecutor.execute(() -> runProbe(candidate, previous));
            } catch (RuntimeException error) {
                snapshot.set(new Ipv6ProbeSnapshot(false, previous));
                candidate.completeExceptionally(error);
                inFlight.compareAndSet(candidate, null);
                return candidate;
            } catch (Error error) {
                snapshot.set(new Ipv6ProbeSnapshot(false, previous));
                candidate.completeExceptionally(error);
                inFlight.compareAndSet(candidate, null);
                throw error;
            }
            return candidate;
        }
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 所有 checkAsync 测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityChecker.java src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "feat(ipv6): checkAsync 在途优先 + single-flight + 缓存 + 单测"
```

### Task 5.7: executor 启动失败 + 单测

- [ ] **Step 1: 写失败测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
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
```

- [ ] **Step 2: 运行确认通过(5.6 实现已覆盖此场景)**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: PASS(5.6 的 `catch (RuntimeException)` 分支已实现此契约;本测试锁定它)。

> 若失败,检查 5.6 Step 3 的 executor catch 分支 `return candidate;`(RuntimeException)是否就位。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "test(ipv6): 锁定 executor 启动失败契约"
```

### Task 5.8: 中断标志 whenComplete 两-latch 时序 + 全量回归

- [ ] **Step 1: 写中断标志测试**

在 `Ipv6ConnectivityCheckerTest.java` 加:

```java
    @Test
    void checkAsync_interruptedRestoresFlagObservedViaWhenComplete() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interruptedObserved = new java.util.concurrent.atomic.AtomicBoolean(false);
        Ipv6ConnectivityChecker c = new Ipv6ConnectivityChecker(
                req -> { entered.countDown(); release.await(); throw new InterruptedException("t"); },
                java.time.Clock.systemUTC(), settings(5),
                java.util.concurrent.Executors.newSingleThreadExecutor());

        java.util.concurrent.CompletableFuture<Ipv6ProbeResult> f = c.checkAsync(false);
        entered.await();
        f.whenComplete((r, e) -> interruptedObserved.set(Thread.currentThread().isInterrupted()));
        release.countDown();
        f.join();
        assertTrue(interruptedObserved.get());
        assertEquals(Ipv6ProbeStatus.UNKNOWN, f.getNow(null).status());
    }
```

- [ ] **Step 2: 运行全量 Checker 测试**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 所有测试 PASS(含中断标志、single-flight、缓存、Error、clock-RE、executor 拒绝)。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/firefly520/fireflymc/client/relay/ipv6/Ipv6ConnectivityCheckerTest.java
git commit -m "test(ipv6): 中断标志 whenComplete 两-latch 时序验证"
```

---

## Task 6: SingleplayerRelayManager 状态机改造

**Files:**
- Modify: `src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayManager.java`

> **不做 JUnit 单测**(见 Testing Strategy)。靠任务 10 手动矩阵 + 白盒日志验证。

- [ ] **Step 1: 加 HostingState 枚举 + 字段 + getter**

在 `SingleplayerRelayManager` 类中,`private SingleplayerRelayManager() {}` 之后加入:

```java
    public enum HostingState { STOPPED, STARTING, HOSTING, STOPPING }

    private final java.util.concurrent.atomic.AtomicReference<HostingState> hostingState =
            new java.util.concurrent.atomic.AtomicReference<>(HostingState.STOPPED);
    private volatile String currentRoomId;

    public HostingState getHostingState() { return hostingState.get(); }
    public String getCurrentRoomId() { return currentRoomId; }
```

并把现有字段 `private String currentRoomId;`(约第 21 行)删除(被上面 volatile 版本替代)。

- [ ] **Step 2: 改造 startHosting(主线程调度 + CAS 转换)**

把现有 `public void startHosting() { ... }` 方法(约第 35-82 行)整体替换为:

```java
    /**
     * 开始将当前单人世界发布到 FireflyMC 联机大厅。
     * 必须在客户端主线程调用;非主线程调用会被调度到主线程。
     */
    public void startHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::startHosting);
            return;
        }
        startHostingOnClientThread();
    }

    private void startHostingOnClientThread() {
        Minecraft mc = Minecraft.getInstance();
        if (!RelayConfig.RELAY.SINGLEPLAYER_RELAY_ENABLED.get()) {
            LOGGER.info("[FireflyMC] 单人世界联机功能未启用");
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[FireflyMC] 当前不在单人世界，无法开启联机");
            return;
        }
        if (!hostingState.compareAndSet(HostingState.STOPPED, HostingState.STARTING)) {
            LOGGER.debug("[FireflyMC] startHosting 重入拒绝,当前状态={}", hostingState.get());
            return;
        }
        int port;
        try {
            if (!server.isPublished()) {
                boolean allowCommands = RelayConfig.RELAY.SINGLEPLAYER_RELAY_ALLOW_COMMANDS.get();
                int requestedPort = findAvailablePort();
                boolean published = server.publishServer(GameType.SURVIVAL, allowCommands, requestedPort);
                server.setUsesAuthentication(false);
                server.setPreventProxyConnections(false);
                LOGGER.info("[FireflyMC] 单人世界开放 LAN 结果: {}, requestedPort={}, allowCommands={}",
                        published, requestedPort, allowCommands);
            } else {
                server.setUsesAuthentication(false);
                server.setPreventProxyConnections(false);
            }
            port = server.getPort();
            ClientState.singleplayerRelayLanPort = port;
            if (port <= 0) {
                LOGGER.warn("[FireflyMC] 单人世界已开放 LAN，但暂未能读取监听端口");
                hostingState.compareAndSet(HostingState.STARTING, HostingState.STOPPED);
                return;
            }
            LOGGER.info("[FireflyMC] 单人世界 LAN 端口已准备: {}", port);
            publishLobbyRoom(mc, server, port);
        } catch (Exception e) {
            ClientState.singleplayerRelayLanPort = -1;
            LOGGER.error("[FireflyMC] 开启单人世界联机准备失败", e);
            hostingState.compareAndSet(HostingState.STARTING, HostingState.STOPPED);
            return;
        }
        if (!hostingState.compareAndSet(HostingState.STARTING, HostingState.HOSTING)) {
            LOGGER.debug("Ignoring hosting completion because state is {}", hostingState.get());
            return;
        }
        ClientState.isSingleplayerRelayHosting = true;
    }
```

- [ ] **Step 3: 改造 stopHosting(主线程调度 + CAS 循环 + finally STOPPED)**

把现有 `public void stopHosting() { ... }` 方法(约第 84-98 行)整体替换为:

```java
    /**
     * 停止单人世界公开联机。必须在客户端主线程调用;非主线程调用会被调度到主线程。
     */
    public void stopHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::stopHosting);
            return;
        }
        stopHostingOnClientThread();
    }

    private void stopHostingOnClientThread() {
        while (true) {
            HostingState current = hostingState.get();
            if (current == HostingState.STOPPED || current == HostingState.STOPPING) return;
            if (hostingState.compareAndSet(current, HostingState.STOPPING)) break;
        }
        if (ClientState.isSingleplayerRelayHosting || ClientState.singleplayerRelayLanPort > 0) {
            LOGGER.info("[FireflyMC] 停止单人世界公开联机状态");
        }
        try {
            RelayLobbyWebSocketClient.getInstance().closeRoom();
            P2PConnectionManager.getInstance().stopHost();
            if (hostBridge != null) {
                hostBridge.stop();
                hostBridge = null;
            }
            RelayLobbyWebSocketClient.getInstance().setHostBridge(null);
        } finally {
            currentRoomId = null;
            ClientState.isSingleplayerRelayHosting = false;
            ClientState.singleplayerRelayLanPort = -1;
            hostingState.set(HostingState.STOPPED);
        }
    }
```

- [ ] **Step 4: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayManager.java
git commit -m "feat(relay): HostingState 四态机 + 主线程调度 + CAS 转换"
```

---

## Task 7: SingleplayerRelayControlScreen

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/screen/SingleplayerRelayControlScreen.java`

> **不做 JUnit 单测**(GUI)。靠任务 10 手动矩阵验证。

- [ ] **Step 1: 创建 Screen**

Create `src/main/java/firefly520/fireflymc/client/screen/SingleplayerRelayControlScreen.java`:

```java
package firefly520.fireflymc.client.screen;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.SingleplayerRelayManager;
import firefly520.fireflymc.client.relay.SingleplayerRelayManager.HostingState;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ProbeResult;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker.Ipv6ProbeSnapshot;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ProbeStatus;
import firefly520.fireflymc.client.relay.p2p.Ipv6AddressCollector;
import firefly520.fireflymc.client.relay.RelayConfig;
import firefly520.fireflymc.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 单人世界联机控制面板:启停联机 + IPv6 出站检测 + 状态展示。 */
public class SingleplayerRelayControlScreen extends Screen {
    private static final int ACCENT_PRIMARY = 0xFFFF69B4;
    private static final int ACCENT_SECONDARY = 0xFFFF1493;
    private static final int TEXT_PRIMARY = 0xFF2D2D2D;
    private static final int TEXT_SECONDARY = 0xFF666666;
    private static final int OK_COLOR = 0xFF228B22;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int SHADOW_LIGHT = 0x30FFFFFF;
    private static final int SHADOW_DARK = 0x40000000;

    private final Screen parent;
    private Button mainButton;
    private Button ipv6TestButton;
    private List<String> guaAddresses;
    private Instant lastSeenCheckedAt;
    private int scrollOffset = 0;

    public SingleplayerRelayControlScreen(Screen parent) {
        super(Component.translatable("gui.fireflymc.singleplayer_relay.control.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.guaAddresses = Ipv6AddressCollector.collectGlobalIpv6();
        this.lastSeenCheckedAt = currentCheckedAt();

        int bw = 140, bh = 20;
        int cx = this.width / 2;
        mainButton = Button.builder(mainButtonLabel(), b -> onMain())
                .bounds(cx - bw - 6, 0, bw, bh).build();
        ipv6TestButton = Button.builder(testButtonLabel(), b -> onTestIpv6())
                .bounds(cx - bw / 2, 0, bw, bh).build();
        Button done = Button.builder(Component.translatable("gui.fireflymc.singleplayer_relay.action.done"),
                b -> onClose()).bounds(cx - 60, 0, 120, bh).build();
        addRenderableWidget(mainButton);
        addRenderableWidget(ipv6TestButton);
        addRenderableWidget(done);
        relayout();
    }

    private void relayout() {
        // 滚动模型:标题区固定 56px,底部完成按钮区固定 32px
        int headerHeight = 56;
        int footerHeight = 32;
        int dialogHeight = Math.min(this.height - 24, 360);
        int dialogY = (this.height - dialogHeight) / 2;
        int footerY = dialogY + dialogHeight - footerHeight;
        int cx = this.width / 2;
        if (mainButton != null) mainButton.setY(footerY - 26);
        if (ipv6TestButton != null) ipv6TestButton.setY(footerY - 4);
        for (var child : this.children()) {
            if (child instanceof Button b && b.getMessage().equals(
                    Component.translatable("gui.fireflymc.singleplayer_relay.action.done"))) {
                b.setY(footerY + 4);
            }
        }
        this.headerHeight = headerHeight;
        this.dialogY = dialogY;
        this.dialogHeight = dialogHeight;
        this.viewportTop = dialogY + headerHeight;
        this.viewportBottom = footerY - 6;
    }

    private int headerHeight, dialogY, dialogHeight, viewportTop, viewportBottom;

    @Override
    public void tick() {
        super.tick();
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        mainButton.active = (s == HostingState.STOPPED || s == HostingState.HOSTING);
        mainButton.setMessage(mainButtonLabel());

        Ipv6ProbeSnapshot snap = Ipv6ConnectivityChecker.getInstance().snapshot();
        boolean enabled = Config.CLIENT.IPV6_PROBE_ENABLED.get();
        ipv6TestButton.active = enabled && !snap.probing();
        ipv6TestButton.setMessage(testButtonLabel());

        // probing 完成(probing true→false 且 checkedAt 变化)时刷新 GUA
        if (lastSeenCheckedAt == null && snap.lastResult() != null
                || (lastSeenCheckedAt != null && snap.lastResult() != null
                    && !lastSeenCheckedAt.equals(snap.lastResult().checkedAt()))) {
            this.guaAddresses = Ipv6AddressCollector.collectGlobalIpv6();
        }
        lastSeenCheckedAt = currentCheckedAt();
    }

    private Instant currentCheckedAt() {
        Ipv6ProbeResult r = Ipv6ConnectivityChecker.getInstance().snapshot().lastResult();
        return r == null ? null : r.checkedAt();
    }

    private Component mainButtonLabel() {
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        return switch (s) {
            case STOPPED -> Component.translatable("gui.fireflymc.singleplayer_relay.action.start");
            case STARTING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.starting");
            case HOSTING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.stop");
            case STOPPING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.stopping");
        };
    }

    private Component testButtonLabel() {
        boolean probing = Ipv6ConnectivityChecker.getInstance().snapshot().probing();
        return probing
                ? Component.translatable("gui.fireflymc.ipv6.action.testing")
                : Component.translatable("gui.fireflymc.ipv6.action.test");
    }

    private void onMain() {
        SingleplayerRelayManager m = SingleplayerRelayManager.getInstance();
        HostingState s = m.getHostingState();
        if (s == HostingState.STOPPED) m.startHosting();
        else if (s == HostingState.HOSTING) m.stopHosting();
    }

    private void onTestIpv6() {
        Ipv6ConnectivityChecker.getInstance().checkAsync(true);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.level != null) minecraft.setScreen(parent);
        else if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int contentHeight = estimateContentHeight();
        int viewportHeight = viewportBottom - viewportTop;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - scrollY * 12));
        return true;
    }

    private int estimateContentHeight() {
        // 粗略估计:联机段(3行) + 主按钮(20) + IPv6 段(2行) + GUA(2+addresses) + 测试按钮(20) + 提示(2行)
        return 40 + 20 + 40 + Math.min(2, Math.max(1, guaAddresses.size())) * 12 + 20 + 24;
    }

    @Override
    public void renderBackground(@Nonnull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不调用 super,避免额外遮罩
    }

    @Override
    public void render(@Nonnull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int dialogWidth = Math.min(380, this.width - 24);
        int dialogHeight = this.dialogHeight;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = this.dialogY;

        drawRoundedRect(g, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);
        drawFrostedGlassBackground(g, dialogX, dialogY, dialogWidth, dialogHeight, 10);
        drawGradientBorder(g, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        Component title = Component.translatable("gui.fireflymc.singleplayer_relay.control.title");
        int titleX = this.width / 2 - this.font.width(title) / 2;
        g.drawString(this.font, title.getVisualOrderText(), (float) titleX, (float) (dialogY + 20), ACCENT_SECONDARY, false);
        int sepY = dialogY + 48;
        drawGradientLine(g, dialogX + 20, sepY, dialogX + dialogWidth - 20, sepY, ACCENT_PRIMARY, ACCENT_SECONDARY);

        // 中间内容区:scissor 裁剪 + scrollOffset
        g.enableScissor(dialogX, viewportTop, dialogX + dialogWidth, viewportBottom);
        int y = viewportTop + 8 - scrollOffset;
        y = renderRelaySection(g, dialogX + 20, y, dialogWidth - 40);
        y += 8;
        y = renderIpv6Section(g, dialogX + 20, y, dialogWidth - 40);
        g.disableScissor();

        super.render(g, mouseX, mouseY, partialTick);
    }

    private int renderRelaySection(GuiGraphics g, int x, int y, int w) {
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.state." + s.name().toLowerCase()),
                x, y, stateColor(s), false);
        y += 14;
        if (s == HostingState.HOSTING) {
            String roomId = SingleplayerRelayManager.getInstance().getCurrentRoomId();
            String roomIdText = roomId == null
                    ? Component.translatable("gui.fireflymc.singleplayer_relay.room_id.pending").getString()
                    : Component.translatable("gui.fireflymc.singleplayer_relay.room_id", abbreviate(roomId, w)).getString();
            g.drawString(this.font, roomIdText, x, y, TEXT_SECONDARY, false);
            y += 12;
            int port = ClientState.singleplayerRelayLanPort;
            g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.lan_port", port).getString(),
                    x, y, TEXT_SECONDARY, false);
            y += 12;
            g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.max_players",
                    RelayConfig.RELAY.SINGLEPLAYER_RELAY_MAX_PLAYERS.get()).getString(), x, y, TEXT_SECONDARY, false);
            y += 12;
        }
        return y;
    }

    private int renderIpv6Section(GuiGraphics g, int x, int y, int w) {
        Ipv6ProbeSnapshot snap = Ipv6ConnectivityChecker.getInstance().snapshot();
        boolean enabled = Config.CLIENT.IPV6_PROBE_ENABLED.get();
        if (!enabled) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.disabled"), x, y, WARN_COLOR, false);
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.subtitle.disabled"), x, y + 12, TEXT_SECONDARY, false);
            return y + 28;
        }
        if (snap.probing()) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.action.testing"), x, y, ACCENT_SECONDARY, false);
            if (snap.lastResult() != null) {
                g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.probing_with_last",
                        previousLabel(snap.lastResult().status())).getString(), x, y + 12, TEXT_SECONDARY, false);
            }
            return y + 28;
        }
        Ipv6ProbeResult r = snap.lastResult();
        if (r == null) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.not_detected"), x, y, TEXT_PRIMARY, false);
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.subtitle.not_detected"), x, y + 12, TEXT_SECONDARY, false);
            return y + 28;
        }
        g.drawString(this.font, statusMainKey(r.status()).getString(), x, y, statusColor(r.status()), false);
        g.drawString(this.font, statusSubtitleKey(r.status(), r.httpStatus()).getString(), x, y + 12, TEXT_SECONDARY, false);
        y += 28;
        g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.last_check",
                relativeTime(r.checkedAt()), r.durationMs()).getString(), x, y, TEXT_SECONDARY, false);
        y += 14;

        g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.label"), x, y, TEXT_PRIMARY, false);
        y += 12;
        if (guaAddresses.isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.none"), x + 8, y, TEXT_SECONDARY, false);
            y += 12;
        } else {
            int shown = Math.min(2, guaAddresses.size());
            for (int i = 0; i < shown; i++) {
                g.drawString(this.font, abbreviate(guaAddresses.get(i), w - 8), x + 8, y, TEXT_SECONDARY, false);
                y += 12;
            }
            if (guaAddresses.size() > 2) {
                g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.more",
                        guaAddresses.size() - 2).getString(), x + 8, y, TEXT_SECONDARY, false);
                y += 12;
            }
        }
        y += 6;
        // 条件提示行(font.split 自动换行)
        Component hint = hintForCurrent();
        for (var line : this.font.split(hint, w)) {
            g.drawString(this.font, line, x, y, TEXT_SECONDARY, false);
            y += 12;
        }
        return y;
    }

    private Component hintForCurrent() {
        HostingState hs = SingleplayerRelayManager.getInstance().getHostingState();
        if (hs != HostingState.HOSTING) {
            return Component.translatable("gui.fireflymc.ipv6.hint.idle");
        }
        Ipv6ProbeResult r = Ipv6ConnectivityChecker.getInstance().snapshot().lastResult();
        if (r == null) return Component.translatable("gui.fireflymc.ipv6.hint.idle");
        return switch (r.status()) {
            case AVAILABLE -> Component.translatable("gui.fireflymc.ipv6.hint.available");
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT ->
                    Component.translatable("gui.fireflymc.ipv6.hint.not_detected");
            default -> Component.translatable("gui.fireflymc.ipv6.hint.probe_failed");
        };
    }

    private String previousLabel(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> Component.translatable("gui.fireflymc.ipv6.previous.available").getString();
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT ->
                    Component.translatable("gui.fireflymc.ipv6.previous.not_detected").getString();
            default -> Component.translatable("gui.fireflymc.ipv6.previous.probe_failed").getString();
        };
    }

    private Component statusMainKey(Ipv6ProbeStatus s) {
        return Component.translatable("gui.fireflymc.ipv6." + statusKey(s));
    }

    private Component statusSubtitleKey(Ipv6ProbeStatus s, Integer httpStatus) {
        if (s == Ipv6ProbeStatus.HTTP_FAILED && httpStatus != null) {
            return Component.translatable("gui.fireflymc.ipv6.subtitle.http_failed", httpStatus);
        }
        return Component.translatable("gui.fireflymc.ipv6.subtitle." + statusKey(s));
    }

    private String statusKey(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> "available";
            case DNS_FAILED -> "dns_failed";
            case CONNECT_FAILED -> "connect_failed";
            case CONNECT_TIMEOUT -> "connect_timeout";
            case TLS_FAILED -> "tls_failed";
            case HTTP_FAILED -> "http_failed";
            case UNKNOWN -> "unknown";
        };
    }

    private int statusColor(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> OK_COLOR;
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT -> WARN_COLOR;
            default -> 0xFFCC0000;
        };
    }

    private int stateColor(HostingState s) {
        return s == HostingState.HOSTING ? OK_COLOR : (s == HostingState.STARTING || s == HostingState.STOPPING ? WARN_COLOR : TEXT_PRIMARY);
    }

    private String relativeTime(Instant t) {
        long mins = Duration.between(t, Instant.now()).toMinutes();
        if (mins < 1) return Component.translatable("gui.fireflymc.time.just_now").getString();
        if (mins < 60) return Component.translatable("gui.fireflymc.time.minutes_ago", mins).getString();
        return Component.translatable("gui.fireflymc.time.hours_ago", mins / 60).getString();
    }

    private String abbreviate(String s, int maxWidth) {
        int maxChars = Math.max(8, maxWidth / 6);
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }

    // —— 粉色毛玻璃绘制工具(复用 SingleplayerSharePromptScreen 风格) ——
    private void drawRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        g.fill(x + r, y, x + w - r, y + h, c);
        g.fill(x, y + r, x + w, y + h - r, c);
        g.fill(x + r, y, x + w - r, y + r, c);
        g.fill(x + r, y + h - r, x + w - r, y + h, c);
        fillCircle(g, x + r, y + r, r, c);
        fillCircle(g, x + w - r, y + r, r, c);
        fillCircle(g, x + r, y + h - r, r, c);
        fillCircle(g, x + w - r, y + h - r, r, c);
    }
    private void fillCircle(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++)
            if (i * i + j * j <= r * r) g.fill(cx + i, cy + j, cx + i + 1, cy + j + 1, c);
    }
    private void drawFrostedGlassBackground(GuiGraphics g, int x, int y, int w, int h, int r) {
        drawRoundedRect(g, x, y, w, h, r, 0xDDFAFAFA);
        drawRoundedRect(g, x + 1, y + 1, w - 2, h - 2, r - 1, 0x40FFFFFF);
        drawRoundedRect(g, x + 2, y + 2, w - 4, h / 2 - 2, r - 2, SHADOW_LIGHT);
    }
    private void drawGradientBorder(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int c = lerpColor(ACCENT_PRIMARY, ACCENT_SECONDARY, ratio);
            g.fill(x + r, y + i, x + w - r, y + i + 1, c);
        }
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int c = lerpColor(ACCENT_SECONDARY, ACCENT_PRIMARY, ratio);
            g.fill(x + r, y + h - 3 + i, x + w - r, y + h - 2 + i, c);
        }
        for (int i = 0; i < 3; i++) g.fill(x + i, y + r, x + i + 1, y + h - r, ACCENT_PRIMARY);
        for (int i = 0; i < 3; i++) g.fill(x + w - 3 + i, y + r, x + w - 2 + i, y + h - r, ACCENT_SECONDARY);
    }
    private void drawGradientLine(GuiGraphics g, int x1, int y, int x2, int y2, int c1, int c2) {
        int len = x2 - x1;
        for (int i = 0; i < len; i++) {
            int c = lerpColor(c1, c2, i / (float) len);
            g.fill(x1 + i, y, x1 + i + 1, y + 1, c);
        }
    }
    private int lerpColor(int c1, int c2, float ratio) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return ((int)(a1 + (a2 - a1) * ratio) << 24) | ((int)(r1 + (r2 - r1) * ratio) << 16)
                | ((int)(g1 + (g2 - g1) * ratio) << 8) | (int)(b1 + (b2 - b1) * ratio);
    }
}
```

> **实现提示**:`relayout()` 中的 `Button.setMessage()` 比较用 `equals` 识别"完成"按钮较脆弱;更稳妥是在类字段保留 `doneButton` 引用。执行者应将其改为字段引用。

- [ ] **Step 2: 修正 done 按钮为字段引用**

把 `init()` 中 `Button done = Button.builder(...)` 改为存字段:

```java
        this.doneButton = Button.builder(Component.translatable("gui.fireflymc.singleplayer_relay.action.done"),
                b -> onClose()).bounds(cx - 60, 0, 120, bh).build();
        addRenderableWidget(mainButton);
        addRenderableWidget(ipv6TestButton);
        addRenderableWidget(doneButton);
```

加字段 `private Button doneButton;`,删除 `relayout()` 内的 `equals` 识别循环,改为:

```java
        if (doneButton != null) doneButton.setY(footerY + 4);
```

- [ ] **Step 3: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/screen/SingleplayerRelayControlScreen.java
git commit -m "feat(screen): 新增联机控制面板(滚动 + 四态 + IPv6)"
```

---

## Task 8: SingleplayerRelayClientEvents 扩展

**Files:**
- Modify: `src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayClientEvents.java`

- [ ] **Step 1: 加导入与常量**

在文件顶部 import 区加:

```java
import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker;
import firefly520.fireflymc.client.screen.SingleplayerRelayControlScreen;
import net.minecraft.client.gui.screens.GameMenuScreen;
```

在 `SingleplayerRelayClientEvents` 类内加常量(与 `JoinMultiplayerScreen` 注入共享尺寸):

```java
    private static final int INJECTED_BUTTON_WIDTH = 120;
    private static final int INJECTED_BUTTON_HEIGHT = 20;
    private static final int INJECTED_BUTTON_MARGIN = 8;
```

- [ ] **Step 2: 扩展 onClientLoggedIn 双检查触发检测**

把现有 `onClientLoggedIn` 方法末尾(设置 `promptPending = true` 之前或之后)加入自动检测触发:

```java
        // IPv6 出站检测:enabled && autoCheck 双检查
        if (Config.CLIENT.IPV6_PROBE_ENABLED.get() && Config.CLIENT.IPV6_PROBE_AUTO_ON_SP_JOIN.get()) {
            Ipv6ConnectivityChecker.getInstance().checkAsync(false);
        }
```

放在 `if (mc.getSingleplayerServer() != null) { ... }` 块内,与 `promptPending = true` 同级。

- [ ] **Step 3: 扩展 onScreenInit 注入 GameMenuScreen 按钮**

在 `onScreenInit` 方法中,现有 `JoinMultiplayerScreen` 分支之前加入 `GameMenuScreen` 分支:

```java
        if (event.getScreen() instanceof GameMenuScreen screen) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !mc.hasSingleplayerServer()) return;
            int x = Math.max(INJECTED_BUTTON_MARGIN, screen.width - INJECTED_BUTTON_WIDTH - INJECTED_BUTTON_MARGIN);
            int y = INJECTED_BUTTON_MARGIN;
            event.addListener(Button.builder(
                    Component.translatable("gui.fireflymc.singleplayer_relay.entry"),
                    b -> mc.setScreen(new SingleplayerRelayControlScreen(screen))
            ).bounds(x, y, INJECTED_BUTTON_WIDTH, INJECTED_BUTTON_HEIGHT).build());
            return;
        }
```

- [ ] **Step 4: 编译确认**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`。

> 若 `GameMenuScreen` 类名在当前 Mojmap/Parchment 下不同(实现时确认),改为实际类名。1.21.1 标准 Mojmap 为 `net.minecraft.client.gui.screens.GameMenuScreen`。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/firefly520/fireflymc/client/relay/SingleplayerRelayClientEvents.java
git commit -m "feat(events): ESC 菜单注入联机入口 + 进世界触发 IPv6 检测"
```

---

## Task 9: i18n key 补齐(Screen 用)

**Files:**
- Modify: `src/main/resources/assets/fireflymc/lang/zh_cn.json`
- Modify: `src/main/resources/assets/fireflymc/lang/en_us.json`

- [ ] **Step 1: 在 zh_cn.json 加 Screen 用 key**

在配置翻译段之后加入:

```json
  "gui.fireflymc.singleplayer_relay.entry": "FireflyMC 联机",
  "gui.fireflymc.singleplayer_relay.control.title": "FireflyMC 联机控制",
  "gui.fireflymc.singleplayer_relay.state.stopped": "未开启",
  "gui.fireflymc.singleplayer_relay.state.starting": "正在开启…",
  "gui.fireflymc.singleplayer_relay.state.hosting": "已发布",
  "gui.fireflymc.singleplayer_relay.state.stopping": "正在停止…",
  "gui.fireflymc.singleplayer_relay.room_id": "房间号：%s",
  "gui.fireflymc.singleplayer_relay.room_id.pending": "房间号：正在获取…",
  "gui.fireflymc.singleplayer_relay.lan_port": "LAN 端口：%s",
  "gui.fireflymc.singleplayer_relay.max_players": "最大玩家：%s",
  "gui.fireflymc.singleplayer_relay.action.start": "开启联机",
  "gui.fireflymc.singleplayer_relay.action.starting": "正在开启…",
  "gui.fireflymc.singleplayer_relay.action.stop": "停止联机",
  "gui.fireflymc.singleplayer_relay.action.stopping": "正在停止…",
  "gui.fireflymc.singleplayer_relay.action.done": "完成",
  "gui.fireflymc.ipv6.action.test": "测试 IPv6",
  "gui.fireflymc.ipv6.action.testing": "检测中…",
  "gui.fireflymc.ipv6.available": "IPv6 出站可用",
  "gui.fireflymc.ipv6.subtitle.available": "已成功连接 IPv6 检测服务",
  "gui.fireflymc.ipv6.not_detected": "尚未检测",
  "gui.fireflymc.ipv6.subtitle.not_detected": "点击下方按钮开始检测",
  "gui.fireflymc.ipv6.dns_failed": "未检测到 IPv6 出站",
  "gui.fireflymc.ipv6.subtitle.dns_failed": "DNS 解析失败",
  "gui.fireflymc.ipv6.connect_failed": "未检测到 IPv6 出站",
  "gui.fireflymc.ipv6.subtitle.connect_failed": "IPv6 连接失败",
  "gui.fireflymc.ipv6.connect_timeout": "未检测到 IPv6 出站",
  "gui.fireflymc.ipv6.subtitle.connect_timeout": "IPv6 连接超时",
  "gui.fireflymc.ipv6.tls_failed": "IPv6 检测失败",
  "gui.fireflymc.ipv6.subtitle.tls_failed": "TLS 握手失败",
  "gui.fireflymc.ipv6.http_failed": "IPv6 检测失败",
  "gui.fireflymc.ipv6.subtitle.http_failed": "HTTP 状态异常（%s）",
  "gui.fireflymc.ipv6.unknown": "IPv6 检测失败",
  "gui.fireflymc.ipv6.subtitle.unknown": "检测服务可能不可达",
  "gui.fireflymc.ipv6.disabled": "检测已关闭",
  "gui.fireflymc.ipv6.subtitle.disabled": "可在配置中重新开启",
  "gui.fireflymc.ipv6.historical": "（历史结果）",
  "gui.fireflymc.ipv6.probing_with_last": "检测中…（上次：%s）",
  "gui.fireflymc.ipv6.previous.available": "出站可用",
  "gui.fireflymc.ipv6.previous.not_detected": "未检测到出站",
  "gui.fireflymc.ipv6.previous.probe_failed": "检测失败",
  "gui.fireflymc.ipv6.last_check": "上次检测：%s · 耗时 %s ms",
  "gui.fireflymc.ipv6.duration_ms": "%s ms",
  "gui.fireflymc.ipv6.gua.label": "本机全局 IPv6",
  "gui.fireflymc.ipv6.gua.hint": "不代表该地址可从公网入站访问",
  "gui.fireflymc.ipv6.gua.none": "未发现",
  "gui.fireflymc.ipv6.gua.more": "另有 %s 个",
  "gui.fireflymc.ipv6.hint.available": "IPv6 出站连接可用；公网入站与 P2P 直连能力尚未验证。",
  "gui.fireflymc.ipv6.hint.not_detected": "未检测到可用的 IPv6 出站连接；联机仍可使用现有中继链路。",
  "gui.fireflymc.ipv6.hint.probe_failed": "IPv6 检测未完成；可能是检测服务不可达，联机功能不受影响。",
  "gui.fireflymc.ipv6.hint.idle": "此结果仅表示 IPv6 出站能力，不代表公网游戏端口可达。",
  "gui.fireflymc.time.just_now": "刚刚",
  "gui.fireflymc.time.minutes_ago": "%s 分钟前",
  "gui.fireflymc.time.hours_ago": "%s 小时前",
```

- [ ] **Step 2: 在 en_us.json 加对应英文**

提供与上面 key 一一对应的英文翻译(执行者按上表 key 填写合理英文值,例:

```json
  "gui.fireflymc.singleplayer_relay.entry": "FireflyMC LAN",
  "gui.fireflymc.singleplayer_relay.control.title": "FireflyMC LAN Control",
  "gui.fireflymc.singleplayer_relay.state.stopped": "Stopped",
  "gui.fireflymc.singleplayer_relay.state.starting": "Starting…",
  "gui.fireflymc.singleplayer_relay.state.hosting": "Hosting",
  "gui.fireflymc.singleplayer_relay.state.stopping": "Stopping…",
  "gui.fireflymc.singleplayer_relay.room_id": "Room: %s",
  "gui.fireflymc.singleplayer_relay.room_id.pending": "Room: retrieving…",
  "gui.fireflymc.singleplayer_relay.lan_port": "LAN port: %s",
  "gui.fireflymc.singleplayer_relay.max_players": "Max players: %s",
  "gui.fireflymc.singleplayer_relay.action.start": "Start Hosting",
  "gui.fireflymc.singleplayer_relay.action.starting": "Starting…",
  "gui.fireflymc.singleplayer_relay.action.stop": "Stop Hosting",
  "gui.fireflymc.singleplayer_relay.action.stopping": "Stopping…",
  "gui.fireflymc.singleplayer_relay.action.done": "Done",
  "gui.fireflymc.ipv6.action.test": "Test IPv6",
  "gui.fireflymc.ipv6.action.testing": "Probing…",
  "gui.fireflymc.ipv6.available": "IPv6 outbound available",
  "gui.fireflymc.ipv6.subtitle.available": "Successfully connected to IPv6 probe service",
  "gui.fireflymc.ipv6.not_detected": "Not probed yet",
  "gui.fireflymc.ipv6.subtitle.not_detected": "Click the button below to start",
  "gui.fireflymc.ipv6.dns_failed": "No IPv6 outbound detected",
  "gui.fireflymc.ipv6.subtitle.dns_failed": "DNS resolution failed",
  "gui.fireflymc.ipv6.connect_failed": "No IPv6 outbound detected",
  "gui.fireflymc.ipv6.subtitle.connect_failed": "IPv6 connection failed",
  "gui.fireflymc.ipv6.connect_timeout": "No IPv6 outbound detected",
  "gui.fireflymc.ipv6.subtitle.connect_timeout": "IPv6 connection timed out",
  "gui.fireflymc.ipv6.tls_failed": "IPv6 probe failed",
  "gui.fireflymc.ipv6.subtitle.tls_failed": "TLS handshake failed",
  "gui.fireflymc.ipv6.http_failed": "IPv6 probe failed",
  "gui.fireflymc.ipv6.subtitle.http_failed": "HTTP status abnormal (%s)",
  "gui.fireflymc.ipv6.unknown": "IPv6 probe failed",
  "gui.fireflymc.ipv6.subtitle.unknown": "Probe service may be unreachable",
  "gui.fireflymc.ipv6.disabled": "Probe disabled",
  "gui.fireflymc.ipv6.subtitle.disabled": "Re-enable in config",
  "gui.fireflymc.ipv6.historical": "(historical)",
  "gui.fireflymc.ipv6.probing_with_last": "Probing… (last: %s)",
  "gui.fireflymc.ipv6.previous.available": "available",
  "gui.fireflymc.ipv6.previous.not_detected": "not detected",
  "gui.fireflymc.ipv6.previous.probe_failed": "probe failed",
  "gui.fireflymc.ipv6.last_check": "Last probe: %s · took %s ms",
  "gui.fireflymc.ipv6.duration_ms": "%s ms",
  "gui.fireflymc.ipv6.gua.label": "Local global IPv6",
  "gui.fireflymc.ipv6.gua.hint": "Does not imply public inbound reachability",
  "gui.fireflymc.ipv6.gua.none": "none found",
  "gui.fireflymc.ipv6.gua.more": "%s more",
  "gui.fireflymc.ipv6.hint.available": "IPv6 outbound is available; public inbound and P2P direct connectivity are not yet verified.",
  "gui.fireflymc.ipv6.hint.not_detected": "No usable IPv6 outbound detected; the relay link is still used.",
  "gui.fireflymc.ipv6.hint.probe_failed": "IPv6 probe incomplete; the probe service may be unreachable. Hosting is unaffected.",
  "gui.fireflymc.ipv6.hint.idle": "This result only reflects IPv6 outbound capability, not public game-port reachability.",
  "gui.fireflymc.time.just_now": "just now",
  "gui.fireflymc.time.minutes_ago": "%s min ago",
  "gui.fireflymc.time.hours_ago": "%s h ago",
```

- [ ] **Step 3: 构建**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/assets/fireflymc/lang/zh_cn.json src/main/resources/assets/fireflymc/lang/en_us.json
git commit -m "feat(i18n): 联机控制面板与 IPv6 检测全部 lang key"
```

---

## Task 10: 构建验证 + 手动测试矩阵

**Files:** 无(验证任务)

- [ ] **Step 1: 全量构建**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`,jar 产出正常。

- [ ] **Step 2: 全量 Checker 单测回归**

Run: `.\gradlew.bat test --tests "*.Ipv6ConnectivityCheckerTest"`
Expected: 全部 PASS。

- [ ] **Step 3: 启动客户端跑手动矩阵**

Run: `.\gradlew.bat runClient`

按 spec §11.3 + §11.4 逐项验证:

- [ ] 3.1 有 IPv6 网络:`AVAILABLE` + 耗时显示;HOSTING 时提示"出站可用;公网入站与 P2P 尚未验证"
- [ ] 3.2 禁用网卡 IPv6:`DNS_FAILED`/`CONNECT_FAILED`/`CONNECT_TIMEOUT` 之一 + 文案"未检测到 IPv6 出站"
- [ ] 3.3 hosts 映射 `ipv6.test-ipv6.com` 到 `::1`(无本地 HTTPS)→ `CONNECT_FAILED`;防火墙丢包→`CONNECT_TIMEOUT`;本地 DNS NXDOMAIN→`DNS_FAILED`;MITM 证书→`TLS_FAILED`。验证 UI 正确分类,不宣称公网不可达
- [ ] 4 自动检测:进世界 15 min 内重进不重跑;超 15 min 重跑;`cacheMinutes=0` 每次重跑;`enabled=false` 不触发
- [ ] 5 ESC 入口:单人世界出现"FireflyMC 联机"按钮;多人服务器不出现
- [ ] 6 面板四态按钮:开启→停止全程按钮禁用/文案正确;快速连点不重入
- [ ] 7 `enabled=false`:测试按钮禁用 + "检测已关闭" + 历史结果保留
- [ ] 8 Screen resize:ESC 菜单只有一个"FireflyMC 联机"按钮(不累积)
- [ ] 9 白盒:`STARTING` 期间从另一调用路径触发 `stopHosting`(或快速点开启后立刻点停止),日志应见 `Ignoring hosting completion`,状态不闪回 `HOSTING`
- [ ] 10.1 320×240 / 高 GUI Scale:布局不越界、可滚动(滚轮)
- [ ] 10.2 `en_us` 文案换行正常
- [ ] 10.3 超长 room ID 省略 + 悬浮
- [ ] 10.4 长 IPv6 地址不越界
- [ ] 10.5 `enabled=false` + 历史:展示"检测已关闭" + 历史标记
- [ ] 10.6 probing 且上次 `TLS_FAILED`:显示"上次:检测失败",**不**显示"不可用"
- [ ] 10.7 `STARTING`/`STOPPING` 条件提示显示中性免责,**不**显示"中继链路"

- [ ] **Step 4: 验收清单核对(spec §14)**

逐条核对 spec §14 验收标准。任何未通过项回到对应任务修复。

- [ ] **Step 5: 最终 Commit(若有修复)**

```bash
git add -A
git commit -m "fix: 手动测试矩阵验证修复"
```

---

## 完成后

- spec `docs/superpowers/specs/2026-07-06-ipv6-relay-control-design.md` 已实现。
- 路线 B(Relay 异步生命周期收敛)未做,记录在 spec §2.3 作为独立专项。
- Manager/Screen 未引入 JUnit(见 Testing Strategy),手动矩阵是唯一验证手段。
