# FireflyMC AI 4.0：整合包模组联动清单

- 盘点日期：2026-10-20；对应 [AI 4.0 重构设计](FireflyMC-AI-4.0-重构设计.md)。
- 输入：`D:\Game\MC\.minecraft\versions\FireflyMC\mods`；**178 个顶层 JAR 全部读取，无元数据解析失败**。
- 方法：ZIP 元数据/资源解析、嵌套依赖清点、重点类使用本机 Java 21 `javap` 检查签名及必要的字节码；Forgematica/MaFgLib 另经 REA 归档检查。没有执行这些 JAR，没有启动 Minecraft 或服务器。
- 本文件是**静态证据与设计取舍**，不是兼容性认证或已实现列表。全量检查覆盖文件/元数据；重点接口检查覆盖下面列出的入口，并非对 178 个 JAR 做完整反编译或逐功能实测。
- 已确认玩法：**全部 AI 加工免实体设备，并直接豁免燃料、热值、FE、魔力/Psi、经验/等级消耗、加工等待及辅助工具耐久费用**，固定采用 `MATERIALS_ONLY`。真实原料、原料型流体、消耗型催化剂、容器、组件变换和概率产物由受信适配器核算。
- 已确认交互规范：默认伙伴名“小萤”，玩家入口只有工作台；不提供 AI 命令/控制台会话、普通聊天唤醒、定时插话或管理工具。AI 回答默认本人可见，逐条预览后分享；本人死亡/进度等事件开关默认关闭。其他功能沿用 `/fireflymc` 规范和 `/点歌` 明确例外。
- 已确认实现/运行规范：全新 AI 不做旧版兼容/配置/历史/名称迁移，保留完善 P0 新核心。AI 只在专用服务端运行并复用现有身份认证；单人和集成 LAN 不提供 AI，客户端仅为符合条件的专服提供工作台/HUD与展示桥。
- 实施进展：见历史 [P0 实施记录](FireflyMC-AI-4.0-P0-实施记录.md)。P0 新基础已有未提交实现，但旧兼容/命令/管理等路径及专服门禁尚待按本轮规则整改；第三方联动、虚拟加工与 UI 仍未实现。旧阶段测试和本表静态证据均不等于新边界或联动运行验收。

## 1. 覆盖与统计

| 内容分组 | 顶层 JAR 数 | 处理原则 |
|---|---:|---|
| 核心自身 | 1 | FireflyMC 3.1.0 安装基线；新 AI 与现有非 AI 功能分清职责 |
| 建造蓝图 | 1 | Forgematica，配合归入依赖库的 MaFgLib |
| 配方与加工 | 12 | 配方视图、冲突选择、各类免设备加工 |
| 物料存储 | 4 | 授权库存、个人战利品与资源对账 |
| 权限保护 | 2 | 领地/交互校验与 AI 能力权限 |
| 伙伴与装备 | 8 | 女仆任务与 Curios 装备生态 |
| 探索与环境 | 47 | 注册表、世界状态、结构/群系/季节与交互 |
| 体验与性能 | 58 | 含客户端体验及服务端优化，分组不代表全部为客户端 Mod |
| 依赖库 | 45 | 支撑其他 Mod，不逐一添加独立 AI 工具 |
| **合计** | **178** | 每个顶层 JAR 只归一个主组，多种作用在联动定位中说明 |

优先适配蓝图、配方、物料、权限和伙伴接口；通用内容先通过注册表/交互索引覆盖，再针对确实存在的特殊加工扩展。一个 Mod 可能同时有通用内容和专属机制，主分组不是能力上限，也不代表全部适配必须一次实现；交付顺序由主设计第 9 节定义。

归档内直接发现 **174 个嵌套 `.jar` 条目**，不等于另有 174 个独立已加载 Mod；主元数据归一化得到 **181 个声明 ID**，包含同包 Stub/别名，也不是运行时 Mod 数量。以 `META-INF/neoforge.mods.toml` 为优先来源，必要时读取 `mods.toml`、`fabric.mod.json`、MANIFEST 或内嵌模组元数据，并解析版本占位符。Connector 和 Kotlin for Forge 的主要 ID 位于嵌套元数据。

磁盘存在 → 声明元数据 → 客户端加载 → 专用服务端加载 → API 探测通过 → 行为验收通过，必须分阶段记录。依赖条目的 `side=BOTH`、文件名带 `neoforge` 或找到类，都不能替代运行证据。

## 2. 本轮设计结论

1. **建造主链路**：Forgematica/MaFgLib 导入与预览 → FireflyMC 权威蓝图/材料单 → JEI/EMI 查询与 Polymorph 式明确选配方 → 虚拟加工 → 授权仓库取料 → OPAC 权限 → FireflyMC 施工与验证。
2. **免设备、免附加费用规则覆盖所有 AI 加工**：工作台、炉子、切石机、锻造台、Chipped 工作台、烹饪/Mod 专用设备及辅助工具无需实际持有；燃料、能量、经验/等级、等待和辅助工具损耗均豁免。各类型仍需核实真实物料与输出语义，未知类型显示“待适配”，不改成“缺机器/燃料/能量”。加工以外的实际传送、采集、战斗和设备运行按原玩法处理。
3. **客户端工具不能成为服务端权威**：Forgematica 材料表/校验器、JEI/EMI 展示配方和地图均为展示或线索，最终库存、配方、保护及世界结果由服务端验证。
4. **有专属接口才扩展专属能力**：通用建材、群系、结构和生物内容先通过注册表/数据包/有界世界查询；依赖库和优化 Mod 主要做兼容性与性能验收。
5. **本目录未发现 Create、AE2、Refined Storage、Mekanism 顶层 JAR**：保留未来扩展位置，不拿未安装的 Mod 作为当前整合包实现前置。

## 3. 重点静态接口与约束

以下入口均针对本次实际 JAR。Java 方法可见性不代表稳定公开 API；版本更新后需要重新探测。表中的用途是拟实现能力，尚未完成集成。

| 目标 | 本轮静态观察 | 拟定联动与限制 |
|---|---|---|
| Forgematica 0.4.2 + MaFgLib 0.4.3 | 同包 ID 分别为 `forgematica/litematica`、`mafglib/malilib`；包名仍是 `fi.dy.masa.litematica` / `fi.dy.masa.malilib`。`DataManager.getSchematicPlacementManager`、`LitematicaSchematic.createEmptySchematic/getSubRegionContainer`、`SchematicPlacement.createFor`、`SchematicPlacementManager.addSchematicPlacement`、placement 事件监听接口存在 | 选中蓝图导入、AI 生成全息图、变换同步；服务端使用中立计划，不能加载客户端类或调用创造粘贴链。负向尺寸、子区变换及文件格式需实测 |
| JEI 19.39.0.369 | `IModPlugin.onRuntimeAvailable/onRuntimeUnavailable`、`IJeiRuntime.getRecipeManager/getRecipesGui`、`IRecipeManager.createRecipeLookup`、`IRecipesGui.show` | 发现/展示/跳转；无服务端 backing 或可信适配语义的展示项不能执行，不用作弊取物 |
| EMI 1.1.24 | `EmiApi.getRecipeManager/displayRecipe/displayRecipes`、`EmiRecipeManager.getRecipesByOutput`、`EmiRecipe.getBackingRecipe` | 与 JEI 共存去重；展示 ID 可能无服务端 backing，不能直接发放客户端给的结果 |
| Polymorph 1.1.0 | `PolymorphApi.getRecipeManager/getPlayerRecipeData`、`IPolymorphRecipeManager.getPlayerRecipe` 有 menu/player 上下文 | AI 用自己的精确 recipeId/代次锁定路线，不覆盖玩家正在打开菜单的选择状态 |
| Storage Drawers 13.11.4 | `DrawerItemHandler`、`IDrawerGroup`、`IDrawerAttributes.isVoid/isUnlimitedStorage/isUnlimitedVending/isSealed/isSuspended`；压缩抽屉有 `IFractionalDrawer.getConversionRate/isSmallestUnit` | 跳过虚拟槽 0；控制器/成员及压缩计量去重，创造/无限供给单独识别。虚空插入可显示无剩余，却已销毁物品，不能用于普通产物/返还目的地 |
| Sophisticated Storage 1.5.80 / Backpacks 3.25.71 / Core 1.4.77 | `IStorageWrapper.getInventoryForInputOutput`、存储实体 `getExternalItemHandler(Direction)`、`BackpackWrapper.fromStack/fromExistingData/getContentsUuid` | 使用外部输入输出能力保留过滤/升级，背包 UUID 和后端身份去重；不直接改私有 NBT，不把模拟成功当跨源事务保障 |
| Open Parties and Claims 0.28.1 | `OpenPACServerAPI.get(server).getChunkProtection()`、`IChunkProtectionAPI.onEntityPlaceBlock/onBlockInteraction`；原事件处理器在返回 true 时取消事件 | **true=拒绝**；破坏用对应 `onBlockInteraction(...,breaking=true,messages=false)`，右键取料/放置等按动作校验。`hasChunkAccess` 不足以判定所有交互；禁止 `giveFullPass` |
| FTB Ranks 2101.1.3 | `FTBRanksAPI.getPermissionValue(ServerPlayer,String)`、`PermissionValue` 存在缺失/布尔/数值分支 | 增加 `fireflymc.ai.*` 能力与配额的候选节点；明确缺失回退，不能随意用 `asBooleanOrTrue` 扩权。节点名为拟定，未注册 |
| Touhou Little Maid 1.5.3 + 两个附属 | `ILittleMaid.addMaidTask/registerAITool/registerAIMaidContext/registerAIFunctionCall`、`TaskManager.add`、`IMaidTask.createBrainTasks`；附属含任务/战斗 Mixin | 高优先可选实体执行桥，可复用已有工具扩展点；主人验证和控制权仲裁，不能让两个 Agent 循环同时操控女仆，FireflyMC 主会话仍唯一 |
| Curios 9.5.1 及装备附属 | `CuriosApi.getCuriosInventory/isStackValid`、槽位锁定/合法性接口 | 装备信息、建议与经授权的携带背包；保命物品、鞘翅和不可卸装备不混进自动消耗池 |
| Aether 内嵌 Accessories 1.1.0-beta.48+1.21.1 | `AccessoriesCapability.get(LivingEntity)/getContainers`、`AccessoriesAPI.canEquip/canUnequip`、`AccessoriesContainer.getAccessories` | 独立饰品槽能力候选；核对实际加载版本与 Curios 桥接后适配并去重，不能认为 Curios 已覆盖所有饰品槽 |
| Chipped 4.0.2 | `ChippedRecipe.ingredients/getResults(ItemStack)`；配方类型 `chipped:workbench` | 免工作台装饰加工，锁定具体目标变体并按真实转换规则扣料，不把候选集合全部发放 |
| Farmer's Delight 1.3.2 / My Nether's Delight | `CookingPotRecipe.getCookTime/getExperience/getOutputContainer`；`CuttingBoardRecipe.getTool/getRollableResults/rollResults`，砧板通用 assemble 仅返回首项副本 | 虚拟烹饪/切割，无需锅/热源/砧板和实际刀具，不扣加工耐久；真实配料/器皿、虚拟工具参数、概率和多产物分别核验，附属配方需检查是否复用同一语义 |
| Cooking for Blockheads 21.1.24 | `CookingForBlockheadsAPI.createKitchen/registerKitchenRecipeHandler`、`Kitchen.getItemProviders/getItemProcessors/canProcess` | 食材来源和配方视图候选；不能因为已有厨房 API，反过来要求虚拟加工先造厨房。来源仍需逐项授权 |
| Apotheosis 8.6.0 | Salvaging 输出含 min/max；Reforging 有材料/印记/等级成本；GemCutting 四槽、`SizedIngredient`、`decrementInputs` | 分解、重铸与宝石虚拟适配，保留随机/组件/数量及真实材料/印记消耗，经验/等级费用豁免；不按每槽一个统一扣料 |
| Apothic Enchanting 1.6.0 / Spawners 1.4.0 | InfusionRecipe 专门提供 `matches/assemble(ItemStack,float,float,float)` 与 requirements；通用 Recipe 输入方法不执行该流程 | 无实体附魔设备且免经验费用；决定结果的虚拟 Eterna/Quanta/Arcana 参数仍需适配。刷怪笼改造是专门交互机制，尚待逐项闭环，不能只因元数据存在就认定可执行 |
| Psi 108 / Iron's Spellbooks 3.16.2 | Psi `ITrickRecipe.getInput/getAssembly/getPiece/getResultItem`；JSON 含 CAD/piece，有维度类型；`IPlayerData.getAvailablePsi/deductPsi` 可见；女仆附属已有 Spellbooks 桥接类 | 虚拟加工免 CAD、Psi/魔力等供能及加工等待，不以原生扣费链为实现前置；真实制作输入/组件、解锁和维度仍须适配。实际施法按其原生冷却/能量规则处理 |
| Aether / Deep Aether / Cataclysm / Twilight Forest | 实际配方 JSON 与类型类包含 `aether:enchanting/freezing/repairing/incubation`、`deep_aether:combining/poison_recipe`、`cataclysm:weapon_fusion/amethyst_bless`、`twilightforest:uncrafting/drying` 等 | 作为专属免设备加工候选，逐项确定组件、消耗、特殊条件与拆解循环风险；本轮没有验证完整执行语义。Aether 还有放置禁令类 RecipeType，不能把所有类型当生产配方 |
| Jade 15.10.5 | `IWailaPlugin.register`、`IWailaCommonRegistration.registerBlockDataProvider`、`IServerDataProvider.appendServerData` | 指向方块的 AI 任务/施工状态提示；只发送已授权最小状态，不把 Jade 当任意数据读取后门 |
| Waystones 21.1.37 | `getActivatedWaystones`、`createDefaultTeleportContext`、`tryTeleport` 入口存在 | 已知地点与路线/传送候选，保留原要求链；不调用 unchecked/force 方法绕过经验、解锁或冷却 |
| Serene Seasons / 两种 Compass | `SeasonHelper/ISeasonState`、罗盘搜索 worker/物品状态类存在 | 季节农业规划与有界目标搜索候选，不能把标签或类存在等同于种植成功/无约束全图搜索 |
| FTB Ultimine 2101.1.15 | 公开 API 主要有 `currentBlockSelection`、`customSelectionCheck`；实现另有破坏/限制流程 | 选区接口不等于安全批量执行接口；工具、疲劳、逐块事件和保护不可跳过，禁止 permission override |
| Double Doors / Dynamic Trees / KleeSlabs | 关联交互/邻居通知、树种网络或局部半砖破坏机制各异 | 实际影响可能超出起点；展开并审核连锁范围，按真实掉落和多格状态对账；具体执行仍需集成测试 |

### 容易误判的三处资源语义

- `DrawerItemHandler` 的 0 号槽为虚拟输入槽，提取返回空，容量 `MAX_INT` 不是可提取库存。
- 抽屉 `isVoid` 的正向插入可能报告全部接收，实际超出部分被销毁；`isUnlimitedVending` 的调整可不减少存量。普通资源账本必须区分有限库存、压缩计量、无限供给和不可逆销毁。
- 客户端蓝图的方块数量不能直接换算成物品数量。门/床、双层台阶、流体、含水状态和 Mod 多格结构需要 `PlacementMaterialResolver`，以服务端实际动作和库存为准。

### 3.1 专用配方资源的补充扫描

有界扫描全部 178 个顶层 JAR 的 `data/<namespace>/{recipe,recipes}/**/*.json`：45 个 JAR 有配方资源，6,392 份 JSON 全部解析成功；27 个 JAR 含非 `minecraft` 根 `type`，共 1,130 份资源、134 个不同根值。2,562 份资源带所检查的条件字段，另外从 Supplementaries 的三个 `neoforge:conditional` 包装中各读到一个内层类型。该扫描不递归读取嵌套依赖，也不包含用户外置数据包或运行时新增配方。

**这些 `type` 是 JSON 序列化标识，通常对应 RecipeSerializer，不是 134 个运行时 RecipeType 或 134 个已实现适配器。** 资源里的 `create:*`、`immersiveengineering:*`、`botanypots:*` 等兼容数据不表示本整合包实际加载了对应 Mod；条件是否成立、数据包覆盖和实际 `Recipe#getType` 均以服务端为准。Aether 放置禁令、JEED 效果说明等也不属于物品生产。

新增需点名的设计候选：女仆本体及万法皆通共 66 份祭坛配方，含 power/实体等字段；Iron's Spellbooks 有 47 份炼金釜 brew/fill/empty 配方；Bumblezone 有保留 NBT 和药水蜡烛制作。加工设备、power 等供能和等待均豁免，真实输入、原料型流体、容器和输出角色逐项核验，实体产出走实体授权。精妙系列、抽屉、Quark、Supplementaries、地图册等动态配方也必须保留正确 `assemble`/余料和组件转移，不能照 JSON 的静态 `result` 复制物品。

逐来源的全部非原版根类型列在第 7 节，作为适配工作目录；这里只完成资源发现，尚未完成所有类型的执行语义验证。

## 4. 输入身份与兼容性待核验项

| 目标文件特征 | SHA-256 |
|---|---|
| forgematica-0.4.2+mc1.21.1.jar | `e19f1653c2f72c771bca2c21519620b71cb32511e25698ba70d5654d50e96c2d` |
| mafglib-0.4.3+mc1.21.1.jar | `d40789ad40e5643ae232a07b4535135fe56585c12538bc30158076f956aab081` |
| jei-1.21.1-neoforge-19.39.0.369.jar | `79b6d034fa233cc87c5fe486387f69cdb54078e1262b440b5c7e7853a0254adf` |
| emi-1.1.24+1.21.1+neoforge.jar | `b68691f94f0727fc517cac2ab55bd6658d1179cc8cc715fe610b60c37469bc4c` |
| open-parties-and-claims-neoforge-1.21.1-0.28.1.jar | `504ade05efa0cdab43cfb0a911694d8381b360976f5470d7e6c1aedd00efa732` |
| StorageDrawers-neoforge-1.21.1-13.11.4.jar | `8bdbc98f32f4166061439e8dda346345012788ddebaeb743a3b585d8a0a0fbbc` |

REA 6.4.0 的 Forgematica 归档检查 Evidence 为 `ev_13f1fe1cef583fb2bb940948c76dee1788cc67cf5b872b462eb0fbfbe3202b28`，manifest 为 `agm_a2c1c2b82593288faba600953230520248f0b52564fc0ea14862753a1adabf47`；MaFgLib Evidence 为 `ev_240fa785c7a17ad32318ed090cedbdf9a7b9738368f3c8706b3bb640f5e825d5`，manifest 为 `agm_41383dda3a68bfcf5e34167374e831d22540d4ff128d9540e456e05cf75efab4`。范围是 ZIP 静态归档与内容身份，不证明 JVM 加载、游戏执行或全部行为兼容；API 与语义另外由本地 JDK 静态检查取得。

需要在实施阶段确认的差异，不在本轮修改安装文件：

- 实际 JEI 元数据声明 Minecraft 范围 `[1.21, 1.21.1)`，与文件名含 `1.21.1` 不同。应核对这份 JAR 的运行加载日志/兼容结果，不能单凭文件名宣布通过，也不据此直接宣布不能运行。
- Sculks 的 NeoForge 元数据版本 `1.4.3.5` 与另一份 mods.toml 的 `1.3.2` 不同；动态光源文件名 `1.0.10` 对应元数据 `1.0.9`，Sodium Extras 文件名 `1.0.8` 对应 `1.0.7`。下面清单保留实际文件名和元数据版本，防止混淆。
- Sophisticated 系列文件名还带构建号，而模组元数据只声明基础版本；适配测试应同时保存文件身份，不能只按基础版本推断每个构建完全相同。
- Aether 包内 `META-INF/jarjar/accessories-neoforge-1.1.0-beta.48+1.21.1.jar` 含独立 `accessories` 元数据和上述 API；这是嵌套依赖候选，不追加为第 179 个顶层 JAR，也不与顶层主分类重复计数。实际 Jar-in-Jar 版本选择及 Curios 桥接关系仍需加载日志/运行验证。
- `blueprint` 是 Team Abnormals 基础库；`mafglib` 归入依赖库也仍是 Forgematica 联动的重要组成。对库的归类不代表可删除。

## 5. 资料与实施前验证

实现以本机 JAR 身份和当前服务端配方为准；上游分支可能继续变化，以下只作为可追溯参考：

- Forgematica 的客户端蓝图定位与开发说明见 [作者仓库](https://github.com/CagayakeGirls/litematica-neoforge)；选区建空蓝图入口见 [1.21.1 分支 LitematicaSchematic](https://github.com/CagayakeGirls/litematica-neoforge/blob/neoforge/1.21.1/src/main/java/fi/dy/masa/litematica/schematic/LitematicaSchematic.java)。
- MaFgLib 的配置/按键/GUI 基础库定位见 [作者仓库](https://github.com/CagayakeGirls/malilib-neoforge)；它没有独立的资源守恒施工职责。
- 配方管理、输入和输出参见 [NeoForge 1.21.1 配方文档](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/)。具体方法名与输入裁边语义以本地目标版本为准：`RecipeManager.byKey`、`CraftingInput.of/ofPositioned`，不能只按旧文档示例。
- 合成剩余物和结果槽兼容流程参考 [NeoForge Recipe 补丁](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/item/crafting/Recipe.java.patch)、[ResultSlot 补丁](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/world/inventory/ResultSlot.java.patch)，实施时验证组件、事件和进度仅按实际操作发放。
- 特殊配方语义可参考 [EMI Recipe API](https://github.com/emilyploszaj/emi/blob/1.21/xplat/src/main/java/dev/emi/emi/api/recipe/EmiRecipe.java)、[ChippedRecipe](https://github.com/terrarium-earth/Chipped/blob/1.21.1/common/src/main/java/earth/terrarium/chipped/common/recipes/ChippedRecipe.java)、[Farmer's Delight CuttingBoardRecipe](https://github.com/vectorwing/FarmersDelight/blob/1.21/src/main/java/vectorwing/farmersdelight/common/crafting/CuttingBoardRecipe.java)。

第三方适配采用已确定的 `MATERIALS_ONLY`，核验专服与客户端实际加载版本，再验证真实物料、免附加费用加工和蓝图行为。联动操作由本人工作台发起，工具结果进入本人工作区，只有明确预览确认的分享才对外发布；不增加 AI 命令、管理工具或单人/LAN 降级。点歌等非 AI 业务保留原入口和权限。

初次模组调查为静态盘点；P0 阶段记录的 430 项单测（AI 32 项）与 13 项 GameTest 通过只覆盖当时实现。本轮未重跑测试，新旧边界整改、专服双客户端、单人/LAN 拒绝门禁、真实供应商和 178 JAR 整合包仍待验收；P1—P3/第三方适配未实现，改动未提交/发布/部署。完整实施按主设计第 14 节组织，阶段不是独立结束目标。

## 6. 全量 JAR 联动分类

以下文件名均相对于输入的 `mods` 目录；版本列取包内元数据，保留与文件名可能不同的声明。“联动定位”为设计建议，不是已实现状态；分组也不决定 Mod 应安装在哪一端。

### 6.1 核心自身（1）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `fireflymc-3.1.0.jar` | `fireflymc@3.1.0` | 当前安装基线；全新 AI 不做旧版迁移，点歌/认证/剧情/联机等现有数据和业务保留。 |

### 6.2 建造蓝图（1）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【全息蓝图】forgematica-0.4.2+mc1.21.1.jar` | `forgematica@0.4.2+mc1.21.1`<br>`litematica@0.4.2+mc1.21.1` | 读取全息投影与结构数据，归一化为服务端施工计划；客户端快照不能授权任意方块状态或数据。 |

### 6.3 配方与加工（12）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【多态合成】polymorph-neoforge-1.1.0+1.21.1.jar` | `polymorph@1.1.0+1.21.1` | 同输入多配方按 recipeId 和目标输出明确选路，不依赖菜单最后一次选择或枚举顺序。 |
| `【合成辅助】craftingtweaks-neoforge-1.21.1-21.1.10.jar` | `craftingtweaks@21.1.10` | 仅复用玩家界面操作习惯；不是 AI 虚拟合成权威执行器。 |
| `【农夫乐事】FarmersDelight-1.21.1-1.3.2.jar` | `farmersdelight@1.3.2` | 虚拟加工需专用语义；免设备/能耗/等待和辅助刀具损耗，真实原料、容器、虚拟工具参数及概率单独核验。 |
| `【傻瓜烹饪／懒人厨房】cookingforblockheads-neoforge-1.21.1-21.1.24.jar` | `cookingforblockheads@21.1.24` | 虚拟加工：复用食物/配方查询与容器语义；厨房设备不是执行前提，物料与流体来源按授权库存核算。 |
| `【神化】Apotheosis-1.21.1-8.6.0.jar` | `apotheosis@8.6.0` | 虚拟加工候选：锻造/重铸/宝石等专用系统逐项适配，保留装备组件、随机性和材料成本。 |
| `【神化附魔】ApothicEnchanting-1.21.1-1.6.0.jar` | `apothic_enchanting@1.6.0` | 虚拟附魔免设备/经验费用和等待；真实输入、附魔规则及决定结果的虚拟属性按专用适配器核验。 |
| `【神化刷怪笼】ApothicSpawners-1.21.1-1.4.0.jar` | `apothic_spawners@1.4.0` | 虚拟加工候选：刷怪笼改造/物品交互不能当普通网格配方，保留消耗和实体/方块状态约束。 |
| `【JEI物品管理器】jei-1.21.1-neoforge-19.39.0.369.jar` | `jei@19.39.0.369` | 客户端配方搜索、分类与展示桥；服务端注册配方和虚拟加工执行器保持权威，插件展示不等于可执行配方。 |
| `chipped-neoforge-1.21.1-4.0.2.jar` | `chipped@4.0.2` | 虚拟加工与建材调色板：解析 ChippedRecipe 的输入和输出集合，指定实际输出 ID；免工作台，按目标结果和实际数量核算转换。 |
| `emi-1.1.24+1.21.1+neoforge.jar` | `emi@1.1.24+1.21.1+neoforge` | 本目录也安装 EMI；与 JEI 共用服务端配方索引和语义模型，避免同一配方重复或冲突，客户端提供查询与跳转。 |
| `MyNethersDelight-1.21.1-1.10.2.jar` | `mynethersdelight@1.10.2` | 农夫乐事扩展内容与配方，复用 Farmer’s Delight 虚拟加工适配器。 |
| `Psi-1.21.1-108.jar` | `psi@1.21.1-108` | 虚拟制作免 CAD/能量/经验费用和等待，真实材料/组件/解锁/维度仍核验；实际施法按原玩法处理。 |

### 6.4 物料存储（4）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【储物抽屉】StorageDrawers-neoforge-1.21.1-13.11.4.jar` | `storagedrawers@13.11.4` | DrawerItemHandler/IDrawerGroup 适配；跳过虚拟槽0，区分真实容量与虚空吞入，识别无限存储/无限售卖、封存和安全提供者。 |
| `【精妙背包】sophisticatedbackpacks-1.21.1-3.25.71.1997.jar` | `sophisticatedbackpacks@3.25.71` | 通过 BackpackWrapper 的 input-output inventory；按 contents UUID 去重、防嵌套循环，保留过滤和无限物品/虚空升级规则。 |
| `【精妙存储】sophisticatedstorage-1.21.1-1.5.80.1999.jar` | `sophisticatedstorage@1.5.80` | 优先 external item handler / input-output wrapper，保留过滤和升级行为；控制器与成员库存不能重复计数。 |
| `lootr-neoforge-1.21.1-1.11.37.122.jar` | `lootr@1.21.1-1.11.37.122` | 个人战利品容器边界：须使用正确玩家上下文与个人库存，默认不当公共仓库或跨玩家材料源。 |

### 6.5 权限保护（2）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `ftb-ranks-neoforge-2101.1.3.jar` | `ftbranks@2101.1.3` | FTBRanksAPI.getPermissionValue 为 AI 能力与配额提供权限节点；必须明确缺失权限的回退规则。 |
| `open-parties-and-claims-neoforge-1.21.1-0.28.1.jar` | `openpartiesandclaims@0.28.1` | OpenPACServerAPI / IChunkProtectionAPI；逐动作检查；本轮核实的 onEntityPlaceBlock/onBlockInteraction 返回 true 表示拒绝，其他入口逐项核对。 |

### 6.6 伙伴与装备（8）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【不死图腾插槽／不死图腾槽位】charmofundying-neoforge-9.1.0+1.21.1.jar` | `charmofundying@9.1.0+1.21.1` | Curios 图腾槽：纳入装备视图和保命策略，默认不被自动合成或建造材料提取消耗。 |
| `【车万女仆：万法皆通】touhou_little_maid_spell-1.21.1-1.8.4-neoforge.jar` | `touhou_little_maid_spell@1.8.4-neoforge` | 现有 Irons Spellbooks 与 Curios 桥、IMaidSpellData 等扩展；复用施法冷却、友军和女仆任务逻辑。 |
| `【车万女仆：香草伙伴】touhou_little_maid_vanilla_companion-1.1.0.jar` | `touhou_little_maid_vanilla_companion@1.1.0` | 女仆战斗附加：已包含对任务与射击逻辑的 Mixin；与 FireflyMC 任务调度互斥/优先级需验证。 |
| `【车万女仆】touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar` | `touhou_little_maid@1.5.3-neoforge+mc1.21.1` | IMaidTask / TaskManager 注册和实体所有者上下文；任务、工作范围、背包与现有 AI 控制权需要仲裁。 |
| `【奇异饰品】artifacts-neoforge-13.2.1.jar` | `artifacts@13.2.1` | 复用 Curios 装备和物品组件，AI 装备建议需要评估属性与主动能力，默认不当消耗材料。 |
| `【鞘翅插槽／鞘翅槽位】elytraslot-neoforge-9.0.2+1.21.1.jar` | `elytraslot@9.0.2+1.21.1` | Curios 鞘翅槽：识别装备与飞行能力，保留穿戴校验，不能混入普通可消耗材料池。 |
| `bettercombat-neoforge-2.4.0+1.21.1.jar` | `bettercombat@2.4.0+1.21.1` | 战斗动画/攻击规则整合，AI 近战走原攻击入口与冷却；不新增跳过伤害事件的直接扣血工具。 |
| `curios-neoforge-9.5.1+1.21.1.jar` | `curios@9.5.1+1.21.1` | CuriosApi.getCuriosInventory / isStackValid；装备槽与普通材料槽分离，保留不可卸下/槽位锁定规则。 |

### 6.7 探索与环境（47）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【超多生物群系】BiomesOPlenty-neoforge-1.21.1-21.1.0.14.jar` | `biomesoplenty@21.1.0.14` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【传送石碑／指路石】waystones-neoforge-1.21.1-21.1.37.jar` | `waystones@21.1.37` | WaystonesAPI 的已激活地点与 teleport context/result；保留发现、经验、冷却及维度限制。 |
| `【地图册】map_atlases-neoforge-1.21-6.6.0.jar` | `map_atlases@1.21-6.6.0` | 已持有地图与标记可用于路线呈现；未探索地图不能充当服务端完整世界数据。 |
| `【动态的树：拓展】DynamicTreesPlus-neoforge-1.21.1-1.3.2.jar` | `dynamictreesplus@1.3.2` | 动态树扩展内容复用对应树种与采集适配器；不直接作为普通原木树处理。 |
| `【动态的树／有活力的树】dynamictrees-neoforge-1.21.1-1.7.2.jar` | `dynamictrees@1.7.2` | 动态树不同于整块原木，采集/补种走其树种与砍伐机制，避免逐方块破坏绕过树网络语义。 |
| `【动物喂养槽】animal_feeding_trough-1.1.2+1.21-neoforge.jar` | `animal_feeding_trough@1.1.2` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【更好地破坏半砖】kleeslabs-neoforge-1.21.1-21.1.11.jar` | `kleeslabs@21.1.11` | 半砖采集具有局部破坏语义；预览、材料回收和蓝图差异计算需读取真实结果。 |
| `【更凶猛的末影龙】dragonfight-1.21-4.7.jar` | `dragonfight@4.7` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【结构变体】repurposed_structures-7.5.21+1.21.1-neoforge.jar` | `repurposed_structures@7.5.21+1.21.1-neoforge` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【锦致装饰】supplementaries-neoforge-1.21.1-3.8.3.jar` | `supplementaries@1.21.1-3.8.3` | 建筑材料与特殊方块：普通建材复用通用施工；容器、红石和功能性方块额外声明副作用。 |
| `【静谧四季／季节】SereneSeasons-neoforge-1.21.1-10.1.0.3.jar` | `sereneseasons@10.1.0.3` | SeasonHelper/ISeasonState 供农业规划判断季节；保持原生长判定，不把作物标签当当前可种保证。 |
| `【夸克】Quark-4.1-482.jar` | `quark@4.1-482` | 建筑材料和原版扩展复用注册表；特殊交互和库存行为按实际组件/事件识别。 |
| `【连锁破坏】ftb-ultimine-neoforge-2101.1.15.jar` | `ftbultimine@2101.1.15` | 可选批量采集后端：逐方块限制、形状、饥饿和工具耐久规则仍生效，不把范围视为一次权限检查。 |
| `【蜜蜂领域／黄蜂领域】the_bumblezone-7.15.2+1.21.1-neoforge.jar` | `the_bumblezone@7.15.2+1.21.1-neoforge` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【墓碑】gravestone-neoforge-1.21.1-1.0.37.jar` | `gravestone@1.21.1-1.0.37` | 玩家死亡物资追踪与恢复任务候选；领地、所有权、危险区和物品保护优先。 |
| `【暮色森林】twilightforest-1.21.1-4.8.3345-universal.jar` | `twilightforest@4.8.3345` | 内容走通用索引；uncrafting/drying 和装备组件配方作为免设备适配候选，拆解成本与循环风险需单独验证。 |
| `【秋原】autumnity-1.21.1-6.0.1.jar` | `autumnity@6.0.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【深入天境】deep_aether-1.21.1-1.1.5.1.jar` | `deep_aether@1.21.1-1.1.5.1` | 内容走注册表/世界索引；combining、poison_recipe 及 Aether 加工扩展另设免设备语义适配候选。 |
| `【舒适用品】comforts-neoforge-9.0.5+1.21.1.jar` | `comforts@9.0.5+1.21.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【双开门】doubledoors-1.21.1-7.2.jar` | `doubledoors@7.2` | 一次交互可能同时改变相邻门；预检覆盖关联门及每个受影响领地，保留原模组交互事件。 |
| `【水产业2／水产品2】Aquaculture-1.21.1-2.7.21.jar` | `aquaculture@2.7.21` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【探险者指南针】ExplorersCompass-1.21.1-3.4.0-neoforge.jar` | `explorerscompass@1.21.1-3.4.0-neoforge` | 可选结构探索适配：沿异步搜索 worker 和配置范围检索，保留已发现信息和查找预算。 |
| `【天境】aether-1.21.1-1.5.10-neoforge.jar` | `aether@1.5.10` | 内容先用注册表；附魔/冻结/修复/孵化另设虚拟加工，放置禁令不能当生产配方；内嵌 Accessories 核对加载与桥接后去重。 |
| `【月亮事件】Enhanced-Celestials-NeoForge-1.21.1-6.0.2.5.jar` | `enhancedcelestials@6.0.2.5` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【灾厄侵扰】IllagerInvasion-v21.1.6-1.21.1-NeoForge.jar` | `illagerinvasion@21.1.6` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【自然罗盘／生物群系指南针】NaturesCompass-1.21.1-3.4.0-neoforge.jar` | `naturescompass@1.21.1-3.4.0-neoforge` | 可选生态探索适配：沿搜索 worker/物品状态和服务器限制检索；结果带维度与边界，不开放无约束全图透视。 |
| `【Iron的法术与魔法书】irons_spellbooks-1.21.1-3.16.2.jar` | `irons_spellbooks@1.21.1-3.16.2` | 炼金等虚拟加工免设备/供能/等待、真实配料流体照扣；战斗保留魔力/冷却/友军规则，可复用女仆已有桥。 |
| `【Mowzie的生物】mowziesmobs-1.21.1-1.8.2.jar` | `mowziesmobs@1.8.2` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的地牢优化】YungsBetterDungeons-1.21.1-NeoForge-5.1.4.jar` | `betterdungeons@1.21.1-NeoForge-5.1.4` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的海底神殿优化】YungsBetterOceanMonuments-1.21.1-NeoForge-4.1.2.jar` | `betteroceanmonuments@1.21.1-NeoForge-4.1.2` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的矿井优化】YungsBetterMineshafts-1.21.1-NeoForge-5.1.1.jar` | `bettermineshafts@1.21.1-NeoForge-5.1.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的桥】YungsBridges-1.21.1-NeoForge-5.1.1.jar` | `yungsbridges@1.21.1-NeoForge-5.1.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的下界要塞优化】YungsBetterNetherFortresses-1.21.1-NeoForge-3.1.5.jar` | `betterfortresses@1.21.1-NeoForge-3.1.5` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG 的要塞优化】YungsBetterStrongholds-1.21.1-NeoForge-5.1.3.jar` | `betterstrongholds@1.21.1-NeoForge-5.1.3` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG的丛林神庙优化】YungsBetterJungleTemples-1.21.1-NeoForge-3.1.2.jar` | `betterjungletemples@1.21.1-NeoForge-3.1.2` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG的末地岛屿优化】YungsBetterEndIsland-1.21.1-NeoForge-3.1.2.jar` | `betterendisland@1.21.1-NeoForge-3.1.2` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG的沙漠神殿优化】YungsBetterDesertTemples-1.21.1-NeoForge-4.1.5.jar` | `betterdeserttemples@1.21.1-NeoForge-4.1.5` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `【YUNG的沼泽小屋优化】YungsBetterWitchHuts-1.21.1-NeoForge-4.1.1.jar` | `betterwitchhuts@1.21.1-NeoForge-4.1.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `Arda's Sculks 1.4.3.5_1 [NEOFORGE] [1.21.1].jar` | `sculks_of_arda@1.4.3.5` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `Explorify v1.6.5.mod.jar` | `explorify@1.6.5` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `hauntedharvest-neoforge-1.21-3.4.0.jar` | `hauntedharvest@1.21-3.4.0` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `L_Ender's Cataclysm 1.21.1-3.32.jar` | `cataclysm@3.32` | 内容走通用索引；weapon_fusion/amethyst_bless 等作为免设备专用加工候选，材料/组件/上下文逐项核验。 |
| `MutantMonsters-v21.1.1-1.21.1-NeoForge.jar` | `mutantmonsters@21.1.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `Ribbits-1.21.1-NeoForge-4.1.6.jar` | `ribbits@1.21.1-NeoForge-4.1.6` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `smallships-neoforge-1.21.1-2.0.0-b2.1.jar` | `smallships@2.0.0-b2.1` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `snowyspirit-neoforge-1.21.1-3.1.7.jar` | `snowyspirit@1.21.1-3.1.7` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |
| `tumbleweeds-1.1.0+mc1.21-1.21.1.jar` | `tumbleweeds@1.1.0` | 优先使用服务端注册表、标签、物品组件、实体与世界数据，保留原事件和交互规则；特殊机制按需适配。 |

### 6.8 体验与性能（58）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【3D 皮肤层】skinlayers3d-neoforge-1.11.2-mc1.21.1.jar` | `skinlayers3d@1.11.2` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【传说提示框】LegendaryTooltips-1.21.1-neoforge-1.5.5.jar` | `legendarytooltips@1.5.5` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【动态 FPS】dynamic-fps-3.11.4+minecraft-1.21.0-neoforge.jar` | `dynamic_fps@3.11.4` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【附魔描述】enchdesc-neoforge-1.21.1-21.1.10.jar` | `enchdesc@21.1.10` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【更多动画】notenoughanimations-neoforge-1.12.4-mc1.21.1.jar` | `notenoughanimations@1.12.4` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【更好的进度】BetterAdvancements-NeoForge-1.21.1-0.4.3.21.jar` | `betteradvancements@0.4.3.21` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【更好的F3】BetterF3-11.0.3-NeoForge-1.21.1.jar` | `betterf3@11.0.3` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【键位冲突显示】Controlling-neoforge-1.21.1-19.0.5.jar` | `controlling@19.0.5` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【截图查看器】screenshot_viewer-1.3.4-neoforge-mc1.21.1.jar` | `screenshot_viewer@1.3.4` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【禁用聊天举报】NoChatReports-NEOFORGE-1.21.1-v2.9.1.jar` | `nochatreports@1.21.1-v2.9.1` | 现有聊天协议体验保留；普通聊天不触发新 AI，新 AI 只接收本人工作台交互。 |
| `【经验机制改革】Clumps-neoforge-1.21.1-19.0.0.1.jar` | `clumps@19.0.0.1` | 经验球合并优化；AI 加工免经验扣除，正常经验奖励按服务端数值且不重复发放，不按实体个数估计。 |
| `【垃圾槽】trashslot-neoforge-1.21.1-21.1.11.jar` | `trashslot@21.1.11` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【聊天动画】chatanimation-neoforge-1.3.1+mc1.21.jar` | `chatanimation@1.3.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【聊天头像】chat_heads-0.15.3-neoforge-1.21.jar` | `chat_heads@0.15.3` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【落叶】fallingleaves-1.21.1-2.5.1.jar` | `fallingleaves@2.5.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【模组目录】catalogue-neoforge-1.21.1-1.11.2.jar` | `catalogue@1.11.2` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【钠 · 扩展】sodium-extra-neoforge-0.9.3+mc1.21.1.jar` | `sodium_extra@0.9.3+mc1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【钠】sodium-neoforge-0.8.12+mc1.21.1.jar` | `sodium@0.8.12+mc1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【钠／Embeddium：动态光源】sodiumdynamiclights-neoforge-1.0.10-1.21.1.jar` | `sodiumdynamiclights@1.0.9` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【钠／Embeddium：附属】sodiumextras-neoforge-1.0.8-1.21.1.jar` | `sodiumextras@1.0.7` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【配置界面】configured-neoforge-1.21.1-2.6.3.jar` | `configured@2.6.3` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【平滑转移】smoothswapping-0.9.3.2-1.21.1-neoforge.jar` | `smoothswapping@0.9.3.2` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【苹果皮】appleskin-neoforge-mc1.21-3.0.9.jar` | `appleskin@3.0.9+mc1.21` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【潜影盒提示框】shulkerboxtooltip-neoforge-5.1.9+1.21.1.jar` | `shulkerboxtooltip@5.1.9+1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【熔炉性能优化】FastFurnace-1.21.1-9.0.1.jar` | `fastfurnace@9.0.1` | 原熔炉性能补丁；AI 虚拟烧炼免炉子/燃料/等待，真实输入输出仍由适配器核验，不据补丁断言可处理任意配方。 |
| `【实体模型特性】entity_model_features-3.2.4-1.21-neoforge.jar` | `entity_model_features@3.2.4` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【实体纹理特性】entity_texture_features_1.21-neoforge-7.1.jar` | `entity_texture_features@7.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【实体渲染机制优化】entityculling-neoforge-1.10.5-mc1.21.1.jar` | `entityculling@1.10.5` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【拾取提示】PickUpNotifier-v21.1.1-1.21.1-NeoForge.jar` | `pickupnotifier@21.1.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【输入法冲突修复】IMBlocker-5.5.4-neoforge+1.20.6-1.21.8.jar` | `imblocker@5.5.4` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【属性修复】attributefix-neoforge-1.21.1-21.1.3.jar` | `attributefix@21.1.3` | 属性范围修复，保留模组实际属性上限，不在 AI 侧硬编码原版边界。 |
| `【鼠标手势】MouseTweaks-neoforge-mc1.21-2.26.1.jar` | `mousetweaks@2.26.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【树叶快速腐烂】fastleafdecay-35.jar` | `fastleafdecay@35` | 树叶衰减时序兼容；采集结果与施工更新需允许原模组延迟副作用。 |
| `【铁氧体磁芯】ferritecore-7.0.3-neoforge.jar` | `ferritecore@7.0.3` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【通用拼音搜索】jecharacters-1.21.1-neoforge-4.5.26.jar` | `jecharacters@4.5.26` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【物理声效重制版】sound-physics-remastered-neoforge-1.21.1-1.5.1.jar` | `sound_physics_remastered@1.21.1-1.5.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【物品边框】ItemBorders-1.21-neoforge-1.2.5.jar` | `itemborders@1.2.5` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【物品高亮】Highlighter-1.21-neoforge-1.1.11.jar` | `highlighter@1.1.11` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【现代化修复】modernfix-neoforge-5.27.20+mc1.21.1.jar` | `modernfix@5.27.20+mc1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【一键背包整理Next】InventoryProfilesNext-neoforge-1.21.1-2.2.5.jar` | `inventoryprofilesnext@2.2.5` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【玉 🔍】Jade-1.21.1-NeoForge-15.10.5.jar` | `jade@15.10.5+neoforge` | 可选 IWailaPlugin 状态展示：显示 AI 工地、任务、物料和失败原因；Jade tooltip 不是服务器执行权限或物料来源。 |
| `【预生成区块】Chunky-NeoForge-1.4.23.jar` | `chunky@1.4.23` | 服务端区块预生成管理；AI 不能因为建造/搜索自行触发无限区块加载或预生成。 |
| `【装备比较】EquipmentCompare-1.21.1-neoforge-1.3.13.jar` | `equipmentcompare@1.3.13` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【自定义游戏加载界面】drippyloadingscreen_neoforge_3.1.2_MC_1.21.1.jar` | `drippyloadingscreen@3.1.2` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【自动汉化更新】I18nUpdateMod-3.7.0-all.jar` | `i18nupdatemod@3.7.0` | 中文资源体验层；fabric.mod.json environment=client，另有 ModLauncher transformation service，实际入口需加载日志确认。 |
| `【自然音效】AmbientSounds_NEOFORGE_v6.3.8_mc1.21.1.jar` | `ambientsounds@6.3.8` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【Reese的钠视频界面】reeses-sodium-options-neoforge-2.2.3+mc1.21.1.jar` | `reeses_sodium_options@2.2.3+mc1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `【Xaero的世界地图】xaeroworldmap-neoforge-1.21.1-1.44.2.jar` | `xaeroworldmap@1.44.2` | 可选客户端地图标记与任务路线展示；支持缺失模组回退，不从缓存推断未加载区块完整状态。 |
| `【Xaero的小地图】xaerominimap-neoforge-1.21.1-26.4.2.jar` | `xaerominimap@26.4.2` | 可选客户端路标桥：发布任务/工地标记；不把客户端地图缓存当服务端可行走或领地权限证据。 |
| `continuity-3.0.0+1.21.neoforge.jar` | `continuity@3.0.0+1.21.neoforge` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `DistantHorizons-3.2.0-b-1.21.1-fabric-neoforge.jar` | `distanthorizons@3.2.0-b` | 远景渲染兼容与性能；元数据同时带 clientSideOnly 与 BOTH，不能仅据它判定服务端可用性。 |
| `fancymenu_neoforge_3.9.8_MC_1.21.1.jar` | `fancymenu@3.9.8` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `ImmediatelyFast-NeoForge-1.6.11+1.21.1.jar` | `immediatelyfast@1.6.11+1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `iris-neoforge-1.8.14-beta.1+mc1.21.1.jar` | `iris@1.8.14-beta.1+mc1.21.1` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `make_bubbles_pop-0.4.0-beta.1-neoforge-mc1.21-1.21.1.jar` | `make_bubbles_pop@0.4.0-beta.1-neoforge` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `maxhealthfix-neoforge-1.21.1-21.1.4.jar` | `maxhealthfix@21.1.4` | 生命值加载/同步修复，AI 健康状态读取应与服务端真实值一致。 |
| `modelfix-1.21-1.10.jar` | `modelfix@1.21-1.10` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |
| `moreculling-neoforge-1.21.1-1.0.8.jar` | `moreculling@1.0.8` | 以兼容和展示为主；渲染、检索、配置或性能能力不能替代服务端权威状态。 |

### 6.9 依赖库（45）

| JAR 文件 | 包内声明 ID 与版本 | 联动定位 |
|---|---|---|
| `【冰山】Iceberg-1.21.1-neoforge-1.3.2.jar` | `iceberg@1.3.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `【精妙核心】sophisticatedcore-1.21.1-1.4.77.2173.jar` | `sophisticatedcore@1.4.77` | 共享 API 依赖：IStorageWrapper/InventoryHandler 为精妙存储与背包的适配边界；避免直接写 NBT 或绕过输入输出过滤。 |
| `【帕秋莉手册】Patchouli-1.21.1-93-NEOFORGE.jar` | `patchouli@1.21.1-93-NEOFORGE` | 手册与知识展示依赖：可指向配置后的 AI 帮助页，书中描述不自动成为可执行规则。 |
| `【信雅互联】connector-2.0.0-beta.15+1.21.1-full.jar` | `connector@2.0.0-beta.15+1.21.1` | Sinytra 跨加载器兼容层；顶层为包装 JAR，connector mod metadata 在嵌套 JAR，实际加载需日志确认。 |
| `aeroblender-1.21.1-1.0.0-neoforge.jar` | `aeroblender@1.0.0` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `ApothicAttributes-1.21.1-2.10.0.jar` | `apothic_attributes@2.10.0` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `architectury-13.0.8-neoforge.jar` | `architectury@13.0.8` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `athena-neoforge-1.21.1-4.0.6.jar` | `athena@4.0.6` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `badpackets-neo-0.8.2.jar` | `badpackets@0.8.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `balm-neoforge-1.21.1-21.0.63.jar` | `balm@21.0.63` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `blueprint-1.21.1-8.1.1.jar` | `blueprint@8.1.1` | Team Abnormals 通用库；不是 Forgematica 全息蓝图实现，不应作为 AI 结构导入接口。 |
| `bookshelf-neoforge-1.21.1-21.1.81.jar` | `bookshelf@21.1.81` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `caelus-neoforge-7.0.1+1.21.1.jar` | `caelus@7.0.1+1.21.1` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `cloth-config-15.0.140-neoforge.jar` | `cloth_config@15.0.140` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `collective-1.21.1-8.39.jar` | `collective@8.39` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Corgilib-NeoForge-1.21.1-5.0.0.9.jar` | `corgilib@5.0.0.9` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `CreativeCore_NEOFORGE_v2.13.41_mc1.21.1.jar` | `creativecore@2.13.41` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `cristellib-neoforge-1.21.1-3.1.7.jar` | `cristellib@3.1.7` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `cupboard-1.21.1-3.9.jar` | `cupboard@3.9` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Data_Anchor-neoforge-1.21.1-2.0.0.14.jar` | `dataanchor@2.0.0.14` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `forgified-fabric-api-0.116.14+2.3.0+1.21.1.jar` | `fabric_api@0.116.14+2.3.0+1.21.1`<br>`forgified_fabric_api@0.116.14+2.3.0+1.21.1` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `framework-neoforge-1.21.1-0.13.11.jar` | `framework@0.13.11` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `ftb-library-neoforge-2101.1.33.jar` | `ftblibrary@2101.1.33` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `geckolib-neoforge-1.21.1-4.9.2.jar` | `geckolib@4.9.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `GlitchCore-neoforge-1.21.1-2.1.0.2.jar` | `glitchcore@2.1.0.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `irons_lib-1.21.1-2.1.0.jar` | `irons_lib@1.21.1-2.1.0` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `konkrete_neoforge_1.9.9_MC_1.21.jar` | `konkrete@1.9.9` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `kotlinforforge-5.12.0-all.jar` | `kotlinforforge@5.12.0` | Kotlin 语言与运行库包装 JAR；顶层 FMLModType=LIBRARY，kotlinforforge mod metadata 在内嵌 kffmod 中。 |
| `libIPN-neoforge-1.21.1-6.6.3.jar` | `libipn@6.6.3` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `lionfishapi-3.1.jar` | `lionfishapi@3.1` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `mafglib-0.4.3+mc1.21.1.jar` | `mafglib@0.4.3+mc1.21.1`<br>`malilib@0.4.3+mc1.21.1` | Forgematica 客户端配置、事件和工具依赖；同包另声明 malilib，不能独立当作服务端建造引擎。 |
| `melody_neoforge_1.0.10_MC_1.21.jar` | `melody@1.0.10` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `moonlight-neoforge-1.21.1-3.1.2.jar` | `moonlight@1.21.1-3.1.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `owo-lib-neoforge-0.12.15.6-beta.1+1.21.jar` | `owo@0.12.15.6-beta.1+1.21` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Placebo-1.21.1-9.9.2.jar` | `placebo@9.9.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `player-animation-lib-forge-2.0.4+1.21.1.jar` | `playeranimator@2.0.4+1.21.1` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `prickle-neoforge-1.21.1-21.1.11.jar` | `prickle@21.1.11` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Prism-1.21.1-neoforge-1.0.11.jar` | `prism@1.0.11` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `PuzzlesLib-v21.1.52-1.21.1-NeoForge.jar` | `puzzleslib@21.1.52` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `resourcefullib-neoforge-1.21-3.0.12.jar` | `resourcefullib@3.0.12` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Searchables-neoforge-1.21.1-1.0.2.jar` | `searchables@1.0.2` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `TerraBlender-neoforge-1.21.1-4.1.0.8.jar` | `terrablender@4.1.0.8` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `yet_another_config_lib_v3-3.8.2+1.21.1-neoforge.jar` | `yet_another_config_lib_v3@3.8.2+1.21.1-neoforge` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `YungsApi-1.21.1-NeoForge-5.1.6.jar` | `yungsapi@1.21.1-NeoForge-5.1.6` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |
| `Zeta-1.1-40.jar` | `zeta@1.1-40` | 运行依赖与已有 API 基础，按需要作为可选编译依赖；不为库单独建立 AI 工具。 |

## 7. 非原版配方序列化标识目录

仅列本轮 27 个含非 minecraft 根 type 的来源；反引号内是完整序列化 ID，乘号后是该来源中的资源条数。同一 ID 可由多个 Mod 提供，不能把各行的类型数相加当作全局去重数；全局共 134 个不同根值、1,130 份资源。样例路径为对应 JAR 内的资源路径，不是磁盘文件。

| 来源 Mod ID | 非原版资源数 | 根 type 与条数 | 样例资源路径 |
|---|---:|---|---|
| `chipped` | 8 | `chipped:workbench` × 8 | `data/chipped/recipe/alchemy_bench.json` |
| `hauntedharvest` | 5 | `botanypots:crop` × 1<br>`farmersdelight:cooking` × 2<br>`farmersdelight:cutting` × 1<br>`hauntedharvest:carved_pumpkin` × 1 | `data/hauntedharvest/recipe/botany_corn.json` |
| `cataclysm` | 9 | `cataclysm:amethyst_bless` × 1<br>`cataclysm:weapon_fusion` × 8 | `data/cataclysm/recipe/amethyst_bless/blessed_amethyst_crab_meat.json` |
| `mynethersdelight` | 46 | `brewinandchewin:fermenting` × 1<br>`farmersdelight:cooking` × 21<br>`farmersdelight:cutting` × 23<br>`farmersdelight:food_serving` × 1 | `data/brewinandchewin/recipe/fermenting/ghast_sourdough.json` |
| `psi` | 19 | `psi:bullet_to_drive` × 1<br>`psi:bullet_upgrade` × 6<br>`psi:colorizer_change` × 1<br>`psi:dimension_trick_crafting` × 2<br>`psi:drive_duplicate` × 1<br>`psi:scavenge` × 1<br>`psi:sensor_attach` × 1<br>`psi:sensor_remove` × 1<br>`psi:trick_crafting` × 5 | `data/patchouli/recipe/guide_book.json` |
| `irons_spellbooks` | 77 | `create:emptying` × 15<br>`create:filling` × 15<br>`irons_spellbooks:alchemist_cauldron_brew` × 15<br>`irons_spellbooks:alchemist_cauldron_empty` × 16<br>`irons_spellbooks:alchemist_cauldron_fill` × 16 | `data/irons_spellbooks/recipe/alchemist_cauldron/brew_epic_ink.json` |
| `storagedrawers` | 5 | `storagedrawers:add_detached_upgrade` × 1<br>`storagedrawers:add_upgrade` × 1<br>`storagedrawers:keyring` × 1<br>`storagedrawers:personal_key_cycle` × 1<br>`storagedrawers:remote_group_upgrade` × 1 | `data/storagedrawers/recipe/add_detached_upgrade.json` |
| `cookingforblockheads` | 2 | `cookingforblockheads:toaster` × 2 | `data/cookingforblockheads/recipe/pamhc2foodcore/toast.json` |
| `farmersdelight` | 164 | `create:filling` × 1<br>`create:milling` × 8<br>`create:mixing` × 3<br>`farmersdelight:cooking` × 28<br>`farmersdelight:cutting` × 106<br>`farmersdelight:dough` × 1<br>`farmersdelight:food_serving` × 1<br>`immersiveengineering:cloche` × 4<br>`immersiveengineering:crusher` × 8<br>`immersiveengineering:fermenter` × 1<br>`immersiveengineering:metal_press` × 1<br>`immersiveengineering:squeezer` × 2 | `data/farmersdelight/recipe/cooking/apple_cider.json` |
| `dynamictrees` | 2 | `dynamictrees:mega_seed` × 1<br>`dynamictrees:seed_conversion` × 1 | `data/dynamictrees/recipe/mega_seed.json` |
| `map_atlases` | 4 | `map_atlases:adding_atlas` × 1<br>`map_atlases:antique_atlas` × 1<br>`map_atlases:crafting_atlas` × 1<br>`map_atlases:cutting_atlas` × 1 | `data/map_atlases/recipe/add_atlas.json` |
| `aether` | 177 | `aether:accessory_freezable` × 2<br>`aether:ambrosium_enchanting` × 1<br>`aether:block_placement_ban` × 3<br>`aether:enchanting` × 10<br>`aether:freezing` × 9<br>`aether:icestone_freezable` × 2<br>`aether:incubation` × 3<br>`aether:item_placement_ban` × 4<br>`aether:placement_conversion` × 38<br>`aether:repairing` × 97<br>`aether:swet_ball_conversion` × 6<br>`jeed:effect_provider` × 2 | `data/aether/recipe/golden_axe_repairing.json` |
| `quark` | 26 | `quark:dye_item` × 1<br>`quark:elytra_duplication` × 1<br>`quark:exclusion` × 1<br>`quark:mixed_exclusion` × 4<br>`quark:slab_to_block` × 1<br>`quark:smithing_rune` × 18 | `data/quark/recipe/building/crafting/chests/mixed_chest_wood.json` |
| `twilightforest` | 89 | `jeed:effect_provider` × 13<br>`twilightforest:casket_repair_recipe` × 1<br>`twilightforest:drying` × 35<br>`twilightforest:emperors_cloth_recipe` × 1<br>`twilightforest:essence_repair_recipe` × 1<br>`twilightforest:magic_map_cloning_recipe` × 1<br>`twilightforest:maze_map_cloning_recipe` × 1<br>`twilightforest:moonworm_queen_repair_recipe` × 1<br>`twilightforest:no_template_smithing` × 1<br>`twilightforest:scepter_repair` × 4<br>`twilightforest:travellers_gear_modifier_shaped_recipe` × 22<br>`twilightforest:travellers_gear_modifier_shapeless_recipe` × 4<br>`twilightforest:travellers_vest_gloves_merge_recipe` × 1<br>`twilightforest:uncrafting` × 3 | `data/minecraft/recipe/dead_tube_coral_block.json` |
| `aquaculture` | 1 | `aquaculture:crafting_special_fish_fillet` × 1 | `data/aquaculture/recipe/fish_fillet.json` |
| `deep_aether` | 54 | `aether:enchanting` × 11<br>`aether:freezing` × 1<br>`aether:repairing` × 20<br>`deep_aether:combining` × 4<br>`deep_aether:floaty_scarf_coloring` × 1<br>`deep_aether:glowing_spores_recipe` × 2<br>`deep_aether:golden_swet_ball_recipe` × 2<br>`deep_aether:poison_recipe` × 13 | `data/deep_aether/recipe/antidote.json` |
| `apotheosis` | 135 | `apotheosis:add_sockets` × 1<br>`apotheosis:malice` × 33<br>`apotheosis:potion_charm_crafting` × 1<br>`apotheosis:potion_charm_infusion` × 1<br>`apotheosis:purity_upgrade` × 5<br>`apotheosis:reforging` × 5<br>`apotheosis:salvaging` × 29<br>`apotheosis:sized_upgrade_recipe` × 27<br>`apotheosis:socketing` × 1<br>`apotheosis:supremacy` × 1<br>`apotheosis:unnaming` × 1<br>`apotheosis:withdrawal` × 1<br>`apothic_enchanting:infusion` × 2<br>`apothic_enchanting:keep_nbt_infusion` × 1<br>`apothic_spawners:spawner_modifier` × 26 | `data/apotheosis/recipe/god_fused_pearl.json` |
| `apothic_spawners` | 32 | `apothic_spawners:spawner_modifier` × 32 | `data/apothic_spawners/recipe/spawner_modifiers/burning.json` |
| `apothic_enchanting` | 19 | `apothic_enchanting:infusion` × 18<br>`apothic_enchanting:keep_nbt_infusion` × 1 | `data/apothic_enchanting/recipe/infusion/budding_amethyst.json` |
| `autumnity` | 18 | `woodworks:sawmill` × 18 | `data/autumnity/recipe/maple_boards_from_maple_logs_sawing.json` |
| `sophisticatedstorage` | 94 | `sophisticatedcore:upgrade_next_tier` × 14<br>`sophisticatedstorage:barrel_material` × 1<br>`sophisticatedstorage:double_chest_tier_upgrade` × 5<br>`sophisticatedstorage:double_chest_tier_upgrade_shapeless` × 1<br>`sophisticatedstorage:flat_top_barrel_toggle` × 1<br>`sophisticatedstorage:generic_wood_storage` × 6<br>`sophisticatedstorage:shulker_box_from_chest` × 6<br>`sophisticatedstorage:shulker_box_from_vanilla_shapeless` × 17<br>`sophisticatedstorage:storage_dye` × 1<br>`sophisticatedstorage:storage_tier_upgrade` × 35<br>`sophisticatedstorage:storage_tier_upgrade_shapeless` × 7 | `data/minecraft/recipe/shulker_box_from_vanilla_shulker_box.json` |
| `sophisticatedcore` | 1 | `sophisticatedcore:upgrade_clear` × 1 | `data/sophisticatedcore/recipe/upgrade_clear.json` |
| `sophisticatedbackpacks` | 30 | `sophisticatedbackpacks:backpack_dye` × 1<br>`sophisticatedbackpacks:backpack_upgrade` × 5<br>`sophisticatedbackpacks:basic_backpack` × 1<br>`sophisticatedbackpacks:smithing_backpack_upgrade` × 1<br>`sophisticatedcore:upgrade_next_tier` × 22 | `data/sophisticatedbackpacks/recipe/advanced_mob_catcher_upgrade.json` |
| `the_bumblezone` | 14 | `the_bumblezone:container_shapeless_recipe_bz` × 6<br>`the_bumblezone:itemstack_smelting_recipe` × 4<br>`the_bumblezone:nbt_keeping_shapeless_recipe` × 2<br>`the_bumblezone:potion_candle_recipe` × 2 | `data/the_bumblezone/recipe/honey_compass.json` |
| `touhou_little_maid` | 43 | `touhou_little_maid:altar_recipe_serializers` × 43 | `data/touhou_little_maid/recipe/altar_recipe/broom.json` |
| `touhou_little_maid_spell` | 23 | `touhou_little_maid:altar_recipe_serializers` × 23 | `data/touhou_little_maid_spell/recipe/altar/anchor_core.json` |
| `supplementaries` | 33 | `create:haunting` × 1<br>`create:mixing` × 1<br>`create:splashing` × 1<br>`farmersdelight:cutting` × 4<br>`jeed:effect_provider` × 2<br>`jeed:potion_provider` × 1<br>`neoforge:conditional` × 3<br>`supplementaries:add_charges` × 3<br>`supplementaries:antique_book` × 2<br>`supplementaries:bamboo_spikes_tipped` × 1<br>`supplementaries:blackboard_duplicate` × 1<br>`supplementaries:confetti_dye` × 1<br>`supplementaries:flag_from_banner` × 1<br>`supplementaries:item_lore` × 2<br>`supplementaries:present_dye` × 1<br>`supplementaries:safe` × 1<br>`supplementaries:soap_clearing` × 1<br>`supplementaries:sus_crafting` × 3<br>`supplementaries:trapped_present` × 1<br>`supplementaries:weathered_map` × 2 | `data/supplementaries/recipe/antique_book.json` |

Supplementaries 的三个 conditional 内层标识另行记录，不增加上述顶层资源总数。此表只说明打包资源存在；是否被加载、是否生产物品、所需输入类型及最终执行语义均须由服务端和受信适配器判定。
