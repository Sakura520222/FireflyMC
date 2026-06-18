# `/关机` 伪关机指令 — 设计规格

> 状态：已批准，实现中 · 日期：2026-06-18

## 一、目标

QQ 群 `/关机` 指令（限群主/管理员）触发"伪关机"维护状态：禁止所有玩家进入**多人服务器**、禁用**中继联机**；**单人 / 局域网 / P2P 联机**不受影响。`/开机` 恢复。

## 二、需求决策

| 维度 | 决策 |
|---|---|
| 指令 | `/关机` 关闭，`/开机` 恢复 |
| 权限 | 仅 QQ 群主/管理员（OneBot `sender.role ∈ {owner, admin}`） |
| 中继 | 严格禁用；P2P 打洞失败**不回退**中继，直接失败 |
| 已在线玩家 | `/关机` 时**立即强制断开**多人服务器连接与中继客机会话 |
| 不受影响 | 单人、局域网、P2P 联机、房主发布房间 |
| 模式判断 | 用现有 API（`getSingleplayerServer()` / `getCurrentServer()` / `RelayGuestJoiner` 会话标记），不用 IP 猜测 |
| 持久化 | 关机状态持久化到云端文件，重启后保持 |

## 三、数据链路

```
QQ群 "/关机"
 → napcat → 云端 QQCommandService.handle_napcat_event
 → 校验 sender.role ∈ {owner, admin}
 → EventNotifyService.set_shutdown(True)：持久化 + 广播 {"type":"server_shutdown"}
 → 回复 QQ 群确认

MC 客户端 handleInbound 收到 server_shutdown
 → ClientState.serverShutdown = true
 → 立即强制断开当前的【多人服务器连接】和【中继客机会话】
 → 后续连接尝试被拦截
```

`/开机` 对称（`server_startup`）。客户端**刚连上云端时**云端推送当前状态（覆盖晚启动/重连的客户端）。

## 四、拦截职责分工（关键）

所有原版连接与联机大厅连接最终都汇聚到 `ConnectScreen.startConnecting`，因此必须分两层拦截，避免误杀 P2P：

| 场景 | 拦截层 | 判据 |
|---|---|---|
| 原版多人菜单发起 | `ConnectScreenMixin`（HEAD 取消） | `serverShutdown && !isLobbyInitiatedConnection` |
| 联机大厅**中继**客机 | `RelayGuestJoiner` 层 | 关机时不启动中继代理；P2P 失败不回退 |
| 联机大厅 **P2P** 客机 | 不拦截 | `isLobbyInitiatedConnection=true` 使 Mixin 放行 |
| 房主发布 / 单人 / 局域网 | 不拦截 | 不经过上述路径 |

## 五、详细设计

### 云端（`E:\项目\firefly_mc`）

**`event_notify.py` — `EventNotifyService`**
- 新增 `_shutdown` 状态 + 持久化文件 `shutdown_state.json`（config 同目录）
- `is_shutdown()` / `set_shutdown(value)`：切换时持久化并 `broadcast_to_clients({"type": "server_shutdown"|"server_startup"})`
- `handle_connection`：客户端连上后若已关机，立即推送 `server_shutdown`
- 新增 `_send_to_client(ws, msg)` 单点推送

**`qq_command.py` — `QQCommandService`**
- `handle_napcat_event` 提取 `data["sender"]["role"]`，传入 `_CommandContext`
- `_CommandContext` 新增 `role` 字段
- 注册 `("关机", _cmd_shutdown)` / `("开机", _cmd_startup)`，`_cmd_help` 补说明
- `_is_authorized(ctx)`：`ctx.role in ("owner", "admin")`
- `_cmd_shutdown`/`_cmd_startup`：权限校验 → 调 `event_notify.set_shutdown()` → 回复群

### MC 客户端（`FireflyMC`）

- **`ClientState`**：新增 `serverShutdown`、`isLobbyInitiatedConnection`
- **`ConnectScreenMixin`**（新建）：`@Inject(method="startConnecting", at=HEAD, cancellable, remap=false)`，关机且非大厅发起时 `ci.cancel()` + 显示 `DisconnectedScreen`
- **`ServerShutdownManager`**（新建）：`onShutdownStateChanged(bool)` → 更新 ClientState；关机时强制断开（`mc.disconnect()` 断原版多人 + `RelayGuestJoiner.stopRelayProxyIfActive()` 断中继客机）
- **`ClientEventWebSocketClient.handleInbound`**：新增 `server_shutdown`/`server_startup` 分支
- **`RelayGuestJoiner`**：新增 `isInAnySession()` / `stopRelayProxyIfActive(reason)`；`startProxyAndConnect` 入口与 P2P 失败回退处增加关机判断（不启动中继/不回退）；`startConnecting` 调用前设 `isLobbyInitiatedConnection=true`、返回后复位
- **`P2PGuestProxy.connectMinecraft`**：`startConnecting` 调用前后设/复位 `isLobbyInitiatedConnection`
- **`mixins.fireflymc.json`**：`client` 数组加 `ConnectScreenMixin`
- **语言文件**：`fireflymc.server_shutdown.title/message/announce`

### `isLobbyInitiatedConnection` 时序

`startConnecting` 是 static、HEAD 同步检查、方法体只 `setScreen` + 异步起连接。因此"调用前置 true、返回后立即置 false"安全。

## 六、文件清单

云端：`event_notify.py`、`qq_command.py`
客户端：`ClientState.java`、`ConnectScreenMixin.java`(新)、`ServerShutdownManager.java`(新)、`ClientEventWebSocketClient.java`、`RelayGuestJoiner.java`、`P2PGuestProxy.java`、`mixins.fireflymc.json`、`lang/zh_cn.json`、`lang/en_us.json`

## 七、验证

- `gradlew.bat build` 通过
- 关机后：原版多人菜单连接被拦截并提示；联机大厅中继加入被拒；P2P 加入正常；单人/局域网正常；已在多人/中继会话者被断开
- 开机后：恢复正常；状态持久化跨云端重启
