# FireflyMC 点歌系统设计文档

- **日期**：2026-08-25
- **状态**：设计定稿，待实现
- **音源参考**：`docs/QQ_MUSIC_MINECRAFT_NEOFORGE_GUIDE.md`、`D:\Bot\AstrBot\data\plugins\astrbot_plugin_music`（生产验证的同款接口调用方）

---

## 1. 概述

玩家在游戏内通过 `/点歌 <歌名>` 从网易云音乐检索并播放歌曲。所有在线玩家（含中途加入者）同步收听，客户端 HUD 左侧渲染状态卡片（曲名-歌手、进度条、当前歌词、排队列表）。

支持三种场景，架构上统一为**服务端权威**：

| 场景 | 无限点歌者 | 其余玩家 |
|---|---|---|
| 单人世界 | 玩家本人 | — |
| 多人（LAN） | 房主 | 最多同时 3 首自点歌曲（播完/被跳/失败释放额度） |
| 多人（服务器） | OP（permission ≥ 2） | 同 LAN |

特权判定统一为一行：

```java
boolean privileged =
        server.isSingleplayerOwner(player.getGameProfile())
        || source.hasPermission(2);
```

单人/LAN 的集成服务器天然命中 `isSingleplayerOwner`，专服命中 `hasPermission(2)`，无需分场景特判。

---

## 2. 音源（实测结论）

### 2.1 搜索接口

- `POST https://music.txqq.pro/`，表单参数：`input`（关键词）、`filter=name`、`type=netease`、`page=1`
- 必带 Headers（浏览器伪装）：`User-Agent`、`Accept: application/json, text/javascript, */*; q=0.01`、`Content-Type: application/x-www-form-urlencoded; charset=UTF-8`、`X-Requested-With: XMLHttpRequest`、`Origin/Referer: https://music.txqq.pro`
- 响应：`{"code":200, "data":[{songid,title,author,url,link,pic,lrc}], "error":""}`

**HTTP 超时与有界读取规范**（全部音乐 HTTP I/O 适用，防止第三方接口卡死导致玩家永久 pending）：

```text
connect timeout      10s
搜索请求超时          15s
时长探测超时          10s
搜索响应体上限        2 MiB（超出即放弃并报错）
```

搜索响应用 `ofInputStream()` 有界读取（与时长探测同理，禁 `ofByteArray()` 无界读入）。

### 2.2 平台选型：仅 netease

实测（2026-08-25，本机）：

| 平台 | 搜索质量 | 音频格式 | 结论 |
|---|---|---|---|
| netease | 原版通常排第一 | MP3（CBR 128k 为主） | ✅ 采用 |
| qq | 翻唱优先；付费歌曲搜不到 | M4A/AAC | 弃用（AAC 解码生态弱 + 付费限制） |
| kugou / migu | 返回空 | — | 弃用 |
| kuwo | 直链是网页非音频 | — | 弃用 |

### 2.3 播放地址：延迟解析入口

队列与网络包中**只存 `songId`（纯数字，需校验）**，客户端开始播放时才访问：

```text
https://music.163.com/song/media/outer/url?id={songId}.mp3
```

当场 302 解析到当前可用的 CDN 地址（Java HttpClient 自动跟随重定向）。

> 这是**稳定的延迟解析入口**，不是永久保证：实际可用性受版权、区域、网易服务状态影响。此设计同时避免把有时效的 CDN URL 提前塞进长队列，并因只访问固定网易域名、songId 校验纯数字而消除了任意 URL 访问的 SSRF 风险。

### 2.4 已知限制

- 付费/VIP 歌曲搜不到（API 行为，非 bug）
- 搜索无 `duration` 字段（实测字段仅 `songid/title/author/url/link/pic/lrc`），时长必须自行探测
- 接口为第三方公益接口，可用性不保证；失败路径必须优雅降级（报错消息 + 不锁定玩家）

### 2.5 网络栈策略

**不做强制 IPv4**。项目已有 IPv6 出站检测模块（`Ipv6ConnectivityChecker`），音乐请求默认双栈。不设置 `-Djava.net.preferIPv4Stack=true` 之类的 JVM 全局属性。若未来确认 txqq/netease 在 IPv6 下确定性故障，再为 `MusicApiClient` 单独实现 IPv4 transport。

---

## 3. 服务端设计

### 3.1 并发模型：单线程状态机

`MusicQueueManager` 的全部状态**只在逻辑服务端线程访问**：

```java
private final ArrayDeque<QueuedSong> queue = new ArrayDeque<>();
private final Set<UUID> lockedPlayers = new HashSet<>();
private final Set<UUID> pendingPlayers = new HashSet<>();
private final Set<UUID> musicCapablePlayers = new HashSet<>();  // 见 3.4 quorum 分母
private static final int MAX_QUEUE_SIZE = 50;                   // 队列硬上限（含特权者）
private long queueEpoch = 0L;          // stop 时自增，作废旧异步结果
private CurrentSong currentSong;       // 含 playbackId / startNanoTime / durationMs
private long nextPlaybackId = 1L;      // 服务端生成的单调递增播放实例 ID
```

**特权者"无限点歌"语义**：指**不受一首锁与 pending 限制**，不是允许无限追加队列——特权者同样受 `MAX_QUEUE_SIZE` 硬上限约束（防止 QueueSync payload 无限膨胀）。队列满时点歌直接拒绝并提示。

流程：

```text
命令
 ↓ Server Thread：权限 + locked/pending 检查，pendingPlayers.add
 ↓ Virtual Thread：HTTP 搜索 / 时长探测（捕获 requestEpoch）
 ↓ server.execute(...)
 ↓ Server Thread：epoch 校验 → 入队 / 切歌 / 解锁 / 广播
```

理由：`currentSong + queue + lockedPlayers + 计时`是一个事务状态，逐个 concurrent collection 无法保证整组操作原子；单线程状态机最可靠。虚拟线程只负责 HTTP I/O 并回传结果。

### 3.2 三首锁（普通玩家限流）

```text
点歌成功入队 → activeSongs[player] +1
该玩家的歌到达任一终态 → activeSongs[player] -1（减到 0 移除）
activeSongs[player] >= 3 → 拒绝点歌（LOCKED）
```

终态定义：

| 终态 | 触发 |
|---|---|
| `FINISHED` | 服务端权威计时到达（`duration + 2s`） |
| `SKIPPED` | 特权者 `/skip` |
| `CANCELLED` | `/stop` 全清 |
| `FAILED` | 失败聚合条件满足（见 3.4） |

- **掉线不解锁**（防"点歌→退服→重进→再点"绕过）
- **搜索失败不锁定**（未成功入队）
- **`pendingPlayers` 防并发竞态**：命令检查通过后先加入 pending，HTTP 返回回服务端线程后再移除并转入 active，防止连续多次 `/点歌` 在写入前双双通过检查
- 特权者跳过 active/pending 检查

### 3.3 服务端权威切歌计时

```java
long elapsedMs = (System.nanoTime() - currentSong.startNanoTime) / 1_000_000L;
boolean songOver = elapsedMs >= currentSong.durationMs + 2_000L;
```

- 用 `System.nanoTime()` 计算相对时长（不受系统时间校准影响），**不得**把原始 nanoTime 发给客户端
- 到点后：当前曲 `FINISHED`（解锁点歌者）→ 队列 poll 下一首广播 `MusicStartPayload`；队列空则广播 `MusicStopPayload`
- 收到新 `MusicStartPayload` 的客户端**无条件** stop 旧曲再 start 新曲——客户端跟服务端队列走，不迁就最慢客户端
- 2s 容差兜底客户端下载/启动慢于服务端计时；个别极慢客户端宁可被截断也不拖慢全服

### 3.4 客户端失败上报：信号而非权威

单客户端失败（如局部网络问题）不得中断其他正常收听的玩家。

```text
单人世界：唯一客户端真实下载/解码失败 → 立即 FAILED

多人：
  单客户端失败 → 仅记录（Set<UUID> failedClients，同一玩家同一 playbackId 去重）
  ≥2 个不同客户端 且 失败数 ≥ quorum 分母的 50% → 提前 FAILED
  否则 → 服务端权威计时正常运行（歌照常结束）
```

**quorum 分母**：`当前在线玩家 ∩ musicCapablePlayers`。注意不能直接复用现有握手集合——当前 `ModPayloadHandler.handleHandshakeReply()` 对 `serverPlayer.server.isSingleplayer()` 直接 return，`VERIFIED_PLAYERS` 不覆盖 LAN 客户端。因此**修改现有握手记录逻辑**：FireflyMC 握手完成（无论 dedicated / integrated/LAN）即记入音乐模块自己的 `Set<UUID> musicCapablePlayers`（不与 `VERIFIED_PLAYERS` 混用，语义不同）；玩家离线时移除，避免残留。

FAILED 终态同样解锁点歌者并切下一首。协议用**服务端生成的 `playbackId`**（而非 songId）关联上报，防止同一首歌被连续点两次时，旧播放实例迟到的 FAILED 误伤新实例。`failureCode` 为受限枚举（`HTTP_FAILED` / `SOURCE_UNAVAILABLE` / `STREAM_INTERRUPTED` / `MP3_DECODE_FAILED`），不接受客户端任意字符串；`NO_JAVASOUND_DEVICE`、`CACHE_WRITE_FAILED` 等**本地降级不构成全局失败**，不上报。

### 3.5 时长探测（入队时，服务端）

Range 请求只拉音频头部（~64KB）解析：

```text
API duration 字段
 ↓ 无（当前 netease 源即无）
Xing / Info 头（有总帧数 → duration = frames × samplesPerFrame / sampleRate）
 ↓
VBRI 头
 ↓
首帧 bitrate + 文件总大小 CBR 估算
 ↓
fallback：保守默认 240s（到期仍正常切歌）
```

**HTTP 细节**：

- `206` 响应时**总大小取 `Content-Range` 的 total**，不得误用 `Content-Length`（后者是分片长度）
- `200`（不支持 Range）时才用 `Content-Length`
- 请求带 `Accept-Encoding: identity`，避免内容编码干扰字节长度语义
- **必须 `BodyHandlers.ofInputStream()` 有界读取**：最多 read 64 KiB 即 `close()`——**严禁 `BodyHandlers.ofByteArray()`**，否则对忽略 Range 头的服务器，"64KB 探测"实际会把完整 MP3 拉进服务端内存
- 探测失败不阻塞入队（用 fallback 时长），仅记日志

### 3.6 skip / stop 语义

```text
/fireflymc music skip（特权）
  当前曲 → SKIPPED（解锁其点歌者）
  有下一首 → 广播 MusicStartPayload(next)
  无下一首 → 广播 MusicStopPayload(SKIPPED)
  不影响队列其他歌曲、不影响进行中的搜索请求

/fireflymc music stop（特权）
  当前曲 + 队列全部 → CANCELLED（逐一解锁点歌者）
  pendingPlayers 清空
  queueEpoch++
  广播 MusicStopPayload(QUEUE_CLEARED) + MusicQueueSyncPayload(空)
```

**epoch 机制**：每个异步点歌任务启动时捕获 `requestEpoch = queueEpoch`；HTTP 返回后若 `requestEpoch != queueEpoch`（期间发生过 stop），结果直接丢弃，防止"stop 清空后旧搜索结果死灰复燃"。

---

## 4. 网络协议（4 个 payload，沿用 `ModNetwork` 集中注册）

现有客户端 handler 反射隔离 + `context.enqueueWork(...)` 模式全部沿用。**`NETWORK_VERSION` 从 `1.0.0` 升级为 `1.1.0`**。

### MusicStartPayload（S→C 广播）

```text
long   playbackId      服务端生成的播放实例 ID（单调递增）
String songId          纯数字，已校验
String title
String author
String lrc             LRC 全文，上限 256 KiB UTF-8
String requesterName   上限 64 字符
long   durationMs      服务端探测结果（HUD 权威总时长）
long   positionMs      普通开始 = 0；中途加入 = 已换算的 elapsed ms
```

时机：新曲开始（广播全员）；玩家登录时对当前曲单独发送（中途加入跟上进度，客户端解码快进丢弃 `positionMs` 之前的 PCM——MP3 解码远快于实时，可接受；第一版不做 Range byte seek）。

`title`/`author` 设上限（如 128/128 字符），远端 API 异常大字符串不入包。

### MusicQueueSyncPayload（S→C 广播）

当前曲概要（title/author/requester）+ 队列项列表（title/author/requester）。不含 url/lrc。队列任何变化后广播，登录时也发送。

### MusicStopPayload（S→C 广播）

```java
enum MusicStopReason {
    FINISHED,       // 自然播放结束且队列空
    SKIPPED,        // 特权者跳过且队列空
    FAILED,         // 失败聚合达成且队列空
    QUEUE_CLEARED   // /stop 全清
}

long playbackId    // = 0 表示"无活动播放实例"（如 /stop 时根本没有 currentSong，只有 pending）
MusicStopReason reason
```

### MusicPlaybackFailedPayload（C→S）

```text
long playbackId        服务端校验 == 当前 playbackId 才受理
enum failureCode：HTTP_FAILED | SOURCE_UNAVAILABLE | STREAM_INTERRUPTED | MP3_DECODE_FAILED
```

服务端按 3.4 的聚合规则处理。

---

## 5. 客户端播放器

### 5.1 技术决策（定稿）

> **JLayer 流式解码 MP3 → PCM → JavaSound SourceDataLine 播放，客户端 Tick 同步 Minecraft MASTER×MUSIC 音量经 Atomic 桥接，完全绕开 Minecraft OpenAL SoundEngine。**

- 完全不接触 MC OpenAL context / SoundEngine / RenderSystem
- 不承诺"绝无崩溃风险"：JavaSound 自身的 `LineUnavailableException`、无 Mixer、Linux 音频环境异常、设备热拔等故障被隔离在独立播放模块，可安全降级为静音模式
- JLayer 1.0.1（Maven Central，143,624 bytes，LGPL），以 jarJar 嵌套 jar 分发，不 shade 不 relocate

### 5.2 播放管线（边下边播）

```text
MusicStartPayload（主线程 enqueueWork）
  → MusicPlaybackManager.start(song, positionMs)
      → 无条件 stop 旧播放（完整关闭序列，见下）
      → 缓存查 music-cache/{songId}.mp3
          命中 → FileInputStream 直接播（并 touch lastModified）
          未命中 → HTTPS outer url 302 → CDN 流式下载
      → 新守护线程：
          InputStream（Buffered）
            ├─ Tee：同时写 music-cache/{songId}.{playbackId}.mp3.part（缓存分支失败仅降级不缓存）
            ↓
          JLayer Bitstream → Decoder → 16bit PCM
            ├─ positionMs 之前的 PCM 直接 discard（中途加入快进）
            ├─ 读 AtomicReference<Float>（音量，见 5.3）乘样本
            ↓
          SourceDataLine.write()（阻塞式背压）
```

**stop() 完整关闭序列**（`interrupt + close line` 不够——网络线程可能正阻塞在 `InputStream.read()`，interrupt 不保证解除）：

```text
invalidate 本地 playbackId/session
→ close HTTP 连接 / InputStream（解除 read 阻塞）
→ close JLayer Bitstream
→ close SourceDataLine
→ 删除自己的 .part
→ interrupt worker
```

旧 worker 后续任何状态写回 / FAILED 上报，都先验证自己仍是当前 `playbackId` 才生效。`.part` 命名含 `playbackId`，保证连续两次播放同一 songId 时新旧实例不争抢同一临时文件。

### 5.3 音量桥（播放线程不碰 Minecraft 对象）

```text
Minecraft Client Tick（读 options.getSoundSourceVolume(SoundSource.MASTER)
                        × options.getSoundSourceVolume(SoundSource.MUSIC)）
        ↓
AtomicReference<Float> effectiveVolume
        ↓
JLayer 播放线程读取纯数值，乘到 PCM 样本
```

MC"主音量 × 音乐"滑条直接生效；音频线程完全不接触 Minecraft 状态。

### 5.4 播放时钟（PlaybackClock 接口，双实现）

| 实现 | 场景 | 进度真相源 |
|---|---|---|
| `JavaSoundPlaybackClock` | 正常 | `basePositionMs + line.getLongFramePosition() * 1000L / sampleRate` |
| `SilentPlaybackClock` | 无 Mixer 静音降级 | `basePositionMs + (System.nanoTime() - localStartNano) / 1_000_000L` |

**`basePositionMs` 即 payload 中的 `positionMs`**——中途加入时 line 刚启动 `getLongFramePosition()` 为 0，没有基准偏移 HUD 会错误地从 `0:00` 开始。普通开始时 `basePositionMs = 0`。

最终统一：

```text
HUD position truth  = PlaybackClock（含 base 偏移）
decodedFrames / writtenFrames = diagnostics only（writtenFrames 领先真实扬声器位置，仅诊断用）
```

`getMicrosecondPosition()` 仅作便利接口，不作状态真相源。

**静音模式不得继续跑解码循环**——没有 `write()` 的阻塞背压，4 分钟的歌几秒内就"播完"，HUD 会瞬间跑完且与服务端计时脱节。静音时保持 metadata/HUD 状态由 monotonic clock 维持进度，收到下一个 Start/Stop/disconnect 才结束。

### 5.5 单客户端播放失败、quorum 未达时的本地行为

玩家 A 访问网易失败而 B/C 正常听歌时，A 不得 HUD 消失或卡死：

```text
本地真实播放失败（HTTP_FAILED / MP3_DECODE_FAILED）
→ 上报 FAILED signal 一次（去重）
→ 删除自己的 .part
→ 本客户端切换 SilentPlaybackClock（复用静音模式）
→ HUD/歌词继续按权威时钟走
→ 等待服务端下一次 Start / Stop
```

### 5.6 客户端时长

- **HUD 总时长以 `MusicStartPayload.durationMs` 为权威**（与服务端切歌时钟一致，避免"进度条走完但歌没切"）
- 客户端本地探测（Xing/VBRI → CBR 估算）保留作 fallback 与自检调试

### 5.7 缓存（MusicCache）

- 目录：运行目录 `music-cache/`，最终文件名 `{songId}.mp3`（songId 纯数字，无路径注入）；临时文件名 `{songId}.{playbackId}.mp3.part`（含 playbackId，新旧播放实例不争抢）
- 写入：`.part` 临时文件 → 完整 EOF 且本次播放成功 → rename。核心不变量：**永远不把未完成文件命名成 `{songId}.mp3`**：

```java
try {
    Files.move(part, target, ATOMIC_MOVE, REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException e) {
    Files.move(part, target, REPLACE_EXISTING);   // 同目录 fallback，风险很低
}
```

- 上限：`MAX_CACHE_BYTES = 256L * 1024 * 1024`（常量，第一版不做配置项）。每次成功写入后惰性清理
- 近似 LRU：**cache hit 时 `Files.setLastModifiedTime(...)` touch 一次**（否则退化为最老写入优先删除）；清理按 lastModified 删最旧
- 缓存读写失败一律静默降级（不缓存/不命中），绝不影响播放主链路

### 5.8 歌词（LrcParser）

- 解析 `[mm:ss.xx]` 时间标签（支持同一行多个时间戳），构建 `TreeMap<Long, String>`
- HUD 当前行 = `floorEntry(positionMs)`
- 无效行跳过；空/无歌词时 HUD 歌词行整体不渲染
- 纯客户端组件，与服务端无关

---

## 6. HUD 卡片（MusicHudRenderer）

挂 `RenderGuiEvent.Post`（沿用现有客户端事件注册模式），读取 `MusicPlaybackState` + `MusicQueueSyncPayload` 缓存。

```text
┌────────────────────────────────┐
│ ♪ 晴天 - 周杰伦                 │
│ ●━━━━━━━━░░░░  2:31 / 4:37     │
│   吹着前奏望着天空              │
│ ── 排队 (5) ──                  │
│ 1. 稻香 - 周杰伦 · Firefly      │
│ 2. 孤勇者 - 陈奕迅 · Alice      │
│ 3. 夜曲 - 周杰伦 · Bob          │
│       还有 2 首                  │
└────────────────────────────────┘
```

- **位置**：屏幕左侧，与现有信息 HUD 组成**纵向 stack**（音乐卡片在上、间隔 4px、现有 server HUD 在下），整体垂直居中——现有 HUDRenderer 是 `x=5` 垂直居中布局，不是顶部；小分辨率/高 HUD_SCALE 下不会越界
- **stack 实现机制**：现有 `HUDRenderer` 自行内部计算垂直居中且 `drawRoundedBorder()` 为 private，需小幅重构共享布局——`ClientHandler.onRenderGui` 统一先算 `Music HUD 高度 + 4px + Server HUD 高度`，整体垂直居中后再分别调 `MusicHudRenderer.renderAt(...)` / `HUDRenderer.renderAt(...)`；圆角绘制抽公共 `HudRenderUtil.drawRoundedBorder(...)` 供两个渲染器共用（HUDRenderer 从 private 改为委托公共工具）
- **显示点歌者名**（灰色 suffix `歌名 - 歌手 · 点歌者`）；超宽截断优先级：**歌名 > 歌手 > 点歌者**（requester 最先被截断）
- **最多显示 3 个排队项**，其余显示"还有 N 首"（完整队列用 `/fireflymc music queue`）
- 复用现有能力：`drawRoundedBorder` 圆角、`HUD_SCALE` 缩放、`hideGui`（F1）隐藏、动态宽度按文本测量、超宽跑马灯
- 歌词行直接切换，不做动画（YAGNI）
- 无歌时整个卡片不渲染
- 进度条：`fill` 双色（已播/未播），时间 `mm:ss / mm:ss`，总时长用服务端 `durationMs`

---

## 7. 命令

```text
/点歌 <歌名...>                       点歌（中文 literal）
/fireflymc music request <歌名...>    同上（英文路径）
/fireflymc music queue                聊天栏输出当前曲+完整队列
/fireflymc music skip                 跳过当前曲（特权）
/fireflymc music stop                 停止并清空（特权，含 pending + epoch）
```

- `<歌名...>` 用 `StringArgumentType.greedyString()`，长度上限 256 字符（超出拒绝）
- **`MusicCommandHandler` 不加 `Dist.DEDICATED_SERVER` 限制**（现有 `SpawnAllCommandHandler` 的值限定模式不适用于本功能——音乐系统必须在单人 integrated server / LAN / 专服全部工作）
- 点歌成功 → 全服播报（含点歌者名）；被锁拒绝 → 仅本人提示
- 新消息一律 `Component.translatable` + `zh_cn.json` / `en_us.json`（新模块规范，不用 `§` literal）

---

## 8. 构建与依赖

```gradle
dependencies {
    jarJar(implementation("javazoom:jlayer:1.0.1")) {
        version {
            strictly "[1.0.1]"
            prefer "1.0.1"
        }
    }
    additionalRuntimeClasspath "javazoom:jlayer:1.0.1"
}
```

- ModDevGradle 下 Jar-in-Jar 库需同时进 `additionalRuntimeClasspath`，否则开发运行环境 `ClassNotFoundException`
- 发布物保留 JLayer 的实际 LGPL 许可证文本与归属说明（NOTICE），保持独立嵌套 jar，不 shade 不 relocate；发布前核对第三方许可清单。**不在设计文档中做"必然完全合规"的法律结论**

## 9. 文件规划

```
fireflymc/music/                          服务端+共享
├── MusicCommandHandler.java              命令（无 Dist 限制）
├── MusicQueueManager.java                队列状态机（Server Thread only + epoch + 失败聚合）
├── MusicApiClient.java                   txqq 搜索客户端（虚拟线程调用）
├── Mp3DurationProbe.java                 Range 头部探测时长（Xing/VBRI/CBR）
└── QueuedSong.java                       队列项
fireflymc/network/                        4 个音乐 Payload（跟随现有集中注册）
├── MusicStartPayload.java
├── MusicQueueSyncPayload.java
├── MusicStopPayload.java
└── MusicPlaybackFailedPayload.java
fireflymc/client/music/                   客户端
├── MusicPlaybackManager.java             生命周期/状态权威/失败上报
├── MusicPlayer.java                      播放线程 decode loop
├── JavaSoundOutput.java                  SourceDataLine 封装
├── PlaybackClock.java                    接口 + JavaSound/Silent 双实现（含 basePositionMs 偏移）
├── MusicCache.java                       .part/rename/LRU/touch
├── LrcParser.java                        LRC 解析
├── MusicPlaybackState.java               HUD 读取的播放状态（当前曲/时钟/歌词行/队列缓存）
└── MusicHudRenderer.java                 HUD 卡片
fireflymc/client/                         客户端共享工具
└── HudRenderUtil.java                    圆角边框等共享绘制工具（HUDRenderer 抽出）
```

修改点：`FireflyMCMod`（客户端事件挂钩：HUD、Client Tick 音量桥、logout 生命周期）、`ClientHandler`（onRenderGui 统一纵向 stack 布局）、`HUDRenderer`（垂直居中改为 renderAt 接受外部 y，圆角委托 HudRenderUtil）、`ModNetwork`（4 payload + `NETWORK_VERSION 1.1.0`）、`ModPayloadHandler`（握手记录 musicCapablePlayers，不再对 integrated server 直接 return——仅音乐 capability 记录部分）、`ClientPayloadHandler`（分发音乐包）、`build.gradle`、lang 文件。

**不新增 Mixin**——客户端事件直接挂 NeoForge Event Bus，沿用现有架构。

---

## 10. 测试

**纯逻辑 JUnit 5 自动测试**（项目已有 junit-jupiter 5.10.2 基建，参照现有 `Ipv6ConnectivityCheckerTest`）：

| 测试类 | 覆盖 |
|---|---|
| `LrcParserTest` | 标准 `[mm:ss.xx]`；同行多时间戳；无效行；空歌词；`floorEntry` 边界 |
| `Mp3DurationProbeTest` | Xing；VBRI；CBR + Content-Range total；206 的 Content-Length 不误当总长度；malformed header；fallback |
| `MusicCacheTest` | `.part` 不算命中；成功 rename；ATOMIC_MOVE fallback；超 256MB 清理；cache hit touch；LRU 删除顺序；`{songId}.{playbackId}.part` 新旧实例隔离 |
| `MusicQueueManagerTest` | 一首锁；pending 防双击；四终态解锁；logout 不解锁；epoch 丢弃 stop 前结果；同 songId 不同 playbackId；MAX_QUEUE_SIZE 超限拒绝（含特权者）；quorum 分母 = 在线 ∩ musicCapablePlayers |
| `PlaybackClockTest` | basePositionMs 偏移（普通开始 0 / 中途加入非 0）；Silent 时钟单调推进 |

**Minecraft/JavaSound 集成手动矩阵**：

| 场景 | 验证 |
|---|---|
| 单人 runClient | 点歌即播、HUD 卡片、进度走动、歌词同步、连点无限、缓存命中秒播、F1 隐藏、MASTER×MUSIC 滑条联动 |
| LAN 双账号 | 房主无限；访客一首锁（拒绝+提示）；访客歌终态解锁；中途加入跟上进度（discard 快进） |
| runServer | OP 无限；普通玩家锁；skip/stop 普通玩家被拒；stop 清队列含 pending |
| 异常 | 断网点歌报错不锁；静音降级（模拟无 Mixer）走完正常时长；半截缓存不命中（.part 清理） |

---

## 11. 风险与已知限制

| 风险 | 缓解 |
|---|---|
| 第三方公益接口不可用 | 失败路径优雅降级：报错消息 + 不锁定玩家；接口层抽象便于未来换源 |
| 付费歌曲搜不到 | API 行为，提示用户"未找到"即可 |
| JavaSound 无输出设备 | SilentPlaybackClock 静音降级，HUD 照常 |
| 部分网络 IPv6 异常 | 默认双栈；确认确定性故障后单独为 MusicApiClient 做 IPv4 transport，不动 JVM 全局 |
| 网易 CDN 链接时效 | songId + 延迟解析入口设计规避 |
| LGPL 分发义务 | 独立 jarJar 嵌套，不 shade/relocate；随发布物保留 JLayer LGPL 许可证文本及归属说明；发布前检查第三方许可清单 |
