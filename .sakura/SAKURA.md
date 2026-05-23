# 项目概述文档：FireflyMC

## 1. 项目简介
FireflyMC 是一个基于 Java 开发的 Minecraft 模组项目，核心功能是为游戏扩展基于 WebSocket 的中继（Relay）和 P2P（点对点）打洞网络通信机制，支持用户在不同网络环境下进行联机。项目采用 Minecraft Mod 开发框架（NeoForge/Forge 体系），注重模块化设计与规范的注册机制。

## 2. 技术栈
- **核心语言**：Java
- **构建工具**：Gradle（含 Gradle Wrapper）
- **基础框架**：Minecraft Mod 开发框架（NeoForge/Forge）
- **网络通信**：Java 11 HttpClient（WebSocket）、UDP（P2P 打洞）、自定义中继协议
- **持续集成**：GitHub Actions
- **任务调度**：ScheduledExecutorService
- **数据序列化**：Gson

## 3. 架构设计与关键决策

### 整体架构
- **中继模块**：`RelayLobbyWebSocketClient` 负责与中继服务器通信，管理房间和成员
- **P2P 模块**：`P2PConnectionManager` 负责 UDP 打洞和数据传输，含乱序重组（ReorderBuffer）和发送窗口（SendWindow）
- **Manager 层**：如 `ItemCleanupManager`，负责任务的生命周期管理
- **Config 层**：配置与业务逻辑分离，支持静态 Config 读取
- **线程模型**：后台任务通过 `server.execute()` 委托至主线程执行 Minecraft 相关操作

### 关键决策
- **单例模式**：多数 Manager 采用单例管理全局状态
- **配置开关**：以 0 值表示功能禁用，正数表示启用
- **资源清理**：任务取消后必须置 null 并记录日志
- **物理侧隔离**：明确区分客户端与服务端逻辑
- **日志脱敏**：所有输出日志的 JSON 必须经过统一脱敏函数，防止泄露 IP、P2P 地址、token

## 4. 已知问题和注意事项

### 严重问题
- **线程安全隐患**：`RelayLobbyWebSocketClient` 的 `webSocket` 字段多线程访问未同步；`textAccumulator` 在 WebSocket 回调中未做线程安全处理
- **Executor 内部阻塞**：多处 `SingleThreadExecutor` 内调用 `Future.join()`，退化为同步执行，可能阻塞心跳
- **断线重连缺失**：WebSocket 断开后无自动重连机制，可能导致功能静默失效
- **静态状态膨胀**：`RelayGuestJoiner` 等类使用多个静态字段，缺乏生命周期重置方法
- **密码安全问题**：`PlayerPasswordManager` 使用纯数字密码和 SHA-256 弱哈希，存在安全风险
- **更新检查机制缺陷**：`UpdateChecker.java` 静态标志 `checked` 线程安全问题，非200状态码静默丢弃

### 设计缺陷
- **上帝类**：`RelayLobbyMessage` 承担过多职责，Guest/Host 消息类型未分离
- **分层混乱**：`RelayLobbyWebSocketClient` 同时管理连接、编解码、心跳，违反单一职责
- **配置热更新缺失**：运行时修改配置不会重新调度任务
- **脱敏函数性能隐患**：心跳等高频路径调用正则/JSON 解析，需评估性能

### 潜在风险
- **P2P 握手无超时兜底**：`probeAndPunch` 无总超时，不兼容 NAT 类型可能导致永久卡死
- **资源泄漏**：`pendingBeforeRegister` 队列无界且无清理机制
- **UDP 窗口满时无背压**：发送窗口满时应暂停发送，当前可能无限缓冲

## 5. 审查中发现的重要模式

### 代码模式
| 模式 | 说明 |
|------|------|
| 定时任务模式 | `ScheduledExecutorService` + `scheduleAtFixedRate` |
| 线程委托模式 | 异步线程 → `server.execute()` → 主线程 |
| 日志脱敏统一入口 | `sanitizeRelayJsonForLog()` 作为唯一脱敏函数 |
| 资源清理模式 | `cancel()` + 置 null + 日志记录 |
| 配置开关模式 | 0 = 禁用，>0 = 启用 |
| 虚拟线程异步操作 | 使用虚拟线程进行网络请求等异步操作 |
| 状态驱动流程 | 条件逻辑优化，从硬编码流程向状态驱动演进 |

### 常见错误模式
- **日志级别脱敏不一致**：同一对象的 debug/info 日志使用不同脱敏路径
- **变量命名与单位脱节**：变量名含 `Seconds` 却使用 `TimeUnit.MINUTES`
- **增量修复隧道视野**：修复类 PR 只关注当前问题，忽略同模块其他遗留风险
- **字符串解析脆弱**：UDP 控制包使用 `String.contains()` 解析 JSON
- **静态状态线程安全不足**：静态标志未使用 `volatile` 或原子类，易引发竞态条件
- **网络请求错误处理不完整**：非200状态码静默丢弃，缺乏具体错误码和消息记录

## 6. 团队约定和规范

### 审查规则
- **日志脱敏完整性校验**：同一方法内所有日志级别必须使用相同的脱敏函数
- **脱敏函数性能审查**：高频路径的脱敏操作需评估复杂度
- **增量修复回溯规则**：修复类 PR 必须快速扫描该模块的其他同类风险
- **单线程 executor 禁止内部 join**：见到即标记为 major
- **WebSocket/HTTP 回调线程安全**：所有共享状态必须同步
- **断线重连强制检查**：任何网络长连接缺失重连机制则为 major
- **P2P 超时兜底**：所有 `CompletableFuture.join()` 必须配套超时
- **增量审查边界声明**：`quick` 模式下必须明确声明审查边界，提示扫描同模块历史问题
- **配置边界测试**：任何由配置开关控制的功能，必须审查配置关闭时的流程完整性
- **静态状态生命周期管理**：静态可变状态必须提供 `reset()` 方法或使用 `volatile`
- **网络请求错误记录**：所有网络请求必须记录具体错误码和消息，避免静默丢弃

### 代码规范
- 新增游戏内容必须遵循 Registry 注册体系
- 修改原版访问权限统一使用 Access Transformers (AT)
- 动态数据存储统一使用 NBT 格式
- Bukkit 消息发送必须走 `server.execute()` 确保主线程
- 所有日志输出的 JSON 必须经过 `sanitizeRelayJsonForLog()`
- 静态可变状态必须提供 reset 方法或使用 volatile

### 项目特有规范
- **Minecraft 版本抽象**：硬编码版本号需改为从 `SharedConstants` 读取
- **UI 代码强制抽取**：超过 10 行绘制代码出现两次即抽象为工具类
- **脱敏函数覆盖全日志级别**：在类注释中明确说明强制要求
- **密码安全专项检查**：建立密码安全检查清单，包括哈希算法、密码复杂度、传输加密等
- **弹窗时序标准化**：为密码、规则、公告等弹窗建立标准的显示顺序和冲突解决机制
- **更新检查集成统一日志**：高频网络路径应优先集成统一日志框架，避免 `System.out.println` 泄露敏感信息

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 语言统计: Java: 510438
- 累计反思次数: 11