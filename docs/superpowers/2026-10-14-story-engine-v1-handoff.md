# Story Engine v1 交接文档

日期：2026-10-14  
仓库：`/home/firefly/Projects/FireflyMC`  
分支：`3.1.0`

## Git 状态

- 交接前工作树干净，`3.1.0` 与 `origin/3.1.0` 同步。
- 当前 HEAD：`233f8f2ddf3f0ca4449f381d4b774bec00993b45`。
- HEAD 提交时间/作者：2026-10-13 10:45:40 +0800，`sakura520222`。
- 提交信息：`1`。该提交已推送，不要改写历史；后续修复应新增提交。
- 该提交包含 Tasks 1–12 的实现、测试、GameTest authoring 和测试 fixtures。
- 本交接文档是新创建的未提交文件。
- `.superpowers/sdd/2026-09-28-story-engine-v1/` 是本地过程记录，未随 Git 提交。

## 当前总状态

Tasks 1–11 已完成并通过独立复审。Task 12 已实现并完成两轮修复，但第三轮修复和最终复审未完成。Task 13 未开始。

| Task | 状态 | 最后记录验证 |
|---|---|---|
| 1 definitions/conditions/actions/codecs | complete | 65/65 focused；`compileJava` 通过 |
| 2 loader/graph/revision | complete | loader 18/18 + revision 6/6；story 回归 141/141 |
| 3 reload/atomic snapshot/index | complete | reload 11/11；compile 通过 |
| 4 progress/world NBT | complete | progress 17/17 + world 7/7 |
| 5 evaluator/watch set | complete | evaluator 17/17；story 141/141 |
| 6 action executor | complete | action 18/18；full 266/266 |
| 7 transition queue/dirty | complete | transition 20/20；full 287/287 |
| 8 lifecycle/config/trigger | complete | trigger 9/9 + config 3/3；full 299/299 |
| 9 snapshot codec/sync service | complete | payload/service 20/20；full 322/322 |
| 10 NeoForge adapters/session gate | complete | adapter 15/15 + gate 3/3；full 340/340 |
| 11 payload/client mirror | complete | mirror 9/9、payload 20/20、story 183/183；full 349/349 |
| 12 story commands/reset | **not approved** | fix round 2 focused 33/33；full 382/382；compile 通过 |
| 13 prologue/GameTest integration | not started | 未创建 task-13 brief/report |

以上测试数字来自对应 task report，不是 2026-10-14 的新鲜重跑。最终交接前必须重新执行需要的验证。

## Task 12 当前阻塞项

最新 scoped review 拒绝 Task 12，剩余缺陷在 `StoryEngine.trigger` dirty ownership：

1. **成功但未完成的 custom trigger 会漏发玩家同步**
   - `evaluator.onEvent(...)` 会先把 counter 从 0 增到 1；
   - 若 threshold 是 2，quest 仍 ACTIVE；
   - 当前源码调用 `runTransaction(..., false)`；
   - `runTransaction` 在 evaluator mutation 之后才计算自己的 before fingerprint，因此看不到该 mutation；
   - 结果返回 `PERFORMED` 但不 mark player dirty，客户端不会收到新 snapshot。
2. **异常路径可能重复 dirty callback**
   - evaluator 成功后若 transaction 阶段异常，`runTransaction` finally 可能已经 mark dirty；
   - `trigger` 外层 catch 又按 fingerprint mark dirty；
   - 生产 sync set 会合并，但 callback 契约不是 exactly-once。
3. **Task 12 report 顶部仍写 initial 18/18、367/367**
   - 后续 fix rounds 已到 33/33、382/382；
   - 顶部应显式标记 initial evidence historical。

### 建议第三轮修复

不要机械把 `runTransaction(..., false)` 改成 `true`；先分离 dirty ownership：

1. 将 evaluator 阶段与 transaction 阶段拆开。
2. evaluator 阶段异常：
   - 使用调用前 progress/world fingerprint 判断 partial mutation；
   - 只为该阶段实际变化 mark dirty；
   - log 并返回 `FAILED`。
3. evaluator 成功后：
   - 计算 `preEvaluatedMutation = !progressBefore.equals(ProgressFingerprint.of(...))`；
   - 将该值传给 `runTransaction`；
   - transaction 阶段异常由 `runTransaction` finally 负责 dirty，外层 catch 不重复 mark。
4. 新增测试：
   - custom event threshold 2，trigger amount 1；
   - assert `PERFORMED`、counter 1、player dirty exactly once、world dirty 0；
   - 保留 multi-candidate evaluator failure 测试，确认 partial mutation 只 mark once；
   - 如可构造 transaction 阶段异常，补充 no-double-mark 测试。
5. 修正 report 顶部 historical wording。
6. 重跑 focused suites、`compileJava`、`./gradlew test`、`git diff --check`，更新 report/manifest，并派发新的 scoped `glm-5.3-flash` 复审。

Task 12 非阻塞 carryover：

- `StoryEngine.CommandApi.of/isolated` 仍 public，已记录为 accepted carryover；
- production offline UUID lookup 主要由 test seam 覆盖，真实 lookup 留给 live/GameTest；
- token 断言只说明 tested sentinel absent，不应夸大为 no random token issued。

## Task 13 必须保留的三个计划修正

Task 13 未开始。正式内容尚未创建，当前只有 test fixtures / GameTest template。

1. `first_light` 是 `initial:true` + `requirements:[]`：
   - bootstrap 后立即 `COMPLETED`；
   - `find_memory` 必须 `ACTIVE`；
   - 不得断言 first login 后 `first_light=ACTIVE`。
2. 正式 `old_trail` 不得用绝对坐标：
   - v1 `location` 是绝对 dimension/x/y/z/radius，spawn 坐标不可靠；
   - 正式节点改成可自然完成的 inventory 条件（例如 torch）；
   - `location` 由 GameTest/test fixture 覆盖。
3. `embers_end` 在 v1 内必须可完成：
   - 不依赖未实现 NPC/Dialogue 的 `found_firefly` custom event；
   - 完成时设置 world flag `prologue.awaiting_firefly=true`；
   - 后续子项目另加 `first_contact` 节点再监听 `found_firefly`。

### Task 13 文件

创建：

- `src/main/resources/data/fireflymc/story/chapters/prologue.json`
- `src/main/resources/data/fireflymc/story/quests/prologue/{first_light,find_memory,heard_voice,quiet_night,old_trail,embers_end}.json`
- GameTest holder/templates 与 `src/test/resources/story_fixture/` 扩展

修改：

- `src/main/resources/assets/fireflymc/lang/{zh_cn,en_us}.json`
- 只有 staged release 3.2.0 时才修改 `gradle.properties`，并同步 app version

### Task 13 验证

- `./gradlew test --tests '*Story*'`
- `./gradlew runGameTestServer`
- `./gradlew clean build`
- dedicated `./gradlew runServer` smoke（如 GameTest 无法覆盖）
- `.sakura/rules/`
- `git diff --check`
- `git status --short`
- `gradle.properties` vs app version

重点：split ItemEntity pickup 实际入库数量、death copy、respawn/login sync、world flag persistence、bad reload old snapshot、clean reload/index rebuild、dimension/biome/structure probes、dedicated class loading、`first_light=COMPLETED` / `find_memory=ACTIVE`、coordinate-independent `old_trail`、completable `embers_end`。

## 关键架构索引

- Definitions/codecs: `story/definition`, `story/condition`, `story/action`
- Pure loader/revision: `story/loader`
- Atomic snapshot/index: `StoryRuntimeSnapshot`, `InterestIndex`
- Progress: `StoryProgress`, `WorldStoryState`, `StoryAttachments`
- Evaluation: `QuestEvaluator`, `ContinuousWatchSet`
- Actions: `ActionExecutor`, `ActionTypes`
- Transaction/dirty: `TransitionQueue`, `StoryEngine`, `SyncSink`
- Lifecycle/trigger: `StoryEngine.register`, `StoryTriggers`, `StorySessionGate`
- Event adapters: `StoryGameEvents`
- Sync/client: `StoryProgressSnapshotPayload`, `StorySyncService`, `ClientStoryState`, `ModNetwork`
- Commands: `StoryCommands`, `StoryResetTokens`
- GameTests: `StoryTriggerGameTests`, `StoryGameEventGameTests`

## 集成 carryovers

- GameTests 已编译，尚未执行 `runGameTestServer`。
- Payload client/server live round-trip 未验证。
- Dedicated command smoke 未验证。
- 需 live 验证：
  - reload listener ordering；
  - attachment persistence/copy-on-death；
  - login/respawn packet delivery；
  - repeated integrated-server lifecycle；
  - real handshake/rules/password session gate timing；
  - pickup/dimension/kill/advancement bus dispatch；
  - GameTest template materialization；
  - dedicated server client-class isolation。
- Optional hardening:
  - concurrent `StoryGameEvents.registerProductionOnce`;
  - partial-registration null-target no-op；
  - Task 11 reflection catch `LinkageError`。

## 建议下一步顺序

1. 完成 Task 12 fix round 3。
2. 更新 report/manifest，重跑 focused/full tests。
3. 派发新的 scoped `glm-5.3-flash` reviewer。
4. Task 12 通过后，从 amended plan 提取 Task 13 brief。
5. Task 13 RED GameTests/fixtures first。
6. 添加正式六节点 prologue 与双语言翻译。
7. 执行 Task 13 JUnit、GameTest、clean build、dedicated smoke。
8. 修复集成评审问题。
9. 派发 fresh whole-branch final reviewer；原流程要求强模型终审，如仍限制只用 `glm-5.3-flash`，先请用户确认是否放宽。
10. 停在 working tree；commit/push 必须等用户明确指令。

