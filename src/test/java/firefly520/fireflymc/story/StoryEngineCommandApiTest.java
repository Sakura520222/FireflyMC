package firefly520.fireflymc.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.QuestEvaluator;
import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoryEngineCommandApiTest {
    private static final ResourceLocation QUEST =
        ResourceLocation.parse("fireflymc:command");
    private static final ResourceLocation EVENT =
        ResourceLocation.parse("fireflymc:event");
    private static final UUID PLAYER_ID = UUID.randomUUID();

    @Test
    void commandCompletionExecutesOrderedActionsOnceAndIsIdempotent() {
        RecordingEffects effects = new RecordingEffects();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        StoryEngine engine = new StoryEngine();
        StoryEngine.StoryCommandContext<String> context = context(
            engine, progress, new WorldStoryState(), snapshot(quest(1)), effects, true);

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).complete(context, QUEST));
        assertEquals(StoryEngine.CommandResult.ALREADY_COMPLETED,
            StoryEngine.CommandApi.of(engine).complete(context, QUEST));
        assertSame(QuestState.COMPLETED, progress.getQuestState(QUEST));
        assertEquals(List.of("flag:first", "message", "flag:second"), effects.order);
    }

    @Test
    void scopedResetsPreserveTheOtherScopeAndMarkOnlyThatScopeDirty() {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.setFlag("player_flag", true);
        WorldStoryState world = new WorldStoryState();
        world.setFlag("world_flag", true);
        List<String> dirty = new ArrayList<>();
        StoryEngine.StoryCommandContext<String> context = context(
            engine,
            progress,
            world,
            snapshot(quest(1)),
            new RecordingEffects(),
            true,
            dirty
        );

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).resetPlayer(context));
        assertSame(QuestState.LOCKED, progress.getQuestState(QUEST));
        assertFalse(progress.getFlag("player_flag"));
        assertTrue(world.getFlag("world_flag"));
        assertEquals(List.of("player"), dirty);

        progress.activate(QUEST);
        progress.setFlag("player_flag", true);
        dirty.clear();
        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).resetWorld(
                world, () -> dirty.add("world"), () -> true));
        assertSame(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertTrue(progress.getFlag("player_flag"));
        assertFalse(world.getFlag("world_flag"));
        assertEquals(List.of("world"), dirty);
    }

    @Test
    void worldVariableCommandsPublishOnlyWorldDirtyState() {
        StoryEngine engine = new StoryEngine();
        WorldStoryState world = new WorldStoryState();
        List<String> dirty = new ArrayList<>();
        var context = context(engine, new StoryProgress(), world, StoryRuntimeSnapshot.empty(),
            new RecordingEffects(), true, dirty);

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).setVar(context, StoryCondition.Scope.WORLD,
                "world_steps", ActionTypes.SetVarAction.Operation.SET, 4));
        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).setVar(context, StoryCondition.Scope.WORLD,
                "world_steps", ActionTypes.SetVarAction.Operation.ADD, 3));
        assertEquals(7, world.getVar("world_steps"));
        assertEquals(List.of("world", "world"), dirty);
    }

    @Test
    void everyCommandMutationRejectsAnActionInvokedStoryCommand() {
        StoryEngine engine = new StoryEngine();
        List<StoryEngine.CommandResult> nested = new ArrayList<>();
        List<String> order = new ArrayList<>();
        RecordingEffects effects = new RecordingEffects(order) {
            @Override
            public void executeCommand(String player, String command) {
                StoryEngine.CommandApi commands = StoryEngine.CommandApi.of(engine);
                StoryProgress progress = new StoryProgress();
                progress.activate(QUEST);
                StoryEngine.StoryCommandContext<String> nestedContext = context(
                    engine, progress, new WorldStoryState(), snapshot(quest(1)), this, true);
                WorldStoryState world = new WorldStoryState();
                nested.add(commands.setFlag(
                    nestedContext, StoryCondition.Scope.PLAYER, "flag", true));
                nested.add(commands.setVar(
                    nestedContext,
                    StoryCondition.Scope.PLAYER,
                    "variable",
                    ActionTypes.SetVarAction.Operation.ADD,
                    1));
                nested.add(commands.unlock(nestedContext, QUEST));
                nested.add(commands.complete(nestedContext, QUEST));
                nested.add(commands.counter(
                    nestedContext,
                    QUEST,
                    "steps",
                    ActionTypes.SetVarAction.Operation.ADD,
                    1));
                nested.add(commands.resetPlayer(nestedContext));
                nested.add(commands.resetWorld(
                    world, () -> { }, () -> true));
                nested.add(commands.trigger(nestedContext, EVENT));
                order.add("nested-probes");
            }
        };
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        StoryEngine.StoryCommandContext<String> context = context(
            engine, progress, new WorldStoryState(), snapshot(quest(1)), effects, true);

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).complete(context, QUEST));

        assertEquals(
            List.of(
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION,
                StoryEngine.CommandResult.NESTED_TRANSACTION),
            nested);
        assertEquals(
            List.of("flag:first", "nested-probes", "message", "flag:second"),
            order);
        assertSame(QuestState.COMPLETED, progress.getQuestState(QUEST));
    }

    @Test
    void counterCommandsClampAndRouteThroughNormalCompletion() {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        RequirementProgressKey key = new RequirementProgressKey(QUEST, "steps", 1);
        StoryEngine.StoryCommandContext<String> context = context(
            engine,
            progress,
            new WorldStoryState(),
            snapshot(quest(2)),
            new RecordingEffects(),
            true
        );

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).counter(
                context, QUEST, "steps", ActionTypes.SetVarAction.Operation.SET, 1));
        assertEquals(1, progress.counter(key));
        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).counter(
                context, QUEST, "steps", ActionTypes.SetVarAction.Operation.ADD, 1));
        assertSame(QuestState.COMPLETED, progress.getQuestState(QUEST));
    }

    @Test
    void counterPersistenceClampsNegativeValuesAndOverflow() {
        StoryProgress progress = new StoryProgress();
        RequirementProgressKey key = new RequirementProgressKey(QUEST, "steps", 1);

        assertTrue(progress.setCounter(key, 4));
        assertTrue(progress.setCounter(key, -5));
        assertEquals(0, progress.counter(key));
        assertTrue(progress.addCounter(key, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, progress.counter(key));
        assertFalse(progress.addCounter(key, 1));
        assertEquals(Integer.MAX_VALUE, progress.counter(key));
        assertTrue(progress.addCounter(key, Integer.MIN_VALUE));
        assertEquals(0, progress.counter(key));
    }

    @Test
    void incompleteTriggerSynchronizesCounterExactlyOnce() {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        List<String> dirty = new ArrayList<>();
        StoryEngine.StoryCommandContext<String> context = context(
            engine, progress, new WorldStoryState(), snapshot(quest(2)),
            new RecordingEffects(), true, dirty);

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).trigger(context, EVENT));

        assertSame(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertEquals(1, progress.counter(new RequirementProgressKey(QUEST, "steps", 1)));
        assertEquals(List.of("player"), dirty);
    }

    @Test
    void transactionDirtyFailureDoesNotRetryTheDirtyCallback() {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        List<String> dirty = new ArrayList<>();
        StoryEngine.StoryCommandContext<String> context = new StoryEngine.StoryCommandContext<>(
            PLAYER_ID, "target", progress, new WorldStoryState(), snapshot(quest(1)),
            QuestEvaluator.CurrentState.empty(), new RecordingEffects(),
            ignored -> {
                dirty.add("player");
                throw new IllegalStateException("Injected dirty sink failure");
            },
            () -> dirty.add("world"), () -> true, true);

        assertEquals(StoryEngine.CommandResult.FAILED,
            StoryEngine.CommandApi.of(engine).trigger(context, EVENT));

        assertSame(QuestState.COMPLETED, progress.getQuestState(QUEST));
        assertEquals(List.of("player"), dirty);
        assertFalse(engine.hasActiveTransaction());
    }

    @Test
    void partialMultiCandidateTriggerFailureMarksPlayerDirtyOnce() {
        ResourceLocation firstQuest = ResourceLocation.parse("fireflymc:aaa");
        ResourceLocation failedQuest = ResourceLocation.parse("fireflymc:zzz");
        QuestDefinition first = new QuestDefinition(
            firstQuest,
            ResourceLocation.parse("fireflymc:chapter"),
            Component.empty(),
            Component.empty(),
            List.of(),
            null,
            false,
            List.of(new RequirementDefinition(
                "steps",
                1,
                new ConditionTypes.CustomEventCondition(EVENT, 2))),
            List.of()
        );
        QuestDefinition failing = new QuestDefinition(
            failedQuest,
            ResourceLocation.parse("fireflymc:chapter"),
            Component.empty(),
            Component.empty(),
            List.of(),
            null,
            false,
            List.of(new RequirementDefinition(
                "boom",
                1,
                new ConditionTypes.CustomEventCondition(EVENT, 1))),
            List.of()
        );
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(firstQuest);
        progress.activate(failedQuest);
        progress.setCounter(
            new RequirementProgressKey(failedQuest, "boom", 1),
            Integer.MAX_VALUE
        );
        List<String> dirty = new ArrayList<>();
        StoryEngine.StoryCommandContext<String> context = context(
            engine,
            progress,
            new WorldStoryState(),
            snapshot(first, failing),
            new RecordingEffects(),
            true,
            dirty
        );

        assertEquals(StoryEngine.CommandResult.FAILED,
            StoryEngine.CommandApi.of(engine).trigger(context, EVENT));

        assertEquals(1, progress.counter(
            new RequirementProgressKey(firstQuest, "steps", 1)));
        assertEquals(List.of("player"), dirty);

        // Gameplay dispatch has the same evaluator-before-transaction boundary.
        StoryProgress gameplayProgress = new StoryProgress();
        gameplayProgress.activate(firstQuest);
        gameplayProgress.activate(failedQuest);
        gameplayProgress.setCounter(
            new RequirementProgressKey(failedQuest, "boom", 1), Integer.MAX_VALUE);
        dirty.clear();
        var gameplay = context(engine, gameplayProgress, new WorldStoryState(),
            snapshot(first, failing), new RecordingEffects(), true, dirty);
        assertThrowsExactly(ArithmeticException.class, () -> engine.dispatch(
            gameplay.transaction(), new StoryEvent(StoryEventType.CUSTOM_EVENT, EVENT, 1, Map.of())));
        assertEquals(List.of("player"), dirty);
    }

    @Test
    void triggerReportsDisabledClosedNoOpPerformedAndRuntimeFailure() {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        StoryEngine.StoryCommandContext<String> context = context(
            engine,
            progress,
            new WorldStoryState(),
            snapshot(quest(1)),
            new RecordingEffects(),
            true
        );

        engine.setStoryEnabled(false);
        assertEquals(StoryEngine.CommandResult.DISABLED,
            StoryEngine.CommandApi.of(engine).trigger(context, EVENT));
        engine.setStoryEnabled(true);

        StoryEngine.StoryCommandContext<String> closed = context(
            engine,
            progress,
            new WorldStoryState(),
            snapshot(quest(1)),
            new RecordingEffects(),
            false
        );
        assertEquals(StoryEngine.CommandResult.SESSION_CLOSED,
            StoryEngine.CommandApi.of(engine).trigger(closed, EVENT));

        StoryEngine.StoryCommandContext<String> noOp = context(
            engine,
            progress,
            new WorldStoryState(),
            StoryRuntimeSnapshot.empty(),
            new RecordingEffects(),
            true
        );
        assertEquals(StoryEngine.CommandResult.NO_OP,
            StoryEngine.CommandApi.of(engine).trigger(noOp, EVENT));

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            StoryEngine.CommandApi.of(engine).trigger(context, EVENT));
        assertSame(QuestState.COMPLETED, progress.getQuestState(QUEST));

        StoryProgress overflow = new StoryProgress();
        overflow.activate(QUEST);
        overflow.setCounter(new RequirementProgressKey(QUEST, "steps", 1), Integer.MAX_VALUE);
        StoryEngine.StoryCommandContext<String> failing = context(
            engine,
            overflow,
            new WorldStoryState(),
            snapshot(quest(1)),
            new RecordingEffects(),
            true
        );
        assertEquals(StoryEngine.CommandResult.FAILED,
            StoryEngine.CommandApi.of(engine).trigger(failing, EVENT));
        assertEquals(Integer.MAX_VALUE, overflow.counter(
            new RequirementProgressKey(QUEST, "steps", 1)));
    }

    private static QuestDefinition quest(int threshold) {
        return new QuestDefinition(
            QUEST,
            ResourceLocation.parse("fireflymc:chapter"),
            Component.empty(),
            Component.empty(),
            List.of(),
            null,
            false,
            List.of(new RequirementDefinition(
                "steps",
                1,
                new ConditionTypes.CustomEventCondition(EVENT, threshold))),
            List.of(
                new ActionTypes.SetFlagAction("first", true, StoryCondition.Scope.PLAYER),
                new ActionTypes.ExecuteCommandAction("fireflymc story counter ..."),
                new ActionTypes.SendMessageAction(Component.literal("message")),
                new ActionTypes.SetFlagAction("second", true, StoryCondition.Scope.PLAYER))
        );
    }

    private static StoryRuntimeSnapshot snapshot(QuestDefinition quest) {
        Map<ResourceLocation, QuestDefinition> quests = new LinkedHashMap<>();
        quests.put(quest.id(), quest);
        return snapshot(quests);
    }

    private static StoryRuntimeSnapshot snapshot(
        QuestDefinition first,
        QuestDefinition second
    ) {
        Map<ResourceLocation, QuestDefinition> quests = new LinkedHashMap<>();
        quests.put(first.id(), first);
        quests.put(second.id(), second);
        return snapshot(quests);
    }

    private static StoryRuntimeSnapshot snapshot(
        Map<ResourceLocation, QuestDefinition> quests
    ) {
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.copyOf(quests),
            interestIndex(quests),
            firefly520.fireflymc.story.loader.StoryGraph.fromQuests(quests),
            "revision"
        );
    }

    private static InterestIndex interestIndex(
        Map<ResourceLocation, QuestDefinition> quests
    ) {
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> values =
            new LinkedHashMap<>();
        quests.values().forEach(quest -> {
            values.put(
                new PreparedStoryData.IndexDeclaration(
                    quest.id(),
                    "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                    EVENT.toString()),
                Set.of(EVENT)
            );
        });
        return InterestIndex.fromDeclarations(values);
    }

    private static StoryEngine.StoryCommandContext<String> context(
        StoryEngine engine,
        StoryProgress progress,
        WorldStoryState world,
        StoryRuntimeSnapshot snapshot,
        ActionExecutor.Effects<String> effects,
        boolean sessionOpen
    ) {
        return context(
            engine,
            progress,
            world,
            snapshot,
            effects,
            sessionOpen,
            new ArrayList<>()
        );
    }

    private static StoryEngine.StoryCommandContext<String> context(
        StoryEngine engine,
        StoryProgress progress,
        WorldStoryState world,
        StoryRuntimeSnapshot snapshot,
        ActionExecutor.Effects<String> effects,
        boolean sessionOpen,
        List<String> dirty
    ) {
        return new StoryEngine.StoryCommandContext<>(
            PLAYER_ID,
            "target",
            progress,
            world,
            snapshot,
            QuestEvaluator.CurrentState.empty(),
            effects,
            ignored -> dirty.add("player"),
            () -> dirty.add("world"),
            () -> true,
            sessionOpen
        );
    }

    private static class RecordingEffects implements ActionExecutor.Effects<String> {
        private final List<String> order;

        RecordingEffects() {
            this(new ArrayList<>());
        }

        RecordingEffects(List<String> order) {
            this.order = order;
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
            order.add("flag:" + flag);
            return true;
        }

        @Override
        public boolean setPlayerVar(
            String player,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            @Nullable Integer min,
            @Nullable Integer max
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlockQuest(String player, ResourceLocation quest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ItemStack addToInventory(String player, ResourceLocation item, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dropAtFeet(String player, ItemStack remaining) {
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
            Component title,
            @Nullable Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(String player, Component message) {
            order.add("message");
        }

        @Override
        public void executeCommand(String player, String command) {
            throw new UnsupportedOperationException();
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
}
