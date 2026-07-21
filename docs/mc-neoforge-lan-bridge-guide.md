# Minecraft 1.21.1 / NeoForge 21.1.241 — LAN 桥接 Mod 开发指南

> 整理日期: 2026-05-02 | 目标: 单人世界通过 WebSocket 中继桥接远程玩家

---

## 一、IntegratedServer LAN 发布

### 1.1 openToLAN 调用

`IntegratedServer` (Mojang mapped) 的 "对局域网开放" 方法：

```java
// net.minecraft.server.integrated.IntegratedServer
public void openToLAN(GameType gameType, boolean allowCommands)
```

- `gameType` — `GameType.SURVIVAL` / `GameType.CREATIVE` / `GameType.ADVENTURE` / `SPECTATOR`
- `allowCommands` — 是否允许作弊命令
- 返回类型: `void`

原版调用链 (按键触发 ESC → "对局域网开放" 按钮):
1. `OpenToLANScreen` → 用户选择参数
2. 调用 `Minecraft#setScreen(null)` 退出菜单
3. 内部调用 `this.singleplayerServer.openToLAN(selectedGameType, commandsAllowed)`
4. `IntegratedServer` 内部:
   - 设置 `this.setGameType(gameType)` 和 `this.setCommandsAllowed(allowCommands)`
   - 调用 `this.getConnection().startTcpServerListener(null)` — **`null` 表示绑定所有接口**
   - 通过 `LanServerPinger` 向局域网广播自身

### 1.2 获取 LAN 端口

LAN 端口在 `openToLAN` 调用后由系统自动分配。获取方式:

```java
IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
if (server != null) {
    // 方式1: 通过 ServerConnectionListener (ChannelHandler)
    ServerConnectionListener listener = server.getConnection();
    // listener 内部持有 TcpServerChannel
    // 端口存储在 listener 的 ChannelGroup 中

    // 方式2: 反射/AccessTransformer 获取 (推荐)
    // ServerConnectionListener 的字段:
    //   private final List<Channel> channels = Collections.synchronizedList(Lists.newArrayList());
    //   Channel 的 localAddress() 就是绑定的端口

    // 方式3: 使用 AT (AccessTransformer) 暴露字段
    // 见下方字段映射
}
```

**关键类映射:**

| Obfuscated (SRG) | Mojang Mapped | 说明 |
|---|---|---|
| `net.minecraft.server.network.ServerConnectionListener` | 同左 | 管理 TCP 连接 |
| `ServerConnectionListener#channels` (field) | `channels` | `List<Channel>` 已建立的连接 |
| `ServerConnectionListener#startTcpServerListener` | 同左 | 启动 TCP 监听, 签名 `(InetAddress)` |
| 底层 Channel 的 `localAddress()` | — | 返回 `SocketAddress`, 可取端口 |

**AT 声明示例 (accesstransformer.cfg):**
```cfg
public-f net.minecraft.server.network.ServerConnectionListener channels # channels
public net.minecraft.server.network.ServerConnectionListener startTcpServerListener(Ljava/net/InetAddress;)V # startTcpServerListener
```

### 1.3 代码中调用 openToLAN

```java
// 必须在渲染/主线程执行
Minecraft mc = Minecraft.getInstance();
IntegratedServer server = mc.getSingleplayerServer();
if (server != null && !server.isPublished()) {
    server.openToLAN(GameType.SURVIVAL, true);
    server.setPublished(true);  // 标记为已发布

    // 获取端口
    ServerConnectionListener connection = server.getConnection();
    // 需要通过 AT 或反射读取 connection 的 channels 列表
    // 新绑定的 listener channel 的 localAddress 包含端口号
}
```

---

## 二、NeoForge 客户端生命周期事件

### 2.1 进入世界相关事件

| 事件 | Bus | 说明 |
|---|---|---|
| `ClientPlayerNetworkEvent.LoggingIn` | Game (NeoForge) | 客户端登录到服务器/集成服务器 (含单人) |
| `ClientPlayerNetworkEvent.LoggedIn` | Game (NeoForge) | 登录完成, player 对象可用 |
| `PlayerEvent.PlayerLoggedInEvent` | Game (NeoForge) | 服务器端也触发; 客户端收到时表示进入世界 |
| `ClientTickEvent.Post` | Game (NeoForge) | 每帧客户端 tick, 可用于检测状态变化 |

**注意:** `PlayerLoggedInEvent` 在逻辑上分为服务端和客户端触发。在集成服务器模式下, 两端都在同一进程:
- **客户端逻辑** → 监听 `ClientPlayerNetworkEvent.LoggedIn`
- **服务端逻辑** → 监听 `PlayerEvent.PlayerLoggedInEvent`

### 2.2 退出世界 / 返回标题界面事件

| 事件 | Bus | 说明 |
|---|---|---|
| `ClientPlayerNetworkEvent.LoggingOut` | Game (NeoForge) | 客户端开始登出, player/connection 可能已为 null |
| `ClientPlayerNetworkEvent.LoggedOut` | Game (NeoForge) | 客户端完全登出, 回到标题画面 |
| `PlayerEvent.PlayerLoggedOutEvent` | Game (NeoForge) | 服务端触发, 玩家断开 |
| `ScreenEvent.Opening` | Game (NeoForge) | 屏幕即将打开 (可拦截) |
| `ScreenEvent.Opened` | Game (NeoForge) | 屏幕已打开 |
| `ScreenEvent.Closing` | Game (NeoForge) | 屏幕即将关闭 |

**`LoggingOut` 特殊行为:**
- 创建新的集成服务器(单人世界)或连接新服务器时也会触发
- 构造参数: `(@Nullable MultiPlayerGameMode controller, @Nullable LocalPlayer player, @Nullable Connection networkManager)`
- 当返回标题界面时, player 和 controller 可能为 null

### 2.3 Client-Only 注册方式

推荐使用 `@EventBusSubscriber` 注解:

```java
@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 客户端 tick 逻辑
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggedIn event) {
        // 客户端登录完成
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggedOut event) {
        // 客户端登出, 清理资源
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof JoinMultiplayerScreen screen) {
            // 在多人列表界面初始化时注入自定义按钮等
        }
    }
}
```

**替代方式 — 手动注册 (Mod 构造函数中):**

```java
public MyMod(IEventBus modBus) {
    NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
    NeoForge.EVENT_BUS.addListener(ClientEvents::onPlayerLoggedIn);
    // ...
}
```

---

## 三、原版多人游戏服务器列表 UI

### 3.1 关键类名 (1.21.1 Mojang 映射)

| 类名 | 包路径 | 说明 |
|---|---|---|
| `JoinMultiplayerScreen` | `net.minecraft.client.gui.screens.multiplayer` | 多人游戏服务器列表主界面 |
| `ServerSelectionList` | `net.minecraft.client.gui.screens.multiplayer` | 服务器列表控件 (ObjectSelectionList) |
| `ServerData` | `net.minecraft.client.multiplayer` | 单个服务器条目数据模型 |
| `ServerList` | `net.minecraft.client.multiplayer` | 服务器列表持久化存储 (读写 servers.dat) |
| `ConnectScreen` | `net.minecraft.client.gui.screens.multiplayer` | 连接服务器的过渡画面 |

### 3.2 ServerData 关键字段

```java
public class ServerData {
    public String name;           // 服务器显示名称
    public String ip;             // 地址:端口
    public ServerData.ServerResourceMode resourceMode;  // 资源包模式
    public boolean hidden;        // 是否在列表中隐藏
    public String icon;           // Base64 编码的服务器图标 (64x64 PNG)
    public ServerData.Type type;  // NORMAL / LAN / REALM

    public enum Type {
        NORMAL,    // 正常服务器
        LAN,       // LAN 发现的服务器
        REALM      // Realm
    }
}
```

### 3.3 ServerList 关键方法

```java
public class ServerList {
    public ServerData get(int index);           // 按索引获取
    public ServerData get(String ip);           // 按地址获取
    public void add(ServerData data);           // 添加服务器
    public void remove(ServerData data);        // 移除服务器
    public void replace(int index, ServerData data);  // 替换
    public void load();                         // 从 servers.dat 加载
    public void save();                         // 保存到 servers.dat
    // ...
}
```

### 3.4 扩展多人列表 — NeoForge 事件 vs Mixin

**NeoForge 事件方式 (推荐首选):**

`ScreenEvent.Init.Post` 可以在 `JoinMultiplayerScreen` 初始化完成后注入自定义元素:
```java
@SubscribeEvent
public static void onScreenInit(ScreenEvent.Init.Post event) {
    if (event.getScreen() instanceof JoinMultiplayerScreen screen) {
        // 可以在这里:
        // 1. 在屏幕上添加自定义按钮 (event.getScreen().addRenderableWidget(...))
        // 2. 访问 screen 的服务器列表字段
    }
}
```

**Mixin 注入点 (更底层的控制):**

当需要修改 `ServerSelectionList` 的渲染/点击行为时, 需要 Mixin:

```java
// 注入 ServerSelectionList 的行渲染
@Mixin(ServerSelectionList.class)
public abstract class MixinServerSelectionList extends ObjectSelectionList<ServerSelectionList.Entry> {

    // 注入点1: 在列表中添加自定义行
    @Inject(method = "getRowWidth", at = @At("RETURN"), cancellable = true)
    private void onGetRowWidth(CallbackInfoReturnable<Integer> cir) { }

    // 注入点2: 覆盖列表行数
    @Inject(method = "getItemCount", at = @At("RETURN"), cancellable = true)
    private void onGetItemCount(CallbackInfoReturnable<Integer> cir) { }

    // 注入点3: 自定义行的渲染和点击
    @Inject(method = "renderList", at = @At("TAIL"))
    private void onRenderList(DrawContext context, int mouseX, int mouseY, float partialTick, CallbackInfo ci) { }
}
```

```java
// 注入 JoinMultiplayerScreen 的连接流程
@Mixin(JoinMultiplayerScreen.class)
public class MixinJoinMultiplayerScreen {

    // 拦截 "加入服务器" 按钮点击
    @Inject(method = "joinSelectedServer", at = @At("HEAD"), cancellable = true)
    private void onJoinSelected(CallbackInfo ci) {
        // 可以自定义连接逻辑或拦截
    }
}
```

---

## 四、客户端连接服务器流程

### 4.1 原版连接流程

```
用户点击 "加入服务器"
    → JoinMultiplayerScreen.joinSelectedServer()
        → ServerData 验证地址格式
        → ConnectScreen.startConnecting(parentScreen, minecraft, ServerAddress, ServerData)
            → 创建 ClientHandshakePacketListener / ClientIntentionPacket
            → 建立 TCP 连接到目标地址
            → 完成握手 → 配置阶段 → 登录阶段 → 游戏阶段
```

### 4.2 代码触发连接

```java
// 方式1: 通过 ConnectScreen (推荐, 有完整的 UI 和错误处理)
ServerAddress address = ServerAddress.parse("127.0.0.1:25565");
ServerData serverData = new ServerData("LAN Bridge", "127.0.0.1:25565", ServerData.Type.NORMAL);

ConnectScreen.startConnecting(
    Minecraft.getInstance().screen,  // 当前屏幕 (作为父屏幕)
    Minecraft.getInstance(),          // Minecraft 实例
    address,                         // 服务器地址
    serverData                       // 服务器数据 (可为 null)
);
```

```java
// 方式2: 直接操作 (无 UI 过渡, 适合静默连接)
Minecraft mc = Minecraft.getInstance();
ServerAddress address = ServerAddress.parse("127.0.0.1:" + lanPort);

// ConnectScreen.startConnecting 内部会创建 Connection 并发起握手
// 需要确保在渲染线程调用
mc.execute(() -> {
    ConnectScreen.startConnecting(
        mc.screen,
        mc,
        address,
        null  // ServerData 可为 null
    );
});
```

### 4.3 连接本地 127.0.0.1:<port>

**安全:** 完全可行。集成服务器默认绑定 `0.0.0.0:<随机端口>`, 本机 `127.0.0.1` 连接不会经过外部网络。

**注意事项:**
- 如果玩家本机已经在这个集成服务器里 (单人世界的主人), **不能第二次连接** — 一个 Minecraft 实例只能有一个活动连接
- 这个连接场景是给 **远程玩家通过 WebSocket 中继连接到本机 LAN 端口** 使用的
- 远程客户端 (运行你 Mod 的另一个 Minecraft 实例) 可以通过 `ConnectScreen.startConnecting` 连接到 `127.0.0.1:<中继本地端口>` — 这里需要一个 WebSocket→TCP 的桥接

---

## 五、WebSocket 二进制帧中继设计

### 5.1 Java 21 `java.net.http.WebSocket` 实践

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

public class McPacketRelay {

    private WebSocket ws;
    private final ByteBuffer accumulator = ByteBuffer.allocate(1 << 20); // 1MB

    public void connect(String serverUrl) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        ws = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(serverUrl), new WebSocketListener())
            .join();
    }

    private class WebSocketListener implements WebSocket.Listener {

        // --- 二进制帧接收 ---
        // 注意: 大消息会被分片 (last=false), 需要手动累积
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // 将分片数据写入 accumulator
            accumulator.put(data);
            if (last) {
                accumulator.flip();
                byte[] fullPacket = new byte[accumulator.remaining()];
                accumulator.get(fullPacket);
                accumulator.clear();

                // 完整的 Minecraft 数据包 — 转发到本地 LAN 端口
                forwardToLocalServer(fullPacket);
            }
            // 返回 null 表示可以继续接收
            // 如果需要背压控制, 返回未完成的 CompletableFuture 来暂停接收
            return null;
        }

        // --- 心跳 (Ping) ---
        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // 自动回复 Pong (JDK WebSocket 默认行为)
            // 无需手动处理, 除非需要自定义
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            error.printStackTrace();
        }
    }

    // --- 发送二进制数据 ---
    public void sendPacket(byte[] packet) {
        if (ws != null && !ws.isInputClosed() && !ws.isOutputClosed()) {
            // 对于大的 Minecraft 数据包 (>125 bytes), JDK 会自动分片
            // 默认分片大小等于发送缓冲区大小
            ws.sendBinary(ByteBuffer.wrap(packet), true);
        }
    }

    // --- 背压控制 ---
    // 方式1: 使用 request(n) 控制接收速率
    // 初始 request(1), 处理完后再次 request(1)
    // 方式2: 在 onBinary 中返回未完成的 CompletableFuture
    //   当本地 TCP 发送缓冲区满时, 返回 pending 的 Future
    //   处理完成后再 complete 该 Future

    // --- 断线重连 ---
    public void reconnect() {
        connect("wss://fk.firefly520.top/relay");
    }
}
```

### 5.2 分片与背压要点

**分片 (Fragmentation):**
- WebSocket 协议允许消息被分成多个帧
- `onBinary` 的 `last` 参数指示是否为最后一帧
- JDK `WebSocket.sendBinary(buffer, last)` — 设置 `last=true` 标记消息结束
- Minecraft 数据包通常 < 2MB, 一般不需要发送端分片
- 但接收端必须处理 `last=false` 的分片情况 (某些代理/中间件可能分片)

**背压 (Backpressure):**
- `WebSocket.Listener` 通过 `request(long n)` 控制接收速率
- JDK 默认在创建时 `request(1)`, 每次 receive 方法返回的 `CompletionStage` 完成后再 `request(1)`
- **隐式背压:** 从 `onBinary` 返回未完成的 `CompletionStage` 会暂停后续消息的接收
- **实践建议:**
  ```
  接收 → 累积分片 → 完整包 → 写入本地 TCP
                                    ↓
                          如果 TCP 缓冲区满 → 阻塞 → 返回未完成 Future (暂停 WS 接收)
                          如果 TCP 缓冲区有空 → 立即返回 null (继续接收)
  ```

### 5.3 wss://fk.firefly520.top/ 服务支持情况

> 需要确认该服务器是否支持以下功能:
> - 二进制帧 (Binary frames) — WebSocket 协议标准特性, 大多数服务端支持
> - 房间列表 / 房间管理 — 取决于服务端实现
> - 心跳保活 — WebSocket 协议内置 Ping/Pong, 服务端是否主动发送取决于配置

**建议:** 在 Mod 端实现自己的房间发现和心跳机制, 不依赖服务端特定功能。Mod 连接后发送注册消息, 服务端维护房间映射表。

---

## 六、中继架构总览

```
房主 (Host)                          远程玩家 (Guest)
┌──────────────┐                    ┌──────────────┐
│ MC 客户端     │                    │ MC 客户端     │
│ (NeoForge)   │                    │ (NeoForge)   │
│              │                    │              │
│ Mod:         │                    │ Mod:         │
│ - openToLAN  │                    │ - WS Client  │
│ - 获取端口   │                    │ - WS→TCP桥   │
│ - WS Client  │                    │ - 127.0.0.1  │
│ - TCP→WS桥   │                    │   :本地端口   │
│ - 房间注册   │                    │              │
└──────┬───────┘                    └──────┬───────┘
       │                                   │
       │    wss://fk.firefly520.top/       │
       │    ┌─────────────────────┐        │
       └───→│ WebSocket 中继服务   │←───────┘
            │                     │
            │ - 房间管理          │
            │ - 二进制帧转发      │
            │ - 心跳检测          │
            └─────────────────────┘
```

---

## 七、安全与权限

### 7.1 公开无密码房间的推荐限制

| 限制项 | 建议值 | 说明 |
|---|---|---|
| 最大玩家数 | 8-10 | LAN 集成服务器默认 `max-players=8`, 不建议超过 10 |
| 房间超时 | 30 分钟无活动 | 房主 AFK 或所有玩家断线后自动清理 |
| 房主断线清理 | 立即/5秒延迟 | 房主断线后, 集成服务器会关闭, 房间应立即标记失效 |
| 限流 | 每连接 64KB/s | Minecraft 游戏数据通常远小于此 |
| 单 IP 连接数 | 1 | 防止单个 IP 占满房间 |
| 心跳间隔 | 15-30 秒 | WebSocket Ping/Pong 或应用层心跳 |
| 数据包大小上限 | 2MB (2097152 bytes) | Minecraft 单包上限, 超过此值的包是异常的 |
| 连接速率限制 | 同 IP 3次/10秒 | 防止暴力连接 |
| 房间 TTL | 最长 24 小时 | 防止僵尸房间 |

### 7.2 房主断线清理流程

```
房主 WS 连接断开
    → 心跳超时 / onClose 触发
    → 中继服务检测到房主离线
    → 通知所有远程玩家 ("房主已断线, 房间关闭")
    → 关闭所有该房间的 WS 连接
    → 从房间列表中移除
    → 清理相关资源

// 远程玩家端
WebSocket onClose → 收到 1001 (Going Away) 或自定义关闭码
    → 显示断线消息
    → 断开本地 TCP 连接 (如果还在连接中)
    → 返回多人服务器列表界面
```

### 7.3 额外安全建议

- **不要暴露房主的公网 IP** — 中继服务应只转发数据, 不泄露 IP
- **房间 ID 使用随机 UUID** — 不可预测, 防止房间枚举
- **速率限制中继层** — 防止滥用 WebSocket 连接
- **数据包校验** — 验证包长度在合理范围内, 丢弃异常包
- **OP 权限隔离** — 远程玩家应被限制为普通玩家, 即使房主开启了 allowCommands

---

## 八、关键类引用 (Javadoc)

| 类 | 用途 | Javadoc |
|---|---|---|
| `IntegratedServer` | 集成服务器, openToLAN | [nekoyue/ForgeJavaDocs](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/) |
| `ServerConnectionListener` | TCP 连接管理 | 同上 |
| `ConnectScreen` | 连接服务器 | [Fabric Yarn](https://maven.fabricmc.net/docs/yarn-1.21.11+build.1/) |
| `JoinMultiplayerScreen` | 多人列表界面 | 同上 |
| `ServerSelectionList` | 服务器列表控件 | 同上 |
| `ServerData` | 服务器条目数据 | [aldak Javadoc](https://aldak.netlify.app/javadoc/1.21.8-21.8.x/) |
| `ServerList` | 服务器列表持久化 | 同上 |
| `ClientPlayerNetworkEvent` | 客户端网络事件 | 同上 |
| `java.net.http.WebSocket` | JDK WebSocket 客户端 | [Oracle JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html) |

---

## 九、参考 Mod 项目

| 项目 | 说明 |
|---|---|
| [LanServerProperties](https://github.com/rikka0w0/LanServerProperties) | 增强原版 LAN 界面, 自定义端口/关闭认证。支持 NeoForge, client-only, 含 Mixin 注入 `openToLAN` 的参考实现 |
| [LAN World Plug-n-Play](https://www.curseforge.com/minecraft/mc-mods/mcwifipnp) | 自动将 LAN 世界发布到公共服务器, 供远程玩家发现和加入 |
| [Custom LAN](https://modrinth.com/mod/custom-lan) | 更多 Open to LAN 自定义选项 |

LanServerProperties 的 `1.21` 分支是参考 `openToLAN` 调用方式、端口获取和 Mixin 注入点的最佳实践。
