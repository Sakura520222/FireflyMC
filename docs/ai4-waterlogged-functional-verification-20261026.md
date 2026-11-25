# 含水放置与施工功能核验增量

日期：2026-10-26。对应设计 6.4.6、6.4.8、6.4.10 的第 13、15 项。本文件说明本批实现与待验证范围，不替代完整实施记录。

## 含水放置

- `PlacementWaterPolicy` 对经过原生放置审计的 `SimpleWaterloggedBlock` 保留真实原位源水；计划和真正派发前各核验一次。现有源水写在固定 before state 中，目标 BlockState 含水，实际原生 `BlockItem` 决定结果并消费真实方块物品。没有新增水桶或免费流体。
- 当前路径仅接受精确 `minecraft:water` source。无水、流水、把原有含水半台阶合成干双台阶、排出流体等必须有另一个明确的流体施工适配器，不能因目标写了 `waterlogged=true` 凭空补水。
- `NativePlacementKernel` 保留原生右键/放置事件、取消回滚、实际消耗、旁路物品和精确目标/邻接世界核验。原生水刻由真实 chunk 写入保存；未把方法返回当作保存证明。
- 含水和流体刻继续不能获得通用安全撤销 token。所有权元数据不支持不改写已经发生的真实材料与世界事实。

## 结构与功能分别报告

- `ConstructionVerification` 记录固定 plan/hash、维度、当前注册代次、时间/Tick、实际目标匹配数/未知数、差异和功能条目。结构可为 `STRUCTURE_COMPLETE`、`PARTIALLY_BUILT` 或 `STRUCTURE_UNVERIFIED`；功能另为 `NOT_APPLICABLE`、`READY`、`WAITING_ACTIVATION` 或 `UNVERIFIED`。遗漏/截断不能算所有功能已核验。
- 施工后读取实际机器条件。缺燃料/原料的真实炉子返回 `STRUCTURE_COMPLETE_FUNCTION_PENDING`，材料与物理结构仍已完成；不再用单纯 BlockState 相同推断机器可运行。`build_verify` 可以再次只读核验，并写入永久主会话的私人 `build_verification` 事实。普通 `build_status` 明确展示历史报告，不能把它当此刻仍有效的检查。
- 功能只读权限限定在该计划实际成功原生放置时捕获的真实 BlockEntity 对象。核验时重新检查当前对象、加载位置、未移除状态及容器保护；同状态的新对象、重启后丢失对象绑定、未知对象不读内部物品。检查不会添加取料/归仓来源授权，审批正文明确说明只读范围。
- 真实炉子的 LIT 变化会正确撤销旧施工 world lease，但不能因此丢掉独立的只读对象身份观察。`FunctionalObjectBindings` 用 WeakReference 和精确 owner/server/conversation/privacy/plan/hash/dimension 键保留有界只读观察（256计划、8192对象）；它不延长旧执行、撤销或取料授权。对象替换/卸载失去原引用、容量淘汰或隐私擦除后只能报未知，不能由相同 BlockState 重建观察。同 scope/位置尝试换对象登记会撤销旧观察，不转移它。
- 实际炉子、烟熏炉和高炉用锁定版本的原生 `canBurn`、`getBurnDuration`、`litTime` 和真实配方类型核验。方法描述符通过 MethodHandle 精确解析；未注册的局部副本隔离输入/燃料/输出，不调用 native setter 的世界回调，不点火、不加工、不扣经验或燃料。真实机器保留 `NATIVE_WORLD_MACHINE_COSTS`，与 AI 虚拟加工的 `MATERIALS_ONLY` 分开。
- 原版炉子等物品本身默认携带 `CONTAINER=EMPTY`，不能用 `has(CONTAINER)` 把正常空机器一律拒绝。`NativePlacementComponents` 仅接受受审六种原版方块、精确 BlockItem 类与真实注册默认一致的空组件；任意 `BLOCK_ENTITY_DATA`、非空/非默认容器继续拒绝。原生放置及 owner 回调后再次核验新 BE 精确类型、没有 loot table、真实 raw 槽位及空内容。观察不调用可能解包战利品的 `Container.isEmpty`。后置失败保留实际未知结果和托管，不退款或重放。
- 原版手动工作台检查真实交互入口；附魔台检查已加载原生书架条件并保留玩家物料/青金石/等级及 Mod 规则；容器检查真实连接/阻挡和原生锁。未知机器/配方/接口不把普通识别算作可运行。功能报告的 READY 只说明所列运行或交互条件，不能变成“已替玩家运行整个系统”的结论。
- 只读再核验从固定历史来源读取，不复用旧施工 epoch、过期 review 或旧权限。执行施工仍受原来的严格审批、世界/注册代次、资源和重启门禁约束。

## 模型最终结果的保存门禁

- `NativeWorldCompletionSaves` 等待实际 canonical 写入及其 journal writer，验证 owner、task、server、主会话及 privacy 绑定。等候释放 IO 切片，但保留原控制令牌和总截止；取消只停止等待者，不取消已接受的真实写入。已完成 future 不能代替缺失收据，缺覆盖或 writer 失败转待对账，不立即重试原生动作。
- 施工终态须物料账本完整保存、每个真实 world debit 对应 VERIFIED 步骤、每条世界/原料/回调保存义务被确认后，才返回结构/功能的最终结果。续接只读取同一原 operation，不重新取料或放置。原生明确拒绝且不含未知副作用时也收集真实保存凭据；不得因为没有成功放置而丢掉取消回滚或回调的保存义务。
- 拆除每步在真实掉落交付及世界/工具/回调保存完成后，才继续下一个尚未执行步骤；前缀 ACCOUNTED 只读核对，不重放。最终全部保存后重新核验实际目标，后续他人修改或未加载如实报告，不覆盖新方块。保存等待与“结构已完成、功能待激活”分别表示，不能混淆。
- `NativeWorldCompletionSavesTest` 四项纯行为覆盖多写入/多 journal、缺收据/失败 writer、不取消后台写入及身份/任务绑定、已经完整保存后不再派发原生步骤。真实运行证据仍由主代理统一补记。

## 本地证据和验证入口

已只读核对本机缓存的 Minecraft 1.21.1 / NeoForge 21.1.264 源码：半台阶/楼梯放置按当前位置真实水生成含水状态；合并半台阶会清空含水，因此不能沿该路径默许排水；炉子的原生容量和燃料算法及锁字段存在。本批调用真实算法，没有复制一份近似生产算法。

- 纯行为：`ConstructionVerificationTest` 三项覆盖结构完成仍待激活、未知/截断不能变 READY、身份未知报告与摘要的持久往返。
- 精简原生：`dedicated-construction` 配合 `fireflymc.validation.constructionClosureOnly=true`，由主代理统一运行。范围包括真实含水半砖物料/源水/保存、无水拒绝、原生取消保水和托管、真实炉子未激活、本服原生点火后的只读状态、同状态换 BE 不继承只读权限。旧 `constructionOwnershipOnly` 路径保持独立。
- 本子任务未自行运行 Gradle、服务器、图形客户端或供应商；下述运行由主代理统一执行并保存完整报告。

### 2026-10-27 最新精简原生结果

`dedicated-construction / construction` 的 closure 分支报告
`build/ai4-validation/reports/runs/dedicated-construction-construction-fbf9b48f-eb4e-4b59-b34c-44f515fc96cb.json`：
**23 项检查全部通过，场景 4,242ms，整轮 Gradle 51 秒**。

- `forceFixtureSaves=false`，`nativeSaveMode=PRODUCTION_NATIVE_SAVE_PUMP`；没有靠夹具手动全服保存满足断言。
- 真实含水橡木半台阶的付料、原位源水、无水拒绝、原生取消后的零消耗收据/真实托管及 canonical 保存通过。
- 真实原版空炉子的默认空 CONTAINER 组件可用；携带 BE NBT 或非空物品容器被拒绝。结构完成但缺输入/燃料时准确报告功能待激活。
- 夹具提供真实输入/燃料、原生炉子实际点火后，功能核验得到可运行条件；查询本身不消费或加工。正常 LIT 变化不再丢失独立只读绑定，同状态换 BE 仍不继承读取权。
- 报告的短场景 MSPT rolling mean 为 `10.1409274583333ms`、最大 rolling sample 为 `16.684304ms`。这不是长期负载或全整合包性能验收。

此结果覆盖实际橡木含水半台阶、原版炉子及列明的安全/保存行为。它不扩展为所有 Mod 机器可运行、所有含水族实测或湿方块通用安全撤销通过，也不代表图形客户端或真实供应商验收。
