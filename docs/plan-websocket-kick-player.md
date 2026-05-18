# 计划书：添加基于 WebSocket 的远程踢人接口

> 关联 Issue: #27
> 编写日期: 2025-01-27
> 优先级: medium

---

## 1. 需求分析

### 1.1 背景
FireflyMC 已具备两套 WebSocket 双端通信体系：
- **服务端事件 WebSocket**（`PlayerEventWebSocketClient`）：用于专用服务端，通过 `wss://fk.firefly520.top/` 连接，已实现远程关服、聊天广播、玩家列表查询、成员验证等功能。所有消息均通过 `key` 字段进行身份认证（`MessageAuthenticator.validateKey`）。
- **客户端联机 Relay WebSocket**（`RelayLobbyWebSocketClient`）：用于单人世界公开联机，通过中继服务器实现 Host-Guest 双端通信，包括房间注册、Guest 加入、流控制、P2P 打洞等。

### 1.2 需求目标
在已有的 WebSocket 通信接口基础上，添加一个**远程踢人接口**，使服务器管理员可以通过 WebSocket 远程踢出指定玩家。

### 1.3 影响范围
主要涉及**服务端事件 WebSocket** 通道（`PlayerEventWebSocketClient`），因为踢人是服务端管理操作。Relay 联机通道属于客户端侧，踢人通常由服务端执行。

---

## 2. 技术方案设计

### 2.1 协议设计

新增一种 WebSocket 消息类型 `kick_player`，遵循项目现有的消息模式（JSON + key 认证）。

#### 2.1.1 请求消息（外部 → Mod 服务端）

```json
{
  "type": "kick_player",
  "playerName": "Steve",
  "reason": "违反服务器规则",
  "key": "<wsAuthKey>"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | 是 | 固定值 `"kick_player"` |
| `playerName` | String | 否* | 目标玩家用户名（与 `playerUuid` 二选一） |
| `playerUuid` | String | 否* | 目标玩家 UUID（与 `playerName` 二选一） |
| `reason` | String | 否 | 踢出原因，显示给被踢玩家，默认为空 |
| `key` | String | 是 | WebSocket 认证密钥 |

> *`playerName` 和 `playerUuid` 至少提供一个；若同时提供，优先使用 `playerUuid`。

#### 2.1.2 响应消息（Mod 服务端 → 外部）

```json
{
  "type": "kick_player_response",
  "status": "success",
  "message": "已踢出玩家 Steve",
  "key": "<wsAuthKey>"
}
```

| status 值 | 说明 |
|-----------|------|
| `success` | 成功踢出玩家 |
| `not_found` | 玩家不在线 |
| `disabled` | 远程踢人功能未启用 |
| `error` | 其他错误（附错误消息） |

### 2.2 新增文件清单

| 文件 | 说明 |
|------|------|
| `KickPlayerMessage.java` | 踢人请求消息的解析模型 |
| `KickPlayerResponseMessage.java` | 踢人响应消息的序列化模型 |

路径均位于 `src/main/java/firefly520/fireflymc/event/websocket/` 包下。

### 2.3 需修改的文件清单

| 文件 | 修改内容 |
|------|----------|
| `PlayerEventWebSocketClient.java` | 在 `onText` 回调中添加 `kick_player` 消息类型的解析和处理分支 |
| `ServerConfig.java` | 添加 `enableRemoteKick` 配置开关（默认 `true`） |

### 2.4 详细设计

#### 2.4.1 `KickPlayerMessage.java`（新建）

```java
package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * 远程踢人请求消息。
 *
 * JSON格式:
 * {
 *   "type": "kick_player",
 *   "playerName": "Steve",
 *   "playerUuid": "可选UUID",
 *   "reason": "踢出原因",
 *   "key": "<wsAuthKey>"
 * }
 */
public class KickPlayerMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("playerName")
    private final String playerName;

    @SerializedName("playerUuid")
    private final String playerUuid;

    @SerializedName("reason")
    private final String reason;

    @SerializedName("key")
    private final String key;

    // constructor, fromJson, getters, isValid() ...
}
```

关键方法：
- `fromJson(String json)` — 反序列化
- `isValid()` — 校验 `type == "kick_player"` 且 `key` 非空且至少提供了 `playerName` 或 `playerUuid`

#### 2.4.2 `KickPlayerResponseMessage.java`（新建）

```java
package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * 远程踢人响应消息。
 */
public class KickPlayerResponseMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("status")
    private final String status;

    @SerializedName("message")
    private final String message;

    @SerializedName("key")
    private final String key;

    // 静态工厂方法: success(), notFound(), disabled(), error()
    // toJson() 序列化
}
```

#### 2.4.3 `PlayerEventWebSocketClient.java` 修改

在 `onText` 回调的消息分发链中添加踢人消息处理分支：

```java
// 在 onText 方法的消息处理链中，紧跟 shutdown 命令之后添加：

// 检查是否是踢人命令
if (!handled) {
    KickPlayerMessage kickMsg = KickPlayerMessage.fromJson(json);
    if (kickMsg != null && kickMsg.isValid()) {
        if (MessageAuthenticator.validateKey(kickMsg.getKey())) {
            handleKickPlayer(webSocket, kickMsg);
        }
        handled = true;
    }
}
```

新增私有方法 `handleKickPlayer`：

```java
/**
 * 处理远程踢人命令
 */
private static void handleKickPlayer(java.net.http.WebSocket ws, KickPlayerMessage kickMsg) {
    // 1. 检查配置开关
    if (!ServerConfig.SERVER.enableRemoteKick.get()) {
        sendResponse(ws, KickPlayerResponseMessage.disabled());
        return;
    }

    // 2. 确保服务器实例可用
    if (server == null) {
        sendResponse(ws, KickPlayerResponseMessage.error("服务器实例未就绪"));
        return;
    }

    // 3. 在主线程执行踢人操作（遵循 server.execute() 委托模式）
    server.execute(() -> {
        ServerPlayer target = findTargetPlayer(kickMsg);

        if (target == null) {
            sendResponse(ws, KickPlayerResponseMessage.notFound(
                kickMsg.getPlayerName() != null ? kickMsg.getPlayerName() : kickMsg.getPlayerUuid()
            ));
            return;
        }

        // 4. 构建踢出原因并执行
        String reason = kickMsg.getReason();
        Component kickReason = reason != null && !reason.isEmpty()
            ? Component.literal("§c[FireflyMC] " + reason)
            : Component.literal("§c[FireflyMC] 你已被管理员踢出");

        server.getPlayerList().remove(target, kickReason);
        LOGGER.info("[FireflyMC] 远程踢人成功: player={}, reason={}",
            target.getName().getString(), reason);
        sendResponse(ws, KickPlayerResponseMessage.success(target.getName().getString()));
    });
}

/**
 * 根据 playerName 或 playerUuid 查找目标玩家
 */
private static ServerPlayer findTargetPlayer(KickPlayerMessage kickMsg) {
    if (server == null) return null;

    // 优先使用 UUID 查找
    if (kickMsg.getPlayerUuid() != null && !kickMsg.getPlayerUuid().isEmpty()) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString(kickMsg.getPlayerUuid());
            return server.getPlayerList().getPlayer(uuid);
        } catch (IllegalArgumentException ignored) {
            // UUID 格式无效，回退到用户名查找
        }
    }

    // 回退到用户名查找
    if (kickMsg.getPlayerName() != null && !kickMsg.getPlayerName().isEmpty()) {
        return server.getPlayerList().getPlayerByName(kickMsg.getPlayerName());
    }

    return null;
}
```

#### 2.4.4 `ServerConfig.java` 修改

在 `ServerConfigImpl` 构造函数的 `server` 配置节中添加：

```java
enableRemoteKick = builder
    .comment("Enable remote player kick via WebSocket")
    .translation("fireflymc.config.server.enable_remote_kick")
    .define("enableRemoteKick", true);
```

新增字段声明：
```java
public final ModConfigSpec.BooleanValue enableRemoteKick;
```

### 2.5 设计决策与注意事项

#### 2.5.1 为什么放在服务端事件通道（`PlayerEventWebSocketClient`）

踢人是**服务端管理操作**，需要 `MinecraftServer` 实例来调用 `getPlayerList().remove()`。Relay 通道是客户端侧的，不具备服务端踢人能力。现有的 `PlayerEventWebSocketClient` 已有成熟的消息认证（`MessageAuthenticator`）、配置开关（如 `enableRemoteShutdown`）和主线程委托模式（`server.execute()`），是放置踢人功能的自然扩展点。

#### 2.5.2 线程安全

- 踢人操作通过 `server.execute()` 委托到主线程执行，遵循项目"Bukkit 主线程消息发送的 `server.execute()` 委托模式"约定。
- 响应发送通过 `EXECUTOR.submit()` 异步发送，不阻塞主线程。

#### 2.5.3 日志脱敏

踢人日志不涉及敏感网络信息（IP、P2P 地址、token），使用普通日志输出即可，无需 `sanitizeRelayJsonForLog()`。

#### 2.5.4 安全性

- 所有踢人请求必须携带有效的 `key`，通过 `MessageAuthenticator.validateKey()` 验证。
- 添加配置开关 `enableRemoteKick`，允许管理员禁用此功能。
- 踢人操作记录 INFO 级别日志，便于审计。

#### 2.5.5 与现有模式的一致性

此方案严格遵循项目中已有的设计模式：
- **消息模式**：参照 `ShutdownCommand` / `ServerMessage` 等现有消息类型，使用 Gson + `@SerializedName`
- **认证模式**：所有 WebSocket 控制消息均通过 `key` 字段认证
- **配置开关模式**：参照 `enableRemoteShutdown`，以布尔值控制功能启用
- **响应模式**：参照 `WebSocketResponse`，统一返回 status + message
- **主线程委托**：参照 `ServerMessageBroadcaster.broadcast()`，使用 `server.execute()`

---

## 3. 实现步骤

| 步骤 | 操作 | 涉及文件 |
|------|------|----------|
| 1 | 创建 `KickPlayerMessage.java` 消息模型 | 新建文件 |
| 2 | 创建 `KickPlayerResponseMessage.java` 响应模型 | 新建文件 |
| 3 | 在 `ServerConfig.java` 添加 `enableRemoteKick` 配置项 | 修改文件 |
| 4 | 在 `PlayerEventWebSocketClient.java` 添加消息分发和处理逻辑 | 修改文件 |
| 5 | 编译验证，确保无语法错误 | — |

---

## 4. 测试要点

### 4.1 功能测试
- 发送合法踢人消息，验证玩家被踢出并收到踢出原因
- 同时提供 `playerName` 和 `playerUuid`，验证 UUID 优先
- 仅提供 `playerName`，验证按用户名查找
- 仅提供 `playerUuid`，验证按 UUID 查找
- 踢出不存在的玩家，验证返回 `not_found` 状态
- 不提供任何标识符，验证 `isValid()` 返回 false

### 4.2 安全测试
- 发送错误 `key`，验证消息被拒绝
- 发送空 `key`，验证消息被拒绝
- 关闭 `enableRemoteKick` 配置，验证返回 `disabled` 状态

### 4.3 边界测试
- 服务器实例为空时发送踢人命令
- `reason` 为 null 或空字符串时使用默认踢出消息
- 无效 UUID 格式回退到用户名查找

---

## 5. 风险评估

| 风险 | 等级 | 说明 |
|------|------|------|
| 密钥泄露导致恶意踢人 | medium | 依赖现有 `MessageAuthenticator` 机制，与远程关服同风险级别 |
| 配置默认启用 | low | 与 `enableRemoteShutdown` 保持一致，默认启用便于使用 |
| 主线程阻塞 | low | 踢人操作是轻量级操作，通过 `server.execute()` 委托不影响性能 |

---

## 6. 未来扩展方向

1. **批量踢人**：支持一次踢出多个玩家（`players` 数组字段）
2. **按 IP 踢人**：支持通过 IP 地址匹配踢出玩家
3. **Ban 整合**：踢人时可选同时 ban 玩家
4. **Relay 联机踢人**：在 Relay Host 侧实现踢出 Guest 的功能（需扩展 `RelayLobbyMessage` 协议）
