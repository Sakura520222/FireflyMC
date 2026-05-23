# FireflyMC 架构文档

## 项目概述

FireflyMC 是一个基于 Java 开发的 Minecraft 模组项目，核心功能是为游戏扩展基于 WebSocket 的中继（Relay）和 P2P（点对点）打洞网络通信机制，支持用户在不同网络环境下进行联机。

## 技术栈

- **核心语言**：Java 21
- **构建工具**：Gradle（含 Gradle Wrapper）
- **基础框架**：NeoForge 1.21.1
- **网络通信**：
  - WebSocket：Java 11 HttpClient
  - P2P：UDP 打洞、自定义中继协议
  - 数据序列化：Gson
- **任务调度**：ScheduledExecutorService
- **线程模型**：虚拟线程 + 主线程委托

## 整体架构

### 模块划分

```
FireflyMC/
├── 中继模块 (Relay Module)
│   ├── RelayLobbyWebSocketClient - 中继服务器通信
│   ├── RelayLobbyMessage - 消息编解码
│   └── RelayGuestJoiner - 客户端加入逻辑
├── P2P 模块 (P2P Module)
│   ├── P2PConnectionManager - UDP 打洞管理
│   ├── ReorderBuffer - 乱序重组
│   ├── SendWindow - 发送窗口管理
│   └── UdpPacketCodec - UDP 包编解码
├── 认证模块 (Auth Module)
│   ├── PlayerPasswordManager - 密码认证
│   ├── PlayerPasswordCommandHandler - 命令处理
│   └── PasswordAuthScreen - 认证界面
├── 管理模块 (Manager Module)
│   ├── ItemCleanupManager - 物品清理任务
│   ├── StarterKitManager - 初始工具包
│   └── UpdateChecker - 更新检查
├── 配置模块 (Config Module)
│   └── ServerConfig - 服务器配置
└── UI 模块 (UI Module)
    ├── HUDRenderer - HUD 渲染
    └── PasswordAuthScreen - 认证界面
```

### 关键架构决策

#### 1. 单例模式
多数 Manager 采用单例管理全局状态，如 `ItemCleanupManager`、`PlayerPasswordManager`。

#### 2. 配置开关模式
- 0 值表示功能禁用
- 正数表示启用并作为参数值
- 配置与业务逻辑分离

#### 3. 线程模型
- **异步任务**：使用 `ScheduledExecutorService` 或虚拟线程
- **主线程委托**：Minecraft 相关操作通过 `server.execute()` 委托至主线程
- **WebSocket 回调**：来自未知线程，需线程安全处理

#### 4. 资源管理
- 任务取消后必须置 null 并记录日志
- 静态状态需提供 reset 方法或使用 volatile
- 网络连接需有断线重连机制

## 核心模块详解

### 中继模块 (Relay Module)

#### RelayLobbyWebSocketClient
- **职责**：与中继服务器通信，管理房间和成员
- **架构问题**：同时承担连接管理、消息编解码、心跳维护，违反单一职责
- **线程安全**：`webSocket` 字段多线程访问未同步，`textAccumulator` 未做线程安全处理

#### RelayLobbyMessage
- **职责**：消息编解码
- **架构问题**：上帝类，承担过多职责，Guest/Host 消息类型未分离

### P2P 模块 (P2P Module)

#### P2PConnectionManager
- **职责**：UDP 打洞和数据传输
- **关键功能**：
  - `probeAndPunch`：P2P 握手
  - 乱序重组：`ReorderBuffer`
  - 发送窗口：`SendWindow`
- **风险**：P2P 握手无超时兜底，不兼容 NAT 类型可能导致永久卡死

#### UdpPacketCodec
- **职责**：UDP 包编解码
- **问题**：手写 JSON 解析，使用 `String.contains()`，脆弱且易出错

### 认证模块 (Auth Module)

#### PlayerPasswordManager
- **职责**：密码认证、哈希、会话管理
- **架构问题**：上帝类倾向，集中处理密码校验、哈希、会话管理
- **安全问题**：
  - 使用纯数字密码
  - SHA-256 弱哈希
  - 静态状态缺乏生命周期管理

### 管理模块 (Manager Module)

#### ItemCleanupManager
- **职责**：物品清理任务调度
- **架构模式**：
  - 定时任务模式：`ScheduledExecutorService` + `scheduleAtFixedRate`
  - 线程委托模式：异步线程 → `server.execute()` → 主线程执行
  - 资源清理模式：`cancel()` + 置 null + 日志记录

#### UpdateChecker
- **职责**：检查模组更新
- **架构问题**：
  - 静态标志 `checked` 线程安全问题
  - 非200状态码静默丢弃
  - 使用 `System.out.println` 日志框架缺失

## 线程安全架构

### 常见线程安全问题

1. **单线程 executor 内部 join**
   ```java
   // 错误：SingleThreadExecutor 中调用 Future.join()
   executor.submit(() -> {
       Future<?> future = ...;
       future.join(); // 阻塞线程
   });
   ```

2. **WebSocket 回调线程安全**
   ```java
   // 错误：StringBuilder 在回调中未同步
   onText(message -> {
       textAccumulator.append(message); // 可能被多线程调用
   });
   ```

3. **静态状态并发访问**
   ```java
   // 错误：静态字段跨线程访问未同步
   private static final Map<String, Player> CONFIRMED_PLAYERS = new ConcurrentHashMap<>();
   ```

### 线程安全解决方案

1. **Executor 内部阻塞**
   - 改为异步回调
   - 使用 `get(timeout, unit)` 替代 `join()`

2. **WebSocket 回调**
   - 使用 `synchronized` 或 `ConcurrentHashMap`
   - 回调中避免长时间阻塞操作

3. **静态状态管理**
   - 使用 `volatile` 或 `AtomicBoolean`
   - 提供 `reset()` 方法明确生命周期
   - 使用 `ConcurrentHashMap` 替代普通 Map

## 网络通信架构

### WebSocket 通信

#### 架构设计
- **客户端**：`RelayLobbyWebSocketClient` 使用 Java 11 HttpClient
- **协议**：自定义中继协议，JSON 格式
- **心跳**：定期发送心跳包维持连接

#### 问题与风险
1. **断线重连缺失**：WebSocket 断开后无自动重连机制
2. **线程安全**：回调可能来自不同线程，需同步处理
3. **性能**：心跳高频调用，脱敏操作需评估性能

### P2P 通信

#### 架构设计
- **打洞协议**：UDP 打洞，支持对称型 NAT
- **数据传输**：自定义协议，支持乱序重组
- **中继 fallback**：P2P 失败时降级到中继转发

#### 问题与风险
1. **握手超时**：`probeAndPunch` 无总超时控制
2. **NAT 兼容性**：对称型 NAT 可能无法打洞成功
3. **窗口管理**：发送窗口满时无背压机制

## 配置架构

### 配置分类

#### 服务端配置 (ServerConfig)
- 物品清理间隔
- 密码认证开关
- 中继服务器地址
- P2P 打洞参数

#### 配置热更新
- **问题**：运行时修改配置不会重新调度任务
- **解决方案**：配置变更时需触发任务重新调度

### 配置边界测试
- **配置开关关闭时**：必须审查流程完整性，避免流程卡死
- **配置值范围校验**：应在加载时完成并记录 warn

## 数据存储架构

### NBT 数据存储
- **动态数据**：统一使用 NBT 格式
- **序列化**：Gson 用于网络传输，NBT 用于本地存储

### 配置存储
- **静态配置**：通过 `Config` 静态字段读取
- **问题**：强耦合配置，难以单元测试

## UI 架构

### HUD 渲染
- **模块**：`HUDRenderer`
- **问题**：固定尺寸导致在线人数少时显示不完整
- **解决方案**：动态高度计算，基于实际内容调整

### 认证界面
- **模块**：`PasswordAuthScreen`
- **问题**：UI 代码重复，两个 Screen 类 80% 代码相同
- **解决方案**：抽取 UI 工具类，统一绘制逻辑

## 安全架构

### 日志脱敏
- **统一入口**：`sanitizeRelayJsonForLog()` 作为唯一脱敏函数
- **覆盖范围**：IP、P2P 地址、token 等敏感信息
- **性能考虑**：高频路径需评估脱敏操作性能

### 密码安全
- **哈希算法**：SHA-256（弱哈希，建议升级至 bcrypt/argon2）
- **密码策略**：纯数字密码限制（需改进）
- **传输加密**：网络传输需加密，避免明文

### 敏感数据保护
- **日志脱敏**：所有日志输出需脱敏
- **网络传输**：避免明文传输敏感数据
- **客户端校验**：服务端必须做最终验证

## 性能架构

### 高频路径优化
1. **心跳机制**：1-30秒/次，需评估脱敏性能
2. **更新检查**：虚拟线程异步操作，需避免竞态条件
3. **P2P 数据传输**：乱序重组和窗口管理需高效实现

### 资源管理
1. **任务调度**：`ScheduledExecutorService` 需合理配置线程池
2. **网络连接**：HttpClient 实例复用，避免频繁创建
3. **内存管理**：`pendingBeforeRegister` 队列需有界和清理机制

## 测试架构

### 单元测试重点
1. **ReorderBuffer**：乱序重组逻辑
2. **SendWindow**：ACK 重传机制
3. **UdpPacketCodec**：编解码正确性
4. **密码认证**：哈希算法、密码策略

### 集成测试重点
1. **P2P 打洞**：不同 NAT 类型兼容性
2. **WebSocket 重连**：断线恢复机制
3. **配置热更新**：运行时配置变更处理

## 部署架构

### 客户端部署
- **Mod 加载**：NeoForge 框架
- **版本兼容**：Minecraft 1.21.1
- **更新检查**：GitHub API 集成

### 服务端部署
- **中继服务器**：独立服务，管理房间和成员
- **P2P 打洞**：客户端直连，减少服务器负载
- **认证服务**：密码验证和会话管理

## 架构演进建议

### 短期改进
1. **线程安全修复**：修复 `RelayLobbyWebSocketClient` 线程安全问题
2. **断线重连**：添加 WebSocket 自动重连机制
3. **密码安全**：升级哈希算法，改进密码策略

### 中期改进
1. **架构重构**：拆分上帝类，明确模块职责
2. **配置热更新**：支持运行时配置变更
3. **性能优化**：评估高频路径性能，优化脱敏操作

### 长期改进
1. **模块化**：进一步拆分模块，降低耦合
2. **测试覆盖**：完善单元测试和集成测试
3. **文档完善**：完善架构文档和 API 文档