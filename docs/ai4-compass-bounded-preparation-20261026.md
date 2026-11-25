# Explorer Compass 有界准备增量

日期：2026-10-26。此文件记录有界准备增量。生产工具与生命周期已接线，根代理统一编译及定向39项通过，其中本增量10项纯测试；**新增原生 prepared-only 场景尚未运行，不能作为未加载结构搜索已运行通过的证据**。

## 实际原生接口证据

- 当前目标为 Explorer's Compass `1.21.1-3.4.0-neoforge`。已有真实 JAR 的只读 Java 21 `javap` 证据位于 `build/ai4-validation/integrations/exploration/explorer-worker-bytecode-20261026.txt`。
- `RandomSpreadSearchWorker.doWork` 在 `getStructureGeneratingAt` 返回后才增加 samples、推进 x/z；`ConcentricRingsSearchWorker.doWork` 同样在返回后增加 samples/chunkIndex。
- `GenericSearchWorker.doWork` 在调用前已按 direction 改写 chunkX/chunkZ。若通过异常让出，下一次直接调用原方法会跳过上次候选。后续必须恢复执行前精确游标，或显式固定并完成同一候选，不能声称简单重试适用于三者。
- 本地锁定 Minecraft/NeoForge 源码 `build/moddev/artifacts/neoforge-21.1.264-sources.jar` 的 `ServerChunkCache.getChunkFuture`：**主线程分支调用 managedBlock**。不能把这个 public 方法直接当作主线程非阻塞 API。私有 `getChunkFutureMainThread` 本身返回调度 future；另一可审计路径是在专用 IO 线程调用 public 方法，由原生主线程执行器完成状态访问。
- 同一原生源码 `ChunkPyramid` 的 `STRUCTURE_STARTS` generation step 没有邻区依赖；`STRUCTURE_REFERENCES/BIOMES` 等更高阶段则要求半径 8 的结构起点。有限准备应仅请求 `STRUCTURE_STARTS`，不能为了查询而请求 FULL 并把衍生生成隐藏在“8个候选”的文字里。

## 已写入的独立协议

- `CompassPreparationBudget`：默认 maximumChunks=0；显式非零上限8，1000—30000毫秒；单调截止仅限制新的派发，晚到真实结果/保存仍须接收。
- `CompassPreparationPlan`：独立 SHA-256 覆盖原固定搜索及新增准备预算。不能拿原搜索批准自动获得新区生成批准；未来工具必须展示新的摘要并执行专门权限检查。
- `CompassPreparationGate`：一个服务器控制器共享实例，全局至多一个在途准备；精确 opaque lease 绑定本人/operation/维度/候选，首次派发后不能更改预算/维度；重复候选拒绝，已派发取消不释放并发、不重派。未知恢复只形成负向隔离。退休内存缓存不删除持久重复校验。
- `CompassPreparationJournal`：`exploration/epoch-N/preparation-journal.jsonl`，绑定服务器、永久主会话、玩家、任务、privacy/execution epoch、注册表代次、原搜索 hash 与新增准备 hash、中心/半径及预算。真实派发前先记录 INTENT/DISPATCHED；原生 future 结果只成为 READY，canonical 保存才成为 SAVED。未知派发重启转 RECOVERY_REQUIRED，不根据当前有同一chunk推断原执行已完成。不曾派发的 INTENT 可在关闭/恢复时退休，不重放生成。取消后的晚到 READY/save 保留恢复门禁，需新 revision/digest 复核。
- `CompassPreparationTest` 新增10项纯行为回归；这是协议、真实临时文件及身份/去重测试，未模拟成功生成区块。根代理统一串行编译/运行已通过；具体总结果由主实施记录记录。

## 已接入的生产与控制器路径

1. 新 `compass_prepared_search_plan/execute`，独立 preparationId/hash 和 WORLD_CHANGE 批准；原普通 `compass_search_*` 保持默认0、不生成。新权限节点 `fireflymc.ai.exploration.prepare` 与工具开关在实际操作前重新检查。主服务审批正文经 `preparedReview` 展示真实费用、完整范围及新增预算。
2. 在真实原生候选遇到未加载区块时让出，恢复三类 worker 的精确游标字段，尤其恢复 Generic 调用前已移动的 chunkX/Z。只恢复搜索游标，不恢复已扣经验、罗盘、玩家或世界状态。继续同一 native worker/同一收费搜索，不另开任务聊天。
3. `NativeCompassPreparation` 在主线程严格反射调用锁定的私有 native scheduler，直接取得 future，不经过 public 方法的 managedBlock。原生 STRUCTURE_STARTS ticket level不提升至FULL；先记录每候选INTENT/DISPATCHED，再主线程重验所有门禁，服务器gate只放行1在途。
4. 原生 future 完成后核验真实 ChunkAccess/坐标/阶段；ImposterProtoChunk 绑定实际 wrapped LevelChunk。普通Proto在本请求ticket释放后等待原生卸载保存；Imposter的 `isUnsaved()` 恒false，额外严格调用锁定 `ChunkMap.save(wrapped)` 一次，仍走原生canonical序列化/region写入及flush，不请求新FULL生成或自造NBT。native拒绝提交和未知提交分别记明确reason，保留真实READY与未保存义务。**标脏、native save返回true和future本身均不是保存证明**。准备 receipt future 与原搜索终态 receipt 合并；非派发的可信拒绝没有准备写盘义务，不伪造空receipt。
5. 取消后保留已派发工作；晚到结果与真实保存仍记录。原搜索先保持RECOVERY再回填已知STOPPED，从而有固定版本的现有COMPASS_RECONCILE入口。预览同时覆盖原搜索与准备journal快照；未知future/缺canonical proof仍不提供清除按钮。ACK、工作区资源settled/隐私门禁均核对两份journal。
6. 新增 `exportPreparationJournal`；根代理已接工作区导出和物理文件白名单。主服务接 `capabilityPolicy` 后，禁用新工具也会使在途准备停止新的派发。

## 坐标隐藏与导出投影补点

- 新 `CompassPrivacyProjection` 统一处理结果、状态、恢复卡/正文/证据和ZIP的准备journal投影。原生规则隐藏坐标时，候选x/z、原点、attempt、保存proof及可枚举推导位置的承诺/原生物品指纹均不进入客户端载荷；真实状态、数量、来源范围和预算仍保留。结构罗盘当前配置关闭坐标后，也会夹紧该准备操作旧的可见结果。
- 服务器原始journal、原始签名和完整审批证据不修改。服务端控制器/验证使用 `preparationOperations/preparationHistory` 读取原证据；不能借ZIP面向客户端的投影重新解析完整内部Operation。
- `WorkspaceExportService` 原有UUID artifact复制也是客户端可见边界；根代理须按主会话的Compass三种内部索引kind，将计划/准备计划/复核文件通过 `CompassPrivacyProjection.artifact` 投影后再写入ZIP。投影不携带可执行审批hash，明确标注 `exportProjectionOnly`，不覆盖服务器原文件。
- 新增3项纯投影回归，包含隐藏位置/承诺、当前规则重新隐藏既有结果、每版历史及签名恢复artifact保真；本补点待根代理统一构建，不扩大native矩阵。

## 仍需实际原生验证

`ExplorationScenario` 新 `fireflymc.validation.explorationPreparedOnly=true` 分支只跑这条最小链：从原生 RandomSpread placement计算新鲜候选，在实际磁盘无候选且无FULL加载的前提下规划；固定新批准、原生一次XP收费、至少1且最多2次准备、原候选续接、真实canonical STRUCTURE_STARTS文件且不提升FULL、必要的新复核与重复/玩家隔离。夹具目前尚未运行；不能把纯测试、编译或原loaded-only场景27项当作该验证通过。Generic/Concentric的游标语义已按真实字节码实现，但本最小村庄场景只运行RandomSpread，不扩大验收范围。

追加既有FULL的低开销分支：复用同一候选，夹具原生生成/保存为FULL后卸载，再由生产工具仅请求STRUCTURE_STARTS；必须观察实际wrapped保存分支、真实canonical收据、地形section保持一致、一次收费和本人不移动。新增一项纯回归保证native save拒绝不能伪装为保存成功。本补点仍待根代理统一编译和运行。

本子任务没有自行运行Gradle/游戏或供应商；运行由根代理串行协调。未修改实际游戏JAR/存档/配置，未暂存/提交/部署，生产Mixin配置无需新增条目。
