# Story Engine v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a server-authoritative, datapack-driven story engine for FireflyMC with per-player quests, world flags, a runnable prologue, and testable NeoForge integration.

**Architecture:** Immutable definitions and an interest index form one atomically replaced runtime snapshot; the server converts game events to signals, evaluates only interested active quests, and drains a re-entrant-safe transition queue. Persist player state in a serializable death-copying attachment and shared state in overworld SavedData; coalesce client progress snapshots at tick end.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.241, Gradle Wrapper 8.10, Mojang Codec/Component/NBT, JUnit 5, NeoForge GameTest.

**Spec:** [Story Engine v1 design](../specs/2026-09-27-story-engine-v1-design.md). Read it in full; the names and semantics below follow its final-review revisions.

## Global Constraints

- Mod ID and package are `fireflymc` and `firefly520.fireflymc`; gameplay must run in integrated/LAN and dedicated servers identically.
- Never mutate Minecraft player/world state or send packets off the server game thread; async callers marshal with `server.execute` and recheck the player instance.
- Definitions are immutable; progress persists IDs and values only. Player attachment must have a serializer **and** `copyOnDeath`; SavedData mutations call `setDirty()`.
- Reload is all-or-nothing for story definitions; failed reload keeps the previous snapshot; reconcile happens on the next server tick, not in the reload stack.
- Existing `docs/report.md` is unrelated and must not be modified. Do not add GeckoLib, Patchouli, FTB Quests, new items, a UI, or cutscenes in this plan.
- `gradle.properties` is the build version authority; adjust `FireflyMCMod.VERSION` consistently if the plan's release-version step is actually taken. Network registrar version becomes `1.5.0` when the payload lands.
- Check [known issues](../../known-issues.md) and `.sakura/rules/` before changing adjacent networking/lifecycle code; do not normalize pre-existing empty reflection catches as part of unrelated work.
- **No autonomous commits or pushes.** Project `CLAUDE.md` explicitly forbids them; each task ends with tests and a working-tree review, not the generic skill's commit step.
- If NeoForge API details differ from the cached `neoforge-21.1.241-sources.jar`, stop that task and ask the user to help check current official docs, per repository instructions.

## Review Focus

These concrete cases are easy to miss even with the spec in hand; the owning tasks below add an explicit test for each.

1. A partially picked-up ItemEntity counts only items entering the inventory; no event/counter progress for a failed pickup (Task 10).
2. A task evaluated with one flag false can be evaluated again after a different action changes that flag **within the same transaction** (Task 7).
3. `requirements: []` immediately completes on activation; duplicate requirement IDs are rejected before publishing a candidate (Task 1).
4. Reload with a nonexistent advancement or changed tag membership retains the old snapshot on failure or publishes the new index consistently on success (Task 3).
5. Switching servers or exiting a world clears the client snapshot, rather than showing another server's quest state (Task 11).

---

## File/ownership map (decide boundaries before adding tasks)

- `story/definition/{ChapterDefinition,QuestDefinition,RequirementDefinition,ActionDefinition,StoryCodecs}.java`: immutable JSON types and parsing; condition/action definition records live in `story/condition/` and `story/action/` respectively.
- `story/loader/{StoryDefinitionLoader,StoryValidator,StoryGraph,StoryRevision,StoryReloadListener,PreparedStoryData}.java`: datapack enumeration, pure validation, graph, canonical hash, and prepare/apply orchestration.
- `story/engine/{StoryEvent,StorySignal,InterestIndex,StoryRuntimeSnapshot,QuestEvaluator,TransitionQueue,ContinuousWatchSet}.java`: pure matching/state machine; no NeoForge event handlers here. Small dispatch classes may be folded only if responsibility remains obvious.
- `story/progress/{StoryProgress,RequirementProgressKey,QuestState,StoryAttachments,WorldStoryState}.java`: private mutation APIs, NBT, player attachment and overworld SavedData.
- `story/condition/{StoryCondition,ConditionPersistence,ConditionTypes}.java` and `story/action/{StoryAction,ActionTypes,ActionExecutor}.java`: typed registry/codec wiring and builtin condition/action behavior.
- `story/{StoryEngine,StoryTriggers}.java`, `story/adapter/StoryGameEvents.java`, `story/sync/StorySyncService.java`, `story/command/StoryCommands.java`: facade, addon API, game event adapters, sync, admin tools.
- `network/StoryProgressSnapshotPayload.java`, `client/story/ClientStoryState.java`, `client/ClientPayloadHandler.java`, `network/ModNetwork.java`: bounded wire snapshot and client mirror; avoid duplicating story state in `ClientState`.
- `src/test/java/firefly520/fireflymc/story/**` and `src/test/resources/story_fixture/**`: unit tests and validation fixtures; GameTests in `src/main/java/firefly520/fireflymc/story/gametest/` so runGameTestServer discovers them.
- `src/main/resources/data/fireflymc/story/{chapters,quests}/**`, `src/main/resources/assets/fireflymc/lang/{zh_cn,en_us}.json`: one playable prologue and translations. `FireflyMCMod.java`, `ServerConfig.java`, `gradle.properties`: minimal integration only.

The task sequence is intentional: model → loader/validation → reload/index → progress → evaluator → actions → transaction → lifecycle → networking → adapters → commands → content → GameTest. Complete each red/green cycle before moving on; a failed `compileJava` due to still-missing *future* task wiring is not a substitute for a runnable current task's focused tests.

### Task 1: Immutable definitions, typed condition/action schemas

**Files:** Create `src/main/java/firefly520/fireflymc/story/definition/{ChapterDefinition,QuestDefinition,RequirementDefinition,StoryCodecs}.java`, `story/condition/{ConditionPersistence,StoryCondition,ConditionTypes}.java`, `story/action/{StoryAction,ActionTypes}.java`; test `src/test/java/firefly520/fireflymc/story/definition/{StoryCodecsTest,StorySchemaTest}.java`.

**Interfaces:** Produces immutable `ChapterDefinition(id,title,subtitle,index)`, `QuestDefinition(id,chapter,title,subtitle,description,icon,initial,requirements,onComplete)`, `RequirementDefinition(id,stateVersion,StoryCondition)`, and `StoryCodecs.parseChapter(ResourceLocation id, JsonElement json)` / `parseQuest(ResourceLocation id, JsonElement json)` returning a definition or throwing `StoryDefinitionException` with a file/field path. `StoryCondition.persistence()` returns STICKY, COUNTER or DYNAMIC; `StoryAction` is an extensible interface (not sealed, because addon registration is a requirement) for nine builtin implementations. `ConditionTypes` and `ActionTypes` dispatch by `ResourceLocation type`, offer an extension-registration method usable before first reload and reject duplicate registrations. Component text uses `ComponentSerialization.CODEC`; do not coerce to String. The condition/action type maps are:

```
COUNTER: pickup(item,count), kill(entity XOR entity_tag,count), custom_event(event,count)
STICKY: enter_dimension(dimension), enter_biome(biome XOR biome_tag),
        enter_structure(structure XOR structure_tag), advancement(id),
        location(dimension,x,y,z,radius)
DYNAMIC: inventory(item,count), flag(name,value,scope)
ACTIONS: set_flag, set_var, unlock_quest, give_item, play_sound,
         show_title, send_message, execute_command, teleport
```

**Concrete RED/GREEN example:**

```java
// StoryCodecsTest
var quest = StoryCodecs.parseQuest(ResourceLocation.parse("fireflymc:prologue/intro"), JsonParser.parseString("""
    {"chapter":"fireflymc:prologue","title":{"translate":"story.fireflymc.intro"},
     "requirements":[],"on_complete":[],"initial":true}
    """));
assertTrue(quest.initial());
assertTrue(quest.requirements().isEmpty());
assertEquals("story.fireflymc.intro",
        ((TranslatableContents) quest.title().getContents()).getKey());
```

Use `TranslatableContents#getKey()` after checking the installed mapping. Implement `parseQuest` by validating required chapter/title and mapping each requirement through `ConditionTypes.decode(type, json)`; collect IDs in a `HashSet` and fail with the quest's resource path when `!seen.add(id)`.

- [ ] **Step 1: Write failing model/schema tests.** Parse an initial quest with `requirements:[]`, `on_complete:[]` and assert `initial()` and both empty lists; assert duplicate requirement IDs fail. Parse a translated Component title and assert the translation key survives a codec round-trip. A table-driven test covers all 10 condition IDs/9 action IDs, unknown `type`, selector both/neither, `count=0`, `give_item.count=-1`, `radius=-1`, nonfinite floating values, `state_version=0`, invalid custom-event ID, invalid Flag `Bad:Flag`, flag/var name collision, and `min > max`. Check the error includes filename/field.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryCodecsTest' --tests '*StorySchemaTest'` and confirm RED for missing types, not unrelated Gradle failures.
- [ ] **Step 3: Implement immutable records and codecs.** Derive definition ID from `data/<ns>/story/quests/<path>.json` (ignore any claimed JSON ID), defensively `List.copyOf`/`Map.copyOf`, dispatch `type` with `ResourceLocation.tryParse` null checks. Default `initial=false`, `state_version=1`, lists empty; `requirements:[]` is valid. Validate per-quest unique IDs, strictly positive count, nonnegative radius, finite numbers, exactly-oneOf, flag/var naming `[a-z0-9_./-]+` **that also parses as a valid `ResourceLocation` path (reject `..` segments — flag/var names double as FLAG_CHANGED/VAR_CHANGED index keys, which are `ResourceLocation`)** and scope collision; `set_var` uses exact integer arithmetic to reject overflow rather than wrap. Choose Mojang Codec/MapCodec or Gson dispatch with **the same strict validation**; all public model APIs remain typed (not raw JSON).
- [ ] **Step 4: Run** focused model/schema tests and `./gradlew compileJava`; inspect that a bad file never silently defaults. No commit.

### Task 2: Pure datapack loader, graph, and stable revision

**Files:** Create `story/loader/{StoryDefinitionLoader,StoryValidator,StoryGraph,StoryRevision,PreparedStoryData}.java`; tests `src/test/java/firefly520/fireflymc/story/loader/{StoryDefinitionLoaderTest,StoryRevisionTest}.java`; fixtures `src/test/resources/story_fixture/{valid,duplicate,cycle,broken,empty}/**`.

**Interfaces:** `StoryDefinitionLoader.prepare(Map<ResourceLocation,JsonElement> resources): PreparedStoryData` parses resource paths `story/chapters/*.json` and `story/quests/**/*.json` from **any namespace**, resolves IDs and records; `StoryValidator.validate(PreparedStoryData)` enforces chapter references and `unlock_quest` targets, static unlock-cycle detection, initial prerequisites, Flag/Var naming and same-scope kind collision. `StoryRevision.sha256(prepared)` returns lowercase hex SHA-256 of canonical definitions. `PreparedStoryData` includes immutable definitions, graph, and unresolved index declarations; it is not a published runtime snapshot.

**Concrete RED/GREEN example:**

```java
var a = new JsonObject();
a.addProperty("chapter", "fireflymc:prologue");
a.add("requirements", new JsonArray());
a.add("on_complete", new JsonArray());
var b = new JsonObject();
b.add("on_complete", new JsonArray());
b.add("requirements", new JsonArray());
b.addProperty("chapter", "fireflymc:prologue");
a.add("title", JsonParser.parseString("{\"text\":\"Introduction\"}"));
b.add("title", JsonParser.parseString("{\"text\":\"Introduction\"}"));
ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
JsonElement chapter = JsonParser.parseString("{\"title\":{\"text\":\"Prologue\"},\"index\":0}");
assertEquals(StoryRevision.sha256(StoryDefinitionLoader.prepare(
                Map.of(chapterPath, chapter, questPath, a))),
             StoryRevision.sha256(StoryDefinitionLoader.prepare(
                Map.of(chapterPath, chapter, questPath, b))));
```

The exact same valid title and chapter definition are present on both sides; the essential assertion is object-key order independence. Use explicit `kind`, `id`, and `canonical-json` length prefixes in the hash input; arrays retain list order.

- [ ] **Step 1: Write failing fixtures/tests.** Assert valid data loads, missing chapter/quest fails, `A→B→A` cycle fails, two requirement IDs in one quest fail, empty requirements pass, and reordering JSON object keys/whitespace does **not** change revision while changing array order **does**. Make `story_fixture` separate from `src/main/resources`.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryDefinitionLoaderTest' --tests '*StoryRevisionTest'`; confirm RED.
- [ ] **Step 3: Implement loader/validator/hash.** Canonicalize sorted ResourceLocation IDs, deterministic recursively sorted object keys, untouched array order; concatenate kind+ID+canonical bytes with unambiguous length delimiters. Make duplicate JSON-derived IDs an error. Parse all definitions before graph validation, return errors naming the resource path/field; reject the **entire** prepared candidate on any failure.
- [ ] **Step 4: Rerun** the two focused classes and `./gradlew compileJava`; inspect deterministic hash across repeated runs. No commit.

### Task 3: Reload apply, resource validation, tag expansion, atomic snapshot

**Files:** Create `story/loader/StoryReloadListener.java`, `story/engine/{StoryRuntimeSnapshot,InterestIndex,StoryEventType}.java`, `story/StoryEngine.java`; extend `story/loader/StoryValidator.java`; test `src/test/java/firefly520/fireflymc/story/loader/StoryReloadTest.java`.

**Interfaces:** `StoryReloadListener` registers via `AddReloadListenerEvent` on the NeoForge GAME bus; its `prepare` handles only parsing/graph/unresolved index declarations. Its main-thread `apply` consults `event.getRegistryAccess()`/`event.getServerResources()`, validates concrete item/entity/dimension/biome/structure/sound IDs with the appropriate registry/lookup and advancements with `getServerResources().getAdvancements().get(id)`, expands tag members, then builds one immutable `StoryRuntimeSnapshot(chapters,quests,interestIndex,graph,revision)` and calls `StoryEngine.install(candidate)` exactly once on success. This task creates a minimal `StoryEngine` facade holding the `volatile` snapshot and pending-reconcile marker; Task 7 adds dispatch/drain, Task 8 lifecycle registration. `InterestIndex.candidates(signalType: StoryEventType, key: ResourceLocation): Set<ResourceLocation>` returns only matching quest IDs (Task 5 wraps event type/key into StoryEvent); resource validation and index building share the same reload generation. **Check vanilla reload listener ordering** so advancement/tag lookup sees staged resources, not previous-generation entries; never publish mixed generations.

**Concrete RED/GREEN example:**

```java
var before = engine.snapshot();
var bad = preparedWithAdvancement("fireflymc:absent");
listener.apply(bad, resolverWithAdvancement(id -> false));
assertSame(before, engine.snapshot());
listener.apply(preparedWithEntityTag("story:undead"),
        resolverWithTag("story:undead", Set.of(id("minecraft:skeleton"))));
assertEquals(Set.of(questId), engine.snapshot().interestIndex()
        .candidates(StoryEventType.ENTITY_KILL, id("minecraft:skeleton")));
```

The fake resolver is a test-side port; production resolution uses the reload event's registry and resource access, validates *before* the final single `volatile` assignment and never merges new index data into an old snapshot.

- [ ] **Step 1: Write failing reload tests.** Construct a fake reference resolver whose advancement lookup fails for `fireflymc:absent`; assert old snapshot object identity remains unchanged. For an entity tag with members `{zombie}`, reload to `{skeleton}` and assert the published index maps only skeleton and revision matches the new definitions. Test empty tag→WARN and no matching candidates, without failing reload.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryReloadTest'`; confirm RED.
- [ ] **Step 3: Implement two-phase listener** with an injected reference-resolver interface for JUnit tests and actual Registry/Tag/advancement lookups in the NeoForge adapter. On failure, emit a resource-path error and preserve the old `volatile` snapshot; on success, swap one snapshot and mark online players for **next tick** reconcile. Never mutate the published maps/index.
- [ ] **Step 4: Run** `./gradlew test --tests '*StoryReloadTest'` and `./gradlew compileJava`; confirm no preparation thread dereferences live registry objects. No commit.

### Task 4: Player attachment and overworld story state

**Files:** Create `story/progress/{RequirementProgressKey,QuestState,StoryProgress,StoryAttachments,WorldStoryState}.java`; tests `src/test/java/firefly520/fireflymc/story/progress/{StoryProgressTest,WorldStoryStateTest}.java`; attach the deferred type to the mod bus in Task 8, when lifecycle wiring is added.

**Interfaces:** `StoryProgress.getQuestState(ResourceLocation): QuestState` (define `QuestState` in `story/progress/QuestState.java`), `activate(id)`, `complete(id)`, `counter(key)`, `incrementCounter(key,amount)`, `markSatisfied(key)`, `getFlag(name)` (missing=false), `getVar(name)` (missing=0), `setFlag`, `setVar`, `addVar` (post-operation clamp), `clearAndBootstrap(...)`, `serializeNBT(provider)`, `deserializeNBT(provider,tag)`; copy-safe immutable views for snapshots. `StoryAttachments.PROGRESS` uses `AttachmentType.serializable(StoryProgress::new).copyOnDeath()`. `WorldStoryState.get(server)` resolves overworld DataStorage; `setFlag/setVar/addVar` automatically call `setDirty()` only when values change. No public mutable maps.

**Concrete RED/GREEN example:**

```java
StoryProgress p = new StoryProgress();
assertFalse(p.getFlag("firefly.met"));
assertEquals(0, p.getVar("firefly.affinity"));
p.activate(questId);
p.complete(questId);
assertEquals(QuestState.COMPLETED, p.getQuestState(questId));
assertThrows(IllegalStateException.class, () -> p.activate(questId));
```

Create the attachment supplier as `AttachmentType.serializable(StoryProgress::new).copyOnDeath().build()` via the installed NeoForge builder signature. `WorldStoryState.setFlag` calls `setDirty()` only on an actual change; its fields remain private.

- [ ] **Step 1: Write failing state tests.** Check default false/0, add from zero then clamp, LOCKED→ACTIVE→COMPLETED, illegal COMPLETE→ACTIVE rejected, NBT round-trip of counters/satisfied with `(quest, requirementId, stateVersion)`, changing version invalidates old values on reconcile, and mutating world Flag/Var marks SavedData dirty. Fixture data includes two independent players to establish isolation.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryProgressTest' --tests '*WorldStoryStateTest'`; confirm RED.
- [ ] **Step 3: Implement encapsulated stores.** Enforce player/main-thread mutation at entry points; NBT structured as quest→requirement→{stateVersion,value}, not a split `#` key. Register serializable attachment and `copyOnDeath` **after** serializer configuration; if the player's attachment manager requires explicit mutation notification, use the API documented in the installed NeoForge sources. Save world state with overworld `DimensionDataStorage.computeIfAbsent(SavedData.Factory, "fireflymc_story_world")` after checking the mapped signature.
- [ ] **Step 4: Run** focused tests and `./gradlew compileJava`; ensure death-copy behavior is separately covered by Task 13 GameTest. No commit.

### Task 5: Condition evaluation and current-state probing

**Files:** Create `story/engine/{StoryEvent,StorySignal,QuestEvaluator,ContinuousWatchSet}.java`; extend `story/condition/StoryCondition.java`; test `src/test/java/firefly520/fireflymc/story/engine/QuestEvaluatorTest.java`.

**Interfaces:** `StoryEvent(type,key,amount,context)` is an immutable, validated event; `StoryEventType` is defined in Task 3. `QuestEvaluator.onEvent(playerProgress,worldState,snapshot,event,currentState): Set<ResourceLocation>` increments only matching active COUNTERs. `QuestEvaluator.evaluate(quest,progress,world,currentState): boolean` checks persisted STICKY, persisted COUNTER and fresh DYNAMIC; `currentState` supplies inventory, position, dimension, biome, structure, and advancement probes through a narrow interface. `ContinuousWatchSet.activate/deactivate/rebuild/tickDue(playerId,serverTick)` tracks only active quests with continuous conditions; phase spreads checks over 20 ticks. Flag and Var invalidations from actions use the same interest candidate lookup.

**Concrete RED/GREEN example:**

```java
RequirementProgressKey key = new RequirementProgressKey(questId, "memory", 1);
var before = evaluator.evaluate(quest, progress, world, probe.withInventory(itemId, 2));
var after = evaluator.evaluate(quest, progress, world, probe.withInventory(itemId, 0));
assertFalse(before);  // count threshold is 3
assertFalse(after);   // inventory is DYNAMIC, not stored as satisfied
assertEquals(0, progress.counter(key));  // pickup counter cannot backfill
```

Make `CurrentState` an injected interface with `inventoryCount(item)`, `inDimension(id)`, `inBiome(id)`, `inStructure(id)`, `atLocation(...)`, `advancementDone(id)`; `evaluate` re-reads dynamic conditions on every call, even when a signal previously made them true.

- [ ] **Step 1: Write failing tests.** Pickup/kill/custom_event do not backfill on activation; dimension and earned advancement are checked immediately; `inventory` with count dropped below threshold fails the final DYNAMIC check; a watched player due at a different phase does not evaluate on the same server tick; a player with no watch entries is skipped; setFlag(false) on missing Flag does not falsely cause a transition.
- [ ] **Step 2: Run** `./gradlew test --tests '*QuestEvaluatorTest'`; confirm RED.
- [ ] **Step 3: Implement minimal evaluator** with pure JUnit-friendly probe interface and interest-index candidates, no world access on off-thread code. The item `pickup` counter is advanced only by a positive amount from a Post event; `inventory` never reads stored satisfaction. Build/rebuild watches when state changes or snapshot reloads, and recheck all DYNAMIC requirements before completion.
- [ ] **Step 4: Rerun** the focused test and `./gradlew compileJava`; compare evaluator behavior to spec §4. No commit.

### Task 6: Action execution and bounded error isolation

**Files:** Create `story/action/ActionExecutor.java`; test `src/test/java/firefly520/fireflymc/story/action/ActionExecutorTest.java`.

**Interfaces:** `ActionExecutor.execute(action,player,storyContext): List<StorySignal>` executes nine builtin Action types on the server main thread; `storyContext` abstracts Progress/World/Sound/Commands/Teleport/Inventory for testability. `executeAll(List<StoryAction>,...)` runs each action independently with `try/catch`, logs an error including quest/action ID without leaking player IP, and returns accumulated invalidation signals even after an action fails.

**Concrete RED/GREEN example:**

```java
var signals = executor.executeAll(List.of(setFlag("firefly.met", true),
        failingAction(), sendMessage(Component.literal("continue"))), player, fakeEffects);
assertTrue(signals.contains(new StorySignal.FlagChanged("firefly.met")));
assertEquals(List.of("flag", "message"), fakeEffects.executed());
```

Execute in declaration order using an independent `try/catch` for each Action; include the quest ID and action type in the error log, and keep the emitted signals from earlier successful actions. For `give_item`, use the remaining stack returned by the inventory attempt when dropping at the player's feet.

- [ ] **Step 1: Write failing action tests.** `set_flag` produces a FlagChanged signal, `set_var add` starts at zero then clamps, `give_item` when inventory rejects part of stack drops only the remainder and signals inventory dirty but **never** Pickup, `teleport` signals location/dimension/biome/structure, an exception from action 2 does not prevent action 3. For execute_command ensure the source resolves `@s` to the quest's ServerPlayer with fixed permission 2 and never interpolates `${context}`.
- [ ] **Step 2: Run** `./gradlew test --tests '*ActionExecutorTest'`; confirm RED.
- [ ] **Step 3: Implement executor** using an injected effect port for pure tests and NeoForge/Minecraft production effects after checking mapped method signatures. `show_title`/`send_message` use Component; `play_sound` validates reference at reload; `execute_command` receives only trusted static datapack content, no dynamic substitution. If tests cannot instantiate `ServerPlayer`, test source-creation via a narrow production helper under GameTest while retaining a pure command-source contract test here.
- [ ] **Step 4: Rerun** focused tests and compile; confirm no item disappears when inventory is full. No commit.

### Task 7: Transaction queue, same-transaction invalidations, and sync dirtiness

**Files:** Create `story/engine/TransitionQueue.java`, `story/StoryEngine.java`; test `src/test/java/firefly520/fireflymc/story/engine/TransitionQueueTest.java`.

**Interfaces:** `StoryEngine.install(candidate)`, `StoryEngine.snapshot()`, `StoryEngine.dispatch(player,event)`, `StoryEngine.reconcile(player)` and `StoryEngine.onServerTick(server)` are the only gameplay entry points. Create `story/engine/SyncSink.java` with `void markDirty(ServerPlayer player)` as a narrow injected interface; Task 9 supplies `StorySyncService` without depending on a not-yet-registered payload. `TransitionQueue.enqueue(id)` deduplicates while **currently queued**; `drain()` removes ID from `queued` **before** evaluation. Completed quests are never completed twice; active unsuccessful quests may be evaluated again after later signals in that transaction. `maxWorkItemsPerTransaction=1024` counts each dequeue, successful or not. Mutation marks a dirty player/world and requests one end-of-tick snapshot flush.

**Concrete RED/GREEN example:**

```java
queue.enqueue(questB);
assertEquals(questB, queue.removeNext()); // removeNext also removes questB from queued
queue.enqueue(questB);                     // later invalidation must permit retry
assertEquals(questB, queue.removeNext());
assertEquals(2, queue.workItemsProcessed());
```

Implement `enqueue` as `if (queued.add(id)) pending.addLast(id)`; `removeNext` pops, removes from `queued`, then increments/checks `workItemsProcessed` (limit 1024). The integration test in Step 1 proves that retry actually completes B after the second flag change, rather than just exercising the queue in isolation.

- [ ] **Step 1: Write failing queue tests.** Enqueue B twice before draining→one queued (all queue access stays on the server thread); dequeue B when x=true,y=false→remain ACTIVE; action from A changes y=true and invalidates B→B re-enqueued/completed **within the same drain**, with exactly one completion side effect. Include empty-requirement quest activation→immediate completion, `A→B→C` chain >8 depth→fully completes, repeated dynamic invalidations→stops at 1024 work items with an ERROR, and one chain with six mutations→one sync dirty mark.
- [ ] **Step 2: Run** `./gradlew test --tests '*TransitionQueueTest'`; confirm RED.
- [ ] **Step 3: Implement queue transaction** with server-thread confinement, non-recursive dispatch (nested signals append to current queue), and `try/finally` on dirty flush scheduling. `complete` state transition precedes best-effort Actions, never rollback. Reconcile advances initial quests and current-state probes on the **tick after** reload. `storyEnabled=false` keeps definitions loading but blocks progression; on re-enable mark online players for reconcile.
- [ ] **Step 4: Rerun** focused tests, `./gradlew compileJava`, and regression `./gradlew test`; inspect that no static singleton retains a stopped server. No commit.

### Task 8: Register facade, attachment, reload listener, and configuration

**Files:** Modify `src/main/java/firefly520/fireflymc/{FireflyMCMod,ServerConfig}.java`; create `story/StoryTriggers.java`; extend `story/StoryEngine.java`; test `src/test/java/firefly520/fireflymc/story/StoryTriggersTest.java`.

**Interfaces:** `StoryEngine.register(modEventBus)` registers the Task 4 deferred attachment type on the mod bus and the Task 3 reload listener once on the GAME bus; adapter and command handlers from Tasks 10 and 12 self-register once on the GAME bus; `StoryEngine.onServerStopping(server)` clears per-server indexes/reconcile/dirty token state while player attachment persists normally. `StoryTriggers.fireCustomEvent(server,player,eventId,amount)` accepts the originating `ServerPlayer` handle and `ResourceLocation`, validates positive amount, and marshals via `server.execute` to re-check current online player **identity** (`getPlayer(player.getUUID()) == player`) before dispatch. Accepting only a UUID from an off-thread caller cannot distinguish a reconnected session, so this API intentionally requires the player instance.

**Concrete RED/GREEN example:**

```java
ServerPlayer original = online.get(id);
server.execute(() -> {
    ServerPlayer current = server.getPlayerList().getPlayer(id);
    if (current == original) engine.dispatch(current, customEvent);
});
```

The API takes the originating `ServerPlayer` instance, which is captured before scheduling; UUID-only invocation is deliberately unsupported because it cannot distinguish a superseded session.

- [ ] **Step 1: Write failing triggers tests.** Off-thread call schedules rather than directly mutates; a player disconnecting and reconnecting between scheduling and execution must not receive the stale event; invalid event ID/negative amount fails before scheduling. Server stop clears pending runtime state but leaves persisted player progress untouched.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryTriggersTest'`; confirm RED.
- [ ] **Step 3: Wire facade.** Add `storyEnabled` BooleanValue default true under SERVER config; pass `IEventBus` through the mod entry, add one `NeoForge.EVENT_BUS` reload listener and shutdown/tick lifecycle hooks, no duplicate registration. Do not refactor unrelated subsystems. Integrate enabled→disabled→enabled config reload via `onConfigReload` and schedule reconcile when reenabled.
- [ ] **Step 4: Rerun** focused test and `./gradlew compileJava`; if a real server cannot be instantiated in JUnit, move actual thread/identity race to GameTest and keep scheduler as a deterministic pure interface test. No commit.

### Task 9: Snapshot codec and sync coalescing

**Files:** Create `network/StoryProgressSnapshotPayload.java`, `story/sync/StorySyncService.java`; test `src/test/java/firefly520/fireflymc/network/StoryProgressSnapshotPayloadTest.java`.

**Interfaces:** Payload contains immutable completed/active quest IDs, player/world flags+vars, structured `RequirementProgressKey` counters/satisfied, tracked quest and `storyRevision`. `StorySyncService.markPlayerDirty(player)`, `markWorldDirty(server)`, `flush(server)` and `sendNow(player)` send only from server thread; world mutation marks all connected players; dirty player send is at most once per tick; `sendNow` serves login/respawn.

**Concrete RED/GREEN example:**

```java
ByteBuf buf = Unpooled.buffer();
try {
    StoryProgressSnapshotPayload.STREAM_CODEC.encode(buf, snapshot);
    assertEquals(snapshot, StoryProgressSnapshotPayload.STREAM_CODEC.decode(buf));
} finally {
    buf.release();
}
```

Encode each collection count as bounded VAR_INT and each ResourceLocation as a bounded UTF-8 string; decode rejects any count beyond the declared maximum *before* allocating collections. Copy mutable progress maps/sets into the outgoing immutable value once per flush.

- [ ] **Step 1: Write failing codec tests.** Round-trip empty and mixed player/world snapshot through `STREAM_CODEC` with `Unpooled.buffer()`; reject duplicate quest IDs, absurd collection lengths, invalid ResourceLocation, corrupted nested counter key, and oversized strings by a documented packet bound. Assert three mutations in one transaction yield **one** send, and a world flag change sends one snapshot to each online player.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryProgressSnapshotPayloadTest'`; confirm RED.
- [ ] **Step 3: Implement bounded codec** with explicit maximum counts/byte lengths and non-ordinal wire format for any enums; do not use Gson `STRING_UTF8` without a maximum as TitleSync does. Encode/decode matching order, immutable defensive copies on decode. `StorySyncService` flushes at server tick Post and uses `PacketDistributor.sendToPlayer` only on game thread.
- [ ] **Step 4: Rerun** codec class and `./gradlew compileJava`; inspect frame size for a realistic 1,000-task progress fixture and set justified limits in code/tests. No commit.

### Task 10: Translate NeoForge events to story signals

**Files:** Create `story/adapter/StoryGameEvents.java`; extend `story/StoryEngine.java`; test `src/test/java/firefly520/fireflymc/story/adapter/StoryGameEventsTest.java`.

**Interfaces:** Adapter methods subscribe to `ItemEntityPickupEvent.Post`, `LivingDeathEvent` (killer must be ServerPlayer), `PlayerChangedDimensionEvent`, `AdvancementEvent.AdvancementEarnEvent`, `PlayerEvent.PlayerLoggedInEvent`, `PlayerEvent.PlayerLoggedOutEvent`, `PlayerEvent.PlayerRespawnEvent`, `ServerTickEvent.Post`; optional inventory-relevant events invalidate watches, and per-player phase tick polling covers missed container/crafting/other-Mod inventory changes. Adapter does **not** inspect quest state or modify progress; it translates IDs/amounts and delegates to StoryEngine.

**Concrete RED/GREEN example:**

```java
int pickedUp = event.getOriginalStack().getCount() - event.getCurrentStack().getCount();
if (event.getPlayer() instanceof ServerPlayer player && pickedUp > 0) {
    engine.dispatch(player, new StoryEvent(StoryEventType.ITEM_PICKUP,
            BuiltInRegistries.ITEM.getKey(event.getOriginalStack().getItem()), pickedUp, Map.of()));
}
```

Keep this conversion in `StoryGameEvents.onItemPickup(ItemEntityPickupEvent.Post)`; never use Pre or the original stack's full count. Verify actual item ID lookup and imports against the installed mapping.

- [ ] **Step 1: Write failing adapter tests.** `Post` with original count 10/current count 6 emits Pickup amount 4; unchanged full stack emits no event; another player's kill emits no progress; an earned advancement carries its ResourceLocation; login bootstraps initial task and schedules send; respawn sends a fresh snapshot. Mock or fake only the event-conversion boundary; use GameTests for actual NeoForge dispatch.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryGameEventsTest'`; confirm RED.
- [ ] **Step 3: Implement adapters** after verifying actual classes in installed `neoforge-21.1.241-sources.jar`; call server-thread engine only, listen Post (not Pre); `original.getCount() - current.getCount()` is the only counter amount. Inspect inventory events available in this version rather than inventing names; rely on watch polling for uncovered sources. Register via one mod-bus or GAME-bus arrangement that executes once, not twice.
- [ ] **Step 4: Rerun** focused tests and `./gradlew compileJava`; compare login timing with existing auth/rules flow; story should not grant items/actions to an unverified dedicated-server player before the project's authentication gates open. If login is premature, defer bootstrap/side effects until existing verification finishes, with a focused integration test. No commit.

### Task 11: Register payload and safe client mirror

**Files:** Modify `network/ModNetwork.java` and `client/ClientPayloadHandler.java`; create `client/story/ClientStoryState.java`; test `src/test/java/firefly520/fireflymc/network/StoryClientMirrorTest.java`.

**Interfaces:** `ClientStoryState.snapshot()` returns an immutable `StoryProgressSnapshotPayload` or empty default; `install(payload)` atomically replaces one volatile reference; `clear()` resets on `ClientPlayerNetworkEvent.LoggingOut` and new-world transition. `ClientPayloadHandler.handleStoryProgressSnapshot(payload,context)` calls `context.enqueueWork` to install. `ModNetwork.registerPayloads` registers `StoryProgressSnapshotPayload.TYPE/STREAM_CODEC` with a client-safe dispatch path; bump `NETWORK_VERSION` to `1.5.0` (not the mod semantic version).

**Concrete RED/GREEN example:**

```java
ClientStoryState.install(serverASnapshot);
assertEquals(serverASnapshot.storyRevision(), ClientStoryState.snapshot().storyRevision());
ClientStoryState.clear();
assertTrue(ClientStoryState.snapshot().completedQuests().isEmpty());
```

Use `private static volatile StoryProgressSnapshotPayload snapshot = EMPTY` and replace/clear only in client-safe lifecycle handlers. `handleStoryProgressSnapshot` uses `context.enqueueWork(() -> ClientStoryState.install(payload))`; no client class may be referenced by dedicated-server class initialization.

- [ ] **Step 1: Write failing mirror tests.** Install a snapshot for server A, clear, then assert no A state remains before a packet for server B; update progress and revision in a single immutable replacement (reader never sees mixed generations); assert client handler is never loaded on dedicated server classpath initialization.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryClientMirrorTest'`; confirm RED.
- [ ] **Step 3: Wire registration and handler.** Preserve existing client reflection pattern (or NeoForge client payload handler API if its server-safe registration is verified); don't add a new empty catch. Prefer logged error and safe client-disconnect on dispatch failure, without broadly changing other payloads. Client logout resets mirror even when world changes unexpectedly; save state only from server packets.
- [ ] **Step 4: Rerun** focused test, `./gradlew test --tests '*StoryProgressSnapshotPayloadTest'`, and `./gradlew compileJava`; check payload protocol version. No commit.

### Task 12: Operator commands and scoped reset confirmation

**Files:** Create `story/command/{StoryCommands,StoryResetTokens}.java`; test `src/test/java/firefly520/fireflymc/story/command/StoryResetTokensTest.java`.

**Interfaces:** Brigadier `/fireflymc story status [player]`, `setflag player|world`, `setvar player|world set|add`, `unlock`, `complete`, `counter set|add`, `trigger`, `reset player|world` and `reset confirm <token>`; commands require level 2 except destructive reset (level 4). `StoryResetTokens.issue(executorId,targetId,scope,now): String` and `consume(token,executorId,targetId,scope,now): boolean` are single-use, expire after 60 seconds, and clear on server stop. `Scope` is a nested `StoryResetTokens.Scope { PLAYER, WORLD }`; for WORLD use a constant target marker (not a player UUID). All IDs use `ResourceLocation.tryParse` null checks and report unknown references. `complete` invokes engine's normal completion path; resets clear only selected scope; player reset re-bootstraps initial tasks.

**Concrete RED/GREEN example:**

```java
String token = tokens.issue(executorId, targetId, Scope.PLAYER, now);
assertFalse(tokens.consume(token, otherExecutorId, targetId, Scope.PLAYER, now));
assertFalse(tokens.consume(token, executorId, targetId, Scope.WORLD, now));
assertTrue(tokens.consume(token, executorId, targetId, Scope.PLAYER, now));
assertFalse(tokens.consume(token, executorId, targetId, Scope.PLAYER, now));
```

`/fireflymc story reset player <player>` only issues a token; `/fireflymc story reset confirm <token>` resolves the target/scope stored in the issued record and invokes the scoped reset exactly once. Use `EntityArgument.player()` and `ResourceLocation.tryParse` before dispatching to StoryEngine.

- [ ] **Step 1: Write failing token and command contract tests.** Token bound to executor/target/scope; expired/wrong-player/reused token rejected; bad ID and unknown quest return an operator error, never throw; reset player preserves world flags; reset world preserves every player's active/completed quests; forced `complete` executes ordered actions once.
- [ ] **Step 2: Run** `./gradlew test --tests '*StoryResetTokensTest'`; confirm RED (add a focused `StoryCommandsTest` when command dispatcher can be built without full server).
- [ ] **Step 3: Implement Brigadier tree** following `MusicCommandHandler` registration pattern with separate `/fireflymc` child registration on `RegisterCommandsEvent`. Require explicit `player`/`world` literals, not shell-style `--world`. Render confirmation command back to issuer, never perform reset on the first call. Use parsed UUID/online-player target rather than freeform unchecked names. Make `complete` transition only ACTIVE quests, preserve COMPLETED idempotency, and route all mutations through engine + dirty sync.
- [ ] **Step 4: Rerun** focused tests and `./gradlew compileJava`; manually inspect every command's permission and suggestion/argument shape. No commit.

### Task 13: Playable prologue, translation, and GameTest integration

**Files:** Create `src/main/resources/data/fireflymc/story/chapters/prologue.json` and `src/main/resources/data/fireflymc/story/quests/prologue/{first_light,find_memory,heard_voice,quiet_night,old_trail,embers_end}.json`; modify `src/main/resources/assets/fireflymc/lang/{zh_cn,en_us}.json` (read entire existing files before editing); create `story/gametest/StoryGameTests.java` and game-test templates under `src/main/resources/data/fireflymc/structure/` as needed; extend `src/test/resources/story_fixture/`; modify `gradle.properties` **only if release version 3.2.0 is being staged** and then keep the app display constant aligned.

**Interfaces:** Content illustrates an initial quest, a real pickup of `minecraft:echo_shard` (no new custom item), a `send_message`/`show_title` bridge, and `unlock_quest` to the next task; use Component translation keys. All example content passes the exact same parser/validator as user datapacks. GameTests verify real item pickup adapter, attachment death copy, login/respawn sync, SavedData persistence, bad JSON reload keeping the previous snapshot, clean reload/index rebuild, dimension/biome/structure probes, and a dedicated server without loading client classes. Document any case that GameTest cannot deterministically automate as a concrete manual `runServer` + `runClient` protocol with exact commands and expected output.

**Concrete RED/GREEN example:**

```json
{"chapter":"fireflymc:prologue", "title":{"translate":"story.fireflymc.prologue.first_light"},
 "initial":true, "requirements":[],
 "on_complete":[{"type":"fireflymc:send_message","text":{"translate":"story.fireflymc.prologue.open"}},
                {"type":"fireflymc:unlock_quest","quest":"fireflymc:prologue/find_memory"}]}
```

The live pickup quest must name an existing item (`minecraft:echo_shard`) and valid chapter; use real translations in both language files. In a GameTest, assert server-side progress/sync via the engine's observable state, not by trying to instantiate a client GUI on the test server.

- [ ] **Step 1: Write failing fixture/GameTests.** A first-login simulated player bootstraps `first_light` and asserts `first_light = COMPLETED`, `find_memory = ACTIVE`; picking up a split ItemEntity progresses only the amount moved; world flag persists across overworld SavedData reload; respawn retains attachment and sends snapshot; malformed datapack on `/reload` keeps old revision and behavior; custom data pack title override yields different `storyRevision`. If a test requires a live client, pin it as a manually reproducible client/server check and keep server-side automatic assertions.
- [ ] **Step 2: Run** `./gradlew test --tests '*Story*'` and `./gradlew runGameTestServer`; expect the newly added tests to fail until the content/integration is wired. Set a command timeout sufficient for NeoForge startup.
- [ ] **Step 3: Add the six-node prologue and translations, then complete integration wiring.** A 20–30 minute-skeleton chain per spec §11.3: `first_light` (initial, empty requirements → message/title bridge), `find_memory` (pickup of ordinary items like `minecraft:echo_shard`), `heard_voice` (flag/set_var bridge advancing the story), `quiet_night` (kill quest, natural story beat), `old_trail` (inventory quest requiring an obtainable ordinary item such as a torch, avoiding absolute world coordinates), `embers_end` (show_title finale that sets world flag `prologue.awaiting_firefly=true`, so the v1 chain is completable without the future NPC system). No NPC/dialogue/cutscene anywhere. Ensure v1 story remains playable without UI (OP `status` and translated messages). Bind GameTests to actual NeoForge 1.21.1 API and templates; avoid basing assertions on unspecified tick ordering.
- [ ] **Step 4: Run** `./gradlew clean build` (includes all JUnit), `./gradlew runGameTestServer`, then dedicated `./gradlew runServer` smoke test if GameTest cannot cover class loading; record test counts and any manual-only gaps in the handoff. Review `.sakura/rules/`, `git diff --check`, `git status --short` (including previously untracked `docs/report.md`) and `gradle.properties` vs app version. No commit or push.

---

## Self-review map

- Spec §§2–3: Tasks 1–4 and 7 cover immutable definitions, progress structure, state transitions, revision, world state.
- Spec §§4–5: Tasks 1, 5–7 and 10 cover every named condition/action, three persistence categories, action isolation, invalidation and queue guard.
- Spec §§6–7: Tasks 2–3, 5, 7–8, 10 cover pure prepare, main-thread apply, atomic install, adapters, phased watches, reconcile.
- Spec §§8–10: Tasks 4, 9, 11–12 cover persistence, bounded packet, client cleanup, commands, enable switch, error semantics.
- Spec §§11–13: Task 13 runs JUnit + GameTests and separates real story content from fixtures; no UI/dialogue/cutscene/other Mod integration is introduced.

The project rule against autonomous commits overrides the writing-plans template's example commit step. Execute each task through its tests and independent review; only the user can request a commit later.

> **Task 13 plan amendment (2026-09-29):** `location` remains fully covered by GameTest fixtures, not by the formal prologue. After the GeckoLib NPC / Dialogue subproject lands, add a separate `fireflymc:prologue/first_contact` node gated by `custom_event fireflymc:prologue/found_firefly`; do not make v1 `embers_end` depend on that event.
