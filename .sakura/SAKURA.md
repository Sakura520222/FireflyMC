# FireflyMC 项目概述

## 项目简介
FireflyMC 是基于 Java 的 Minecraft 模组（NeoForge，MC 1.21.1），核心功能为 WebSocket 中继和 P2P UDP 打洞联机，附带 AI 聊天、称号系统、在线时长限制、掉落物清理、新手福利包、更新检查等。

## 技术栈
Java 21 · Gradle · NeoForge 21.1.219 · Java 11 HttpClient (WebSocket) · UDP P2P · Gson · GitHub Actions

## 架构设计
- **中继模块**: `RelayLobbyWebSocketClient`，管理房间/成员
- **P2P 模块**: `P2PConnectionManager` + `ReliableUdpChannel`（SendWindow + ReorderBuffer），支持 IPv6 双栈
- **会话管理**: `ClientEventWebSocketClient`，含会话生命周期与连接代际
- **管理器层**: `TitleManager`（称号）、`ItemCleanupManager`（清理倒计时）、`PlayerPasswordManager`（密码）等单例
- **线程模型**: 异步线程 → `server.execute()` → 主线程；虚拟线程用于网络请求
- **日志脱敏**: 统一 `sanitizeRelayJsonForLog()`

## 已知问题

### 严重
- **线程安全**: `RelayLobbyWebSocketClient` 多线程未同步；`textAccumulator` 非线程安全
- **Executor 内部阻塞**: `SingleThreadExecutor` 内 `Future.join()` 退化为同步
- **断线重连缺失**: WebSocket 无自动重连
- **静态状态膨胀**: `RelayGuestJoiner` 等无生命周期重置
- **密码安全**: 纯数字密码 + SHA-256 弱哈希
- **TOCTOU 竞态**: ConcurrentHashMap 组合操作（更新+持久化+同步）缺 synchronized
- **closeHandler 链断裂**: 资源清理链未覆盖所有异常断开路径

### 设计缺陷
- **上帝类**: `RelayLobbyMessage` 职责过多
- **忙轮询**: `while(isFull()) sleep(5)` 应改 `Condition.await()`
- **反射静默失败**: 空 catch 块无日志
- **IPv6 敏感地址**: 可能上报 link-local/ULA
- **Mixin 兼容性**: SRG/Intermediary 映射未验证

## 审查规则
| 规则 | 级别 |
|------|------|
| 日志脱敏全级别统一 | major |
| 单线程 executor 禁止内部 join | major |
| WebSocket/HTTP 回调共享状态必须同步 | major |
| 长连接缺失重连→拒绝合并 | blocker |
| 反射 catch 必须记录日志 | major |
| 组合操作(状态+副作用)须 synchronized | major |
| join() 必须配套 orTimeout() | major |
| 静态可变状态: volatile + reset() | major |
| 跨线程静态布尔标志用 AtomicBoolean | major |
| 新增文件(>50行)必须有审查意见 | suggestion |
| 忙轮询 while+sleep 标记 suggestion | suggestion |
| 新增 Mixin 必须评估兼容性与冲突 | major |
| 用户输入(命令/配置)边界校验 | major |
| HttpClient 实例必须复用 | minor |
| 配置开关关闭时流程完整性 | major |
| 增量审查必须声明边界，扫描同模块历史问题 | process |

## 常见错误模式
- 增量修复隧道视野（忽略同模块遗留风险）
- 静态状态缺 volatile/AtomicBoolean
- 网络请求非200静默丢弃
- 变量名与单位脱节（含 Seconds 用 MINUTES）
- 大版本更新分特性审查不足

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 语言统计: Java: 536629
- 累计反思次数: 21
