# 高频问题模块索引

> 基于 30 次审查反思整理，标记各模块的已知风险和审查关注点。

## 🔴 高风险模块

### `RelayLobbyWebSocketClient`
- **线程安全**：WebSocket 回调来自未知线程，`textAccumulator` 非线程安全（第42次审查 major）
- **上帝类倾向**：连接管理 + 消息编解码 + 心跳维护 + 房间管理
- **断线重连**：缺失为阻塞级缺陷
- **closeHandler 链断裂**：资源清理链未覆盖所有异常断开路径
- **executor 内部 join**：`SingleThreadExecutor` 中 `Future.join()` 退化为同步

### `ClientEventWebSocketClient`
- **高频问题模块**：21次反思中出现频率最高
- **连接代际**：需 `sessionGeneration` 防止重连竞态
- **重连竞态**：新连接建立后旧连接的回调仍可能到达
- **配置边界**：`event_notification.enabled` 关闭时流程需验证

### `ClientState`
- **静态状态泛滥中心**：跨线程访问、缺少 volatile/AtomicBoolean
- **新增字段风险**：每次新增字段都需审查 volatile 和 reset 调用点
- **生命周期管理薄弱**：`serverShutdown` 等标志缺少明确重置时机

### `P2PConnectionManager`
- **竞态条件**：组合操作（状态更新+网络同步）缺 synchronized
- **握手超时无兜底**：对称 NAT 导致 `probeAndPunch()` 可能永远卡死
- **IPv6 地址过滤**：`fd00::/8` ULA 可能被上报（`isSiteLocalAddress` 不覆盖）

## 🟠 中风险模块

### `PlayerPasswordManager`
- **静态状态生命周期**：`CONFIRMED_PLAYERS` 等缺 reset()
- **密码安全**：SHA-256 弱哈希 + 纯数字密码（长期未修复）
- **配置边界**：`playerAuthEnabled` 禁用时流程卡死

### `UpdateChecker`
- **线程安全**：`checked` 标志需 AtomicBoolean
- **版本号硬编码**：多次违反规范
- **日志框架**：`System.out.println` 未统一

### `RelayGuestJoiner`
- **职责过多**：P2P 代理创建 + 中转逻辑 + 静态状态管理
- **组合操作原子性**：状态赋值 + 网络操作缺 synchronized

### `ModNetwork`
- **反射调用系统性空 catch**：6处反射全部空 catch 块（第56次审查发现）

## 🟡 关注模块

### `ServerShutdownManager`
- 状态生命周期需审查：关机状态在连接断开/重连时是否正确重置

### `ConnectScreenMixin`
- 安全网关职责：条件判断需与 `ClientState` 语义严格对齐
- Mixin 兼容性需验证（SRG/Intermediary mappings）

### `TitleWorldRenderer`
- 渲染代码易在运行时暴露（状态泄漏导致后续渲染错乱）
- 必须按 WorldRenderer 检查清单逐项验证
