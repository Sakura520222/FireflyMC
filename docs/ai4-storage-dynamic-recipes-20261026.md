# Storage 动态配方适配

本记录属于完整 AI 4.0 目标的增量，不将静态调查或编译当作原生通过。实际实例只读。

## 精确基线

- Storage Drawers `13.11.4`。
- Sophisticated Backpacks `1.21.1-3.25.71.1997`。
- Sophisticated Core `1.21.1-1.4.77.2173`。
- Sophisticated Storage `1.21.1-1.5.80.1999`。
- 逐 JAR SHA-256、逐类 javap、Vineflower 1.10.1 反编译源保留在忽略目录 `build/ai4-validation/integrations/storage-dynamic/`。这些是静态证据；后续隔离无界面 GameTest、专服的运行证据单列于下文，不涉及用户实际实例。

Loader 版本与完整构建版本分开绑定：Backpacks loader 为 `3.25.71`、Storage 为 `1.5.80`、Core 为 `1.4.77`；完整带 Minecraft/构建号的字符串来自各自模块的 `META-INF/MANIFEST.MF`，并非 ModList 的版本字符串。Drawers 两者均为 `13.11.4`。运行时同时检查 loader、该配方类所属模块的 manifest、Core 所属模块 manifest、精确类及 serializer/type，避免按文件名或全局 ClassLoader 的第一个 manifest 猜测。

## 首批真实实现

`ai.processing.advanced.StorageDynamicRecipes` 按精确类、Mod/Core 版本、RecipeType 和 serializer 分派。具体九格或三锻造角色先深隔离，然后调用当前服务端同一配方的 `matches/assemble/getRemainingItems`；有序裁切偏移映射回原九格。原生锻造菜单不转移合成返还物，仍按实际角色扣料。

- 抽屉：安装升级、游离抽屉升级/清除、钥匙环、个人钥匙循环、远程组升级。
- 背包：基础背包、普通/锻造升级、双色染色。
- Core：升级等级、清除升级设置。
- 方块存储：普通/双箱有序与无序升级、染色、木材款式、桶材质、平顶切换、空箱转潜影盒。

输入、输出和返还组件全部由 native 计算，不复制 JSON 静态结果。回调仍由既有带 intent 的高级执行器提交，operationId、真实取料、保存和取消不在适配器中另造一套。

BackpackWrapper 的 `setSlotNumbers` 实际仅修改隔离输出 ItemStack 的槽数组件，先前“可能改 SharedStorage”的初始推断已由完整字节码闭包排除；不借该推断将正常背包升级全部拒绝。带 UUID 的输入仍随原生组件转移，绝不另复制其内容。

## 共享副作用类别与中央提交

Sophisticated Storage 的 `StackStorageWrapper.fromStack` 在带 UUID 时会访问/可能初始化真实 ItemContentsStorage；vanilla 潜影盒转换更会新建 UUID 并将真实内容写入 Shared SavedData。新 `StorageDynamicSharedRecipe` 已接带 UUID 的四种升级及第21个 profile `ShulkerBoxFromVanillaShapelessRecipe`：

1. 根据真实输入持有的 storage UUID，读取并深隔离最多九个条目；另保留当前 operation 派生的唯一新 UUID。没有把全服 SavedData 内容放入计划、上下文或指纹。
2. 预览在私有 native `ItemContentsStorage.load` 对象中调用同配方 `matches/assemble/getRemainingItems`，不写真实条目。操作 UUID 在预览、重验及实际回调中一致；普通玩法的 UUID 生成不改变。
3. 高级执行器先保留原真实 escrow 并持久记录 native-production intent；闭包只凭不可变标识及组件/NBT快照重新解析当前配方、真实 canonical store 与选中条目。声明当前原生保存 scope 的精确对象后，调用原生 assemble，不直接把 shadow tag 灌入真实数据。
4. 实际输出与原生写集必须和固定预览吻合。Vanilla 潜影盒的真实内层 ItemStack 数量和组件单独对比，原生插入丢弃/容器不兼容不能被掩盖为成功；未知变更保留恢复屏障，不重放。
5. 仅观察到选中条目实际变化时标记同一原生 SavedData dirty，并由已有 canonical 写入观察器/受限保存泵闭合证明。没有在 helper 中强制全服保存或另造物料账本。

专属 native scope 会在其他 UUID 的 get/has/set/remove 前拒绝访问；新 UUID 最多一个。只清理这次私有 native assemble 所创建的 wrapper cache key，不清理玩家真实缓存或全局缓存。变换期只加载纯 JDK flags，维持严格 `defaultRequire=1`；没有活动 scope 时不限制普通 Mod 使用。

基础背包配方正常静态结果没有 UUID；若数据包让结果携带 UUID 或旧自定义数据，原生 `removeContentsUuid` 可能删除真实外部内容，当前不会在预览中运行该危险分支。

`StorageDynamicGameTests` 的首五条用例从当前 RecipeManager 获取真实 RecipeHolder：背包先由原生 wrapper 保存七颗钻石，再检验升级/染色输出、UUID 与全局内容不变；另核验抽屉升级组件、桶顶切换及原生清除设置。三个纯元数据行为测试本身不构成原生组件或存储 UUID 行为证明。

根代理首批报告 `adapter-runs/storage_dynamic-20261026-155853-603.json`：**79项GameTest通过，9.661秒，整个隔离运行39.098秒**，包含上述五条新纯类别原生用例。该证据证明真实 loader/manifest 双绑定、背包已有 UUID/七颗钻石/姓名保留及预览不写 SharedData、抽屉组件、桶顶与升级设置；不扩展为后加共享提交通过。

后加两条共享 GameTest（共七条）只核验私有 native 预览、已有 UUID/内层内容保持、无关 UUID 更新不进入本计划指纹、vanilla 潜影盒固定输出 UUID，以及集成 GameTest 环境的真实提交拒绝。拒绝必须同时满足 `NATIVE_BUILD_SAVE_SCOPE_INVALID`、`entered=false`、提交闭包零派发、原载体与真实共享条目不变。**GameTest 不作共享提交成功证明；正向 native-production、材料账本、实际迁移、保存与去重在独立专服验证。** 另五项纯 scope/NBT 回归覆盖条目隔离、原操作 UUID、缓存收集阶段和类型/深度/循环边界，包含新只读入口在 scope 外、其他 UUID 及 scope 退休后的拒绝。

共享提交与产物交付分开：`requiresNativeAssemblyCommit=true` 明确告知中央执行器，不能先将原料替换成只有预览 UUID 的 `PRODUCED` 记录。原真实 escrow 保持到 native-production intent 后的实际 assemble/callback；只有已观察到固定输出、余料及条目写集吻合，才能单次消费原 escrow 并进入实际 products/CALLBACK_DONE。已知 Main 零派发保留/恢复原 escrow 后才可返还，未知派发不能退款、清除或重放。中央账本/运行时已接入此协议，子 helper 不另建第二份账本；取消、超时、重启协议的回归证据以主记录为准，不由下面三条正向服务链推断。

发有共享 UUID 的真实物品前等待对应 SharedData/callback 保存证明，交付后再等待玩家/仓库保存；保存等待的续接只交付已生产产物或返回最终结果，不重新调用此前的 native assemble。工作区、原批准、控制截止和 operationId 均保持。最终专服报告实际观察到 `WAITING_INPUT_SAVE → WAITING_NATIVE_PRODUCTION_SAVE → WAITING_FINAL_NATIVE_SAVE`，结束时每个操作均为 `COMPLETED` 且 `saved=true`。

## 2026-10-27 实际失败与修复

- 首次元数据匹配曾混淆 loader 基础版本与带构建号的 manifest；已按上面的双绑定修复，首批真实运行证明实际安装版本能够进入受审 profile。
- `storage_dynamic-20261026-171412-928.log` 的共享升级 fixture 误选普通箱桶。实际 `StackStorageWrapper.isAllowedInStorage` 对非 `ShulkerBoxItem` 返回 false，插入七颗钻石被正确拒绝；现固定真实 `sophisticatedstorage:copper_shulker_box` 配方，不放宽原生容器限制。另一项失败来自错误要求精妙输出也提供通用 ITEM capability，现按该版本实际共享存储语义处理。
- 随后的 GameTest 真实提交被 `NATIVE_BUILD_SAVE_SCOPE_INVALID` 拒绝：GameTestServer 不是专用服务端，生产门禁正确。已将这两条用例改为预览及拒绝验证，将正向提交留在 dedicated fixture；没有为测试放宽门禁。
- Vanilla 潜影盒的守恒核验曾再次调用 `fromStack`。`storage_dynamic-20261026-172952-276.log` 保留完整 cause：`getContentsNbt → getUpgradeHandler → StorageWrapper.load → fromStack` 尝试第二次 UUID 分配，触发 `STORAGE_DYNAMIC_UUID_BUDGET`；深拷贝载体仍不足以消除原生加载副作用。现在输出核验只通过当前 native scope 的受限 `storeForRead(uuid)` 读取已存在的选中条目副本，用原生 `ItemStackHandler` 解码；vanilla 源仍读取真实 capability。没有为核验打开 wrapper、初始化条目、消耗第二个 UUID 或降低组件/数量守恒要求。
- 首次专服背包输入被盘点拒绝，原因是本人物品栏中的 BackpackItem 载体默认不作为可提取材料，而非 `MaterialPreferences` 默认保留项。fixture 通过真实箱子、`blockCandidate`、预览及确认授权提供载体，其他配料仍在本人背包；精确绑定来源、槽位和完整组件，不构造假 stock/grant。最终验证箱内载体确已取空，重复请求仍为空。生产的本人背包载体保留规则未改；从已授权仓库提供载体是本次实际通过的入口。
- “已有 UUID 升级绝不能增加任何 key”的旧 fixture 断言不符合此版本真实原生写集：native 可能产生一个确定的空 reserved 条目，同时输出保留原 UUID。现在仅接受固定计划预测的、同 operation 派生的至多一个新 UUID，并逐阶段核对全局精确集合；所有无关旧条目仍逐条比较完整 NBT。旧 live-wrapper 检查还可能自行创建随机身份，已改为只读 canonical NBT 副本解码；最终 `afterService == afterReadOnlyInspection`，无检查侧新增身份。没有删除原生空条目或清理此前失败留下的隔离世界数据来掩盖差异。
- 分阶段 UUID 集合起初以匿名 `keySet` 交给 Gson，诊断字段被序列化为 null/省略；现显式构造排序后的 `JsonArray`。原集合等式及完整 NBT 断言没有削弱。各次失败报告和日志均保留，不改写为通过。

## 最终独立运行证据与限制

使用 Java 21、项目锁定的 Minecraft 1.21.1 / NeoForge 21.1.264，以及 `storage_dynamic` 精确 JAR 子集。根代理统一执行；本子批没有另外启动 Gradle、游戏或收费模型。

- 专服入口：`scripts/ai4-validate-server.ps1 -Scenario dedicated-storage-dynamic -ModProfile storage_dynamic`。报告 `build/ai4-validation/reports/runs/dedicated-storage-dynamic-storage_dynamic-84a7df1b-b2d3-4a48-bbfe-1bd411b8db71.json`：**PASSED，73 项检查 / 3 条真实服务链，7,490 ms，场景 156 Tick（分发器测量 175 Tick）**；`fixtureSaveRequests=0`，使用生产保存泵，共三个真实 `ItemCraftedEvent`。

| 实际配方 | 耗时 | 本次验证范围 |
|---|---:|---|
| `sophisticatedbackpacks:copper_backpack` | 3,400 ms | 已授权箱子中的载体真实取料；原 UUID、命名七颗钻石和载体名称保持；原生回调一次、保存、重复拒绝及主会话回读 |
| `sophisticatedstorage:copper_shulker_box` | 2,571 ms | 已有 UUID 的 native-production；固定原生写集、唯一可预测 reserved 条目、所有无关旧条目保持；内层七颗钻石和名称、保存前等待、实际交付与去重 |
| `minecraft:shulker_box_from_vanilla_shulker_box` | 1,437 ms | vanilla 容器真实材料迁入 operation 固定 UUID；预览不写真 store、原生提交后才产生条目；内层组件/数量、全局精确集合、只读检查、保存、去重及主会话回读 |

三条链均经过实际 `AdvancedProcessingService → GameToolBridge`、现有认证的 mock 网络玩家、真实库存/材料账本及 canonical 保存。每条只有一次真实 crafted event，最终 `COMPLETED/saved`，实际工具完整结果从同一永久主会话重新打开读取。此场景不含完整 `AgentRuntime` 或供应商推理，不冒充跨 JVM/断电、图形客户端或全整合包验收。

- GameTest 报告 `build/ai4-validation/adapter-runs/storage_dynamic-20261026-173450-423.json`：**81 / 81 通过，测试执行 6.941 秒，整轮 24.560 秒**。其中本组为前述七条；其余为该隔离 profile 一同运行的既有测试，不能说成 81 条 Storage 专属用例。
- 专服保存派发实测：28 次调用、失败 0；`PLAYER_NBT` 单次最大 **57.5988 ms**，`SAVED_DATA` 单次最大 **1.0148 ms**，聚合单 Tick 最大 **59.1421 ms**。原生保存不可抢占，2 ms 是派发软预算；这些短场景数据不证明硬 Tick 上限、长期 MSPT 或完整包性能通过。
- 21 个 profile 的已接实现不等于所有变体均已原生执行。此次正向服务链只覆盖表中三配方，抽屉钥匙/所有材质与染色组合、双箱、锻造背包等不能由它们扩报为逐变体通过。版本不匹配、危险基础背包自定义 UUID/旧数据分支及不确定原生回调仍明确拒绝或进入恢复；后续源码最终全量 build/JUnit 结果由主记录单列。

没有启动完整图形游戏或双客户端，相关实测按用户最新指令免做并保留未验证；未替换实际实例 JAR、配置或存档，未暂存、提交、推送、发布或部署。
