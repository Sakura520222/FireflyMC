# FireflyMC 项目概述

## 项目简介
FireflyMC 是基于 Java 的 Minecraft 模组（NeoForge，MC 1.21.1），核心功能为 WebSocket 中继/P2P UDP 联机、AI Agent 聊天（tool calling）、认证锁定、称号/时长限制/掉落清理等。

## 技术栈
Java 21 · Gradle · NeoForge 21.1.219 · Java HttpClient(WS) · UDP P2P · Gson · OpenAI API · GitHub Actions

## 架构设计
- **中继/P2P**: `RelayLobbyWebSocketClient` + `P2PConnectionManager`(SendWindow+ReorderBuffer)
- **AI Agent**: `AgenticToolLoop`(tool calling) + FunctionTool注册表 + `ToolContext`统一上下文
- **认证锁定**: `ClientAuthLockoutManager`(JSON持久化) + `PlayerPasswordManager`
- **会话**: `ClientEventWebSocketClient`，含连接代际
- **管理器**: `TitleManager`/`ItemCleanupManager`/`ChatHistoryManager`(热重载)等单例
- **命令**: 统一前缀 `fireflymc <sub>`

## 已知问题

### 严重
- **ModNetwork 空 catch**: 4+轮未修🔴，根源在反射分发器，需资深介入
- **线程安全**: `RelayLobbyWebSocketClient`多线程未同步；`ClientState`静态膨胀缺volatile
- **Auth锁定**: 纯客户端可删文件绕过；lockoutMinutes缺校验；disconnect包序竞态
- **AgenticToolLoop**: tool_calls截断后历史未同步致API拒绝

### 设计缺陷
- **AI Tool安全**: 缺参数校验框架，破坏性操作无额外安全层
- **上帝类**: `RelayLobbyMessage`职责过多
- **密码安全**: 纯数字+SHA-256弱哈希

## 审查规则
| 规则 | 级别 |
|------|------|
| 日志脱敏全级别统一 | major |
| 单线程executor禁止内部join | major |
| WS/HTTP回调共享状态须同步 | major |
| 长连接缺重连→blocker | blocker |
| 组合操作须synchronized | major |
| join()须配orTimeout() | major |
| 静态可变: volatile+reset() | major |
| 网络包客户端防御校验 | major |
| 安全机制失效默认major(含AI) | major |
| 新文件>50行须有意见 | major |
| 同类缺陷>2轮未修→阻断 | major |
| 🔴模块增量提升一级 | major |
| AI破坏性操作须白名单 | major |
| AI查询结果强制截断≤50 | major |
| 发包后disconnect须包序原子 | major |
| 安全功能须服务端全覆盖 | major |
| 删除>50行须确认校验迁移 | major |
| Mixin兼容性逐个验证 | major |
| 配置热重载三要素 | major |
| tryParse()强制判空 | major |
| toLowerCase须Locale.ROOT | minor |
| PR描述不符→minor | minor |
| major→须request_changes | process |
| 增量审查文件覆盖率约束 | process |
| 全量两阶段(链路+清单) | process |
| 增量评分锚定基线 | process |

## 常见错误模式
- **增量隧道视野**: 大PR增量覆盖微量，结构风险不可达
- **评分漂移**: 局部高分稀释遗留问题紧迫感
- **备注≠行动**: ≥3轮备注为免责须阻断
- **静默失败>异常**: tryParse返回null须强制处理
- **AI Tool错误=安全边界**: 错误响应喂养AI决策
- **防御编程缺失**: 网络/反射/HTTP均缺防御

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 语言统计: Java: 573458
- 累计反思次数: 41
