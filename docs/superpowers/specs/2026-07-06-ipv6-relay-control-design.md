# IPv6 联机增强与单人 ESC 联机控制设计

- 日期：2026-07-06
- 模组：FireflyMC 2.5.6（Minecraft 1.21.1 NeoForge 21.1.219）
- 状态：设计定稿，待实现

## 1. 背景与目标

FireflyMC 已有完整的"单人世界 → 公开联机大厅"链路（`SingleplayerRelayManager` 开放 LAN、注册到中继 WebSocket 大厅、可选 P2P UDP 直连），以及本机 IPv6 GUA 收集器（`Ipv6AddressCollector`，供 P2P 候选）。当前存在两处缺口：

1. **进入世界后无持久控制入口**：仅有一次性弹窗 `SingleplayerSharePromptScreen`，选"暂不开启"后没有任何再次开启/停止联机的入口。
2. **缺少外部 IPv6 出站能力检测**：本机有 GUA 地址 ≠ 公网 IPv6 出站可达（运营商可能拦截 / 站点不可达），房主无法得知自己真实的 IPv6 出网状态。

本次目标：

- 在单人 ESC 菜单（`GameMenuScreen`）注入"FireflyMC 联机"入口按钮，打开一个**联机控制面板**，集中控制联机启停、查看状态、检测 IPv6。
- 通过 `https://ipv6.test-ipv6.com/` 系列端点检测客户端的 **IPv6 出站能力**，结果缓存、可在面板手动重测。
- 进入单人世界时后台静默检测一次（可配置），结果缓存复用。

## 2. 范围

### 2.1 本次实现

- ESC 菜单入口按钮 + 联机控制面板 UI。
- IPv6 出站能力检测器（test-ipv6.com 纯 IPv6 端点）。
- 联机状态四态机（`STOPPED/STARTING/HOSTING/STOPPING`）的权威状态源（限定保证范围，见 §5.1）。
- 配置项、i18n、错误处理边界。

### 2.2 非目标（明确不做）

- **不**证明"房主 IPv6 游戏端口可被公网入站直连"——这需要中心服务器实际回连探测，属未来扩展。
- **不**让 IPv6 检测结果影响 P2P / 中继代码路径。检测结果仅供 UI 展示，P2P 核心完全不感知。
- **不**自建检测端点（`v6-check.example.com` 之类），本期继续依赖 test-ipv6.com 公益服务。
- **不**改 P2P / relay 传输核心逻辑；**不**治理现有 relay 核心的跨线程迟到回调竞态（见 §5.1、§10 已知限制、§2.3 未来专项）。
- 对 `SingleplayerRelayManager` 只做主线程调度对齐、状态标记与只读 getter 的最小增量。

### 2.3 未来扩展（预留，不在本期）

- **Relay 异步生命周期收敛（独立专项，路线 B）**：为每次 hosting 操作引入 generation/session token，使 `publishRoom`、房间回调、P2P host probe、`HostBridge` stream 创建都校验当前代次；停止操作使代次失效，并等待或拒绝所有迟到回调。本期采用的路线 A（§5）诚实记录该竞态存在但不解决。
- 中心服务器对房主 `[GUA]:游戏端口` 的 TCP/UDP 回连探测，确认真正的公网可直连。
- 自建仅 AAAA 的检测端点，规避 test-ipv6.com 在大陆网络的不可靠性。
- 面板扩展：在线玩家列表（需 `RelayHostBridge` 暴露连接数接口）、邀请码、跳转 test-ipv6.com 完整诊断页。

## 3. 架构总览

### 3.1 数据流

```
[进入单人世界]
     │
     ├──► SingleplayerRelayClientEvents.onClientLoggedIn
     │         │
     │         └──► Ipv6ConnectivityChecker.checkAsync(force=false)   (虚拟线程,后台静默)
     │                  │
     │                  └──► GET https://ipv6.test-ipv6.com/images/hires_ok.png?cb=<UUID>
     │                          │  (NO_PROXY, Redirect.NEVER, 请求级 timeout, BodyHandlers.discarding)
     │                          └──► Ipv6ProbeResult 写入 Checker 内 AtomicReference<Ipv6ProbeSnapshot>
     │
     └──► (保留) 进世界一次性弹窗 SingleplayerSharePromptScreen，不动

[按 ESC → GameMenuScreen]
     │   ScreenEvent.Init.Post 注入"FireflyMC 联机"按钮（仅单人世界）
     │
     └──► 点击 → SingleplayerRelayControlScreen（新面板）
              │
              ├──► 读快照：Manager.getHostingState() / getCurrentRoomId()
              │              ClientState.singleplayerRelayLanPort
              │              Ipv6ConnectivityChecker.getInstance().snapshot()
              │              Ipv6AddressCollector.collectGlobalIpv6()
              │
              ├──► [开启/停止联机] → Manager.startHosting() / stopHosting()   (均调度到客户端主线程)
              └──► [测试 IPv6]     → Ipv6ConnectivityChecker.checkAsync(force=true)
```

### 3.2 设计原则

- **唯一权威状态源**：IPv6 检测状态只在 `Ipv6ConnectivityChecker`；联机**意图/UI** 状态只在 `SingleplayerRelayManager`。`ClientState` 不再加 IPv6 字段。权威性限定见 §5.1。
- **UI 只读快照 + 发命令**：面板不写 Manager / Checker 内部状态，只读快照、调用公开方法。
- **零新 Mixin**：ESC 按钮复用现有 `ScreenEvent.Init.Post` 模式（与 `JoinMultiplayerScreen` 注入一致）。
- **检测结果不干预网络路径**：P2P / 中继代码不读 `Ipv6ProbeResult`。

### 3.3 组件清单

**新增（4 个 .java）**

| 文件 | 职责 |
|---|---|
| `client/relay/ipv6/Ipv6ProbeStatus.java` | 公开枚举，7 终态 |
| `client/relay/ipv6/Ipv6ProbeResult.java` | 公开 record |
| `client/relay/ipv6/Ipv6ConnectivityChecker.java` | 单例；内含公开嵌套 `Ipv6ProbeSnapshot`、包私有 `ProbeTransport`；唯一状态源 |
| `client/screen/SingleplayerRelayControlScreen.java` | 联机控制面板 |

**修改（5）**

| 文件 | 改动 |
|---|---|
| `client/relay/SingleplayerRelayManager.java` | +`HostingState` 公开嵌套枚举 + `AtomicReference<HostingState>` + CAS 转换 + `stopHosting` 补主线程调度（与 `startHosting` 对齐）+ `getHostingState()` / `getCurrentRoomId()` getter；`currentRoomId` 加 `volatile` |
| `client/relay/SingleplayerRelayClientEvents.java` | `onScreenInit` 加 `GameMenuScreen` 注入；`onClientLoggedIn` 触发 `checkAsync(false)` |
| `Config.java` | +`ipv6_probe` 配置组（4 项，`defineInRange`） |
| `assets/fireflymc/lang/zh_cn.json` | +i18n key |
| `assets/fireflymc/lang/en_us.json` | +i18n key |

`ClientState.java` / `FireflyMCMod.java` **不改**。

## 4. IPv6 检测器设计（`Ipv6ConnectivityChecker`）

### 4.1 类定位与 JavaDoc 红线

类头 JavaDoc 必须写明（实现时逐字落地）：

> 本检测器仅证明**客户端 → 外部 HTTPS 服务的 IPv6 出站能力**。不证明房主 IPv6 游戏端口可被公网入站直连。UI 不得据此宣称"可被 IPv6 玩家直连"或"公网可达"，也不得声称检测结果会驱动 P2P / 中继路径选择（P2P 核心不读取本检测结果）。

### 4.2 内部常量（不进 config）

```
ENDPOINT     = "https://ipv6.test-ipv6.com/images/hires_ok.png"
USER_AGENT   = "FireflyMC-Launcher/" + FireflyMCMod.VERSION + " IPv6ConnectivityCheck"
```

- 端点选纯 IPv6（仅 AAAA、无 A）的静态小图片，避免 HEAD 在部分 CDN/代理的不稳定行为，`?cb=<UUID>` 防 CDN 缓存假阳性。
- 响应体直接丢弃（`BodyHandlers.discarding()`），不解析、不限读。

### 4.3 数据结构

```java
public enum Ipv6ProbeStatus {
    AVAILABLE,
    DNS_FAILED,
    CONNECT_FAILED,
    CONNECT_TIMEOUT,
    TLS_FAILED,
    HTTP_FAILED,
    UNKNOWN
}

public record Ipv6ProbeResult(
        Ipv6ProbeStatus status,
        Instant checkedAt,        // 检测完成时刻
        long durationMs,          // nanoTime 差换算
        @Nullable Integer httpStatus
) {}

// Checker 公开嵌套 record（跨包供 UI 原子读取，不独立成文件）
public record Ipv6ProbeSnapshot(
        boolean probing,
        @Nullable Ipv6ProbeResult lastResult
) {
    private static Ipv6ProbeSnapshot idle()                     { return new Ipv6ProbeSnapshot(false, null); }
    private static Ipv6ProbeSnapshot probing(@Nullable Ipv6ProbeResult previous) { return new Ipv6ProbeSnapshot(true, previous); }
    private static Ipv6ProbeSnapshot done(Ipv6ProbeResult result)               { return new Ipv6ProbeSnapshot(false, result); }
}
```

- `probing(@Nullable previous)` 允许 null（首次检测无历史）。转入 probing 时保留 `lastResult`，UI 可显示"检测中…(上次:可用)"。
- UI 通过 `snapshot()` 一次原子读取，**不**分别暴露 `isProbing()` / `lastResult()`（避免读撕裂）。

### 4.4 HttpClient 配置（生产单例构造器内）

```java
private Ipv6ConnectivityChecker() {
    HttpClient client = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)         // 禁用代理：经代理成功只证明代理有 v6
            .followRedirects(HttpClient.Redirect.NEVER) // 显式禁止重定向：防 302→双栈域名的 v4 假阳性
            .build();
    this.transport = request -> client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    this.clock = Clock.systemUTC();
}
```

- `HttpClient` 单例复用，**不**在 builder 上设 `connectTimeout`——超时由每次 `HttpRequest.timeout()` 动态读配置，使配置热修改立即生效。
- HTTP 版本用默认（H2 优先）；端点降级导致的 TLS 失败已归 `TLS_FAILED`，不影响结论。
- Checker 只持有 `transport` 与 `clock`，**不**保留 `httpClient` 字段，使测试构造器与生产闭合（见 §4.11）。

### 4.5 字段与并发

```java
private final AtomicReference<Ipv6ProbeSnapshot> snapshot = new AtomicReference<>(Ipv6ProbeSnapshot.idle());
private final AtomicReference<CompletableFuture<Ipv6ProbeResult>> inFlight = new AtomicReference<>();
private final ProbeTransport transport;
private final Clock clock;
```

### 4.6 `checkAsync(boolean force)` 决策表（**在途检测优先于缓存**）

进入顺序：先 `enabled` 守卫 → 再 `inFlight` → 再缓存 → 再 CAS 占位。

| 步骤 | 条件 | 行为 |
|---|---|---|
| 0 | `IPV6_PROBE_ENABLED == false` | 返回 `CompletableFuture.failedFuture(IllegalStateException)` |
| 1 | `inFlight` 存在且未完成 | **复用**同一 Future（force 与否均复用，不打断） |
| 1' | `inFlight` 存在但已完成 | CAS 清理后 continue |
| 2 | 缓存有效（`lastResult != null` 且 `cacheMinutes > 0` 且 `now - checkedAt < cacheMinutes`）且 `!force` | 返回 `CompletableFuture.completedFuture(cached)` |
| 3 | 否则 | CAS 占位 candidate Future → snapshot 转 `probing(prev)` → 起虚拟线程 |

> **在途优先的理由**：`force=true` 刷新期间，迟到的 `force=false` 调用应加入当前检测、共享其结果，而非直接返回旧缓存（保证"所有调用方拿到同一 Future"的可测不变量）。`cacheMinutes=0` 时 `isCacheValid` 永远返回 false → 每次都重测（但仍在途复用）。

### 4.7 single-flight 实现（先 CAS 占位再起虚拟线程）

```java
public CompletableFuture<Ipv6ProbeResult> checkAsync(boolean force) {
    if (!Config.IPV6_PROBE_ENABLED.get()) {
        return CompletableFuture.failedFuture(new IllegalStateException("IPv6 probe is disabled"));
    }
    while (true) {
        CompletableFuture<Ipv6ProbeResult> existing = inFlight.get();
        if (existing != null) {
            if (!existing.isDone()) return existing;
            inFlight.compareAndSet(existing, null);
            continue;
        }
        Ipv6ProbeResult cached = snapshot.get().lastResult();
        if (!force && isCacheValid(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<Ipv6ProbeResult> candidate = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, candidate)) continue;   // 被别人抢了，重来
        snapshot.updateAndGet(prev -> Ipv6ProbeSnapshot.probing(prev.lastResult()));
        Thread.ofVirtual().name("fireflymc-ipv6-probe").start(() -> runProbe(candidate));
        return candidate;
    }
}
```

任务完成顺序：**先 `snapshot.set(done(result))`，再 `candidate.complete(result)`，最后 `inFlight.compareAndSet(candidate, null)`**。

### 4.8 检测流程（`runProbe`，虚拟线程内同步调用）

**不捕获 `Error`**；中断处理在此处，不进 `classify`：

```java
private void runProbe(CompletableFuture<Ipv6ProbeResult> candidate) {
    long startedNanos = System.nanoTime();
    Ipv6ProbeStatus status;
    @Nullable Integer httpStatus;
    try {
        int code = transport.send(buildRequest());        // 见 §4.4, 每次动态读 timeout
        status = (code >= 200 && code < 300) ? AVAILABLE : HTTP_FAILED;
        httpStatus = code;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();               // 恢复中断标志
        status = UNKNOWN;
        httpStatus = null;
    } catch (IOException e) {
        status = classify(e);
        httpStatus = null;
    } catch (RuntimeException e) {
        status = UNKNOWN;
        httpStatus = null;
    }
    Instant checkedAt = clock.instant();                  // 用完成时刻
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    Ipv6ProbeResult result = new Ipv6ProbeResult(status, checkedAt, durationMs, httpStatus);
    snapshot.set(Ipv6ProbeSnapshot.done(result));
    candidate.complete(result);
    inFlight.compareAndSet(candidate, null);
}

private HttpRequest buildRequest() {
    return HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT + "?cb=" + UUID.randomUUID()))
            .timeout(Duration.ofSeconds(Config.IPV6_PROBE_TIMEOUT_SECONDS.get()))   // 每次动态读
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
}
```

### 4.9 异常分类（`classify(IOException)`，全链扫描 + 语义优先级）

> 设计意图：外层 `ConnectException` 可能掩盖内层 `SSLException`。因此**遍历整条 cause chain**，对每个节点判定其所属语义类与优先级，**返回最高优先级**（数值小者优先），而非按嵌套顺序取首个。

优先级（数值小者优先）：

| 优先级 | 异常 | 状态 |
|---|---|---|
| 1 | `UnknownHostException` | `DNS_FAILED` |
| 2 | `HttpTimeoutException` / `SocketTimeoutException`（`HttpConnectTimeoutException` 是前者子类，不单列） | `CONNECT_TIMEOUT` |
| 3 | `SSLException`（含 `SSLHandshakeException` / `SSLProtocolException`） | `TLS_FAILED` |
| 4 | `ConnectException` / `NoRouteToHostException` | `CONNECT_FAILED` |

```java
private static Ipv6ProbeStatus classify(IOException error) {
    int bestRank = Integer.MAX_VALUE;
    Ipv6ProbeStatus best = UNKNOWN;
    for (Throwable c = error; c != null; c = c.getCause()) {
        Ipv6ProbeStatus s;
        int rank;
        if (c instanceof UnknownHostException)                                      { s = DNS_FAILED;     rank = 1; }
        else if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) { s = CONNECT_TIMEOUT; rank = 2; }
        else if (c instanceof SSLException)                                         { s = TLS_FAILED;     rank = 3; }
        else if (c instanceof ConnectException || c instanceof NoRouteToHostException) { s = CONNECT_FAILED; rank = 4; }
        else continue;
        if (rank < bestRank) { bestRank = rank; best = s; }
    }
    return best;
}
```

- `CONNECT_FAILED` 覆盖"本机无 IPv6 默认路由 / 系统返回 Network unreachable / 目标拒绝连接"。
- `classify` **不**处理 `InterruptedException`（已在 §4.8 `runProbe` catch 中恢复中断并归 `UNKNOWN`）。
- 测试时禁止构造语义混乱链（如 `HttpTimeoutException` 包 `UnknownHostException`）；合理包装如 `new IOException(new UnknownHostException())`。

### 4.10 生命周期

- `onClientLoggedIn`（单人）且 `IPV6_PROBE_AUTO_ON_SP_JOIN=true` → `checkAsync(false)` 静默跑。
- 面板"测试 IPv6"按钮 → `checkAsync(true)`。
- `onClientLoggedOut` → **不取消在途、不清空状态**；下次进单人世界按缓存有效期决定是否重测（IPv6 是客户端网络能力，跨世界复用语义正确）。
- `IPV6_PROBE_ENABLED=false`：`checkAsync` 返回 `failedFuture`；UI 因按钮禁用正常不会触发。

### 4.11 测试注入点（包私有）

```java
@FunctionalInterface
interface ProbeTransport {
    int send(HttpRequest request) throws IOException, InterruptedException;   // 仅返回状态码
}

// 包私有测试构造（不依赖公网、不构造完整 HttpResponse）
Ipv6ConnectivityChecker(ProbeTransport transport, Clock clock) {
    this.transport = transport;
    this.clock = clock;
}
```

## 5. Manager 状态机（`SingleplayerRelayManager`，路线 A）

### 5.1 保证范围（必须逐字写进 JavaDoc 与 spec）

> `HostingState` 是联机操作意图与 UI 展示的唯一权威状态源，用于防止公开入口重复调用、保证客户端主线程上的启停顺序，并为界面提供四态信息。
>
> **它不代表底层 WebSocket executor、P2P 回调和 HostBridge 异步资源已经完全收敛，也不解决现有 relay 核心的跨线程迟到回调竞态。**

因此：
- `HOSTING` 表示主线程已执行完启动同步流程并发布托管意图；
- `STOPPED` 表示 Manager 当前可见清理流程已执行完成，并允许新的用户操作；
- **不构成**底层所有异步回调已终止的证明（executor 队列、WebSocket 回调触发的 P2P probe、HostBridge stream 可能在停止后迟到执行）。

完整治理列入 §2.3 未来专项（路线 B）。

### 5.2 状态枚举与字段

```java
public enum HostingState { STOPPED, STARTING, HOSTING, STOPPING }

private final AtomicReference<HostingState> hostingState =
        new AtomicReference<>(HostingState.STOPPED);
private volatile @Nullable String currentRoomId;

public HostingState getHostingState() { return hostingState.get(); }
@Nullable public String getCurrentRoomId() { return currentRoomId; }
```

- 用 `AtomicReference` 而非 `volatile`：`volatile` 只保证可见性，不保证状态转换原子性。
- `currentRoomId` 由主线程写、渲染线程读，加 `volatile` 保证可见性（注意 `RelayLobbyWebSocketClient.closeRoom` 内部 executor 线程也会写它自己的 `currentRoomId` 字段，那是另一个对象字段，与 Manager 的 `currentRoomId` 不同）。

### 5.3 主线程调度（两个公开入口对齐）

```java
public void startHosting() {
    Minecraft mc = Minecraft.getInstance();
    if (!mc.isSameThread()) { mc.execute(this::startHosting); return; }
    startHostingOnClientThread();
}

public void stopHosting() {
    Minecraft mc = Minecraft.getInstance();
    if (!mc.isSameThread()) { mc.execute(this::stopHosting); return; }
    stopHostingOnClientThread();
}
```

真正逻辑放进私有方法，避免重新调度后再次穿过复杂入口判断。保证 UI 点击、世界退出、后台线程触发的启停请求最终都由客户端主线程串行执行 → 状态机 CAS 转换无交错。

### 5.4 CAS 转换实现

允许的状态转换：

```
STOPPED  → STARTING
STARTING → HOSTING
STARTING → STOPPED     启动失败
STARTING → STOPPING    启动期间要求停止
HOSTING  → STOPPING
STOPPING → STOPPED
```

`startHostingOnClientThread()` 入口（通过 `RELAY_ENABLED` / `server != null` 等既有检查后）：

```java
if (!hostingState.compareAndSet(HostingState.STOPPED, HostingState.STARTING)) {
    return;   // 已在启动/运行/停止中，拒绝重入
}
```

启动成功处（原 `ClientState.isSingleplayerRelayHosting = true` 同一位置）：

```java
if (!hostingState.compareAndSet(HostingState.STARTING, HostingState.HOSTING)) {
    // 启动操作期间状态已发生变化，不得重新发布为 HOSTING。
    // 本次仅维护 Manager/UI 状态，不保证底层异步回调资源已完全收敛。
    // relay 核心的迟到回调治理留待独立生命周期重构（路线 B）。
    LOGGER.debug("Ignoring hosting completion because state is {}", hostingState.get());
    return;
}
ClientState.isSingleplayerRelayHosting = true;
```

> 此处**只能**确认：不会错误覆盖成 `HOSTING`、不会设置 `isSingleplayerRelayHosting=true`。**不能**确认：远端房间已关闭 / P2P probe 不会晚到 / HostBridge 不会再创建 stream / executor 队列已清空。

启动 catch 块（**CAS 回退，不无条件 set**）：

```java
hostingState.compareAndSet(HostingState.STARTING, HostingState.STOPPED);
```

若状态已被 `stopHosting` 推进到 `STOPPING`，启动异常处理不得提前改写，由停止流程最终置 `STOPPED`。

`stopHostingOnClientThread()` 入口（CAS 循环）：

```java
while (true) {
    HostingState current = hostingState.get();
    if (current == HostingState.STOPPED || current == HostingState.STOPPING) return;
    if (hostingState.compareAndSet(current, HostingState.STOPPING)) break;
}
try {
    // ... 原清理逻辑（closeRoom / stopHost / hostBridge.stop / setHostBridge(null) 等）...
} finally {
    currentRoomId = null;
    ClientState.isSingleplayerRelayHosting = false;
    hostingState.set(HostingState.STOPPED);   // STOPPING → STOPPED
}
```

> 现有 `ClientState.isSingleplayerRelayHosting`（boolean）**保留不动**，继续由 Manager 同步设置，供 `SingleplayerSharePromptScreen` 等现有调用方使用；新面板优先读 `getHostingState()` 获得四态细粒度。

## 6. ESC 入口注入

扩展 `SingleplayerRelayClientEvents.onScreenInit`（`ScreenEvent.Init.Post` 已在 `FireflyMCMod` 注册，不重复注册）：

```java
if (!(event.getScreen() instanceof GameMenuScreen screen)) return;   // 类名以 Mojmap 实际映射为准，实现时确认
Minecraft mc = Minecraft.getInstance();
if (mc.level == null || !mc.hasSingleplayerServer()) return;          // 仅单人世界

int x = Math.max(MARGIN, screen.width - INJECTED_BUTTON_WIDTH - MARGIN);
int y = MARGIN;
event.addListener(Button.builder(
    Component.translatable("gui.fireflymc.singleplayer_relay.entry"),
    b -> mc.setScreen(new SingleplayerRelayControlScreen(screen))
).bounds(x, y, INJECTED_BUTTON_WIDTH, INJECTED_BUTTON_HEIGHT).build());
```

- 尺寸/边距常量（`INJECTED_BUTTON_WIDTH=120` / `INJECTED_BUTTON_HEIGHT=20` / `MARGIN=8`）提取为 `SingleplayerRelayClientEvents` 常量，与现有 `JoinMultiplayerScreen` 注入共享，避免日后改一处漏一处。
- 按钮文本用 `Component.translatable`，不用 `Component.literal`。
- `Init.Post` 每次 widget 重建触发（MC 自身 widget 也每次 init 重建），不累积，无需额外防重复；只需保证事件处理器只注册一次（`FireflyMCMod` 已保证）。
- 多人服务器 ESC 菜单不出现（`level != null && hasSingleplayerServer()` 双重守卫）。

## 7. 联机控制面板（`SingleplayerRelayControlScreen`）

### 7.1 布局

复用 `SingleplayerSharePromptScreen` 的粉色毛玻璃 dialog 风格（阴影 + 毛玻璃 + 渐变边框 + 星星 + 渐变分割线），dialog 尺寸按 §7.6 自适应。自上而下：

```
┌────────────────────────────────────────────┐
│  ★  FireflyMC 联机控制  ★                  │  标题 + 星星 + 渐变线
│────────────────────────────────────────────│
│  联机状态: ● 已发布 / ○ 未开启 / ○ 正在开启 │  状态行(带色,四态)
│  房间号: a3f1-...  LAN 端口: 54321          │  仅 HOSTING 时显示完整;roomId 暂缺显示"正在获取…"
│  最大玩家: 8                                │
│                                            │
│        [   开启联机   ] / [   停止联机   ]  │  主按钮(按状态互斥/禁用)
│────────────────────────────────────────────│
│  IPv6 出站: ✓ 可用 / ✗ ... / ⟳ 检测中      │  IPv6 状态行
│  上次检测: 2 分钟前 · 耗时 318ms            │  时间戳+延迟;probing 显示"检测中…(上次:…)"
│  本机全局 IPv6:                             │  标签;悬浮 hint
│    2001:db8::1                              │  每行一个地址
│    2001:db8::2                              │
│    另有 3 个                                │  超过 2 个折叠
│                                            │
│          [   测试 IPv6   ]                  │  检测按钮(probing/disabled 时禁用)
│────────────────────────────────────────────│
│  (条件提示行,见 §8.2;窄屏自动换行)          │
│                                            │
│          [   完成   ]                       │  返回父级
└────────────────────────────────────────────┘
```

### 7.2 数据来源（全部只读快照）

| 显示项 | 来源 |
|---|---|
| 联机四态 | `SingleplayerRelayManager.getInstance().getHostingState()` |
| 房间号 | `SingleplayerRelayManager.getInstance().getCurrentRoomId()` |
| LAN 端口 | `ClientState.singleplayerRelayLanPort` |
| 最大玩家 | `RelayConfig.RELAY.SINGLEPLAYER_RELAY_MAX_PLAYERS.get()` |
| IPv6 全部 | `Ipv6ConnectivityChecker.getInstance().snapshot()` |
| 本机 GUA | `Ipv6AddressCollector.collectGlobalIpv6()` |

多字段非原子（`HOSTING` + `roomId=null` 可能短暂不一致），UI 用占位符"正在获取…"兜底，不抛异常。

### 7.3 按钮状态机

| `HostingState` | 主按钮 | 文案 |
|---|---|---|
| `STOPPED` | `[开启联机]` 可用 | `action.start` |
| `STARTING` | `[正在开启…]` 禁用 | `action.starting` |
| `HOSTING` | `[停止联机]` 可用 | `action.stop` |
| `STOPPING` | `[正在停止…]` 禁用 | `action.stopping` |

| IPv6 快照 | 测试按钮 |
|---|---|
| `probing=true` | 禁用，文案"检测中…" |
| `probing=false` 且 `IPV6_PROBE_ENABLED=true` | 可用 |
| `IPV6_PROBE_ENABLED=false` | 禁用，保留并标记历史结果 |

`[完成]` 按钮与 ESC 均调用统一 `onClose()`：

```java
@Override public void onClose() {
    if (minecraft != null && minecraft.level != null) minecraft.setScreen(parent);
    else if (minecraft != null) minecraft.setScreen(null);
}
```

### 7.4 tick / render 分工

- `tick()`：读快照，更新按钮 `active` / `setMessage`（主按钮按 `HostingState`、测试按钮按 `probing` / `enabled`）。
- `render()`：只读最新快照绘制文字，无副作用。`AtomicReference` 每帧读取无并发问题。
- GUA 刷新：`init()` 收集一次；记录上次见到的 `Ipv6ProbeResult.checkedAt()`；当 `probing true→false` 且 `checkedAt` 变化时重新收集一次。**不**每帧枚举网卡。

### 7.5 GUA 展示规则

- 标签：`gui.fireflymc.ipv6.gua.label` = "本机全局 IPv6"。
- 悬浮 hint：`gua.hint` = "不代表该地址可从公网入站访问"。
- 0 个：显示 `gua.none` = "未发现"。
- 1～2 个：逐行显示。
- 超过 2 个：显示前两个 + `gua.more` = "另有 %s 个"。
- 每个 IPv6 地址独占一行，不用分号塞同一行（防窄 GUI 溢出）。
- 单个地址超过可用宽度时省略显示，悬浮提示展示完整值。

### 7.6 低分辨率与英文换行约束（实现必须满足）

```
dialogWidth  = min(380, screen.width - 24)
dialogHeight = min(requiredHeight, screen.height - 24)
```

- 条件提示行用 `font.split(component, availableWidth)` 自动换行，不硬编码折行。
- 房间号与 LAN 端口在窄屏（`availableWidth < 阈值`）拆成两行。
- IPv6 地址超可用宽度时省略 + 悬浮完整值。
- 至少在逻辑 GUI 尺寸 320×240 下验证布局不越界、不重叠。
- 必须验证 `en_us`（英文文案更长），不能只测中文。

## 8. 文案规则

### 8.1 IPv6 状态文案（7 终态 + probing + idle + disabled）

| 状态 / 场景 | 主状态 | 副信息 |
|---|---|---|
| `AVAILABLE` | IPv6 出站可用 | 已成功连接 IPv6 检测服务 |
| `DNS_FAILED` | 未检测到 IPv6 出站 | DNS 解析失败 |
| `CONNECT_FAILED` | 未检测到 IPv6 出站 | IPv6 连接失败 |
| `CONNECT_TIMEOUT` | 未检测到 IPv6 出站 | IPv6 连接超时 |
| `TLS_FAILED` | IPv6 检测失败 | TLS 握手失败 |
| `HTTP_FAILED` | IPv6 检测失败 | HTTP 状态异常（附 `%s` 状态码） |
| `UNKNOWN` | IPv6 检测失败 | 检测服务可能不可达 |
| `probing=true`（有上次结果） | ⟳ 检测中… | 上次：出站可用 / 未检测到出站 / 检测失败（三分类，见下） |
| `probing=true`（无上次结果） | ⟳ 检测中… | — |
| 从未检测 | 尚未检测 | 点击下方按钮开始检测 |
| `enabled=false`（有历史） | 检测已关闭 | （标记为历史结果） |
| `enabled=false`（无历史） | 检测已关闭 | 可在配置中重新开启 |

**历史"上次"三分类**（不得把后三类渲染成"不可用"，否则破坏三分类）：

| 上次 `status` | "上次："文案 | lang key |
|---|---|---|
| `AVAILABLE` | 上次：出站可用 | `gui.fireflymc.ipv6.previous.available` |
| `DNS_FAILED` / `CONNECT_FAILED` / `CONNECT_TIMEOUT` | 上次：未检测到出站 | `gui.fireflymc.ipv6.previous.not_detected` |
| `TLS_FAILED` / `HTTP_FAILED` / `UNKNOWN` | 上次：检测失败 | `gui.fireflymc.ipv6.previous.probe_failed` |

> 区分原则：`TLS_FAILED` / `HTTP_FAILED` / `UNKNOWN` 用"检测失败"（第三方服务可能故障，不等于用户无 IPv6）；`DNS_FAILED` / `CONNECT_FAILED` / `CONNECT_TIMEOUT` 用"未检测到出站"（更可能是用户侧无 IPv6 路径）。

### 8.2 条件提示行（红线：不得暗示检测结果驱动 P2P / 中继路径）

| 场景 | 文案 key 内容 |
|---|---|
| 联机已开 + `AVAILABLE` | IPv6 出站连接可用；公网入站与 P2P 直连能力尚未验证。 |
| 联机已开 + `DNS_FAILED` / `CONNECT_FAILED` / `CONNECT_TIMEOUT` | 未检测到可用的 IPv6 出站连接；联机仍可使用现有中继链路。 |
| 联机已开 + `TLS_FAILED` / `HTTP_FAILED` / `UNKNOWN` | IPv6 检测未完成；可能是检测服务不可达，联机功能不受影响。 |
| 联机未开启 | 此结果仅表示 IPv6 出站能力，不代表公网游戏端口可达。 |

### 8.3 红线（禁止的是"肯定性结论"，而非词汇本身）

以下内容**不得作为肯定性检测结论**出现；但在"不代表""尚未验证"等**明确否定或限制性说明**中可以使用相关术语（如 §8.2 的免责声明）：

- 不得宣称已具备公网入站能力；
- 不得宣称 IPv6 玩家一定能够直连；
- 不得宣称检测结果已使 P2P 优先或中继切换；
- 不得将检测失败等同于公网不可达。

## 9. 配置项（`Config.java` `ipv6_probe` 组）

```java
builder.push("ipv6_probe").translation("fireflymc.configuration.ipv6_probe");

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

- 手动"测试 IPv6"按钮不受 `autoCheckOnSingleplayerJoin` 控制（仅 `enabled` 控制其可用性）。
- endpoint / UA / 重试 / HTTP 版本留内部常量，不进 config。

## 10. 错误处理与边界

| 场景 | 处理 |
|---|---|
| `enabled=false` | 自动检测不触发；面板测试按钮禁用 + "检测已关闭"；保留历史结果标记 |
| test-ipv6.com 不可达（大陆网络） | 归 `UNKNOWN`，文案"检测服务可能不可达,联机功能不受影响"——不误报"无 IPv6" |
| 检测中关闭面板 / 退出世界 | 不取消在途任务，自然完成或超时；退出世界不清缓存 |
| 进世界瞬间自动检测 + 立刻手动点测试 | single-flight 在途优先，复用同一 Future |
| `startHosting` 抛异常 | catch 用 `compareAndSet(STARTING, STOPPED)`；不覆盖 STOPPING；面板按钮回到 `[开启联机]` |
| 启动期间调用 `stopHosting` | 两个公开入口均调度到客户端主线程；状态按 `STARTING → STOPPING → STOPPED` 串行转换。启动完成处 CAS 失败时不得覆盖成 `HOSTING`，也不得设置 hosting boolean。底层 executor/WebSocket/P2P 迟到资源竞态属于现有 relay 核心限制，本期不解决 |
| `getCurrentRoomId()` 短暂 null（HOSTING 期间） | UI 占位"正在获取…"，不抛异常 |
| 网络切换致 GUA 变化 | probing 完成（`probing true→false` 且 `checkedAt` 变化）时重新收集 GUA |
| Screen resize / 资源重载 | `Init.Post` 重注入不累积按钮（MC widget 每次重建） |
| **已知限制（本期不解决）** | `publishRoom`/`closeRoom` executor 队列、WebSocket 回调触发的 P2P probe 和 HostBridge stream 可能在停止后迟到执行；完整治理需要 generation token 或异步操作收敛模型，列入 §2.3 后续 relay 生命周期重构（路线 B） |

## 11. 测试策略

### 11.1 Checker 单测（注入 `ProbeTransport` + `Clock`，不依赖公网）

**single-flight 用 `CountDownLatch` 控制**（不依赖真实时间）：

```java
AtomicInteger calls = new AtomicInteger();
CountDownLatch entered = new CountDownLatch(1);
CountDownLatch release = new CountDownLatch(1);
ProbeTransport transport = request -> {
    calls.incrementAndGet();
    entered.countDown();
    release.await();
    return 204;
};
```

测试顺序：发起第一次 `checkAsync(false)` → 等 `entered` → 发起第二次 → 断言两引用同一 Future → `calls==1` → `release.countDown()` → 等完成 → 再 `force=true` → 断言开始第二次 transport。

**必测项**：
- `classify`：各异常类型 + cause-chain 包装（如 `new IOException(new UnknownHostException())`、`new IOException(new SSLHandshakeException("t"))`）；语义优先级（外层 `ConnectException` + 内层 `SSLException` → `TLS_FAILED`）；禁止语义混乱链。
- `enabled=false`：返回 `failedFuture`，**不**调用 transport、**不**把 snapshot 改成 `probing`。
- `force=true`：绕过缓存；但已有在途检测时仍复用（不产生第二次 transport 调用）。
- probing 时保留上一次结果（snapshot 转 `probing(prev)` 后 `lastResult` 不变）。
- HTTP 边界：199 / 300 → `HTTP_FAILED`；200 / 299 → `AVAILABLE`。
- 收到 HTTP 响应时 `httpStatus` 非 null；连接异常 / 中断 / 运行时异常时为 null。
- transport 抛异常后 Future 正常完成（结果为对应失败状态），`inFlight` 被清空。
- `InterruptedException` 后 `Thread.currentThread().isInterrupted()` 为 true，状态归 `UNKNOWN`。
- `cacheMinutes=0`：不复用结果（`isCacheValid` 恒 false）。
- 缓存 TTL：刚好 `cacheMinutes` 时判定过期。
- `RuntimeException` 不被 `catch (Throwable)` 吞，归 `UNKNOWN`；`Error` 不被捕获（传播）。

### 11.2 Manager 测试（真实生命周期，路线 A 边界）

**本期验收范围**（必测）：
- 非主线程调用 `startHosting` / `stopHosting` 会调度到客户端主线程。
- 重复 `startHosting()` 不重入（CAS `STOPPED→STARTING` 失败即返回）。
- `STARTING` 时白盒/另一调用路径调用 `stopHosting()`，状态序列 `STARTING → STOPPING → STOPPED`，**不闪回 `HOSTING`**（启动完成处 CAS 失败）。
- 启动 catch 用 `compareAndSet(STARTING, STOPPED)`，不覆盖 `STOPPING`。
- `ClientState.isSingleplayerRelayHosting` 只在成功进入 `HOSTING` 后置 true；`STOPPING` 完成后置 false。

**本期不验证**（属路线 B 专项范围，不列入验收）：
- 停止后绝无迟到 P2P probe；
- 停止后绝无新 HostBridge stream；
- executor 队列完全排空；
- 远端房间绝无泄漏。

> 不写"裸 `AtomicReference` 状态机单测"——那只测 JDK CAS，不覆盖 Manager 逻辑。

### 11.3 手动测试矩阵（客户端实跑）

1. 有 IPv6 网络 → `AVAILABLE` + 耗时显示 + 联机已开时提示"出站可用；公网入站与 P2P 尚未验证"。
2. 禁用网卡 IPv6 → `DNS_FAILED` 或 `CONNECT_FAILED` 或 `CONNECT_TIMEOUT`（取决于系统）+ 文案"未检测到 IPv6 出站"。
3. hosts 映射 `ipv6.test-ipv6.com` 到 `::1` 且本地无 HTTPS 服务 → 通常 `CONNECT_FAILED`；防火墙静默丢包 → `CONNECT_TIMEOUT`；本地 DNS 规则返回 NXDOMAIN → `DNS_FAILED`；HTTPS 中间人/错误证书 → `TLS_FAILED`。**验证点**：UI 正确展示实际分类，且不把检测失败宣称为公网不可达（Windows hosts 不能直接制造 NXDOMAIN，需用本地 DNS 规则或 DNS 工具）。
4. 自动检测：进世界 15 min 内重进不重跑；超 15 min 重跑；`cacheMinutes=0` 每次重跑。
5. ESC 入口：单人世界出现、多人服务器不出现、`hasSingleplayerServer` 守卫生效。
6. 面板四态按钮：开启→停止全程按钮禁用/文案正确；快速连点不重入（CAS 拒绝）。
7. `enabled=false`：按钮禁用 + "检测已关闭" + 历史结果保留。
8. Screen resize：`Init.Post` 重注入不累积按钮（ESC 菜单只有一个"FireflyMC 联机"按钮）。
9. （白盒/日志验证，非 UI 点击）`STARTING` 期间从另一调用路径触发 `stopHosting`：状态不闪回 `HOSTING`，日志可见"Ignoring hosting completion"。

### 11.4 UI 手动测试补充

- 最小逻辑分辨率（320×240）/ 高 GUI Scale 下布局不越界、不重叠。
- `en_us` 文案换行正常（英文更长）。
- 超长 room ID 不撑破 dialog（省略 + 悬浮）。
- 完整长 IPv6 地址不越界（省略 + 悬浮）。
- `enabled=false` + 有历史结果：展示"检测已关闭" + 历史结果标记。
- probing 且上次为 `TLS_FAILED`：显示"上次：检测失败"，**不**显示"不可用"。

## 12. lang key 清单

```
# 入口与标题
gui.fireflymc.singleplayer_relay.entry              FireflyMC 联机
gui.fireflymc.singleplayer_relay.control.title      FireflyMC 联机控制

# 联机四态
gui.fireflymc.singleplayer_relay.state.stopped      未开启
gui.fireflymc.singleplayer_relay.state.starting     正在开启…
gui.fireflymc.singleplayer_relay.state.hosting      已发布
gui.fireflymc.singleplayer_relay.state.stopping     正在停止…

# 联机信息（参数化）
gui.fireflymc.singleplayer_relay.room_id            房间号：%s
gui.fireflymc.singleplayer_relay.room_id.pending    房间号：正在获取…
gui.fireflymc.singleplayer_relay.lan_port           LAN 端口：%s
gui.fireflymc.singleplayer_relay.max_players        最大玩家：%s

# 联机操作按钮
gui.fireflymc.singleplayer_relay.action.start       开启联机
gui.fireflymc.singleplayer_relay.action.starting    正在开启…
gui.fireflymc.singleplayer_relay.action.stop        停止联机
gui.fireflymc.singleplayer_relay.action.stopping    正在停止…
gui.fireflymc.singleplayer_relay.action.done        完成

# IPv6 操作按钮
gui.fireflymc.ipv6.action.test                      测试 IPv6
gui.fireflymc.ipv6.action.testing                   检测中…

# IPv6 状态（主 + 副）
gui.fireflymc.ipv6.available                        IPv6 出站可用
gui.fireflymc.ipv6.subtitle.available               已成功连接 IPv6 检测服务
gui.fireflymc.ipv6.not_detected                     尚未检测
gui.fireflymc.ipv6.subtitle.not_detected            点击下方按钮开始检测
gui.fireflymc.ipv6.dns_failed                        未检测到 IPv6 出站
gui.fireflymc.ipv6.subtitle.dns_failed              DNS 解析失败
gui.fireflymc.ipv6.connect_failed                   未检测到 IPv6 出站
gui.fireflymc.ipv6.subtitle.connect_failed          IPv6 连接失败
gui.fireflymc.ipv6.connect_timeout                  未检测到 IPv6 出站
gui.fireflymc.ipv6.subtitle.connect_timeout         IPv6 连接超时
gui.fireflymc.ipv6.tls_failed                       IPv6 检测失败
gui.fireflymc.ipv6.subtitle.tls_failed              TLS 握手失败
gui.fireflymc.ipv6.http_failed                      IPv6 检测失败
gui.fireflymc.ipv6.subtitle.http_failed             HTTP 状态异常（%s）
gui.fireflymc.ipv6.unknown                          IPv6 检测失败
gui.fireflymc.ipv6.subtitle.unknown                 检测服务可能不可达
gui.fireflymc.ipv6.disabled                         检测已关闭
gui.fireflymc.ipv6.subtitle.disabled                可在配置中重新开启
gui.fireflymc.ipv6.historical                       （历史结果）
gui.fireflymc.ipv6.probing_with_last                检测中…（上次：%s）

# 历史"上次"三分类
gui.fireflymc.ipv6.previous.available               出站可用
gui.fireflymc.ipv6.previous.not_detected            未检测到出站
gui.fireflymc.ipv6.previous.probe_failed            检测失败

# IPv6 元信息（参数化）
gui.fireflymc.ipv6.last_check                       上次检测：%s · 耗时 %s ms
gui.fireflymc.ipv6.duration_ms                      %s ms

# GUA
gui.fireflymc.ipv6.gua.label                        本机全局 IPv6
gui.fireflymc.ipv6.gua.hint                         不代表该地址可从公网入站访问
gui.fireflymc.ipv6.gua.none                         未发现
gui.fireflymc.ipv6.gua.more                         另有 %s 个

# 条件提示行
gui.fireflymc.ipv6.hint.available                   IPv6 出站连接可用；公网入站与 P2P 直连能力尚未验证。
gui.fireflymc.ipv6.hint.not_detected                未检测到可用的 IPv6 出站连接；联机仍可使用现有中继链路。
gui.fireflymc.ipv6.hint.probe_failed                IPv6 检测未完成；可能是检测服务不可达，联机功能不受影响。
gui.fireflymc.ipv6.hint.idle                        此结果仅表示 IPv6 出站能力，不代表公网游戏端口可达。

# 时间相对值（可选）
gui.fireflymc.time.just_now                         刚刚
gui.fireflymc.time.minutes_ago                      %s 分钟前
gui.fireflymc.time.hours_ago                        %s 小时前

# 配置翻译
fireflymc.configuration.ipv6_probe                  IPv6 联机检测
fireflymc.configuration.ipv6_probe.enabled          启用 IPv6 出站检测
fireflymc.configuration.ipv6_probe.auto_check_on_singleplayer_join  进入单人世界时自动检测
fireflymc.configuration.ipv6_probe.timeout_seconds  每次检测超时（秒）
fireflymc.configuration.ipv6_probe.cache_minutes    检测结果缓存时长（分钟，0=不缓存）
```

`zh_cn.json` 用上表中文；`en_us.json` 提供对应英文翻译。

## 13. 实现顺序

1. 确认/建立 `src/test` 与测试依赖（JUnit / Mockito）。
2. `Ipv6ProbeStatus` / `Ipv6ProbeResult`（纯数据结构）。
3. `Config.java` 增加 `ipv6_probe` 配置组及配置翻译 key（**先于 Checker**，因 Checker 引用配置字段）。
4. `Ipv6ConnectivityChecker` + `ProbeTransport` / `Clock` / `Ipv6ProbeSnapshot` + Checker 单测。
5. `SingleplayerRelayManager` 状态机改造（`HostingState` + 主线程调度对齐 + CAS 转换 + getter + `currentRoomId` volatile）+ Manager 生命周期测试。
6. `SingleplayerRelayControlScreen`（UI + tick/render + GUA 刷新 + §7.6 布局约束），同时一次性补齐其全部 lang key。
7. `SingleplayerRelayClientEvents` 扩展（`GameMenuScreen` 注入 + `onClientLoggedIn` 触发检测）。
8. 构建与静态检查：`.\gradlew.bat build`。
9. 手动测试矩阵（§11.3 + §11.4）全过。

> Manager 的状态语义必须在写 UI（步骤 6）前完成（步骤 5），否则 UI 会建立在不稳定的状态语义上。

## 14. 验收标准

- 单人 ESC 菜单出现"FireflyMC 联机"按钮；多人不出现。
- 面板四态按钮与 `HostingState` 一致；快速连点不重入。
- 有/无 IPv6 两种网络下，IPv6 状态分类与文案正确；检测服务不可达时不误报"无 IPv6"。
- 进世界静默检测一次；缓存有效期内不重跑；手动按钮可强制重测。
- `enabled=false` 时按钮禁用、文案正确、历史结果保留。
- 全程不出现 §8.3 列出的肯定性结论。
- 状态机转换符合 §5.4 转换图；启动期间被停止不闪回 `HOSTING`（白盒验证）。
- 构建通过：`.\gradlew.bat build`。
- **不**把"停止后绝无迟到回调资源"作为验收项（属路线 B 专项）。
