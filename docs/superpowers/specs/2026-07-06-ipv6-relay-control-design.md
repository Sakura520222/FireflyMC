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
- 联机状态四态机（`STOPPED/STARTING/HOSTING/STOPPING`）的权威状态源。
- 配置项、i18n、错误处理边界。

### 2.2 非目标（明确不做）

- **不**证明"房主 IPv6 游戏端口可被公网入站直连"——这需要中心服务器实际回连探测，属未来扩展。
- **不**让 IPv6 检测结果影响 P2P / 中继代码路径。检测结果仅供 UI 展示，P2P 核心完全不感知。
- **不**自建检测端点（`v6-check.example.com` 之类），本期继续依赖 test-ipv6.com 公益服务。
- **不**改 P2P / relay 传输核心逻辑；对 `SingleplayerRelayManager` 只做状态标记与只读 getter 的最小增量。

### 2.3 未来扩展（预留，不在本期）

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
              ├──► [开启/停止联机] → Manager.startHosting() / stopHosting()
              └──► [测试 IPv6]     → Ipv6ConnectivityChecker.checkAsync(force=true)
```

### 3.2 设计原则

- **唯一权威状态源**：IPv6 检测状态只在 `Ipv6ConnectivityChecker`；联机状态只在 `SingleplayerRelayManager`。`ClientState` 不再加 IPv6 字段。
- **UI 只读快照 + 发命令**：面板不写 Manager / Checker 内部状态，只读快照、调用公开方法。
- **零新 Mixin**：ESC 按钮复用现有 `ScreenEvent.Init.Post` 模式（与 `JoinMultiplayerScreen` 注入一致）。
- **检测结果不干预网络路径**：P2P / 中继代码不读 `Ipv6ProbeResult`。

### 3.3 组件清单

**新增（4 个 .java）**

| 文件 | 职责 |
|---|---|
| `client/relay/ipv6/Ipv6ProbeStatus.java` | 公开枚举，7 终态 |
| `client/relay/ipv6/Ipv6ProbeResult.java` | 公开 record |
| `client/relay/ipv6/Ipv6ConnectivityChecker.java` | 单例；内含公开嵌套 `Ipv6ProbeSnapshot`；唯一状态源；内含包私有 `ProbeTransport` 接口用于测试注入 |
| `client/screen/SingleplayerRelayControlScreen.java` | 联机控制面板 |

**修改（5）**

| 文件 | 改动 |
|---|---|
| `client/relay/SingleplayerRelayManager.java` | +`HostingState` 公开嵌套枚举 + `AtomicReference<HostingState>` + CAS 转换 + `getHostingState()` / `getCurrentRoomId()` getter；`currentRoomId` 加 `volatile` |
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

### 4.4 HttpClient 配置

```java
this.httpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)        // 禁用代理：经代理成功只证明代理有 v6，不证明本机
        .followRedirects(HttpClient.Redirect.NEVER) // 显式禁止重定向：防 302→双栈域名的 v4 假阳性
        .build();
```

- `HttpClient` 单例复用。**不**在 builder 上设 `connectTimeout`——超时由每次 `HttpRequest.timeout()` 动态读配置，使配置热修改立即生效。
- HTTP 版本用默认（H2 优先）；未来端点降级导致的 TLS 失败已归 `TLS_FAILED`，不影响结论。

### 4.5 字段与并发

```java
private final AtomicReference<Ipv6ProbeSnapshot> snapshot = new AtomicReference<>(Ipv6ProbeSnapshot.idle());
private final AtomicReference<CompletableFuture<Ipv6ProbeResult>> inFlight = new AtomicReference<>();
private final HttpClient httpClient;
private final ProbeTransport transport;     // 生产：httpClient.send(...discarding)
private final Clock clock;                  // 生产：Clock.systemUTC()；测试可注入
```

### 4.6 `checkAsync(boolean force)` 决策表

| 条件 | force=false | force=true |
|---|---|---|
| 缓存有效（`lastResult != null` 且 `cacheMinutes > 0` 且 `now - checkedAt < cacheMinutes`） | 返回 `CompletableFuture.completedFuture(cached)`，不发请求 | 跳过缓存，继续往下 |
| `inFlight` 存在且未完成 | **复用**同一 Future | **复用**同一 Future（不打断） |
| `inFlight` 存在但已完成 | CAS 清理后 continue | 同左 |
| 否则 | CAS 占位 candidate Future → 起虚拟线程 | 同左 |

> `cacheMinutes=0` 语义：自动检测仍可触发，但**不复用**已完成结果（每次都重测）。`force=true` 始终跳过缓存判定。

### 4.7 single-flight 实现（先 CAS 占位再起虚拟线程）

```java
public CompletableFuture<Ipv6ProbeResult> checkAsync(boolean force) {
    while (true) {
        Ipv6ProbeSnapshot current = snapshot.get();

        if (!force && isCacheValid(current.lastResult())) {
            return CompletableFuture.completedFuture(current.lastResult());
        }

        CompletableFuture<Ipv6ProbeResult> existing = inFlight.get();
        if (existing != null) {
            if (!existing.isDone()) return existing;
            inFlight.compareAndSet(existing, null);
            continue;
        }

        CompletableFuture<Ipv6ProbeResult> candidate = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, candidate)) continue;   // 被别人抢了，重来

        snapshot.updateAndGet(prev -> Ipv6ProbeSnapshot.probing(prev.lastResult()));
        Thread.ofVirtual().name("fireflymc-ipv6-probe").start(() -> runProbe(candidate));
        return candidate;
    }
}
```

任务完成顺序：**先 `snapshot.set(done(result))`，再 `candidate.complete(result)`，最后 `inFlight.compareAndSet(candidate, null)`**。确保新请求启动时旧 Future 已完成，避免竞态。

### 4.8 检测流程（虚拟线程内同步调用）

```
long startedNanos = System.nanoTime();
try {
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT + "?cb=" + UUID.randomUUID()))
            .timeout(Duration.ofSeconds(Config.IPV6_PROBE_TIMEOUT_SECONDS.get()))   // 每次动态读
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    HttpResponse<Void> response = transport.send(request);        // BodyHandlers.discarding()
    int code = response.statusCode();
    status = (code >= 200 && code < 300) ? AVAILABLE : HTTP_FAILED;
    httpStatus = code;
} catch (Throwable e) {
    status = classify(e);     // 见 4.9
    httpStatus = null;
}
Instant checkedAt = clock.instant();
long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
Ipv6ProbeResult result = new Ipv6ProbeResult(status, checkedAt, durationMs, httpStatus);
```

### 4.9 异常分类（cause-chain 单循环，按首个识别异常）

```java
private static Ipv6ProbeStatus classify(Throwable error) {
    for (Throwable c = error; c != null; c = c.getCause()) {
        if (c instanceof UnknownHostException)                          return DNS_FAILED;
        if (c instanceof ConnectException || c instanceof NoRouteToHostException)
                                                                       return CONNECT_FAILED;
        if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException)
                                                                       return CONNECT_TIMEOUT;  // HttpConnectTimeoutException 是其子类，无需单独分支
        if (c instanceof SSLHandshakeException)                         return TLS_FAILED;
    }
    if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();                            // 恢复中断标志
    }
    return UNKNOWN;
}
```

- 非安全分类（`InterruptedException` 等）归 `UNKNOWN` 并恢复中断标志。
- `CONNECT_FAILED`（`ConnectException` / `NoRouteToHostException`）覆盖"本机无 IPv6 默认路由 / 系统直接返回 Network unreachable / 目标拒绝连接"，区别于"超时"和"DNS 失败"。

### 4.10 生命周期

- `onClientLoggedIn`（单人）且 `IPV6_PROBE_AUTO_ON_SP_JOIN=true` → `checkAsync(false)` 静默跑。
- 面板"测试 IPv6"按钮 → `checkAsync(true)`。
- `onClientLoggedOut` → **不取消在途、不清空状态**；下次进单人世界按缓存有效期决定是否重测（IPv6 是客户端网络能力，跨世界复用语义正确）。
- `IPV6_PROBE_ENABLED=false`：Checker 防御性返回 `CompletableFuture.failedFuture(new IllegalStateException("IPv6 probe is disabled"))`；UI 流程因按钮禁用正常不会触发。

### 4.11 测试注入点（包私有）

```java
@FunctionalInterface
interface ProbeTransport {
    HttpResponse<Void> send(HttpRequest request) throws IOException, InterruptedException;
}

// 生产单例
private Ipv6ConnectivityChecker() {
    this.httpClient = HttpClient.newBuilder()...build();
    this.transport = req -> httpClient.send(req, HttpResponse.BodyHandlers.discarding());
    this.clock = Clock.systemUTC();
}

// 包私有测试构造
Ipv6ConnectivityChecker(ProbeTransport transport, Clock clock) { ... }
```

## 5. Manager 状态机（`SingleplayerRelayManager`）

### 5.1 状态枚举与权威源

```java
public enum HostingState { STOPPED, STARTING, HOSTING, STOPPING }

private final AtomicReference<HostingState> hostingState =
        new AtomicReference<>(HostingState.STOPPED);
private volatile @Nullable String currentRoomId;

public HostingState getHostingState() { return hostingState.get(); }
@Nullable public String getCurrentRoomId() { return currentRoomId; }
```

- 用 `AtomicReference` 而非 `volatile`：`volatile` 只保证可见性，不保证状态转换原子性。
- `currentRoomId` 由 WebSocket 回调 / 主线程写入、渲染线程读取，加 `volatile` 保证可见性。

### 5.2 允许的状态转换

```
STOPPED  → STARTING
STARTING → HOSTING
STARTING → STOPPED     启动失败
STARTING → STOPPING    启动期间要求停止
HOSTING  → STOPPING
STOPPING → STOPPED
```

### 5.3 CAS 转换实现

`startHosting()` 入口（通过 `mc.isSameThread` / `RELAY_ENABLED` / `server != null` 检查后）：

```java
if (!hostingState.compareAndSet(HostingState.STOPPED, HostingState.STARTING)) {
    return;   // 已在启动/运行/停止中，拒绝重入
}
```

启动成功处（原 `ClientState.isSingleplayerRelayHosting = true` 同一位置）：

```java
if (!hostingState.compareAndSet(HostingState.STARTING, HostingState.HOSTING)) {
    // 期间已被 stopHosting CAS 成 STOPPING（或已回退 STOPPED），不得覆盖成 HOSTING。
    // stopHosting 已接管清理（其 CAS 循环已成功进入 STOPPING 并会执行清理 + finally 置 STOPPED），
    // 此处直接返回，不重复清理，也不设 ClientState.isSingleplayerRelayHosting。
    return;
}
ClientState.isSingleplayerRelayHosting = true;
```

启动 catch 块：`hostingState.set(HostingState.STOPPED);`（从 STARTING 回退）。

`stopHosting()` 入口（CAS 循环）：

```java
while (true) {
    HostingState current = hostingState.get();
    if (current == HostingState.STOPPED || current == HostingState.STOPPING) return;
    if (hostingState.compareAndSet(current, HostingState.STOPPING)) break;
}
// ... 原清理逻辑 ...
// finally:
hostingState.set(HostingState.STOPPED);
ClientState.isSingleplayerRelayHosting = false;
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

复用 `SingleplayerSharePromptScreen` 的粉色毛玻璃 dialog 风格（阴影 + 毛玻璃 + 渐变边框 + 星星 + 渐变分割线），加大 dialog 尺寸以容纳更多信息。自上而下：

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
│  上次检测: 2 分钟前 · 耗时 318ms            │  时间戳+延迟;probing 显示"检测中…(上次:可用)"
│  本机全局 IPv6:                             │  标签;悬浮 hint
│    2001:db8::1                              │  每行一个地址
│    2001:db8::2                              │
│    另有 3 个                                │  超过 2 个折叠
│                                            │
│          [   测试 IPv6   ]                  │  检测按钮(probing/disabled 时禁用)
│────────────────────────────────────────────│
│  (条件提示行,见 §8.2)                       │
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
| `probing=true`（有上次结果） | ⟳ 检测中… | 上次：可用 / 不可用 |
| `probing=true`（无上次结果） | ⟳ 检测中… | — |
| 从未检测 | 尚未检测 | 点击下方按钮开始检测 |
| `enabled=false`（有历史） | 检测已关闭 | （标记为历史结果） |
| `enabled=false`（无历史） | 检测已关闭 | 可在配置中重新开启 |

> 区分原则：`TLS_FAILED` / `HTTP_FAILED` / `UNKNOWN` 用"检测失败"（第三方服务可能故障，不等于用户无 IPv6）；`DNS_FAILED` / `CONNECT_FAILED` / `CONNECT_TIMEOUT` 用"未检测到出站"（更可能是用户侧无 IPv6 路径）。

### 8.2 条件提示行（红线：不得暗示检测结果驱动 P2P / 中继路径）

| 场景 | 文案 key 内容 |
|---|---|
| 联机已开 + `AVAILABLE` | IPv6 出站连接可用；公网入站与 P2P 直连能力尚未验证。 |
| 联机已开 + `DNS_FAILED` / `CONNECT_FAILED` / `CONNECT_TIMEOUT` | 未检测到可用的 IPv6 出站连接；联机仍可使用现有中继链路。 |
| 联机已开 + `TLS_FAILED` / `HTTP_FAILED` / `UNKNOWN` | IPv6 检测未完成；可能是检测服务不可达，联机功能不受影响。 |
| 联机未开启 | 此结果仅表示 IPv6 出站能力，不代表公网游戏端口可达。 |

### 8.3 永不出现（UI 红线）

- ~~"可被 IPv6 玩家直连"~~
- ~~"公网可达"~~
- ~~"P2P 将优先尝试"~~ / ~~"将使用中继服务器"~~（暗示检测结果切换网络路径）

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
| 进世界瞬间自动检测 + 立刻手动点测试 | single-flight 复用同一 Future |
| `startHosting` 抛异常 | `hostingState` 从 STARTING 回 STOPPED；面板按钮回到 `[开启联机]` |
| 启动期间调用 `stopHosting` | CAS 将 STARTING→STOPPING；启动成功处 CAS(STARTING→HOSTING) 失败 → 执行清理，不覆盖成 HOSTING |
| `getCurrentRoomId()` 短暂 null（HOSTING 期间） | UI 占位"正在获取…"，不抛异常 |
| 网络切换致 GUA 变化 | probing 完成（`probing true→false` 且 `checkedAt` 变化）时重新收集 GUA |
| Screen resize / 资源重载 | `Init.Post` 重注入不累积按钮（MC widget 每次重建） |

## 11. 测试策略

### 11.1 可单测的纯逻辑（若项目有 `src/test`，建议落地）

利用 `ProbeTransport` + `Clock` 注入构造包私有测试实例，**不依赖公网**：

- `classify(Throwable)`：构造各类异常及 cause-chain（如 `new IOException(new UnknownHostException())`、`new CompletionException(new SSLHandshakeException("t"))`）断言分类正确；禁止构造语义混乱链（如 `HttpTimeoutException` 包 `UnknownHostException`）。
- 缓存判定 `isCacheValid`：边界（刚好 `cacheMinutes`、超时、无 `lastResult`、`cacheMinutes=0`）。
- single-flight：并发发起两个 `checkAsync(false)` 仅调用一次 `transport.send`；`inFlight` 完成后被清空；所有调用方拿到同一 Future 结果。
- 状态机 CAS：`HostingState` 转换合法/非法路径（用单独的 AtomicReference 状态机单测，或对 Manager 做白盒单测）。

### 11.2 手动测试矩阵（客户端实跑）

1. 有 IPv6 网络 → `AVAILABLE` + 耗时显示 + 联机已开时提示"出站可用；公网入站与 P2P 尚未验证"。
2. 禁用网卡 IPv6 → `DNS_FAILED` 或 `CONNECT_FAILED` 或 `CONNECT_TIMEOUT`（取决于系统）+ 文案"未检测到 IPv6 出站"。
3. hosts 屏蔽 test-ipv6.com → **不固定预期**（映射不存在地址→`CONNECT_FAILED`；NXDOMAIN→`DNS_FAILED`；防火墙静默丢包→`CONNECT_TIMEOUT`；HTTPS 拦截→`TLS_FAILED`）；验证点：UI 正确展示实际分类，且不把检测失败宣称为公网不可达。
4. 自动检测：进世界 15 min 内重进不重跑；超 15 min 重跑；`cacheMinutes=0` 每次重跑。
5. ESC 入口：单人世界出现、多人服务器不出现、`hasSingleplayerServer` 守卫生效。
6. 面板四态按钮：开启→停止全程按钮禁用/文案正确；快速连点不重入（CAS 拒绝）。
7. `enabled=false`：按钮禁用 + "检测已关闭" + 历史结果保留。
8. Screen resize：`Init.Post` 重注入不累积按钮（ESC 菜单只有一个"FireflyMC 联机"按钮）。
9. 启动期间点停止：`STARTING`→`STOPPING`→`STOPPED`，不出现 `HOSTING` 闪现。

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

## 13. 实现顺序建议

1. `Ipv6ProbeStatus` / `Ipv6ProbeResult`（纯数据结构）。
2. `Ipv6ConnectivityChecker`（含 `ProbeTransport` / `Clock` / `Ipv6ProbeSnapshot`），先写 `classify` / `isCacheValid` / `checkAsync` 单测逻辑。
3. `SingleplayerRelayManager` 状态机改造（`HostingState` + CAS + getter + `currentRoomId` volatile）。
4. `Config.java` `ipv6_probe` 组 + lang key 基础条目。
5. `SingleplayerRelayControlScreen`（UI + tick/render + GUA 刷新）。
6. `SingleplayerRelayClientEvents` 扩展（`GameMenuScreen` 注入 + `onClientLoggedIn` 触发检测）。
7. lang key 补齐（zh/en）。
8. 手动测试矩阵全过。

## 14. 验收标准

- 单人 ESC 菜单出现"FireflyMC 联机"按钮；多人不出现。
- 面板四态按钮与 `HostingState` 一致；快速连点不重入。
- 有/无 IPv6 两种网络下，IPv6 状态分类与文案正确；检测服务不可达时不误报"无 IPv6"。
- 进世界静默检测一次；缓存有效期内不重跑；手动按钮可强制重测。
- `enabled=false` 时按钮禁用、文案正确、历史结果保留。
- 全程不出现 §8.3 红线文案。
- 构建通过：`.\gradlew.bat build`。
