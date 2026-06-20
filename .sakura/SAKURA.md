# FireflyMC 项目概述

## 项目简介
FireflyMC 是基于 Java 的 Minecraft 模组（NeoForge，MC 1.21.1），核心功能为 WebSocket 中继和 P2P UDP 打洞联机，附带 AI 聊天、称号系统、在线时长限制、掉落物清理、新手福利包、更新检查等。

## 技术栈
Java 21 · Gradle · NeoForge 21.1.219 · Java 11 HttpClient (WebSocket) · UDP P2P · Gson · GitHub Actions

## 架构设计
- **中继模块**: `RelayLobbyWebSocketClient`，管理房间/成员
- **P2P 模块**: `P2PConnectionManager` + `ReliableUdpChannel`（SendWindow + ReorderBuffer），支持 IPv6 双栈
- **会话管理**: `ClientEventWebSocketClient`，含会话生命周期与连接代际
- **认证锁定**: `ClientAuthLockoutManager`（服务端判定→Payload同步→客户端拦截）
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
- **反射静默失败**: 空 catch 块无日志；`ModNetwork.java` 6处空catch经三轮修复仍失败，标记🔴需资深开发者介入
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

| 忙轮询 while+sleep 标记 suggestion | suggestion |
| 新增 Mixin 必须评估兼容性与冲突 | major |
| 用户输入(命令/配置)边界校验 | major |
| HttpClient 实例必须复用 | minor |
| 配置开关关闭时流程完整性 | major |
| 增量审查必须声明边界，扫描同模块历史问题 | process |
| 安全机制失效默认 major（限流/锁定/认证绕过） | major |
| 隐私外传配置默认值 false | major |
| WebSocket 链路审查三件套（重连+线程安全+closeHandler） | major |
| 长连接兜底路径（超时+fallback+reset） | major |
| WorldRenderer 强制检查清单（5项） | major |
| Mixin 移除必须验证功能对等 | major |
| 同类缺陷强制批量修复（不允许单点修复） | major |
| 配置变更逐项审查（默认值+边界值+热更新） | major |
| 网络包客户端防御校验（数值字段范围检查） | major |
| 新增文件>50行必须有审查意见（正面评价≠意见） | major |
| 修复类PR强制本地编译，CI绿色或截图 | major |
| 同一PR连续两轮评分下降→建议更换修复者 | process |
| 开发者暴露语法缺陷时审查必须给可复制模板 | major |
| 全量审查按功能链路组织（配置→触发→传输→处理→展示→清理） | process |
| 正面评价不替代审查意见（新增核心文件至少1条改进建议） | major |

## 常见错误模式
- 增量修复隧道视野（忽略同模块遗留风险）
- 静态状态缺 volatile/AtomicBoolean
- 网络请求非200静默丢弃
- 变量名与单位脱节（含 Seconds 用 MINUTES）
- 大版本更新分特性审查不足
- 修复能力断层：开发者不理解"修什么"，将审查意见当单行任务执行，非全局模式修复
- 新代码偏见：对写得好的新代码放松审查（正面评价≠审查意见）
- 全量审查≠逐文件扫读，应按链路组织
- 安全审查缺乏攻击者视角：安全控制能否被绕过

## 仓库信息
- 仓库名: Sakura520222/FireflyMC
- 语言统计: Java: 573458
- 累计反思次数: 31
