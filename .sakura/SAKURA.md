# FireflyMC 项目概述

## 项目简介
FireflyMC 是基于 Java 的 Minecraft 模组（NeoForge，MC 1.21.1），核心功能为 WebSocket 中继/P2P UDP 联机、AI Agent 聊天（tool calling）、认证锁定、称号/时长限制/掉落清理等。

## 技术栈
Java 21 · Gradle · NeoForge 21.1.241 · Java HttpClient(WS) · UDP P2P · Gson · OpenAI API · GitHub Actions

## 架构设计
- **中继/P2P**: `RelayLobbyWebSocketClient` + `P2PConnectionManager`(SendWindow+ReorderBuffer)
- **AI Agent**: `AgenticToolLoop`(tool calling) + FunctionTool注册表 + `ToolContext`统一上下文
- **音乐**: 客户端代理搜索/下载/播放，`MusicQueueManager`(服务端权威)，`MusicPlaybackManager`(本地)，异常清理统一入口
- **认证锁定**: `ClientAuthLockoutManager`(JSON持久化) + `PlayerPasswordManager`
- **会话**: `ClientEventWebSocketClient`，含连接代际
- **管理器**: `TitleManager`/`ItemCleanupManager`/`ChatHistoryManager`(热重载)等单例
- **命令**: 统一前缀 `fireflymc <sub>`

## 已知问题

### 严重
- **ModNetwork 空 catch**: 4+轮未修🔴，根源在反射分发器，需资深介入
- **线程安全**: `RelayLobbyWebSocketClient`多线程未同步；`ClientState`静态膨胀缺volatile；`MusicQueueManager`并发会话缺原子/同步；`MusicCache` LRU 删除并发安全未确认
- **Auth锁定**: 纯客户端可删文件绕过；lockoutMinutes缺校验；disconnect包序竞态
- **AgenticToolLoop**: tool_calls截断后历史未同步致API拒绝

### 音乐与网络
- **HTTPS→HTTP**: `HttpClient.Redirect.ALWAYS`未修复，明文下载风险持续；多入口未统一整改
- **join()缺超时**: 时长探测等异步阻塞未配 `orTimeout`；单线程 Executor 中 `join()` 致阻塞风险
- **Payload枚举越界**: 多处 `values()[ordinal]` 缺边界检查；`FailureCode.ordinal()` 直接用于网络传输，兼容性风险
- **资源清理**: `JavaSoundOutput`、`HttpClient`、`.part`文件异常路径未统一关闭
- **日志脱敏**: 歌名/昵称等用户可编辑字段未统一脱敏；新代码仍有未脱敏日志点；`MusicServerBridge.onClientFailure` 未使用 `sanitize()`
- **缓存完整性**: 半截缓存落盘被当作完整缓存；`finalizePartFile`前未校验长度/校验和；`ensureInitialized` 失败路径缺单元测试
- **计数误导**: `wipeLegacyCache` 计数包含删除失败文件，日志与实际不符
- **早EOF冲突**: 早EOF容差240s与fallback时长冲突，可能导致播放中断误判

### 设计缺陷
- **AI Tool安全**: 缺参数校验框架，破坏性操作无额外安全层；图片多模态需隐私默认关闭与日志脱敏
- **上帝类**: `RelayLobbyMessage`职责过多
- **密码安全**: 纯数字+SHA-256弱哈希
- **配置可见性**: 多线程读取 `Config` 缺 `volatile`
- **文档-代码一致性**: `requestVolume` 所属文件未标注；`queue` 指令可能刷屏无分页

## 审查规则
| 规则 | 级别 |
|------|------|
| 日志脱敏全级别统一 | major |
| 网络发包主线程强制化 | major |
| HTTPS→HTTP重定向禁止 | major |
| join()须配orTimeout() | major |
| HttpClient单例复用 | major |
| Payload枚举安全校验 | major |
| 渲染器push/pop配对try/finally | minor |
| 配置可见性volatile/同步 | major |
| 事件注册统一管理 | minor |
| 热更新验证 | info |
| 文件层级安全审计 | major |
| 渲染线程安全声明 | minor |
| 单线程executor禁止内部join | major |
| WS/HTTP回调共享状态须同步 | major |
| 长连接缺重连→blocker | blocker |
| 组合操作须synchronized | major |
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
| 许可证同步检查 | major |
| 安全/可靠性改动须配套单元测试 | major |
| PR描述不符→minor | minor |
| major→须request_changes | process |
| 增量审查文件覆盖率约束 | process |
| 全量两阶段(链路+清单) | process |
| 增量评分锚定基线 | process |
| 所有日志点统一脱敏 | major |
| 计数日志仅统计成功操作 | major |
| 异常路径须有单元测试 | major |
| 业务阈值禁止硬编码 | major |
| 枚举序列化须安全校验 | major |
| 流播放状态图显式定义 | major |
| HTTP重定向仅允许HTTPS | major |

## 常见错误模式
- **增量隧道视野**: 大PR增量覆盖微量，结构风险不可达
- **评分漂移**: 局部高分稀释遗留问题紧迫感
- **备注≠行动**: ≥3轮备注为免责须阻断
- **静默失败>异常**: tryParse返回null须强制处理
- **防御编程缺失**: 网络/反射/HTTP均缺防御
- **测试-契约分离**: fail-closed 路径缺测试导致回归
- **日志脱敏遗漏**: 新增日志点未走统一脱敏

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 语言统计: Java: 884925
- 累计反思次数: 15
