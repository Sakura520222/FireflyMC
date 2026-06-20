# 已知问题与技术债务

记录已识别但暂未修复的问题，避免遗忘。每条包含：背景、问题、影响、暂不修复的依据、后续修复方向。

条目编号规则：按模块前缀 + 三位序号，如 `WS-001`（WebSocket 模块）、`RD-001`（渲染模块）。

---

## WS-001 关机维护期保持的 WebSocket 连接缺少最终清理

- **状态**: 待修复（低优先级）
- **识别日期**: 2026-06-20
- **当前版本**: 2.5.6
- **相关代码**:
  - `ClientEventNotificationEvents.java` 的 `onClientLoggedOut`（约 37-45 行）
  - `ClientState.java` 的 `serverShutdown` 字段
  - `ClientEventWebSocketClient.java` 的 `sessionActive` 字段

### 背景

云端服务器维护时通过事件 WebSocket 下发 `server_shutdown`，客户端置 `ClientState.serverShutdown = true` 并强制断开玩家与多人服务器的连接。为能在维护结束后接收云端下发的 `server_startup` 通知（从而把 `serverShutdown` 复位为 false），`onClientLoggedOut` 在 `serverShutdown` 为 true 时**故意跳过** `close()`，保持 WebSocket 连接。

### 问题

这条"为等待 `server_startup` 而保持"的连接**没有最终关闭的兜底时机**。若云端异常（`server_startup` 消息丢失 / 未发送 / 云端机器宕机），连接与 `sessionActive = true` 会持续挂起，直到玩家退出游戏（JVM 退出）才被动释放。

### 影响

严重度：**低**。已有两层兜底显著限制了实际损害：

1. `ClientEventWebSocketClient` 内置 `startHeartbeat()` 定时心跳 + `scheduleReconnect()` 重连，连接不会静默僵死；
2. 玩家关闭游戏时 JVM 退出会释放连接。

真正的泄漏窗口仅出现在：玩家将游戏挂在主菜单长时间不操作，**且**云端同时异常。单客户端代价轻微（一个 TCP 连接 + 心跳流量），但若云端集中维护、大量客户端同时处于该状态，会对云端连接数造成压力。

### 暂不修复的依据

- 云端维护为低频事件；
- 心跳 + JVM 退出兜底已覆盖绝大多数场景；
- 引入超时需要额外产品决策（保持时长阈值、由谁触发强制关闭），当前无明确需求驱动。

### 后续修复方向

实现前需先确定两项产品决策：

1. **最大保持时长**：建议 5~10 分钟未收到 `server_startup` 即视为云端异常；
2. **强制关闭的触发点**，可选方案：
   - 在 `ClientEventWebSocketClient` 增加 `closeIfStale()`，由 `onClientTick` 定时检查保持时长，超限后调用 `close()`；
   - 在 `onClientLoggedOut` 记录保持起始时间戳，后续 tick 中判定超时。

> **实现注意**：强制关闭连接后，需一并考虑 `serverShutdown` 是否复位。若仅关闭连接而保留 `serverShutdown = true`，`ConnectScreenMixin` 仍会拦截多人连接，玩家依旧无法加入服务器——问题表面解决实则未解决。更健壮的设计是将"维护期 WebSocket 生命周期"抽象为独立状态机（`ENTER_MAINTENANCE → WAITING_STARTUP → TIMEOUT → CLEANUP`），而非在现有流程中用 if-else 打补丁。
