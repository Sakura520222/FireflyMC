# AI 4.0 建材、容器与地图册动态配方

日期：2026-10-26。本文记录代码与受信合同；编译、GameTest和服务链结果由主实施记录补记，不以本文或资源扫描作为运行通过。

## 服务端执行入口

`BuildingDynamicRecipes` 通过既有 advanced 具体输入流程接入：`supports`、`inputSize`、`inputRequirements`、`verify`。普通动态配方为固定9格，Quark符文锻造为固定3格，每次一个真实操作。只认已加载且仍是同一 `RecipeHolder` 的原生class、RecipeType、RecipeSerializer及锁定Mod版本；不从JSON静态result产生物品。

| 模组合同 | 实际类别 | 边界 |
|---|---|---|
| Quark 4.1-482 + Zeta 1.1-40 | exclusion、mixed_exclusion、slab_to_block、elytra_duplication、smithing_rune、dye_item | 排除wrapper的parent只接受受审原版shaped/shapeless；排除引用有界并拒循环。原生Mixed/Slab递归标记在异常后也恢复，不把配方临时状态留给其他玩家 |
| Supplementaries 1.21.1-3.8.3 | 黑板复制、彩屑/礼物染色、旗帜、保险箱、去皂、竹刺药水、书籍/lore、补充charges、陷阱礼物、sus crafting | 使用原生matches/assemble/remaining，保留真实容器内容、名称、药水、文本及余料。其Create兼容/JEED/conditional资源不自动注册为生产能力 |
| Map Atlases 1.21-6.6.0 | Create/Add/Cut的VANILLA已加载地图ID、空白地图；Antique仅无filled ID路径 | 最多64个输入地图ID，非VANILLA/SLICED、未载入头部及有filled ID的Antique明确拒绝。后者和Supplementaries WeatheredMap会分配真实地图ID/SavedData，未纳入纯preview适配 |

所有输入及输出按 `RecipeInputIsolation` 持久codec隔离。Atlas的 `MapCollection/EmptyMaps/SelectedSlices` 可共享可变prototype，故 `BuildingDynamicComponents.detachDefaults` 只替换虚拟栈的这三个已知默认组件，使用有界独立codec解码；不修改全局Item默认值。SelectedSlices的Java对象身份比较由独立codec语义与无共享对象证明补足，其他未知共享组件仍拒绝。

剪刀为MapAtlas Cut的辅助工具：没有真实剪刀时只在隔离工作grid内注入虚拟剪刀，真实输入snapshot与材料需求保持空；虚拟剪刀及其native remainder绝不归仓。若玩家明确提供真实剪刀则取1、原物无损返1，`reusableGridSlots`按既有账本合同校验。原生模板/容器余料同样来自真实投入，不退款未投入的设备或燃料。

## 地图头部隔离

专用 `fireflymc-ai-atlas-recipes.mixins.json` 只对已锁定Map Atlases版本启用 `MapDataHolder.find` / `MapAtlasItem.getMaps` 两处合同。普通玩家操作在未激活adapter scope时照常走原生行为。plugin变换期仅触碰纯JDK hooks标记，不加载Minecraft或目标Mod运行时，保留严格require=1。

- scope先从本人真实输入的ID，在overworld已加载SavedData cache核同一对象；只读center、scale、dimension、locked等头部，拒磁盘加载，不读取或复制colors/decorations/整份私人地图。
- 为原生算法构造不注册到世界的独立metadata对象，按ID/Type复用同一个holder以保留原生身份语义；真实地图ID不分配、不重命名、SavedData不保存或修改。
- 输入及新建输出的getMaps都使用独立MapCollection，避免初始化共享EMPTY。真实native三方法在同一主线程slice内连续调用，保留原生菜单选择和组件更新；只调用纯输出 `validateSelectedSlices`，不在preview发统计或事件。
- callback使用verify阶段预建的 `Prepared` header view，重验原cache身份/头部后复用，不在 `NativeConstructionSave` scope内构造SavedData，不豁免未知保存效果。像素更新不属于纯ID装册的契约，不读取像素hash以使正常探索不误失效。

## 本批验证入口

- `BuildingDynamicRecipesTest` 三项纯合同：版本/类/type/serializer精确匹配、非生产JSON与分配地图类别拒绝、锻造与剪刀角色。
- Header helper两项纯Bootstrap/隐私合同，见对应测试源码。
- `AiBuildingDynamicGameTests` 五个真实已加载recipe用例：混合8板、真实箱内容转Safe、礼物染色内容、空白地图取出且虚拟剪刀不返造、预先真实创建的filled-map只读header且preview不增加地图SavedData。

子代理未运行Gradle、游戏或供应商，未暂存、提交、部署或改动用户实际实例。原生字节码/资源静态证据在忽略目录 `build/ai4-validation/integrations/processing/dynamic/`；仍须主代理统一编译与隔离GameTest，不能将这些静态记录写成验收通过。

首轮主代理运行 `building_dynamic-20261026-160546-194`：79项中77项通过，两个Atlas用例在header scope构造时被拒。锁定Minecraft源码确认 `GameTestServer.isDedicatedServer()` 返回false；该scope是无服务初始化的纯cache/header计算，不负责开放AI。已删除其冗余的server-type检查，保留主线程、同owner/level、不可重入、版本、hooks及cache身份核验，未改AI服务/事件/工具桥的专服认证门禁，也未添加GameTest识别特例。此修正待主代理复验，不提前记Atlas运行通过。
