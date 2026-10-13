package firefly520.fireflymc.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.action.StoryAction;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.QuestEvaluator;
import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.engine.StorySignal;
import firefly520.fireflymc.story.engine.SyncSink;
import firefly520.fireflymc.story.engine.TransitionQueue;
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.loader.StoryGraph;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import firefly520.fireflymc.story.sync.StorySyncService;
import firefly520.fireflymc.story.sync.StorySyncServiceTestAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TransitionQueueTest {
    private static final ResourceLocation QUEST_A =
        ResourceLocation.parse("fireflymc:a");
    private static final ResourceLocation QUEST_B =
        ResourceLocation.parse("fireflymc:b");
    private static final ResourceLocation QUEST_C =
        ResourceLocation.parse("fireflymc:c");
    private static final UUID PLAYER = UUID.fromString(
        "00000000-0000-0000-0000-000000000001");
    private static final UUID ENGINE_PLAYER = UUID.fromString(
        "00000000-0000-0000-0000-000000000002");

    @Test
    void enqueueDeduplicatesOnlyWhileWorkIsCurrentlyQueued() {
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {});

        queue.enqueue(QUEST_B);
        queue.enqueue(QUEST_B);

        assertEquals(List.of(QUEST_B), queue.queuedIds());

        assertEquals(QUEST_B, queue.removeNext());
        assertFalse(queue.isQueued(QUEST_B));
        assertNull(queue.removeNext());

        queue.enqueue(QUEST_B);
        assertTrue(queue.isQueued(QUEST_B));
        assertEquals(QUEST_B, queue.removeNext());
        assertEquals(2, queue.workItemsProcessed());
    }

    @Test
    void queueAccessIsConfinedToItsServerThread() throws Exception {
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {});
        CountDownLatch started = new CountDownLatch(1);
        List<String> failures = new ArrayList<>();
        Thread foreignThread = new Thread(() -> {
            started.countDown();
            String[] operations = {
                "enqueue",
                "removeNext",
                "drain",
                "workItemsProcessed",
                "queuedIds",
                "isQueued",
                "limitExceeded"
            };
            for (String operation : operations) {
                try {
                    invoke(operation, queue);
                    failures.add(operation + " did not reject a foreign thread");
                } catch (IllegalThreadStateException expected) {
                    // The queue deliberately exposes no cross-thread operations.
                }
            }
        });
        foreignThread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        foreignThread.join(1_000);
        assertEquals(List.of(), failures);
    }

    @Test
    void actionInvalidationRetriesActiveQuestInSameTransaction() {
        Set<ResourceLocation> active = new LinkedHashSet<>(List.of(QUEST_A, QUEST_B));
        Set<ResourceLocation> completed = new LinkedHashSet<>();
        List<ResourceLocation> completionEffects = new ArrayList<>();
        java.util.Map<String, Boolean> state = new java.util.HashMap<>(
            Map.of("x", true, "y", false));
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {
            if (!active.contains(questId) || completed.contains(questId)) {
                return;
            }
            if (QUEST_A.equals(questId)) {
                completed.add(questId);
                active.remove(questId);
                completionEffects.add(questId);
                // A's best-effort action changes y and emits FLAG_CHANGED.
                state.put("y", true);
                workQueue.enqueue(QUEST_B);
                return;
            }
            if (Boolean.TRUE.equals(state.get("y"))) {
                completed.add(questId);
                active.remove(questId);
                completionEffects.add(questId);
            }
            // y is still false: B remains ACTIVE and can be invalidated later.
        });

        queue.enqueue(QUEST_B);
        queue.enqueue(QUEST_A);
        assertTrue(queue.drain());

        assertEquals(List.of(QUEST_A, QUEST_B), completionEffects);
        assertEquals(3, queue.workItemsProcessed());
        assertTrue(completed.contains(QUEST_A));
        assertTrue(completed.contains(QUEST_B));
        assertFalse(active.contains(QUEST_B));
    }

    @Test
    void unlockedEmptyRequirementQuestCompletesImmediately() {
        Set<ResourceLocation> active = new LinkedHashSet<>(List.of(QUEST_A));
        Set<ResourceLocation> completed = new LinkedHashSet<>();
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {
            if (QUEST_A.equals(questId) && active.remove(questId)) {
                completed.add(questId);
                // unlock_quest activates B and puts it in this same transaction.
                active.add(QUEST_B);
                workQueue.enqueue(QUEST_B);
            } else if (QUEST_B.equals(questId)) {
                active.remove(questId);
                completed.add(questId);
            }
        });

        queue.enqueue(QUEST_A);
        assertTrue(queue.drain());

        assertEquals(Set.of(QUEST_A, QUEST_B), completed);
        assertEquals(2, queue.workItemsProcessed());
    }

    @Test
    void dynamicUnlockChainCompletesBeyondEightDepthWithoutRecursion() {
        List<ResourceLocation> chain = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            chain.add(ResourceLocation.parse("fireflymc:chain_" + index));
        }
        Set<ResourceLocation> active = new LinkedHashSet<>(chain);
        Set<ResourceLocation> completed = new LinkedHashSet<>();
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {
            if (!active.remove(questId)) {
                return;
            }
            completed.add(questId);
            int next = chain.indexOf(questId) + 1;
            if (next < chain.size()) {
                workQueue.enqueue(chain.get(next));
            }
        });

        queue.enqueue(chain.getFirst());
        assertTrue(queue.drain());

        assertEquals(Set.copyOf(chain), completed);
        assertEquals(11, queue.workItemsProcessed());
    }

    @Test
    void repeatedDynamicInvalidationsAbortAtWorkItemLimit() {
        Set<ResourceLocation> active = new LinkedHashSet<>(List.of(QUEST_B));
        List<ResourceLocation> evaluations = new ArrayList<>();
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {
            if (QUEST_B.equals(questId)) {
                evaluations.add(questId);
                // A dynamic flag/set-var cycle keeps invalidating the still-active quest.
                workQueue.enqueue(QUEST_B);
            }
        });

        queue.enqueue(QUEST_B);
        assertFalse(queue.drain());

        assertTrue(queue.limitExceeded());
        assertEquals(TransitionQueue.MAX_WORK_ITEMS_PER_TRANSACTION, queue.workItemsProcessed());
        assertEquals(TransitionQueue.MAX_WORK_ITEMS_PER_TRANSACTION, evaluations.size());
    }

    @Test
    void sixMutationChainMarksSyncDirtyOnceAtTickEnd() {
        List<ResourceLocation> chain = List.of(
            QUEST_A,
            QUEST_B,
            QUEST_C,
            ResourceLocation.parse("fireflymc:d"),
            ResourceLocation.parse("fireflymc:e"),
            ResourceLocation.parse("fireflymc:f"));
        Set<ResourceLocation> active = new LinkedHashSet<>(chain);
        Set<UUID> dirtyPlayers = new LinkedHashSet<>();
        List<UUID> tickFlush = new ArrayList<>();
        TransitionQueue queue = new TransitionQueue((questId, workQueue) -> {
            if (!active.remove(questId)) {
                return;
            }
            // Every completed quest mutates state and requests the same player snapshot.
            dirtyPlayers.add(PLAYER);
            int next = chain.indexOf(questId) + 1;
            if (next < chain.size()) {
                workQueue.enqueue(chain.get(next));
            }
        });

        queue.enqueue(chain.getFirst());
        assertTrue(queue.drain());

        // The end-of-tick flush is the only point that calls SyncSink.
        dirtyPlayers.forEach(playerId -> tickFlush.add(playerId));
        assertEquals(6, chain.size());
        assertEquals(List.of(PLAYER), tickFlush);
    }

    @Test
    void storyEngineReplaysScopedFlagAndCoalescesDirtyInOneTransaction() {
        ResourceLocation questA = ResourceLocation.parse("fireflymc:engine_a");
        ResourceLocation questB = ResourceLocation.parse("fireflymc:engine_b");
        StoryRuntimeSnapshot snapshot = snapshot(index(
            new PreparedStoryData.IndexDeclaration(
                questA,
                "on_complete[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "story.ready",
                StoryCondition.Scope.WORLD),
            new PreparedStoryData.IndexDeclaration(
                questB,
                "requirements[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "story.ready",
                StoryCondition.Scope.WORLD)),
            quest(
                questA,
                List.of(),
                List.of(new ActionTypes.SetFlagAction(
                    "story.ready",
                    true,
                    StoryCondition.Scope.WORLD))),
            quest(
                questB,
                List.of(new RequirementDefinition(
                    "ready",
                    1,
                    new ConditionTypes.FlagCondition(
                    "story.ready",
                        true,
                        StoryCondition.Scope.WORLD))),
                List.of()));

        StoryProgress progress = new StoryProgress();
        WorldStoryState world = new WorldStoryState();
        progress.activate(questA);
        progress.activate(questB);
        List<String> playerDirty = new ArrayList<>();
        List<String> worldDirty = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            "player",
            progress,
            world,
            snapshot,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects(progress, world),
            playerDirty::add,
            () -> worldDirty.add("world"),
            () -> true);
        StoryEngine engine = new StoryEngine();

        engine.runTransaction(context, Set.of(questA));

        assertEquals(QuestState.COMPLETED, progress.getQuestState(questA));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(questB));
        assertTrue(world.getFlag("story.ready"));
        assertEquals(List.of("player"), playerDirty);
        assertEquals(List.of("world"), worldDirty);
        assertEquals(
            StoryCondition.Scope.WORLD,
            new StorySignal.FlagChanged("story.ready", StoryCondition.Scope.WORLD).scope());
    }

    @Test
    void storyEngineReconcileActivatesAndCompletesEmptyRequirements() {
        ResourceLocation initial = ResourceLocation.parse("fireflymc:initial");
        StoryRuntimeSnapshot snapshot = snapshot(
            InterestIndex.empty(),
            initialQuest(initial, List.of(), List.of()));
        StoryProgress progress = new StoryProgress();
        WorldStoryState world = new WorldStoryState();
        List<String> playerDirty = new ArrayList<>();
        List<String> worldDirty = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            "player",
            progress,
            world,
            snapshot,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects(progress, world),
            playerDirty::add,
            () -> worldDirty.add("world"),
            () -> true);
        StoryEngine engine = new StoryEngine();

        engine.reconcileTransaction(context);

        assertEquals(QuestState.COMPLETED, progress.getQuestState(initial));
        assertEquals(List.of("player"), playerDirty);
        assertEquals(List.of(), worldDirty);
    }

    @Test
    void storyDisableBlocksProgressionAndReenableRequestsReconcile() {
        ResourceLocation initial = ResourceLocation.parse("fireflymc:blocked");
        StoryRuntimeSnapshot snapshot = snapshot(
            InterestIndex.empty(),
            initialQuest(initial, List.of(), List.of()));
        StoryProgress progress = new StoryProgress();
        WorldStoryState world = new WorldStoryState();
        List<String> playerDirty = new ArrayList<>();
        List<String> worldDirty = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            "player",
            progress,
            world,
            snapshot,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects(progress, world),
            playerDirty::add,
            () -> worldDirty.add("world"),
            () -> true);
        StoryEngine engine = new StoryEngine();

        engine.setStoryEnabled(false);
        engine.reconcileTransaction(context);
        engine.runTransaction(context, Set.of(initial));

        assertEquals(QuestState.LOCKED, progress.getQuestState(initial));
        assertEquals(List.of(), playerDirty);
        assertEquals(List.of(), worldDirty);

        engine.setStoryEnabled(true);
        assertTrue(engine.hasPendingReconcile());
    }

    @Test
    void graphUnlockCompletesEmptyAndNonContinuousSuccessorsThroughProductionActions() {
        ResourceLocation first = ResourceLocation.parse("fireflymc:unlock_a");
        ResourceLocation second = ResourceLocation.parse("fireflymc:unlock_b");
        ResourceLocation third = ResourceLocation.parse("fireflymc:unlock_c");
        StoryRuntimeSnapshot current = snapshot(
            InterestIndex.empty(),
            quest(first, List.of(), List.of(new ActionTypes.UnlockQuestAction(second))),
            quest(second, List.of(), List.of(new ActionTypes.UnlockQuestAction(third))),
            quest(third, List.of(), List.of()));
        StoryProgress progress = new StoryProgress();
        progress.activate(first);
        StoryEngine engine = new StoryEngine();
        List<String> playerDirty = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = engineContext(
            engine,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            new MutableState(),
            new DelegatingEffects(progress, new WorldStoryState()),
            playerDirty,
            new ArrayList<>());

        engine.runTransaction(context, Set.of(first));

        assertEquals(QuestState.COMPLETED, progress.getQuestState(first));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(second));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(third));
        assertEquals(List.of("player"), playerDirty);
        assertTrue(engine.watched(ENGINE_PLAYER).isEmpty());
    }

    @Test
    void realTransactionCompletesThreeQuestsWithOneStorySyncMark() {
        ResourceLocation first = ResourceLocation.parse("fireflymc:sync_unlock_a");
        ResourceLocation second = ResourceLocation.parse("fireflymc:sync_unlock_b");
        ResourceLocation third = ResourceLocation.parse("fireflymc:sync_unlock_c");
        StoryRuntimeSnapshot current = snapshot(
            InterestIndex.empty(),
            quest(first, List.of(), List.of(new ActionTypes.UnlockQuestAction(second))),
            quest(second, List.of(), List.of(new ActionTypes.UnlockQuestAction(third))),
            quest(third, List.of(), List.of()));
        StoryProgress progress = new StoryProgress();
        progress.activate(first);
        StoryEngine engine = new StoryEngine();
        StorySyncService service = StorySyncServiceTestAccess.createService();
        StoryEngineTestAccess.setSyncSink(engine, service);
        StoryEngine.TransactionContext<UUID> context = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects<>(progress, new WorldStoryState()),
            ignored -> engine.requestPlayerDirty(ENGINE_PLAYER),
            () -> engine.requestWorldDirty(),
            () -> true);

        engine.runTransaction(context, Set.of(first));

        assertEquals(QuestState.COMPLETED, progress.getQuestState(first));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(second));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(third));
        Map<UUID, UUID> online = Map.of(ENGINE_PLAYER, ENGINE_PLAYER);
        engine.flushDirty(new StoryEngine.TickContext<>(
            () -> true,
            1,
            List.of(context),
            online::get,
            playerId -> StorySyncServiceTestAccess.markDirty(service, playerId, true)
        ));
        assertEquals(
            Set.of(ENGINE_PLAYER),
            StorySyncServiceTestAccess.dirtyTargets(service)
        );

        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(ENGINE_PLAYER, (Runnable) () -> sends.add(ENGINE_PLAYER)),
            playerId -> true,
            true
        );
        assertEquals(List.of(ENGINE_PLAYER), sends);
    }

    @Test
    void realWorldDirtyEnginePathExpandsToOneServiceSendPerOnlinePlayer() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:sync_world");
        StoryRuntimeSnapshot current = snapshot(
            index(new PreparedStoryData.IndexDeclaration(
                questId,
                "on_complete[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "story.sync_world",
                StoryCondition.Scope.WORLD)),
            quest(questId,
                List.of(),
                List.of(new ActionTypes.SetFlagAction(
                    "story.sync_world",
                    true,
                    StoryCondition.Scope.WORLD))));
        StoryProgress progress = new StoryProgress();
        progress.activate(questId);
        WorldStoryState world = new WorldStoryState();
        StoryEngine engine = new StoryEngine();
        StorySyncService service = StorySyncServiceTestAccess.createService();
        StoryEngine.TransactionContext<UUID> onlineOne = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            ENGINE_PLAYER,
            progress,
            world,
            current,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects<>(progress, world),
            ignored -> engine.requestPlayerDirty(ENGINE_PLAYER),
            () -> engine.requestWorldDirty(),
            () -> true);
        StoryEngine.TransactionContext<UUID> onlineTwo = new StoryEngine.TransactionContext<>(
            PLAYER,
            PLAYER,
            new StoryProgress(),
            world,
            current,
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects<>(new StoryProgress(), world),
            ignored -> engine.requestPlayerDirty(PLAYER),
            () -> engine.requestWorldDirty(),
            () -> true);

        engine.runTransaction(onlineOne, Set.of(questId));
        Map<UUID, UUID> online = Map.of(ENGINE_PLAYER, ENGINE_PLAYER, PLAYER, PLAYER);
        engine.flushDirty(new StoryEngine.TickContext<>(
            () -> true,
            1,
            List.of(onlineOne, onlineTwo),
            online::get,
            playerId -> StorySyncServiceTestAccess.markDirty(service, playerId, true)
        ));
        assertEquals(
            Set.of(ENGINE_PLAYER, PLAYER),
            StorySyncServiceTestAccess.dirtyTargets(service)
        );

        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(
                ENGINE_PLAYER, (Runnable) () -> sends.add(ENGINE_PLAYER),
                PLAYER, (Runnable) () -> sends.add(PLAYER)
            ),
            playerId -> true,
            true
        );
        assertEquals(Set.of(ENGINE_PLAYER, PLAYER), Set.copyOf(sends));
        assertEquals(2, sends.size());
    }

    @Test
    void serverStartRebindIsIdempotentAndRepeatable() {
        StoryEngine engine = new StoryEngine();
        SyncSink prior = player -> {};
        StoryEngineTestAccess.setSyncSink(engine, prior);

        StoryEngineTestAccess.onServerStarted(engine, false);
        assertSame(prior, engine.installedSink());

        StoryEngineTestAccess.onServerStarted(engine, true);
        assertSame(StorySyncService.instance(), engine.installedSink());

        StoryEngineTestAccess.onServerStarted(engine, true);
        assertSame(StorySyncService.instance(), engine.installedSink());
        assertTrue(engine.watched(ENGINE_PLAYER).isEmpty());
    }

    @Test
    void idleTickDoesNotDirtyAndRealDueWatchFlushesOnce() {
        ResourceLocation itemId = ResourceLocation.parse("minecraft:stick");
        ResourceLocation questId = ResourceLocation.parse("fireflymc:watch");
        StoryRuntimeSnapshot current = snapshot(
            InterestIndex.empty(),
            quest(questId,
                List.of(new RequirementDefinition(
                    "item",
                    1,
                    new ConditionTypes.InventoryCondition(itemId, 1))),
                List.of()));
        MutableState state = new MutableState();
        StoryProgress progress = new StoryProgress();
        progress.activate(questId);
        StoryEngine engine = new StoryEngine();
        List<String> playerDirty = new ArrayList<>();
        List<String> flushed = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = engineContext(
            engine,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            state,
            new DelegatingEffects(progress, new WorldStoryState()),
            playerDirty,
            new ArrayList<>());
        engine.activateWatch(context, questId);
        long idleTick = (20 - engine.watchPhase(ENGINE_PLAYER) + 1) % 20;

        engine.onServerTick(tickContext(
            idleTick,
            List.of(context),
            player -> flushed.add(player)));
        assertEquals(QuestState.ACTIVE, progress.getQuestState(questId));
        assertEquals(List.of(), playerDirty);
        assertEquals(List.of(), flushed);

        state.inventory = 1;
        long dueTick = (20 - engine.watchPhase(ENGINE_PLAYER)) % 20;
        engine.onServerTick(tickContext(dueTick, List.of(context), player -> flushed.add(player)));

        assertEquals(QuestState.COMPLETED, progress.getQuestState(questId));
        assertEquals(List.of("player"), playerDirty);
        assertEquals(List.of("player"), flushed);
    }

    @Test
    void partialStickyProgressWithoutCompletionMarksPlayerDirtyOnce() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:partial-sticky");
        ResourceLocation advancement = ResourceLocation.parse("minecraft:story/root");
        ResourceLocation item = ResourceLocation.parse("minecraft:stick");
        StoryRuntimeSnapshot current = snapshot(
            InterestIndex.empty(),
            quest(questId,
                List.of(
                    new RequirementDefinition(
                        "advancement",
                        1,
                        new ConditionTypes.AdvancementCondition(advancement)),
                    new RequirementDefinition(
                        "inventory",
                        1,
                        new ConditionTypes.InventoryCondition(item, 1))),
                List.of()));
        StoryProgress progress = new StoryProgress();
        progress.activate(questId);
        StoryEngine engine = new StoryEngine();
        List<String> playerDirty = new ArrayList<>();
        StoryEngine.TransactionContext<String> context = engineContext(
            engine,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            new MutableState(),
            null,
            playerDirty,
            new ArrayList<>());

        engine.runTransaction(context, Set.of(questId));

        assertEquals(QuestState.ACTIVE, progress.getQuestState(questId));
        assertTrue(progress.isSatisfied(new RequirementProgressKey(
            questId,
            "advancement",
            1)));
        assertEquals(List.of("player"), playerDirty);
    }

    @Test
    void flushExpandsWorldDirtyDropsOfflineAndIsolatesReachableSinkFailure() {
        ResourceLocation flagAction = ResourceLocation.parse("fireflymc:world-action");
        StoryRuntimeSnapshot current = snapshot(
            index(new PreparedStoryData.IndexDeclaration(
                flagAction,
                "on_complete[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "story.world",
                StoryCondition.Scope.WORLD)),
            quest(flagAction,
                List.of(),
                List.of(new ActionTypes.SetFlagAction(
                    "story.world",
                    true,
                    StoryCondition.Scope.WORLD))));
        StoryEngine engine = new StoryEngine();
        UUID offline = UUID.fromString("00000000-0000-0000-0000-000000000099");
        StoryEngine.TransactionContext<String> onlineOne = engineContext(
            engine,
            ENGINE_PLAYER,
            activeProgress(flagAction),
            new WorldStoryState(),
            current,
            QuestEvaluator.CurrentState.empty(),
            null,
            new ArrayList<>(),
            new ArrayList<>());
        StoryEngine.TransactionContext<String> onlineTwo = engineContext(
            engine,
            PLAYER,
            activeProgress(ResourceLocation.parse("fireflymc:online-two")),
            new WorldStoryState(),
            StoryRuntimeSnapshot.empty(),
            QuestEvaluator.CurrentState.empty(),
            null,
            new ArrayList<>(),
            new ArrayList<>());

        engine.runTransaction(onlineOne, Set.of(flagAction));
        engine.requestPlayerDirty(offline);
        List<String> attempts = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        engine.flushDirty(tickContext(0, List.of(onlineOne, onlineTwo), player -> {
            attempts.add(player);
            if ("player".equals(player)) {
                failures.add(player);
                throw new IllegalStateException("expected sink failure");
            }
        }));

        assertEquals(List.of("player", "player-two"), attempts);
        assertEquals(List.of("player"), failures);

        List<String> laterAttempts = new ArrayList<>();
        StoryEngine.TransactionContext<String> formerlyOffline = engineContext(
            engine,
            offline,
            activeProgress(ResourceLocation.parse("fireflymc:formerly-offline")),
            new WorldStoryState(),
            StoryRuntimeSnapshot.empty(),
            QuestEvaluator.CurrentState.empty(),
            null,
            new ArrayList<>(),
            new ArrayList<>());
        engine.flushDirty(tickContext(
            1,
            List.of(formerlyOffline),
            laterAttempts::add));
        assertEquals(List.of(), laterAttempts);
    }

    @Test
    void samePlayerNestedDispatchAppendsToActiveTransaction() {
        ResourceLocation questA = ResourceLocation.parse("fireflymc:nested-a");
        ResourceLocation questC = ResourceLocation.parse("fireflymc:nested-c");
        ResourceLocation customEvent = ResourceLocation.parse("fireflymc:nested-trigger");
        StoryRuntimeSnapshot current = snapshot(
            index(
                new PreparedStoryData.IndexDeclaration(
                    questC,
                    "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                    customEvent.toString(),
                    null)),
            quest(questA,
                List.of(),
                List.of(new ActionTypes.ExecuteCommandAction("fireflymc story nested"))),
            quest(questC,
                List.of(new RequirementDefinition(
                    "ready",
                    1,
                        new ConditionTypes.CustomEventCondition(customEvent, 1))),
                List.of()));
        StoryProgress progress = new StoryProgress();
        progress.activate(questA);
        progress.activate(questC);
        StoryEngine engine = new StoryEngine();
        List<String> playerDirty = new ArrayList<>();
        AtomicReference<StoryEngine.TransactionContext<String>> active = new AtomicReference<>();
        BiConsumer<StoryEngine.TransactionContext<String>, StoryEvent> nested = (ignored, ignoredEvent) ->
            engine.dispatch(active.get(), event(
                StoryEventType.CUSTOM_EVENT,
                ResourceLocation.parse("fireflymc:nested-trigger"),
                1));
        StoryEngine.TransactionContext<String> context = engineContext(
            engine,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            QuestEvaluator.CurrentState.empty(),
            new RecordingEffects(progress, new WorldStoryState(), nested, null),
            playerDirty,
            new ArrayList<>());
        active.set(context);

        engine.runTransaction(context, Set.of(questA));

        assertEquals(QuestState.COMPLETED, progress.getQuestState(questA));
        assertEquals(QuestState.COMPLETED, progress.getQuestState(questC));
        assertEquals(List.of("player"), playerDirty);
        assertEquals(0, engine.pendingDispatchCount());
    }

    @Test
    void samePlayerNestedDispatchRemainsSubjectToActiveTransactionCap() {
        ResourceLocation questA = ResourceLocation.parse("fireflymc:nested-cap-a");
        ResourceLocation questC = ResourceLocation.parse("fireflymc:nested-cap-c");
        ResourceLocation customEvent = ResourceLocation.parse("fireflymc:nested-cap-trigger");
        StoryRuntimeSnapshot current = snapshot(
            index(new PreparedStoryData.IndexDeclaration(
                questC,
                "requirements[0]",
                PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                customEvent.toString(),
                null)),
            quest(questA,
                List.of(),
                List.of(new ActionTypes.ExecuteCommandAction("fireflymc story nested-cap"))),
            quest(questC,
                List.of(new RequirementDefinition(
                    "ready",
                    1,
                    new ConditionTypes.CustomEventCondition(customEvent, 1))),
                List.of()));
        StoryProgress progress = new StoryProgress();
        progress.activate(questA);
        progress.activate(questC);
        StoryEngine engine = new StoryEngine();
        List<String> playerDirty = new ArrayList<>();
        AtomicReference<StoryEngine.TransactionContext<String>> active = new AtomicReference<>();
        StoryEngine.TransactionContext<String> context = engineContext(
            engine,
            ENGINE_PLAYER,
            progress,
            new WorldStoryState(),
            current,
            QuestEvaluator.CurrentState.empty(),
            new RecordingEffects(
                progress,
                new WorldStoryState(),
                (ignored, ignoredEvent) -> engine.dispatch(
                    active.get(),
                    event(StoryEventType.CUSTOM_EVENT, customEvent, 1)),
                null),
            playerDirty,
            new ArrayList<>());
        active.set(context);
        List<ResourceLocation> initialWork = new ArrayList<>();
        initialWork.add(questA);
        for (int index = 1;
             index < TransitionQueue.MAX_WORK_ITEMS_PER_TRANSACTION;
             index++) {
            initialWork.add(ResourceLocation.parse("fireflymc:cap-padding-" + index));
        }

        engine.runTransaction(context, initialWork);

        assertEquals(QuestState.COMPLETED, progress.getQuestState(questA));
        assertEquals(QuestState.ACTIVE, progress.getQuestState(questC));
        assertEquals(1, progress.counter(new RequirementProgressKey(questC, "ready", 1)));
        assertEquals(List.of("player"), playerDirty);
        assertEquals(0, engine.pendingDispatchCount());
    }

    @Test
    void crossPlayerNestedDispatchDefersUntilNextTick() {
        ResourceLocation questA = ResourceLocation.parse("fireflymc:cross-a");
        ResourceLocation questD = ResourceLocation.parse("fireflymc:cross-d");
        ResourceLocation customEvent = ResourceLocation.parse("fireflymc:cross-trigger");
        StoryRuntimeSnapshot current = snapshot(
            index(
                new PreparedStoryData.IndexDeclaration(
                    questD,
                    "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                    customEvent.toString(),
                    null)),
            quest(questA,
                List.of(),
                List.of(new ActionTypes.ExecuteCommandAction("fireflymc story cross"))),
            quest(questD,
                List.of(new RequirementDefinition(
                    "ready",
                        1,
                        new ConditionTypes.CustomEventCondition(customEvent, 1))),
                List.of()));
        StoryProgress firstProgress = new StoryProgress();
        StoryProgress secondProgress = new StoryProgress();
        WorldStoryState sharedWorld = new WorldStoryState();
        firstProgress.activate(questA);
        secondProgress.activate(questD);
        StoryEngine engine = new StoryEngine();
        StoryEngine.TransactionContext<String> first = engineContext(
            engine,
            ENGINE_PLAYER,
            firstProgress,
            sharedWorld,
            current,
            QuestEvaluator.CurrentState.empty(),
            null,
            new ArrayList<>(),
            new ArrayList<>());
        StoryEngine.TransactionContext<String> second = engineContext(
            engine,
            PLAYER,
            secondProgress,
            sharedWorld,
            current,
            QuestEvaluator.CurrentState.empty(),
            null,
            new ArrayList<>(),
            new ArrayList<>());
        StoryEngine.TransactionContext<String> firstWithNested = engineContext(
            engine,
            ENGINE_PLAYER,
            firstProgress,
            sharedWorld,
            current,
            QuestEvaluator.CurrentState.empty(),
            new RecordingEffects(
                firstProgress,
                sharedWorld,
                (ignored, ignoredEvent) -> engine.dispatch(
                    second,
                    event(StoryEventType.CUSTOM_EVENT, customEvent, 1)),
                null),
            new ArrayList<>(),
            new ArrayList<>());

        engine.runTransaction(firstWithNested, Set.of(questA));
        assertEquals(QuestState.ACTIVE, secondProgress.getQuestState(questD));
        assertEquals(1, engine.pendingDispatchCount());

        engine.onServerTick(tickContext(0, List.of(firstWithNested, second), ignored -> {}));
        assertEquals(QuestState.COMPLETED, secondProgress.getQuestState(questD));
        assertEquals(0, engine.pendingDispatchCount());
    }

    @Test
    void serverStoppingResetsTransientRuntimeAndDetachesSink() throws Exception {
        StoryEngine engine = new StoryEngine();
        List<String> sinkCalls = new ArrayList<>();
        SyncSink installed = player -> sinkCalls.add(player.getUUID().toString());
        engine.setSyncSink(installed);
        assertSame(installed, engine.installedSink());
        engine.installSnapshot(snapshot(InterestIndex.empty()));
        engine.setStoryEnabled(false);
        engine.requestPlayerDirty(ENGINE_PLAYER);
        engine.requestWorldDirty();
        setBooleanField(engine, "activeTransactionPlayerMutated", true);
        setBooleanField(engine, "activeTransactionWorldMutated", true);

        engine.onServerStopping(() -> true);

        assertEquals(StoryRuntimeSnapshot.empty().revision(), engine.snapshot().revision());
        assertTrue(engine.snapshot().quests().isEmpty());
        assertTrue(engine.storyEnabled());
        assertFalse(engine.hasPendingReconcile());
        assertTrue(engine.watched(ENGINE_PLAYER).isEmpty());
        assertFalse(booleanField(engine, "activeTransactionPlayerMutated"));
        assertFalse(booleanField(engine, "activeTransactionWorldMutated"));
        assertNotSame(installed, engine.installedSink());
        engine.requestPlayerDirty(ENGINE_PLAYER);
        engine.installedSink().markDirty(null);
        assertEquals(List.of(), sinkCalls);
    }

    @Test
    void storyEngineRejectsWrongThreadGameplayAccess() {
        StoryEngine engine = new StoryEngine();
        StoryEngine.TransactionContext<String> context = new StoryEngine.TransactionContext<>(
            ENGINE_PLAYER,
            "player",
            new StoryProgress(),
            new WorldStoryState(),
            StoryRuntimeSnapshot.empty(),
            QuestEvaluator.CurrentState.empty(),
            new DelegatingEffects(new StoryProgress(), new WorldStoryState()),
            ignored -> {},
            () -> {},
            () -> false);

        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> engine.dispatch(context, event(StoryEventType.ITEM_PICKUP, QUEST_A, 1)));
        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> engine.onServerTick(new StoryEngine.TickContext<>(
                () -> false,
                0,
                List.of(context),
                ignored -> null,
                ignored -> {})));
    }

    @Test
    void publicGameplaySurfaceRemainsNarrow() throws Exception {
        Set<String> publicMethods = java.util.Arrays.stream(StoryEngine.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic() && !method.isBridge())
            .map(TransitionQueueTest::signature)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertEquals(
            Set.of(
                "public static void install"
                    + "(firefly520.fireflymc.story.engine.StoryRuntimeSnapshot)",
                "public static void register(net.neoforged.bus.api.IEventBus)",
                "public static void onConfigReload(boolean)",
                "public firefly520.fireflymc.story.engine.StoryRuntimeSnapshot snapshot()",
                "public void dispatch"
                    + "(net.minecraft.server.level.ServerPlayer,"
                    + "firefly520.fireflymc.story.engine.StoryEvent)",
                "public void reconcile"
                    + "(net.minecraft.server.level.ServerPlayer)",
                "public void onServerTick"
                    + "(net.minecraft.server.MinecraftServer)"),
            publicMethods);
        assertTrue(Modifier.isPublic(StoryEngine.class.getModifiers()));
        assertFalse(Modifier.isPublic(StoryEngine.class.getDeclaredConstructor().getModifiers()));
    }

    private static String signature(Method method) {
        StringBuilder result = new StringBuilder("public ");
        if (Modifier.isStatic(method.getModifiers())) {
            result.append("static ");
        }
        result.append(method.getReturnType().getName())
            .append(' ')
            .append(method.getName())
            .append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(parameters[index].getName());
        }
        return result.append(')').toString();
    }

    private static void setBooleanField(
        StoryEngine engine,
        String name,
        boolean value
    ) throws ReflectiveOperationException {
        Field field = StoryEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(engine, value);
    }

    private static boolean booleanField(
        StoryEngine engine,
        String name
    ) throws ReflectiveOperationException {
        Field field = StoryEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(engine);
    }

    private static void invoke(String operation, TransitionQueue queue) {
        switch (operation) {
            case "enqueue" -> queue.enqueue(QUEST_A);
            case "removeNext" -> queue.removeNext();
            case "drain" -> queue.drain();
            case "workItemsProcessed" -> queue.workItemsProcessed();
            case "queuedIds" -> queue.queuedIds();
            case "isQueued" -> queue.isQueued(QUEST_A);
            case "limitExceeded" -> queue.limitExceeded();
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private static StoryEngine.TransactionContext<String> engineContext(
        StoryEngine engine,
        UUID playerId,
        StoryProgress progress,
        WorldStoryState world,
        StoryRuntimeSnapshot snapshot,
        QuestEvaluator.CurrentState currentState,
        ActionExecutor.Effects<String> effects,
        List<String> playerDirty,
        List<String> worldDirty
    ) {
        return new StoryEngine.TransactionContext<>(
            playerId,
            ENGINE_PLAYER.equals(playerId) ? "player" : "player-two",
            progress,
            world,
            snapshot,
            currentState,
            effects == null ? new DelegatingEffects(progress, world) : effects,
            ignored -> {
                playerDirty.add("player");
                engine.requestPlayerDirty(playerId);
            },
            () -> {
                worldDirty.add("world");
                engine.requestWorldDirty();
            },
            () -> true);
    }

    private static StoryEngine.TickContext<String> tickContext(
        long serverTick,
        List<StoryEngine.TransactionContext<String>> players,
        java.util.function.Consumer<String> markDirty
    ) {
        Map<UUID, String> byId = new HashMap<>();
        players.forEach(context -> byId.put(context.playerId(), context.player()));
        return new StoryEngine.TickContext<>(
            () -> true,
            serverTick,
            players,
            byId::get,
            markDirty);
    }

    private static StoryEvent event(StoryEventType type, ResourceLocation key, int amount) {
        return new StoryEvent(type, key, amount, Map.of());
    }

    private static StoryProgress activeProgress(ResourceLocation questId) {
        StoryProgress progress = new StoryProgress();
        progress.activate(questId);
        return progress;
    }

    private static final class MutableState implements QuestEvaluator.CurrentState {
        private int inventory;
        private boolean advancement = true;

        @Override
        public int inventoryCount(ResourceLocation item) {
            return inventory;
        }

        @Override
        public boolean inDimension(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inBiome(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inBiomeTag(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inStructureTag(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inStructure(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean atLocation(
            ResourceLocation dimension,
            double x,
            double y,
            double z,
            float radius
        ) {
            return false;
        }

        @Override
        public boolean advancementDone(ResourceLocation id) {
            return advancement;
        }
    }

    private static QuestDefinition quest(
        ResourceLocation id,
        List<RequirementDefinition> requirements,
        List<? extends StoryAction> onComplete
    ) {
        return quest(id, requirements, onComplete, false);
    }

    private static QuestDefinition initialQuest(
        ResourceLocation id,
        List<RequirementDefinition> requirements,
        List<? extends StoryAction> onComplete
    ) {
        return quest(id, requirements, onComplete, true);
    }

    private static QuestDefinition quest(
        ResourceLocation id,
        List<RequirementDefinition> requirements,
        List<? extends StoryAction> onComplete,
        boolean initial
    ) {
        return new QuestDefinition(
            id,
            ResourceLocation.parse("fireflymc:chapter"),
            net.minecraft.network.chat.Component.empty(),
            net.minecraft.network.chat.Component.empty(),
            List.of(),
            null,
            initial,
            requirements,
            List.copyOf(onComplete));
    }

    private static StoryRuntimeSnapshot snapshot(
        InterestIndex index,
        QuestDefinition... quests
    ) {
        Map<ResourceLocation, QuestDefinition> values = new HashMap<>();
        for (QuestDefinition quest : quests) {
            values.put(quest.id(), quest);
        }
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.copyOf(values),
            index,
            StoryGraph.fromQuests(values),
            "test-revision");
    }

    private static InterestIndex index(PreparedStoryData.IndexDeclaration... declarations) {
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> values = new HashMap<>();
        for (PreparedStoryData.IndexDeclaration declaration : declarations) {
            values.put(declaration, Set.of(ResourceLocation.parse(declaration.target())));
        }
        return InterestIndex.fromDeclarations(values);
    }

    private static final class RecordingEffects implements ActionExecutor.Effects<String> {
        private final StoryProgress progress;
        private final WorldStoryState world;
        private final BiConsumer<StoryEngine.TransactionContext<String>, StoryEvent> commandDispatch;
        private final java.util.function.Consumer<ResourceLocation> unlockCallback;

        private RecordingEffects(
            StoryProgress progress,
            WorldStoryState world,
            BiConsumer<StoryEngine.TransactionContext<String>, StoryEvent> commandDispatch,
            java.util.function.Consumer<ResourceLocation> unlockCallback
        ) {
            this.progress = progress;
            this.world = world;
            this.commandDispatch = commandDispatch;
            this.unlockCallback = unlockCallback;
        }

        @Override
        public boolean isServerThread(String player) {
            return true;
        }

        @Override
        public boolean setFlag(
            String player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            boolean changed = scope == StoryCondition.Scope.PLAYER
                ? progress.setFlag(flag, value)
                : world.setFlag(flag, value);
            return changed;
        }

        @Override
        public boolean setPlayerVar(
            String player,
            String var,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            Integer min,
            Integer max
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlockQuest(String player, ResourceLocation quest) {
            progress.activate(quest);
            if (unlockCallback != null) {
                unlockCallback.accept(quest);
            }
        }

        @Override
        public net.minecraft.world.item.ItemStack addToInventory(
            String player,
            ResourceLocation item,
            int count
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dropAtFeet(
            String player,
            net.minecraft.world.item.ItemStack remaining
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void playSound(
            String player,
            ResourceLocation sound,
            float volume,
            float pitch
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showTitle(
            String player,
            net.minecraft.network.chat.Component title,
            net.minecraft.network.chat.Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(
            String player,
            net.minecraft.network.chat.Component message
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void executeCommand(String player, String command) {
            if (commandDispatch != null) {
                commandDispatch.accept(null, null);
            }
        }

        @Override
        public void teleport(
            String player,
            ResourceLocation dimension,
            double x,
            double y,
            double z
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private record DelegatingEffects<P>(
        StoryProgress progress,
        WorldStoryState world
    ) implements ActionExecutor.Effects<P> {
        @Override
        public boolean isServerThread(P player) {
            return true;
        }

        @Override
        public boolean setFlag(
            P player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            return scope == StoryCondition.Scope.PLAYER
                ? progress.setFlag(flag, value)
                : world.setFlag(flag, value);
        }

        @Override
        public boolean setPlayerVar(
            P player,
            String var,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            Integer min,
            Integer max
        ) {
            int lower = min == null ? Integer.MIN_VALUE : min;
            int upper = max == null ? Integer.MAX_VALUE : max;
            return operation == ActionTypes.SetVarAction.Operation.SET
                ? progress.setVar(var, amount, lower, upper)
                : progress.addVar(var, amount, lower, upper);
        }

        @Override
        public void unlockQuest(P player, ResourceLocation quest) {
            progress.activate(quest);
        }

        @Override
        public net.minecraft.world.item.ItemStack addToInventory(
            P player,
            ResourceLocation item,
            int count
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dropAtFeet(
            P player,
            net.minecraft.world.item.ItemStack remaining
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void playSound(
            P player,
            ResourceLocation sound,
            float volume,
            float pitch
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showTitle(
            P player,
            net.minecraft.network.chat.Component title,
            net.minecraft.network.chat.Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(P player, net.minecraft.network.chat.Component message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void executeCommand(P player, String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void teleport(
            P player,
            ResourceLocation dimension,
            double x,
            double y,
            double z
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
