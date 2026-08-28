# 项目记忆

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 累计反思次数: 15

## 代码问题与审查要点

### 日志脱敏完整性
同一方法内所有日志级别必须使用相同脱敏函数。新增日志需全局检查同文件其他日志点。封装 `logSafe()` 统一入口。

### 网络请求禁止静默丢弃
所有网络请求必须记录具体错误码和消息，禁止空 catch 或静默忽略。反射调用失败同理，必须记录 warn/debug 日志。

### 单线程 executor 禁止内部 join
`SingleThreadExecutor` 中 `Future.join()` 导致线程阻塞，应改为异步回调或 `get(timeout)`。executor 内访问的字段必须注释声明线程约束。

### WebSocket/HTTP 回调线程安全
回调默认来自未知线程，累加器（StringBuilder/ByteBuffer）必须同步。

### P2P 握手超时兜底
所有 `CompletableFuture.join()` 必须配套 `orTimeout()` 或 `get(timeout, unit)`。

### 断线重连强制要求
网络长连接必须有断线重连机制，缺失为**阻塞级**缺陷，直接拒绝合并。

### 静态可变状态生命周期
跨线程静态字段必须：(1) `volatile` 或 `AtomicBoolean`；(2) 提供 `reset()`；(3) 在 closeHandler 末端调用。缺失并发修饰一律标记 `major`。

### 组合操作原子性
"内存状态更新 + 网络同步/持久化"的组合操作必须使用 `synchronized` 或显式锁，不能仅依赖并发容器的单次操作安全。

### IPv6 地址过滤
收集器必须不上报 link-local（fe80::/10）/ULA（fd00::/8）。注意 `isSiteLocalAddress()` 不覆盖 ULA。

### Mixin 审查要点
(1) 注入点兼容性；(2) 多 mapping 环境验证；(3) 目标方法变更健壮性。**移除 Mixin 时须列出注入点→新实现映射表**。

### 用户输入边界验证
玩家命令参数、配置项等外部输入，必须在入口做长度、格式验证，超限截断或拒绝。

### 安全机制失效默认 major
限流/锁定/认证等安全控制缺陷（含绕过、失效、旁路）默认按 major 起步，降级须明确论证。

### 网络包客户端防御校验
客户端接收 Payload 数值字段须做范围校验，超限拒绝或截断。

### 渲染器审查清单
新增渲染器必须检查：渲染阶段、PoseStack push/pop 配对（用 try/finally 包裹）、RenderSystem 状态恢复、距离裁剪、与原实现功能对等。`RenderLevelStageEvent` 失去引擎原生视锥体剔除，必须重新实现裁剪。

### 网络包枚举安全
所有自定义 Payload 使用 `values()[ordinal]` 时必须使用 `EnumUtil.fromOrdinal` 边界检查，避免 `ArrayIndexOutOfBoundsException`。

### Payload 数据一致性
当 Payload 的 Codec 容量大于业务逻辑限制时，必须在数据出口处（构造 Payload 前）截断，不仅依赖入口校验。

### 缓存写入成功判定
引入 `CacheWriteResult`（SUCCESS/INCOMPLETE/FAILED），仅在流读取至 EOF 且校验通过后标记 SUCCESS，异常路径须显式置 false。

### 登录全量同步协议
玩家登录/重连必须发送完整状态快照，单点同步会导致 UI 长期不一致。

### 虚拟线程使用约束
虚拟线程仅适用于阻塞 IO，绝不能绕过主线程操作游戏状态或发送数据包。Handler/Callback 中禁止无限制启动虚拟线程。

### 异常日志统一记录
`catch (Throwable e)` 必须统一记录 `log.error("...", e)`，禁止空 catch 或仅调用失败方法。异步任务（虚拟线程、Executor、CompletableFuture）必须捕获 Throwable 并记录，禁止空 catch。

### 失败路径单元测试
涉及禁用/启用契约（如 `ensureInitialized`）、安全边界（路径校验、文件写入、网络请求）的方法必须至少一个失败路径单元测试。

### 日志计数一致性
统计类日志（"已删 N 个"）必须仅基于成功操作计数，禁止包含失败操作。

### HttpClient HTTPS 降级禁止
所有 `HttpClient` 必须显式 `Redirect.NEVER`，或重定向后校验最终 scheme 为 https。

## 近期审查模式总结

- **新代码偏见**：正面评价≠审查意见，>50行文件必须至少1条改进建议
- **修复类 PR 隧道视野**：审查者易只验证当前修复，未扫描同类风险点。同类缺陷必须批量修复
- **增量审查隧道视野**：`quick` 模式易忽略同模块历史问题，PR 模板应强制声明"是否波及同模块"
- **全量审查须链路驱动**：按功能链路（配置→触发→传输→处理→展示→清理）组织，避免按文件列表逐个扫读
- **全量审查两轮法**：第一轮规则驱动（checklist 搜索匹配），第二轮文件驱动（独立深度审查）
- **配置全生命周期审查**：默认值、边界值（0/最大值）、热更新重置、关闭时流程完整性
- **安全审查攻击者视角**：验证安全控制能否被构造输入绕过，不能仅靠读代码
- **修复 PR 强制编译**：提交前必须编译通过，CI 绿色标记
- **连续降分触发机制**：同一 PR 连续两轮评分下降，建议更换修复者
- **开发能力不足时停止增量审查**：同一文件连续两轮编译级错误，转为给出完整修复代码模板
- **增量审查标准动作**：看 diff 验逻辑，扫上下文防遗漏

## 规范速查表

| 规则 | 说明 |
|------|------|
| 日志脱敏全级别覆盖 | 同一数据源所有日志级别使用相同脱敏函数 |
| 单线程 executor 禁止 join | 审查发现立即标记 major |
| 静态状态必须可重置 | volatile/AtomicBoolean + reset() + closeHandler 调用 |
| P2P 超时强制兜底 | 所有异步等待必须设置超时 |
| 长连接强制重连 | 缺失为阻塞级缺陷 |
| 组合操作原子性 | 内存+副作用必须显式同步 |
| IPv6 地址过滤 | 禁止上报 link-local/ULA |
| Mixin 兼容性验证 | 新增/移除 Mixin 必须评估兼容性并提供映射表 |
| 版本号禁止硬编码 | 统一使用 `FireflyMCMod.VERSION` |
| HttpClient 实例复用 | 禁止方法内重复创建 |
| 安全机制失效默认 major | 限流/锁定/认证绕过默认 major，降级需论证 |
| 隐私外传配置默认 false | 涉及用户数据上报的配置必须默认 false |
| WebSocket 三件套 | 新增WS代码须回答：重连、回调线程安全、closeHandler |
| 长连接兜底路径 | 非常规保持须有：超时上限、fallback清理、reset |
| 失败路径测试 | 安全边界方法必须至少1个失败路径单元测试 |
| 日志计数语义 | 统计日志仅基于成功操作，禁止包含失败 |
| HttpClient HTTPS | 必须 Redirect.NEVER 或校验最终 scheme |
| 虚拟线程约束 | JDK 21-23 禁止进入 synchronized 锁块 |
| WorldRenderer 检查清单 | 渲染阶段、PoseStack配对、状态恢复、距离裁剪、功能对等 |
| 同类缺陷批量修复 | 审查指出模式缺陷须列出所有实例一并修复 |
| 配置变更逐项审查 | 默认值、边界值、热更新 |
| 网络包客户端防御校验 | Payload数值字段须做范围校验 |
| Javadoc 约束违反 | 字段 Javadoc 声明行为被违反时至少 major |
| 网络包主线程强制化 | 所有 `PacketDistributor.sendToServer()` 必须包裹在主线程执行 |
| CompletableFuture 超时强制 | 所有 `join()` 必须配套 `orTimeout` 或 `get(timeout, unit)` |
| HTTPS 禁止 HTTP 重定向 | 必须设置 `Redirect.NEVER` 或校验最终 URI scheme |
| Payload 枚举安全校验 | 使用 `EnumUtil.fromOrdinal` 防止越界 |
| 缓存写入成功判定 | 使用 Result 类型，确保 EOF 且校验通过后才标记成功 |
| 登录全量同步协议 | 玩家登录必须发送完整状态快照 |
| 虚拟线程资源控制 | Handler/Callback 中禁止无限制启动虚拟线程 |
| 渲染器状态恢复 | `push` 必须配对 `pop` 且用 `try/finally` 包裹 |
| 配置可见性 | 多线程读取的配置字段必须 `volatile` |
| 事件注册统一管理 | 所有事件注册必须在 `ModEventBusSubscriber` 中统一管理 |
| 热更新验证 | 配置项必须验证运行时热更新回调是否实现 |
| 文件层级安全审计 | 涉及 I/O 必须校验路径合法性、异常捕获与日志脱敏 |
| 缓存完整性校验 | finalizePartFile 前必须校验文件长度或校验和，未通过则删除或标记损坏 |
| 异常分支不可空 | 所有 catch/else/switch 中的 RETRY/FAIL 分支必须显式记录日志、上报或清理 |
| 枚举序列化安全 | 禁止 ordinal() 直接用于网络传输，必须每次枚举变更时同步提升 NETWORK_VERSION |
| 容错阈值配置化 | 所有业务阈值（超时/重试/缓存大小）必须通过配置项或构造函数注入 |
| 单元测试覆盖要求 | 异常路径必须提供单元测试，新增类覆盖率 ≥90%，deterministic 测试 |
| 跨线程资源同步 | 共享文件/缓存对象必须 synchronized/ReentrantLock/Concurrent |
| 文档-代码一致性 | 自动对比 Config、指令注册、常量与文档描述是否同步 |
| 配置归属标注 | 文档中配置项必须标注所属 toml 文件 |
| 长输出警示 | 一次性输出 >30 行的指令必须注明可能刷屏 |
| HTTPS 降级禁止 | HttpClient 必须显式设置 Redirect.NEVER，重定向后校验 scheme 为 https |
| instanceof 包装流判定 | 包装流类型判断必须检查最内层实现，否则提供 getRawStream() 或 isGuarded() |
| 虚拟线程网络发包 | PacketDistributor.sendToServer 必须包裹在 Minecraft.getInstance().execute() 中 |
| 语言资源一致性 | i18n 文件必须通过 lint 检查键值对完整、未使用键、占位符一致 |
| 代理模式信任边界 | 服务端必须将客户端回包视为不可信输入，严格校验所有字段 |

## 高频问题模块

- `RelayLobbyWebSocketClient` — 线程安全、上帝类、断线重连、executor阻塞（🔴）
- `ClientEventWebSocketClient` — 连接代际、重连竞态、配置边界、心跳重连（🔴）
- `ClientState` — 静态状态泛滥、volatile缺失、生命周期薄弱（🔴）
- `P2PConnectionManager` — 竞态条件、握手超时、IPv6过滤（🔴）
- `ModNetwork` — 反射调用系统性空 catch，三轮修复失败，需资深开发者介入（🔴）
- `MusicCache` — 半截文件落盘、LRU 删除未加锁、缓存完整性校验缺失（🔴）
- `MusicProxySearchClient` — 虚拟线程直接发包、并发节流缺失（🔴）
- `MusicApiClient` — HTTPS 降级风险（🔴）
- `PlayerPasswordManager` — 密码安全、静态状态、配置边界（🟠）
- `UpdateChecker` — 线程安全、版本硬编码、日志框架（🟠）
- `RelayGuestJoiner` — 职责过多、组合操作原子性（🟠）