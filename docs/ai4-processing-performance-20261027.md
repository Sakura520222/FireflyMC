# 十玩家加工负载与生产保存泵

本子记录只覆盖隔离无界面专服的加工组件负载，不等于完整 AgentRuntime、供应商、图形客户端或整合包长期性能验收。实际游戏实例保持只读。

## 旧证据与本次变化

旧 `ProcessingPerformanceScenario` 已运行十名已认证 mock 网络玩家、八轮共八十次实际工作台合成，但在每轮调用 `saveAll`、统计/进度保存及 `level.save`，主动加速了原生保存。旧吞吐与延迟仍保留为当时夹具证据，不作为当前生产保存泵下的指标。

2026-10-27 本次只修改验证夹具，不改变生产保存器、工具、调度或世界规则：

- 默认 `fireflymc.validation.forceFixtureSaves=false`，运行循环不再请求全服、玩家或世界保存。显式设为 `true` 才执行旧夹具保存分支，并累加 `fixtureSaveRequests`。
- 同一操作始终复用最初的 `AgentTool.Context`、`TaskControl`、taskId、完整调用和批准摘要。续接不新建 120 秒控制令牌，不延长原截止时间。
- 实际 `Deferred.readiness` 完成后才续接，支持输入、回调/产物和最终保存的多阶段等待；每轮保留有界的续接次数，等待期间不占桥接 IO worker 执行槽。实际输入保存等待与各阶段数量独立记入报告。
- 生产泵可能在驱动器收集十名玩家的等待结果期间完成部分保存，因此不要求驱动器稍后观察时 `inputsSaved` 仍为 false。仍要求每次初始返回确为 `WAITING_INPUT_SAVE`，未续接时状态为 `ESCROWED` 且没有产物；最终每项必须具备真实 canonical journal 保存证明及 `ledger.saved()`，所有玩家物料守恒与工作区隔离断言保留。
- 报告新增 `forceFixtureSaves`、`fixtureSaveRequests`、`usesProductionNativeSavePump`、`deferredPhases`、真实 `nativeSaveDispatch` 指标。保留既有计时字段，使用时必须同时说明保存模式，不能与旧强存数据混算。

## 固定场景与本次实际结果

入口：`scripts/ai4-validate-server.ps1 -Scenario dedicated-performance`。仍为原十名已认证 mock 连接、100 Tick 预热、八轮共八十次原版工作台合成，每名玩家每轮仅一个在途操作，四个桥接工具 IO worker。每名玩家预置 32 槽木板，每轮实际消耗四块，最终恰收八个工作台。

根代理统一运行一次新场景，报告 `build/ai4-validation/reports/runs/dedicated-performance-c7e37602-5b7d-4a4a-83e1-99bdcd917d8f.json`：**PASSED，80 / 80 个原生加工操作**。环境 Windows 11、Java `21.0.12.1`、Minecraft `1.21.1`、NeoForge `21.1.264`，堆上限 1 GiB；加载 Minecraft、NeoForge、FireflyMC 与验证 Mod，不能当作 200+ Mod 负载。

- 实际工作负载 **38,911 ms / 779 Tick**，100 Tick 预热，验证分发器测量 898 Tick；吞吐 **2.055974 次/秒**。这些是本次生产保存泵下的新指标，不与旧强存场景混算。
- 十名玩家均实际少 32 块木板、多八个工作台；80 条账本操作完成，所有归属、真实 canonical journal、最终 `ledger.saved()` 断言通过。
- `forceFixtureSaves=false`、`fixtureSaveRequests=0`、`usesProductionNativeSavePump=true`；实际观察到 **80 次 `WAITING_INPUT_SAVE` 和 80 次 `WAITING_FINAL_NATIVE_SAVE`**。十玩家调用都等待时的 16 次桥接 IO 活跃采样均为 0，峰值活跃桥接 IO 为 4。
- 已用堆峰值 **362,388,232 字节**，堆上限 1,073,741,824 字节。未测长时间分配/GC 趋势。

| 本次计时 | 样本 | 均值 | p95 | 最大 |
|---|---:|---:|---:|---:|
| 规划延迟 | 80 | 1,046.2994 ms | 1,726.8584 ms | 2,171.0958 ms |
| 初始派发至输入等待 | 80 | 1,216.9393 ms | 1,410.0807 ms | 1,449.7210 ms |
| 含生产原生保存的完成观察延迟 | 80 | 3,805.5990 ms | 4,256.0941 ms | 4,304.2462 ms |
| 滚动 MSPT | 39 | 2.785526 ms | 7.190895 ms | 9.849627 ms |

盘点单 Tick 最大 **7.6051 ms**、超过 2 ms 的采样 3 次；加工派发单 Tick 最大 **17.1791 ms**、超过 2 ms 的采样 17 次。生产保存派发记录 352 次调用、352 次检查、失败 0；`PLAYER_NBT` 单次最大 **41.4809 ms**，聚合单 Tick 最大 **41.5640 ms**。保存派发的超软预算 Tick 为 319 次，不能因为滚动 MSPT 较低就隐藏这些峰值。

报告生成采样点仍有 **queuedTargets=1、inFlightStores=1**；不写成 0，也不把这一时刻说成保存泵已完全排空。它是调度器在运行中的全局快照；本次八十条被测操作都满足独立的正向保存与结清断言，不能反过来用全局队列大小代替逐操作证明。

本子批未自行运行 Gradle、专服或测试；以上为根代理一次新场景产生的独立报告，旧强存结果仍保留为历史记录。

生产原生保存不可抢占，2 ms 为派发软预算。即使守恒与保存正确，也不据短场景声称硬 Tick 上限、200+ Mod 持续负载、真实十客户端或 FPS 通过。没有暂存、提交、发布、部署或修改实际实例。
