# AI 4.0 Dynamic Trees 原生采集实现记录（2026-10-26）

本记录描述本轮新增代码和静态证据。尚未启动真实 DT/Plus 运行场景，不能把下面的实现路径、协议单测或编译通过写成整树行为验收通过。

## 实装对象与静态依据

- 只读目标：`D:\Game\MC\.minecraft\versions\FireflyMC\mods` 内的 Dynamic Trees NeoForge 1.21.1 **1.7.2** 与 Dynamic Trees Plus **1.3.2**。
- REA 打开实装核心 JAR，SHA-256：`ef94737b99e68a0b489b56f901fcc71a3ac8e7a16e3a22ac8413c5f66df566f0`。实际实例未改写。
- 原生签名与字节码位于忽略的 `build/ai4-validation/integrations/environment/tree-*.txt`，包括 `tree-harvest-protocol-20261026.txt`、`tree-falling-bytecode-20261026.txt`、`tree-plus-mushroom-bytecode-20261026.txt`、`tree-animation-bytecode-20261026.txt` 与 `tree-player-pickup-bytecode-20261026.txt`。
- `BranchBlock.onDestroyedByPlayer` **创建 FutureBreak 并返回 false**；`FutureBreak.process` 在后续 Tick 调用 `futureBreak`。后者调用真实 `destroyBranchFromNode`、树种掉落、`FallingTreeEntity.dropTree` 与 `damageAxe`。因此 false 不是“未执行，可以重试”的证据。
- `FallingTreeEntity.tick` 后续执行邻接更新、倒树动画、叶片/木材掉落与实体删除。Fallover 动画在伤害事件之前就可能改变生物速度，因此不能仅取消 hurt 事件宣称影响已被约束。
- Plus 蘑菇使用 `destroyMushroomCap` 和真实形状簇；仙人掌使用自己的原生网络分析。它们不按普通整块原木遍历破坏。

## 当前代码路径

主包：`src/main/java/firefly520/fireflymc/ai/integration/trees/`。

- `DynamicTreeAccess`：精确版本/类核验；原生 `MapSignal + SpeciesNode + NodeInspector` 调查。显式关闭原生默认启用的 `destroyLoopedNodes`，拒绝循环、多根、溢出、越界网络和未加载边界。完整批准范围包括原生家族扩展两次的冠层读写范围、树干/根/壳体和邻接边界。
- `TreeHarvestPlan`：固定 operationId、玩家/服务端/主会话/隐私/选区/注册表版本、真实手持工具、树种/家族/根、完整坐标和原状态、原生配置指纹与计划摘要。
- 普通就近起手不要求玩家先手工指定两角点：服务端在树根周围生成最多 16,317 格的只读候选预算，完整原生影响范围最终写入固定 plan，只有该 plan 的工作台审批授予执行权，不调用通用区域 confirm。默认树 plan 不受先前施工选区限制；`useSelectedRegion=true` 才显式复用已有确认区域，远程起手则强制已有确认区域及独立 proxy 权限。树冠展开逐格核保护，不因树冠超过玩家触及距离而追加远程起手权限。
- DT 原生 SoilBlock 的精确 SpeciesBlockEntity 按版本适配，砍伐前每阶段重新核验根树种；其他数据块仍拒绝，不把原生树根误判为任意容器。
- `TreeHarvestService`：工作台计划/审批/执行/状态工具；复用已有有界主线程 dispatcher；IO 使用单线程、有界队列。共享 `MaterialLedger.NativeScopeLease` 锁定本人真实库存，同时共享 `NativeWorldScopeLease` 锁定真实影响坐标。启动恢复只恢复负向资源围栏，不获得执行权。
- `TreeHarvestNative`：真实 START/STOP/ABORT、每实际 Tick 至多一次原生挖掘进度、真实 `gameMode.destroyBlock`。只有外层 ServerPlayerGameMode.destroyBlock 正常返回 true、树状态未变，且捕获恰好一个相同玩家/世界/坐标/状态/ticks=0 的原生 FutureBreak 才进入 QUEUED（DT 自身 onDestroyedByPlayer 返回 false，与外层布尔语义不同）；后续真实 Tick 调用该原生回调一次，不重排另一份作业。异常下恢复原生静态 destroyMode，防止污染其他玩家的后续行为。
- 原生倒树和物品在本操作的保存范围中调用真实 `ServerLevel.tickNonPassenger`。正常世界调度暂不重复推进这些带本操作标签的实体；普通玩家手动砍树和其他来源的掉落不进入该范围。
- `DynamicTreeNativeScope` 与专用 mixin：只移交本操作的 FutureBreak，只接收实际原生生成的 FallingTreeEntity/标准 ItemEntity；每次世界写入仍经过唯一全局 mutation tracker 及逐格权限重核验。未知新实体、越界写入或未适配副作用进入恢复状态。
- 真实耐久和饥饿由原生行为产生，记录实际手持快照与 exhaustion，不用 MATERIALS_ONLY 免除采集成本。

## 倒树、掉落与保存

- 原配置开启倒树伤害时，固定审批明示本人承担原生倒树碰撞、击退和伤害风险。精确 DT 1.7.2 的 `FalloverAnimationHandler.testEntityCollision` 返回原生真实碰撞列表后，在 `handleMotion` 首次目标速度写入及 hurt 之前整体核验：只有本人是已批准活体，其他活体触及真实碰撞几何则停止该阶段并转待对账。手动树完全不受此 scope 约束；不修改配置、免疫、速度或伤害规则。旧全方向球门禁已删除，附近但不相交的实体不再阻止起手。
- 原生生成的掉落实体保留 UUID、组件、真实数量和坐标。只在本人实际原版碰撞拾取范围内调用原生 `ItemEntity.playerTouch`，遵守自然拾取延迟和原生事件；不传送玩家、远程收物或清零延迟。
- 当前精确 1.21.1 `Player.aiStep` 的正常非骑乘拾取范围为玩家 AABB 扩展 `(1, .5, 1)`；直接调用 playerTouch 不会自行验证这个距离，因此执行器显式核验。
- 40 个自然采集 Tick 后仍有实际世界余物，结果为 `WORLD_REMAINDER`，工具返回 `success=false / TREE_REAL_PICKUP_REQUIRED`。原目标未拾齐；已确认副作用可以保存结算，实际余物继续保留世界，不能宣称已进入本人库存。
- 未结算实体带独立树操作标记，阻止外部 Tick、拾取及合并破坏待核验身份；保存结算后恢复正常原生行为。其他来源和其他适配器的实体不受该标记约束。
- 成功终态最后一次原生保存同时证明世界余物的 persistent 标记已经移除；等待凭据/日志确认时仍以服务端与真实 UUID 的内存索引隔离。未结算重启只按日志中的原生 UUID 恢复负向隔离。这样只有标记清理已 canonical 保存的已结算历史才能被隐私擦除，不会因旧日志擦除而在下次重启重新冻结已结算余物。
- 倒树/掉落实体在被原生更新、拾取或删除前，使用现有 `NativeSaveObserver.suspendEntity`，之后把旧凭据 handoff 到实际玩家/世界/新实体状态/实体删除的组合保存凭据，避免树在首次保存前消失使早期 join 凭据永久挂起。统计、进度、POI、随机序列与 canonical 原生数据保存仍通过已有观察器处理。
- 完整实际结果先记入独立 `tree-harvest/epoch-*/journal.jsonl`；每原生阶段有独立 durable intent 和实际结果。每阶段 canonical 原生保存凭据齐全之前，不释放共享物理库存/世界范围。

## 取消与未验证边界

- 原生砍伐之前的可靠取消走真实 ABORT，丢弃本执行器持有的未执行 FutureBreak。已产生倒树/掉落/未知副作用之后，取消、断线、失败、重启进入 `RECOVERY_REQUIRED`，不重砍、不补发、不回滚创造材料。
- 重启时未结算日志恢复负向物理/世界围栏；未结算树/掉落实体保持隔离，不能普通 resume。
- `TreeRecoveryModule` 已提供专用正向对账：仅当所有已接受阶段都有完整已知终态、每阶段 canonical 保存凭据齐全、原生任务已停止、没有尚在运行的原生倒树，且冻结余物逐一符合原生 UUID、组件、真实数量和位置时，才生成新版本/新摘要审批。批准只收束该已知历史并释放原资源/实体隔离，不调用 `native.step`、不重砍、不拾取或补发，不将原目标自动判成完成。未知阶段、缺凭据、外部消失或未对账冻结实体不提供放行入口。
- 本轮新增 `TreeHarvestJournalTest` 检查固定范围/玩家隔离、QUEUED 不结算、重启不重放、缺保存覆盖、迟到结果保留恢复障碍及逐阶段凭据。它们使用明确的合成观察，仅验证协议。
- 真实 DT/Plus 类加载与 mixin 注入、树种网络/蘑菇冠层/粗干壳体、倒树和自然拾取、OPAC/FTB 逐块拒绝、满仓和取消/重启保存的实际运行行为均未验证。没有修改实际实例、启动游戏、调用模型供应商、暂存、提交、推送或发布。

## 真实运行夹具增量（待根代理执行）

- 新 TreeHarvestScenario 在隔离专服用实际 Species/Family API 放置一个3节点橡树网络及真实根土；仅夹具初始化使用 setBlock，不属于生产采集。使用已认证的两个mock连接玩家，主玩家持真实铁斧，从实际生存触及距离规划，无手工两角选区。
- 场景走生产计划、工具审批桥、真实 FutureBreak/倒树/真实输出、每阶段日志与 canonical 保存，检查其他玩家计划拒绝、斧耐久、没有传送玩家、真实地面余物非自动交付、完整资源围栏释放及 operationId 去重。
- 碰撞门禁已从实际1.7.2字节码核对：handleMotion 在 testEntityCollision 返回后，先写两次 LivingEntity.setDeltaMovement，再 hurt。新增第5个专用mixin在整个列表进入这些作用前核验。原生阶段中途失败后仍记录已有实体/方块及保存义务，不把异常变成重放资格。
- 目前尚未收到此新夹具的编译或运行通过结果；不将其写成已验收。

- 首次无DT的maid原生启动暴露确定缺陷：DynamicTreeMixinPlugin.postApply直接加载含MC类型的scope，Bootstrap重入。现已把标记迁到纯JDK的DynamicTreeHooks并在onLoad预初始化，postApply不再引用scope；Minecraft目标同样必须通过实际DT/Plus版本门禁，缺DT不安装树Tick/物品Mixin。此次失败保留，待根代理复验，未称通过。

- 首轮tree真实场景 65f9ab02-6e3a-478c-a6b5-6cbb6bf98343 被生产门禁正确拒绝（TREE_WORLD_UNLOADED_OR_OUTSIDE，尚未执行原生效果，journal为空）。栈落在完整footprint逐格审批核验，非起点或survey未加载；夹具超平坦地表y=-60，原生树冠/邻接包络向下越过最小建造高度-64。夹具已把树地平面提升至至少minBuildHeight+16，原先7×7真实FULL/FORCED票保持；生产未加载/世界边界门禁完全保留，待复验。

- 第二真实tree场景 d75df159-c3e5-42b4-abe8-8aa2ab8b60a5 到真实执行首stage即安全转RECOVERY_REQUIRED；未砍树、产物为空。实际DT 1.7.2 SoilBlock 同时声明 colorMultiplier(BlockColors,...)，Class.getMethod(getSpecies)枚举声明方法会在专服解析客户端BlockColors。现对受审SoilBlock.getSpecies和BranchBlock.futureBreak采用明确MethodType的public MethodHandle，只解析所需服务端描述符；不关闭DistCleaner、不跳过根树种核验。首stage/原生分派失败现记录完整真实cause栈，工具和日志保留诚实恢复状态，不伪造保存凭据或成功。待根代理复验。

- 第三真实tree场景15af233c-375b-44eb-83d3-0872fc494e80已过专服精确描述符并完成真实挖掘，READY接管却被错误的外层返回值假设拒绝，未执行FutureBreak/倒树。锁定NeoForge的ServerPlayerGameMode字节码（tree-game-mode-contract-20261026.txt）证明：destroyBlock在removeBlock/onDestroyedByPlayer=false后仍正常返回true，不能把它等同DT回调的false。现只接受外层true、恰好一个身份/坐标/原状态/ticks=0已核验的真实FutureBreak、无scope错误且原树未变；其他情况仍拒绝/恢复，不重排或重放。夹具补真实地面碰撞让mock玩家真正站立，生产挖掘时间及空中惩罚不修改。待复验。

## 根土原生延迟Tick因果闭环（待运行核验）

- d7ed557e-a997-43bf-be48-ee424e308bbf 已实际QUEUED→FALLING、3格原生变化及1个倒树实体；下一stage被原世界lease外来变更门禁拒绝。真实SoilBlock字节码显示trunk邻居改变会原生scheduleTick(root,SoilBlock,1)，tick中无trunk则doDecay。此为确定需要纳入的原生因果机制；实际首次失效来源仍需新origin诊断确认，不据此忽略其他来源。
- 新第6个专用LevelTicks.schedule钩子仅在ACTIVE AI scope捕获：同一ServerLevel实际blockTicks、计划内root、同一真实原状态/SoilBlock、原生due恰为当前gameTime+1且NORMAL优先级。保留原ScheduledTick对象、原due/顺序和原生type+position去重；其他玩家/普通树/其他调度完全不转交。已有外部根tick在起手前明确拒绝。
- 根回调独立新持久nativeRootTickIntent，包含实际捕获ID/位置/原状态/due/优先级/顺序；在已有公平dispatcher、新主线程阶段和原lease/权限/世界状态下，原due到达且原生shouldTickBlocksAt允许时调用真实BlockState.tick。先退休回调后进入原生，异常不具备重试资格。每阶段照常保存真实变更，accepted/completed根tick完整进入结果日志，缺任何实际结果时不结算、不正向放行。重启仅恢复负向围栏，不重建回调运行权。
- 夹具不再在ServerLevel.save后第二次调用同一DimensionDataStorage.save，并在前后等待原生IO完成，避免人为并发同一NeoForge SavedData原子写；保留曾发生的AccessDenied错误，不能把它当成功保存。
- 新协议回归覆盖根回调独立意图、重复意图拒绝、缺回调结果即使有保存哈希仍不可结算/重启不重放；其synthetic证据不代表真实游戏通过。等待根代理统一编译和必要单场景验证。

- 8a6a2f62-5562-45d6-ad1b-a28dfaf818d8 已捕获1个根回调但completedRootTicks始终为空，故不是动画自行卡住：调度器总优先尝试根回调。已核锁定ServerLevel.shouldTickBlocksAt字节码（tree-level-tick-predicate-20261026.txt），它直接把long传给DistanceManager.inBlockTickingRange，要求ChunkPos key，原实现误传BlockPos.asLong导致永远不在Tick范围。现改为真实ChunkPos(root).toLong，仍保留原due/真实Tick范围门禁，不延时、清动画或移动实体。夹具故障报告补有界原生tickCount/动画状态/位置速度及根due/完成/真实chunkTickRange字段，全部仅私有本地报告；保留forceFixtureSaves=false/零手动保存，待复验。
