# Story Engine v1 设计文档

- **日期**: 2026-09-27
- **子项目**: 剧情引擎 v1（总构想见仓库根 `文件.md`，子项目序列的第 1 个）
- **状态**: 设计冻结 / Ready for implementation planning（已通过分节评审与用户终审）
- **目标版本**: FireflyMC 3.2.0（`gradle.properties` 发布前提升）

---

## 1. 背景与目标

FireflyMC 将从"生存整合包 + 功能 Mod"升级为以流萤为核心叙事的剧情 RPG 整合包。本 Mod（`fireflymc`）自研剧情运行时：**Java 只实现通用引擎，具体剧情全部 JSON/数据包化**；社区 Mod 仅作为"舞台"。

总构想拆分为 7 个子项目，依序交付：

1. **Story Engine v1**（本文档）
2. 萤火纪行 UI（任务书 Screen + 追踪 HUD）
3. Dialogue Engine
4. GeckoLib NPC（流萤实体）
5. Cutscene Engine
6. 社区 Mod Integration
7. 第一季剧情内容批量编写

### v1 范围

**做**：数据包加载器、章节/任务定义模型、Condition/Action 注册表、Flag/变量系统、玩家进度持久化（Data Attachment + copyOnDeath）、世界剧情状态（SavedData）、网络同步（含 storyRevision）、队列式任务完成事务、调试命令、示例序章内容、双层测试。

**不做**（明确延后）：任务书 UI/HUD（子项目 2）、对话与 `dialogue_choice`/`interact_entity` 条件（子项目 3）、`spawn_entity`/`play_music`（子项目 4）、`play_cutscene`/`change_theme`/`unlock_archive`（子项目 5）、新物品/方块注册（内容阶段）、客户端定义下发（子项目 2 决策）、Action journal / exactly-once（远期）。

### 关键产品决策（已确认）

| 决策点 | 结论 |
|---|---|
| 多人语义 | 每个玩家独立剧情进度 + 世界级共享 Flag/变量 |
| v1 内容 | 引擎 + 真实序章故事（不含为覆盖率硬塞的测试链） |
| 引擎架构 | 自研、事件驱动 + 兴趣索引定向重评估 + 持续条件错峰轮询 |
| 剧情文本 | 全部 `Component`（支持字面量与翻译键两种形态） |

---

## 2. 总体架构

### 2.1 数据流（唯一合法路径，从 v1 严格执行）

```
NeoForge Game Event                    Story Action 副作用
        │                                     │
        ▼                                     ▼
   Story Adapter                      StorySignal 生成
        │                                     │
        └──────────────┬──────────────────────┘
                       ▼
                 StoryEvent（标准化事件）
                       ▼
                 InterestIndex（兴趣索引）
                       ▼
                 QuestEvaluator（纯逻辑评估）
                       ▼
                 TransitionQueue（队列式事务）
                       ▼
          ┌────────────┴────────────┐
          ▼                         ▼
   State Transition            StoryAction 执行
   (LOCKED→ACTIVE→COMPLETED)   (best-effort，逐个 try/catch)
          └────────────┬────────────┘
                       ▼
                Progress Mutation（封装方法）
                       ▼
                Dirty Sync（合并发送）
```

- **Adapter 内零状态判断逻辑**：只做 NeoForge Event → StoryEvent 转换。
- **外部游戏事件与内部 Action 副作用统一汇聚为 StoryEvent**，入口不同、管线相同。

### 2.2 包结构

```
firefly520/fireflymc/story/
├── StoryEngine.java            # 门面：生命周期挂载、对 FireflyMCMod 的唯一入口
├── loader/                     # StoryReloadListener + Definition record + StoryGraph + 校验
├── condition/                  # StoryCondition 接口（含 STICKY/COUNTER/DYNAMIC）+ 注册表 + 实现
├── action/                     # StoryAction 接口 + 注册表 + 实现
├── progress/                   # StoryProgress 附件 + WorldStoryState SavedData + FlagStore
├── engine/                     # StoryEvent/StorySignal + InterestIndex + QuestEvaluator
│                               #   + TransitionQueue + ContinuousWatchSet + StoryRuntimeSnapshot
├── adapter/                    # NeoForge 游戏事件/tick 适配层（唯一碰 MC 事件的层）
├── sync/                       # StorySyncService（合并发送）
├── command/                    # /fireflymc story 子命令树
└── StoryTriggers.java          # 对外 API：custom_event 触发（Dialogue/Cutscene 复用）
client/story/ClientStoryState.java   # 客户端进度镜像（本版只存不用）
network/StoryProgressSnapshotPayload.java  # S→C 全量进度 + storyRevision
```

### 2.3 两条铁律

1. **Definition 永远不可变，Runtime 永远不修改 Definition**。所有 Definition 为 record；玩家/世界状态只存 ID、状态、数值，**绝不持有、修改或序列化 Definition 本体**。
2. **状态只能经 `StoryProgress.getQuestState(id)` 推导**（返回 `LOCKED/ACTIVE/COMPLETED` 枚举），调用方禁止自行 `contains` 判断；迁移只允许 `LOCKED→ACTIVE→COMPLETED`，`COMPLETED` 不可回退（唯一例外：`reset` 命令）。

---

## 3. 数据模型

### 3.1 任务定义（`data/<ns>/story/quests/<章节>/<id>.json`，文件路径即任务 ID）

```json
{
  "chapter": "fireflymc:prologue",
  "title": { "text": "坠落的萤火", "color": "#8EEDE2" },
  "subtitle": { "text": "寻找散落在森林中的记忆" },
  "description": [
    { "text": "那并不是普通的光。" },
    { "translate": "story.fireflymc.prologue.voice" }
  ],
  "icon": "fireflymc:textures/gui/quest/memory_fragment.png",
  "initial": false,
  "requirements": [
    { "id": "memory", "state_version": 1, "type": "fireflymc:pickup",
      "item": "minecraft:echo_shard", "count": 3 }
  ],
  "on_complete": [
    { "type": "fireflymc:set_flag", "flag": "prologue.heard_voice", "value": true },
    { "type": "fireflymc:unlock_quest", "quest": "fireflymc:prologue/first_contact" }
  ]
}
```

- 文本字段（chapter/quest 的 title/subtitle/description、`show_title`/`send_message`）一律 `Component` Codec：支持 `{"text":..., "color":...}` 字面量与 `{"translate":...}` 翻译键。
- `requirements`：AND 关系；`id` 必填、同一 Quest 内唯一（计数器/满足记录的持久化键），`state_version` 默认 1 且必须 ≥ 1（见 3.4）。空数组合法：任务一旦激活即立即完成、执行 `on_complete`（可用于纯 Action 桥接节点）。
- `initial: true` = 新玩家引导根任务；**禁止**用"无静态入边即根任务"推导。加载期校验 initial 任务存在且 chapter 引用有效。
- `icon` 本版仅存储定义，不渲染（UI 子项目消费）。

### 3.2 章节定义（`data/<ns>/story/chapters/<id>.json`）

仅排序/分组元数据：`title`、`subtitle`、`index`。任务通过自身 `chapter` 字段反向归属，章节文件**不**列任务清单（避免两处维护）。

### 3.3 StoryRuntimeSnapshot（不可变，单引用原子替换）

```java
public record StoryRuntimeSnapshot(
    Map<ResourceLocation, ChapterDefinition> chapters,
    Map<ResourceLocation, QuestDefinition> quests,
    InterestIndex interestIndex,
    StoryGraph graph,
    String revision          // 定义规范化内容的 SHA-256
) {}
```

`volatile StoryRuntimeSnapshot` 单字段；reload 只有 `this.snapshot = candidate` 一次赋值——Definition、索引、图、revision 永远属于同一代。**不搞两个 volatile 分开替换**。

### 3.4 玩家进度（Data Attachment，挂 Player）

```java
class StoryProgress {              // 所有字段私有，仅暴露封装方法
    Set<ResourceLocation> completedQuests;
    Set<ResourceLocation> activeQuests;
    Map<String, Boolean> flags;            // 剧情布尔（玩家 scope）
    Map<String, Integer> vars;             // 好感度等数值（玩家 scope）
    Map<RequirementProgressKey, Integer> counters;   // 仅 COUNTER 条件
    Set<RequirementProgressKey> satisfied;           // 仅 STICKY 条件
    ResourceLocation trackedQuest;         // 追踪任务（HUD 预留），完成后清空
}
```

- 附件启用**持久化序列化 + `copyOnDeath`**（NeoForge 附件默认不随死亡复制，必须显式开启）。
- 随 MC 常规存档周期落盘；死亡/重生保留。
- 客户端不修改进度，服务端是唯一权威。
- 未定义默认值（玩家/世界两个 scope 一致）：未设置 Flag = `false`；未设置 Var = `0`；`set_var add` 从 0 起算；clamp 在 set/add 计算完成后执行。

```java
public record RequirementProgressKey(
    ResourceLocation questId,
    String requirementId,
    int stateVersion      // 定义里 bump → 旧运行时状态自动失效重评估
) {}
```

序列化为嵌套结构（按 questId → requirementId → {version, value}），不做字符串拼接键。

### 3.5 世界剧情状态（SavedData，挂 Overworld）

`fireflymc_story_world`：结构相同的 flags/vars（世界 scope；未定义默认值与 clamp 时序同 3.4）。Overworld 不会像普通维度一样完全卸载，非维度专属数据挂它。**所有变更必须走封装方法** `setFlag/setVar/addVar`（内部 `put + setDirty()`），不暴露可变集合。

### 3.6 Flag/变量命名规则（加载期强制校验）

- 模式：`[a-z0-9_./-]+`（禁止空串、空白、`#`、`:`、大写）；鼓励层级命名（`firefly.met`、`sam.identity_known`、`chapter01.memory_found`）。
- **同一 scope 内 flag 名与 var 名不得重复**（否则 `{"flag":"firefly.affinity"}` 与 `{"var":"firefly.affinity"}` 并存极难排查）。

---

## 4. Condition 系统

### 4.1 三类生命周期（接口硬语义）

```java
enum ConditionPersistence { STICKY, COUNTER, DYNAMIC }
```

| 类别 | 持久化 | 语义 |
|---|---|---|
| STICKY | `satisfied` 集合 | 瞬时条件，满足一次永久生效 |
| COUNTER | `counters` 计数 | 任务激活后累计，达到 count 判定满足，不回溯 |
| DYNAMIC | 无 | 每次评估实时求值 |

**激活/reconcile 时的 current-state evaluation**：`LOCKED→ACTIVE` 与 reload reconcile 时立即按当前状态求值 STICKY 与 DYNAMIC——玩家已身处暮色森林时才解锁"进入暮色森林"应立即满足；`advancement` 读取玩家当前成就状态。**COUNTER（kill/pickup/custom_event）不回溯**，只从 ACTIVE 后累计。

### 4.2 v1 条件清单

| type | 持久化类别 | 参数 | 语义 |
|---|---|---|---|
| `fireflymc:pickup` | COUNTER | item, count | ACTIVE 后由玩家**拾取实体**进入背包的累计数量 |
| `fireflymc:inventory` | DYNAMIC | item, count | 当前背包持有 ≥ N（状态条件） |
| `fireflymc:kill` | COUNTER | entity ∥ entity_tag, count | 累计击杀 |
| `fireflymc:enter_dimension` | STICKY | dimension | 进入维度 |
| `fireflymc:enter_biome` | STICKY | biome ∥ biome_tag | 到达群系 |
| `fireflymc:enter_structure` | STICKY | structure ∥ structure_tag | **玩家当前位置处于结构范围内**（当前已加载区域判定，绝不 `/locate` 式搜索最近结构）；换 chunk 时与错峰轮询时判定 |
| `fireflymc:advancement` | STICKY | id | 获得成就（激活时回读现状） |
| `fireflymc:flag` | DYNAMIC | flag, value, scope(player/world) | 实时检查；flag 变更本身进兴趣索引触发重评估 |
| `fireflymc:location` | STICKY | dimension, x, y, z, radius | 到达坐标 |
| `fireflymc:custom_event` | COUNTER | event(ResourceLocation), count | API/命令触发的累计事件 |

`∥` 表示 exactly-oneOf：`entity` 与 `entity_tag` 必须二选一（biome/structure 同理），两个都写或都没写 → 校验失败。标签成员的具体 ID 在 apply 展开后进入最终 InterestIndex；Tag 存在但成员为空时保留条件为不可匹配状态并 WARN。

**`inventory` 的评估时机**：事件驱动优先（捡起、容器取出、合成产出等 NeoForge 可挂的事件）+ 错峰 tick 兜底进 ContinuousWatchSet；**提交 `ACTIVE→COMPLETED` 前对全部 DYNAMIC 条件做一次终检**（防止事件触发后物品已被消耗仍完成任务）。

### 4.3 注册表

`Map<ResourceLocation, MapCodec<? extends StoryCondition>>` 简单注册表（不挂 vanilla Registry 对象），按 JSON `type` 字段多态分发，Codec 解析错误带文件定位。后续子项目/其他 Mod 可继续注册新类型（`StoryTriggers`/注册表公开 API 预留）。

---

## 5. Action 系统

### 5.1 v1 动作清单

| type | 参数 | 语义 |
|---|---|---|
| `fireflymc:set_flag` | flag, value, scope | 设 Flag（触发 FLAG_CHANGED invalidation） |
| `fireflymc:set_var` | var, op(set/add), amount, min?, max? | 设/加数值，可选 clamp（触发 VAR_CHANGED） |
| `fireflymc:unlock_quest` | quest | LOCKED→ACTIVE（进同一事务队列） |
| `fireflymc:give_item` | item, count | 优先入背包，**满包则掉落玩家脚下，绝不静默吞掉**；触发 inventory dirty，**不增加 pickup 计数**（奖励不推进收集任务） |
| `fireflymc:play_sound` | sound, volume, pitch | 对玩家播放 |
| `fireflymc:show_title` | title, subtitle?, fade_in, stay, fade_out | 章节标题式演出（Component） |
| `fireflymc:send_message` | text | 聊天栏剧情文本（Component） |
| `fireflymc:execute_command` | command | **静态字符串**，以当前任务所属 `ServerPlayer` 的 CommandSourceStack、固定权限等级 2 执行（`@s` 即该玩家） |
| `fireflymc:teleport` | dimension, x, y, z | 场景转换（触发 dimension/location/biome/structure 重评估） |

### 5.2 失败语义（v1 冻结）

```
requirements 全满足
    ↓
先 ACTIVE → COMPLETED（状态先落定）
    ↓
逐个执行 action，每个独立 try/catch
    ↓
单个失败 → ERROR 日志 → 继续执行后续 action
```

**禁止任何回滚**（`COMPLETED→ACTIVE` 不存在；give_item/teleport/execute_command 无法安全撤销）。Action 为 best-effort 副作用；服务器在动作执行中途崩溃时 v1 不承诺 exactly-once（远期可用 action journal 强化）。

### 5.3 副作用回流（统一管线）

`set_flag/set_var` → `FLAG_CHANGED/VAR_CHANGED` 信号；`give_item` → inventory dirty；`teleport` → 位置类重评估——全部经 InterestIndex 进入 TransitionQueue，**同一 transition transaction 内**即完成连锁（任务 A set_flag → 任务 B flag 条件同事务完成，不等下一 tick）。

### 5.4 `execute_command` 安全约束

- 只允许静态 command string，**不支持 StoryEvent.context 插值**（防命令注入演化）；
- 以当前任务所属 `ServerPlayer` 的 CommandSourceStack 执行，**固定权限等级 2**（`@s` = 剧情玩家；需要更高权限的命令不执行）；不继承触发者可能变化的实际 OP 等级；
- Data Pack 与服务端 Mod 配置同级，视为**受信任内容**；
- 动态循环（`execute_command → /fireflymc story trigger → quest complete → ...`）由 1024 work item 上限兜底。

---

## 6. 加载与校验（严格原子 reload）

### 6.1 流程

```
prepare（异步线程，纯数据工作）
  解析 JSON（Codec）→ Definition record（不可变）
  纯定义校验 + StoryGraph 构建 + 未解析的索引声明
  计算 revision → 生成 PreparedStoryData（Definition、Graph、未解析索引声明）
        ↓
apply（主线程）
  Registry/Tag 与 Advancement 引用校验
  展开 Tag 成员 → 构建最终不可变 InterestIndex
  组装 candidate StoryRuntimeSnapshot → 单次原子 swap
        ↓
  标记所有在线玩家 needsReconcile
        ↓
下一 ServerTickEvent.Post
  统一 reconcile（不进 reload 调用栈跑任务链）
```

- 涉及真实 Registry/Tag 是否存在的校验放在能拿到 reload 上下文的位置执行，异步线程不碰 MC runtime 对象。校验因此拆两段：**纯结构校验**（Codec、命名规则、oneOf、图引用/环——引用目标只需 ID 格式合法）在 prepare（异步）完成；**资源引用校验**在 apply（主线程，用 `AddReloadListenerEvent` 提供的 RegistryAccess、ServerResources）完成，展开 Tag 成员后构建最终索引，全部通过才允许 swap。
- 单个 `StoryReloadListener` 监听 `data/<ns>/story/` 根目录，按首段路径分 `chapters/`/`quests/`——章节与任务**原子一起重建**。

### 6.2 严格原子语义

**任一**文件解析失败或校验失败 → **整个 candidate 作废** → 保持上一版 StoryRuntimeSnapshot 不变（绝不部分更新）。ERROR 日志精确定位文件与字段。

### 6.3 校验清单（加载期）

- Codec 解析、未知 condition/action type、缺字段；
- flag/var 命名规则（3.6）与同 scope flag-var 同名冲突；
- 同一 Quest 内 `requirement.id` 唯一；`state_version >= 1`、COUNTER/`inventory` 的 `count > 0`、`location.radius >= 0`、`give_item.count > 0`，所有浮点数参数（坐标、音量、音调、半径及可选范围）必须为有限值；`requirements: []` 明确合法且激活后立即完成；
- `unlock_quest` 引用存在性 + **unlock 环拒绝**（StoryGraph 静态查环）；
- quest→chapter、initial→quest/chapter 引用；
- Registry 引用存在性：item/entity/dimension/structure/biome/sound 的具体 ID 经 Registry/HolderLookup 校验，不存在 → ERROR（candidate 作废）；
- Advancement 非 Registry 条目：经 `AddReloadListenerEvent#getServerResources()` 访问 `ReloadableServerResources#getAdvancements().get(id)` 校验，不存在 → ERROR；
- exactly-oneOf（entity∥entity_tag 等）；
- tag 存在但为空 → **WARN**（不失败；兼容包可能有意留空等可选 Mod 注入）。

### 6.4 在线玩家 reconcile

- 进度引用已删除任务：数据保留、WARN 一次、本次会话不评估；
- requirements 变化（同 state_version）：重评估，老玩家可能立即完成链；
- `state_version` 变化：丢弃该 Requirement 旧运行时状态（counters/satisfied），按新定义重评估；
- reconcile 含 current-state evaluation（见 4.1）。

---

## 7. 运行时机制

### 7.1 事件与信号

```java
public record StoryEvent(
    StoryEventType type,        // ITEM_PICKUP / ENTITY_KILL / DIMENSION_ENTER / ...
    ResourceLocation key,
    int amount,
    Map<String, String> context
) {}
```

Adapter（NeoForge 事件挂载点）：`ItemEntityPickupEvent.Post`（`getOriginalStack().getCount() - getCurrentStack().getCount()` 计算实际进入背包数量，仅成功拾取时触发）、`LivingDeathEvent`（击杀者为 ServerPlayer）、`PlayerChangedDimensionEvent`、`AdvancementEvent.AdvancementEarnEvent`、`PlayerRespawnEvent`（重发 snapshot）、`PlayerLoggedIn/LoggedOut`、`ServerTickEvent.Post`（ContinuousWatchSet 轮询 + sync flush），以及 NeoForge 提供的容器取出/合成产出事件中可挂载的部分（挂不上的路径由 ContinuousWatchSet 错峰轮询兜底，见 4.2 终检）。

### 7.2 兴趣索引（全局，玩家过滤在评估时）

```
ITEM_PICKUP     minecraft:echo_shard → { questA: [memory], questF: [req3] }
ENTITY_KILL     minecraft:zombie     → { questC: [req2] }
DIMENSION_ENTER twilightforest:...   → { questD: [req1] }
FLAG_CHANGED    firefly.met          → { questB: [req1] }
```

索引键永远是具体 ID（tag 条件已在 apply 阶段展开为成员集），`StoryEvent` 不携带 tag。事件来了只重评估有兴趣的 Requirement，绝不遍历全部 ACTIVE 任务全部条件。

### 7.3 TransitionQueue（队列式事务）

```java
Deque<ResourceLocation> queue;        // 待（重）评估任务
Set<ResourceLocation> queued;         // 仅对当前在队列内的任务去重；出队即移除
```

- 每次事件/信号 → 将候选任务入队（若已在队列中则去重）→ 出队时从 `queued` 移除 → 评估 → 满足则完成、执行 on_complete、解锁并重新入队候选。仍为 ACTIVE 的任务允许被本事务后续 invalidation 再次入队；COMPLETED 状态阻止重复完成。
- **无连锁深度上限**（正常剧情几十层合法，图校验已静态拒绝 unlock 环）；仅有 `maxWorkItemsPerTransaction = 1024` 的宽防御上限，按**出队评估次数**计数（含未完成的重评估），针对图校验捕捉不到的动态循环（custom_event↔action 等），超限 ERROR 并中止该事务。
- 事务结束时统一 dirty sync（见 8.2）。

### 7.4 ContinuousWatchSet 与错峰

- 任务 `LOCKED→ACTIVE` 时加入、`ACTIVE→COMPLETED` 时移出、reload/reconcile 时重建；按玩家维护（location/biome/structure/DYNAMIC-inventory 兜底）。
- 轮询相位错峰：`(playerPhase + serverTick) % 20 == 0`（每玩家每秒一次，分散到 20 个 tick，避免同 tick 尖峰）。

### 7.5 追踪任务

被追踪任务完成后 `trackedQuest` 清空；自动切换下一任务留给 UI 子项目。

---

## 8. 持久化与同步

### 8.1 持久化

| 数据 | 载体 | 落盘 |
|---|---|---|
| 玩家进度 | Data Attachment（serializable + copyOnDeath） | MC 常规存档周期（自动） |
| 世界剧情状态 | Overworld SavedData | 封装方法自动 `setDirty()` |

单人/局域网（集成服务器）与专用服走**同一套服务端逻辑**，无分支。

### 8.2 同步（合并发送）

- `StoryProgressSnapshotPayload`（S→C）：登录后 + **事务结束/tick 尾合并发送**（一次任务完成连锁 6 个变更只发 1 个包）；玩家变更 → 仅该玩家；世界 Flag 变更 → 广播所有在线玩家；respawn 后重发。
- 载荷：completed/active、flags/vars（玩家+世界）、counters/satisfied、trackedQuest、**`storyRevision`**。
- `storyRevision` = 全部 chapters+quests 规范化序列化后的 SHA-256：Definition 按 ResourceLocation 排序，对象键按确定性顺序排序，requirements/actions **数组保留声明顺序**；不同 JSON 空白或对象键排版得同 hash，语义或声明顺序变化则改变 hash。客户端 `ClientStoryState` 用**不可变进度快照 + volatile 引用整体替换**，断开服务器/退出世界时清空，避免读取上一服务器残留状态（本版只存不用）。UI 子项目凭 revision 决定是否需要服务端下发定义（预计采用服务端下发裁剪版定义，服务器为唯一真相）。
- **定义本版不下发客户端**（同 jar 自带数据包）；服务器数据包覆盖导致的端侧差异由 storyRevision 在 UI 子项目暴露。
- `ModNetwork.NETWORK_VERSION` 随新包 bump 至 **1.5.0**（强制双端同 Mod 版本）。
- 所有发包在服务器主线程（审查规则）。

---

## 9. 命令与配置

### 9.1 `/fireflymc story` 子命令树（OP 权限，显式 scope 而非 flag 参数）

```
/fireflymc story status [player]
/fireflymc story setflag player <player> <flag> <true|false>
/fireflymc story setflag world <flag> <true|false>
/fireflymc story setvar  player <player> <var> set|add <n>
/fireflymc story setvar  world <var> set|add <n>
/fireflymc story unlock  <quest> [player]
/fireflymc story complete <quest> [player]     # 走正规 ACTIVE→COMPLETED→on_complete 状态机
/fireflymc story counter  <quest> <reqId> set|add <n> [player]
/fireflymc story trigger <event> [player]      # custom_event，ResourceLocation 参数
/fireflymc story reset player <player>         # 二次确认：短时效 token
/fireflymc story reset world                   # 独立确认，与玩家 reset 互不牵连
```

- `complete` 只走正规状态机（触发 on_complete）；单纯改状态的 `mark-completed` 开发命令**本版不提供**，避免一词两义。
- `reset player` 清 completed/active/counters/satisfied/玩家 flags+vars/trackedQuest 后**重新 bootstrap initial 任务**；`reset world` 只清世界 scope。两者都走同一种短时效 token 二次确认（各自独立发 token，互不通用）。
- 确认 token 绑定执行者+目标+scope+过期时间（防误执行旧确认）。
- 所有参数 `tryParse` 判空 + 未知 ID 给操作者反馈（审查规则）。
- reload 用原版 `/reload`，不另做命令。

### 9.2 配置

`ServerConfig` 新增 `storyEnabled`（默认 true）。**false 时不停止加载 Definition**（reload 照常、快照照常换），只让 Adapter/Evaluator 不再推进剧情；重新启用后安排一次 reconcile 即可，无需重启资源系统。

---

## 10. 错误处理汇总

| 场景 | 行为 |
|---|---|
| JSON 解析失败 / 未知 type / 命名违规 / flag-var 冲突 / unlock 环 / 悬空引用 / Registry ID 不存在 / oneOf 违规 | 整个 candidate 作废，保持旧快照，ERROR 定位文件字段 |
| tag 存在但为空 | WARN，不失败 |
| 单个 Action 执行异常 | ERROR 日志，继续后续 action，不回滚 |
| 单次事务出队评估次数 > 1024 | ERROR 并中止该事务 |
| 进度引用已删任务 | 数据保留，WARN 一次，不评估 |
| give_item 满包 | 掉落玩家脚下 |
| 命令非法参数 | tryParse 判空 + 反馈 |

---

## 11. 测试策略（两层）

### 11.1 纯逻辑 JUnit（不依赖运行中的服务器）

Codec 解析（合法/非法/未知 type/命名违规/oneOf/唯一 Requirement ID/数值边界/空 requirements）、Definition 校验、StoryGraph（环/悬空/initial）、InterestIndex、QuestEvaluator（三类条件语义、current-state evaluation、counters/satisfied、DYNAMIC 终检）、TransitionQueue（queued 去重、同一任务在后续信号后可重评估、1024 work item 上限）、state_version 失效迁移、revision 规范化稳定性、同步 dirty/coalescing、StoryProgress NBT 往返、WorldStoryState 封装 mutation 触发 dirty。

### 11.2 NeoForge 集成层（GameTest / 测试专服，`./gradlew runGameTestServer`）

Data Attachment 持久化与 copyOnDeath、respawn 重同步、登录同步、Payload 注册与往返、真实 NeoForge Event → Adapter → 完成链、SavedData 落盘、`/reload` 严格原子与 reconcile、跨维度 teleport、structure/biome 条件、专用服行为。

### 11.3 内容分离

- **正式内容**：`story/chapters/prologue.json` + 一条真实序章故事链（20-30 分钟体量的骨架，纯引擎能力可表达的节点），不为覆盖率硬塞条件。
- **验收夹具**：`src/test/resources/story_fixture/` 覆盖全部 10 条件 / 9 动作 / reload / cycle / broken reference / state_version——不进正式数据包。

---

## 12. 验收标准

1. 专用服与单人/LAN 一致：新玩家进服自动激活 initial 任务链，逐个满足条件后任务自动完成、动作执行、连锁解锁；
2. 进度跨重登、跨死亡（copyOnDeath）保留；世界 Flag 全服共享且落盘；
3. `/reload` 严格原子：坏 JSON 不影响现有剧情运行；
4. 同步无风暴：一次连锁只发一个 snapshot；
5. `/fireflymc story` 全子命令可用，reset 带 token 确认；
6. `./gradlew build test` 与 GameTest 全绿；
7. `.sakura/rules/` 审查规则对照通过（主线程发包、volatile+reset、tryParse 判空、枚举安全、日志无敏感信息）。

---

## 13. 后续子项目衔接点（本版预留，不实现）

- `StoryTriggers.fireCustomEvent(...)` API → Dialogue（3）/Cutscene（5）复用；调用契约：入口只在服务器主线程修改 StoryProgress，异步调用必须经 `server.execute(...)` 委托，并在主线程再次确认目标玩家仍在线且为同一连接实例——不能在第三方网络回调直接修改进度；
- `ClientStoryState` + `storyRevision` → UI 子项目（2）消费，届时决策定义下发方案；
- `trackedQuest` → HUD 追踪；
- Condition/Action 注册表公开注册入口 → 社区 Mod Integration（6）；
- `icon` 字段、`initial`/章节 `index` → 萤火纪行章节树 UI。

---

## 附：评审记录

- 2026-09-28 终审通过，纳入 6 项修正与 4 项实现约束：queued 可重评估、Post 拾取、Tag 展开推迟至 apply、Advancement 独立校验、Flag/Var 默认值、schema 边界、revision 规范化、客户端快照清理、主线程触发 API、玩家上下文固定权限命令。
- 2026-09-27 分节评审通过，累计纳入 25 项修正（第 1 节 6 项、第 2 节 9 项、第 3 节 10 项），关键项：copyOnDeath、结构化 RequirementKey、StoryRuntimeSnapshot 单引用原子替换、严格原子 reload、队列式事务（删除深度上限）、storyRevision、initial 任务、pickup/inventory 语义分离、state_version、enter_structure、Action 不回滚、Component 文本、双层测试。
