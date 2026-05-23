# 经验教训与常见问题模式

## 审查经验教训

### 增量审查的局限性

#### 问题描述
- **增量审查盲区**：`quick` 模式仅聚焦增量修改，易忽略同模块历史遗留问题
- **修复类 PR 的隧道视野**：审查者易只验证当前修复，未扫描该模块其他同类风险点
- **多轮小修复掩盖系统性问题**：连续小修可能掩盖底层设计问题

#### 解决方案
1. **强制边界声明**：在 PR 模板中增加"是否波及同模块历史问题"检查项
2. **定期全量评估**：定期进行模块级全量健康度评估
3. **审查模板优化**：强制审查者声明审查边界，提示扫描同模块历史问题

### 配置边界测试缺失

#### 问题描述
- **配置开关关闭时流程卡死**：如 `playerAuthEnabled` 配置关闭时，密码验证流程仍执行
- **配置值范围校验缺失**：配置加载时未验证值域合理性
- **配置热更新缺失**：运行时修改配置不会重新调度任务

#### 解决方案
1. **强制配置边界测试**：任何由配置开关控制的功能，必须审查配置关闭时的流程完整性
2. **配置值范围校验**：应在加载时完成并记录 warn
3. **配置热更新机制**：配置变更时需触发任务重新调度

### 安全问题持续关注

#### 问题描述
- **系统级安全问题被忽视**：如密码弱哈希、明文传输等长期未修复
- **日志脱敏不完整**：同一对象的 debug/info 日志使用不同脱敏路径
- **敏感数据泄露风险**：网络传输、日志输出中的敏感信息未脱敏

#### 解决方案
1. **安全专项审查**：每次相关模块审查时重新评估安全风险
2. **日志脱敏完整性**：强制同一数据源的所有日志级别使用相同脱敏函数
3. **敏感数据保护**：建立敏感数据保护清单，定期检查

## 常见代码问题模式

### 线程安全问题

#### 单线程 executor 内部 join
```java
// 错误模式
executor.submit(() -> {
    Future<?> future = ...;
    future.join(); // 阻塞线程，退化为同步执行
});

// 正确模式
executor.submit(() -> {
    CompletableFuture<?> future = ...;
    future.thenAccept(result -> {
        // 异步处理结果
    });
});
```

#### WebSocket 回调线程安全
```java
// 错误模式
onText(message -> {
    textAccumulator.append(message); // 可能被多线程调用
});

// 正确模式
onText(message -> {
    synchronized (textAccumulator) {
        textAccumulator.append(message);
    }
});
```

#### 静态状态并发访问
```java
// 错误模式
private static final Map<String, Player> CONFIRMED_PLAYERS = new HashMap<>();

// 正确模式
private static final ConcurrentHashMap<String, Player> CONFIRMED_PLAYERS = new ConcurrentHashMap<>();
private static final AtomicBoolean checked = new AtomicBoolean(false);
```

### 网络通信问题

#### 断线重连缺失
```java
// 错误模式：WebSocket 断开后无重连机制
webSocketClient.connect();

// 正确模式：添加重连逻辑
webSocketClient.connect().exceptionally(ex -> {
    scheduleReconnect();
    return null;
});
```

#### P2P 握手超时缺失
```java
// 错误模式：无超时控制
CompletableFuture<Void> future = probeAndPunch();
future.join(); // 可能永久卡死

// 正确模式：添加超时
CompletableFuture<Void> future = probeAndPunch();
future.orTimeout(30, TimeUnit.SECONDS);
```

#### 网络请求错误静默丢弃
```java
// 错误模式：非200状态码静默丢弃
if (response.statusCode() == 200) {
    // 处理响应
}

// 正确模式：记录具体错误
if (response.statusCode() == 200) {
    // 处理响应
} else {
    logger.error("API request failed: {} - {}", response.statusCode(), response.body());
}
```

### 安全问题

#### 日志脱敏不完整
```java
// 错误模式：debug 和 info 日志使用不同脱敏
logger.debug("Raw JSON: {}", json); // 未脱敏
logger.info("Sanitized JSON: {}", sanitizeRelayJsonForLog(json)); // 脱敏

// 正确模式：统一脱敏
logger.debug("JSON: {}", sanitizeRelayJsonForLog(json));
logger.info("JSON: {}", sanitizeRelayJsonForLog(json));
```

#### 密码安全问题
```java
// 错误模式：弱哈希算法
String hash = SHA256(password);

// 正确模式：使用慢哈希
String hash = bcrypt(password, 10000); // 或 argon2
```

#### 敏感数据传输
```java
// 错误模式：明文传输密码
sendPassword(password);

// 正确模式：加密传输
sendEncryptedPassword(encrypt(password));
```

### 架构设计问题

#### 上帝类
```java
// 错误模式：单一类承担过多职责
class RelayLobbyMessage {
    // 连接管理
    // 消息编解码
    // 心跳维护
    // 房间管理
}

// 正确模式：拆分职责
class RelayConnectionManager { /* 连接管理 */ }
class RelayMessageCodec { /* 消息编解码 */ }
class RelayHeartbeatManager { /* 心跳维护 */ }
```

#### 配置强耦合
```java
// 错误模式：直接读取静态配置
if (Config.playerAuthEnabled) {
    // 业务逻辑
}

// 正确模式：通过接口注入
if (config.isPlayerAuthEnabled()) {
    // 业务逻辑
}
```

#### UI 代码重复
```java
// 错误模式：两个 Screen 类 80% 代码相同
class PasswordAuthScreen {
    // 绘制逻辑
}

class RulesScreen {
    // 重复的绘制逻辑
}

// 正确模式：抽取工具类
class ScreenRenderer {
    public static void drawBackground() { /* 统一背景绘制 */ }
    public static void drawButton() { /* 统一按钮绘制 */ }
}
```

## 技术模式经验

### 定时任务模式

#### ItemCleanupManager 模式
```java
// 模式：ScheduledExecutorService + scheduleAtFixedRate
ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
ScheduledFuture<?> task = executor.scheduleAtFixedRate(
    () -> server.execute(() -> cleanupItems()),
    0, intervalSeconds, TimeUnit.SECONDS
);

// 清理模式
public void stop() {
    if (task != null) {
        task.cancel(false);
        task = null;
    }
}
```

### 线程委托模式

#### Bukkit 消息发送
```java
// 模式：异步线程 → server.execute() → 主线程
executor.submit(() -> {
    // 异步计算
    String message = calculateMessage();
    server.execute(() -> {
        // 主线程发送消息
        player.sendMessage(message);
    });
});
```

### 日志脱敏模式

#### 统一脱敏入口
```java
// 模式：单一脱敏函数覆盖所有日志级别
private String sanitizeRelayJsonForLog(String json) {
    // 正则替换敏感字段
    return json.replaceAll("\"address\":\"[^\"]*\"", "\"address\":\"***\"");
}

// 使用
logger.debug("JSON: {}", sanitizeRelayJsonForLog(json));
logger.info("JSON: {}", sanitizeRelayJsonForLog(json));
```

### 配置开关模式

#### 0 值表示禁用
```java
// 模式：0 = 禁用，>0 = 启用
int warningSeconds = config.getWarningSeconds();
if (warningSeconds > 0) {
    // 启用警告功能
    scheduleWarningTask(warningSeconds);
}
```

## 项目特有经验

### Minecraft Mod 开发经验

#### Registry 注册体系
- **原则**：新增游戏内容必须遵循 Registry 注册体系
- **方法**：使用 `DeferredRegister` 或 `RegisterEvent`
- **注意**：查询操作只能在注册完成后进行

#### Access Transformers
- **原则**：修改原版访问权限统一使用 AT
- **配置**：在 `build.gradle` 中配置 `accessTransformers`

#### NBT 数据存储
- **原则**：动态数据存储统一使用 NBT 格式
- **序列化**：使用 `CompoundTag` 存储数据

### FireflyMC 特有经验

#### 中继协议设计
- **消息格式**：JSON 格式，需统一脱敏
- **心跳机制**：定期发送维持连接
- **房间管理**：中继服务器管理房间和成员

#### P2P 打洞技术
- **打洞协议**：UDP 打洞，支持对称型 NAT
- **乱序重组**：`ReorderBuffer` 处理 UDP 包乱序
- **中继 fallback**：P2P 失败时降级到中继转发

#### 密码认证流程
- **客户端校验**：前端验证密码长度和格式
- **服务端验证**：后端进行最终验证和哈希
- **会话管理**：使用静态字段管理玩家状态

## 改进计划

### 短期改进（1-2 周）

1. **线程安全修复**
   - 修复 `RelayLobbyWebSocketClient` 线程安全问题
   - 修复 `textAccumulator` 同步问题
   - 添加 `volatile` 或 `AtomicBoolean` 到静态标志

2. **断线重连机制**
   - 添加 WebSocket 自动重连
   - 实现指数退避重试策略
   - 添加重连状态通知

3. **密码安全改进**
   - 升级哈希算法至 bcrypt/argon2
   - 改进密码策略，禁止纯数字密码
   - 加强网络传输加密

### 中期改进（1-2 月）

1. **架构重构**
   - 拆分 `RelayLobbyMessage` 上帝类
   - 明确模块职责，降低耦合
   - 抽取 UI 工具类，消除代码重复

2. **配置热更新**
   - 实现配置变更监听
   - 添加任务重新调度机制
   - 支持运行时配置修改

3. **性能优化**
   - 评估高频路径性能开销
   - 优化脱敏操作，添加缓存
   - 改进 P2P 数据传输效率

### 长期改进（3-6 月）

1. **模块化架构**
   - 进一步拆分模块，降低耦合
   - 实现接口抽象，支持扩展
   - 完善模块间通信机制

2. **测试覆盖完善**
   - 完善单元测试，覆盖核心逻辑
   - 添加集成测试，验证端到端流程
   - 实现自动化测试流水线

3. **文档完善**
   - 完善架构文档和 API 文档
   - 添加代码注释和使用示例
   - 建立知识库和最佳实践

## 检查清单

### 代码审查检查清单

- [ ] **功能完整性**：PR 描述的功能点是否都在代码中实现
- [ ] **线程安全**：检查 executor、WebSocket 回调、静态状态
- [ ] **网络通信**：检查断线重连、超时控制、错误处理
- [ ] **安全问题**：检查日志脱敏、密码安全、敏感数据传输
- [ ] **配置边界**：检查配置开关关闭时的流程完整性
- [ ] **架构设计**：检查上帝类、分层混乱、配置耦合
- [ ] **性能影响**：评估高频路径的性能开销
- [ ] **历史问题关联**：检查是否波及同模块已知风险
- [ ] **测试覆盖**：检查边界条件、异常处理
- [ ] **文档完整性**：检查注释、常量定义、接口语义

### 部署前检查清单

- [ ] **线程安全验证**：所有静态状态使用 volatile 或 AtomicBoolean
- [ ] **断线重连测试**：WebSocket 断开后能自动重连
- [ ] **密码安全验证**：哈希算法符合安全标准
- [ ] **配置边界测试**：配置开关关闭时流程正常
- [ ] **性能测试**：高频路径性能符合要求
- [ ] **兼容性测试**：支持不同 Minecraft 版本和 NAT 类型

### 运维监控清单

- [ ] **日志监控**：检查敏感信息泄露
- [ ] **性能监控**：监控高频路径性能指标
- [ ] **错误监控**：记录网络请求错误和异常
- [ ] **安全监控**：定期检查安全漏洞
- [ ] **配置监控**：监控配置变更和热更新