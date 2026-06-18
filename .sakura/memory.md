# 项目记忆

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 累计反思次数: 21

## 常见代码问题与审查要点

### 日志脱敏完整性
同一方法内所有日志级别（debug/info/warn/error）必须使用相同脱敏函数。新增日志需全局检查同文件其他日志点。

### 网络请求禁止静默丢弃
所有网络请求（含 API 调用）必须记录具体错误码和消息，禁止空 catch 或静默忽略。反射调用失败同理，必须记录 warn/debug 日志。

### 单线程 executor 禁止内部 join
`SingleThreadExecutor` 中 `Future.join()` 导致线程阻塞，应改为异步回调或 `get(timeout)`。executor 内访问的字段必须注释声明线程约束。

### WebSocket/HTTP 回调线程安全
回调默认来自未知线程，`StringBuilder`/`ByteBuffer` 等累加器必须同步。

### P2P 握手超时兜底
所有 `CompletableFuture.join()` 必须配套 `orTimeout()` 或 `get(timeout, unit)`，防止卡死。

### 断线重连强制要求
网络长连接必须有断线重连机制，缺失为**阻塞级**缺陷，直接拒绝合并。

### 静态可变状态生命周期
跨线程静态字段必须：(1) `volatile` 修饰或使用 `AtomicBoolean`；(2) 提供 `reset()` 方法；(3) 在 closeHandler 链末端调用 reset。静态布尔标志优先使用 `AtomicBoolean`。

### 组合操作原子性
涉及"内存状态更新 + 网络同步/文件持久化"的组合操作，必须使用 `synchronized` 或显式锁，不能仅依赖并发容器的单次操作安全。

### 忙轮询检测
`Thread.sleep` 在循环中使用必须标记 suggestion，应改为 `Condition.await()`。

### IPv6 地址过滤审查
IPv6 收集器必须验证不上报 link-local（`fe80::/10`）/ULA（`fd00::/8`）地址。

### Mixin 审查要点
新增 Mixin 类必须：(1) 说明注入点与其他模组的兼容性；(2) 验证在所有支持的 mapping 环境（Mojang/SRG/Intermediary）下工作；(3) 评估对目标方法变更的健壮性。

### 用户输入边界验证
玩家命令参数、配置项等外部输入，必须在服务端入口进行长度、格式验证，超限值截断或拒绝。

## 近期审查模式总结

- **增量审查盲区**：`quick` 模式仅聚焦增量修改，易忽略同模块历史遗留问题。PR 模板应强制声明"是否波及同模块历史问题"。
- **修复类 PR 的隧道视野**：审查者易只验证当前修复，未扫描该模块其他同类风险点。
- **大版本审查策略**：大版本更新应按特性拆分审查清单逐项打勾，避免被新代码偏见蒙蔽。
- **新增文件强制审查**：新增行数 > 50 的 `.java` 文件必须有至少 1 条审查意见。
- **配置全生命周期审查**：新增配置项必须审查默认值、边界值（0/最大值）、热更新后状态重置、关闭时流程完整性。
- **closeHandler 链完整性**：资源清理链必须覆盖所有异常断开路径。
- **安全设计持续关注**：认证模块每次审查都需重新评估密码传输、哈希算法风险。

## 规范建议

| 规则 | 说明 |
|------|------|
| 日志脱敏全级别覆盖 | 同一数据源所有日志级别使用相同脱敏函数 |
| 脱敏函数统一入口 | 封装 `logSafe()` 工具方法，强制所有模块使用 |
| 单线程 executor 禁止 join | 审查发现立即标记 major |
| 静态状态必须可重置 | volatile/AtomicBoolean + reset() + closeHandler 调用 |
| P2P 超时强制兜底 | 所有异步等待必须设置超时 |
| 长连接强制重连 | 缺失为阻塞级缺陷 |
| 组合操作原子性 | 内存+副作用必须显式同步 |
| 忙轮询检测 | while+sleep 模式必须标记 suggestion |
| IPv6 地址过滤 | 禁止上报 link-local/ULA 地址 |
| Mixin 兼容性验证 | 新增 Mixin 必须评估多 mapping 兼容与模组冲突 |
| 网络请求错误日志 | 禁止静默丢弃，必须记录错误码 |
| 版本号禁止硬编码 | 统一使用 `FireflyMCMod.VERSION` |
| HttpClient 实例复用 | 禁止方法内重复创建 |

## 需要特别关注的领域

- `ClientEventWebSocketClient` 心跳重连与线程安全（高频问题模块）
- `P2PConnectionManager` 竞态条件与超时控制
- 所有 `SingleThreadExecutor` 中的 `join()` 调用
- `UpdateChecker` 静态标志线程安全与日志框架统一
- `PlayerPasswordManager` 静态状态生命周期
- 配置开关（如 `event_notification.enabled`）关闭时的流程完整性
- 弹窗时序标准化（密码、规则、公告显示顺序）
- 管理员指令安全审查（权限验证、参数校验、操作日志）
