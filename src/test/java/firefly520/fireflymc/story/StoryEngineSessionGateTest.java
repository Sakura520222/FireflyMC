package firefly520.fireflymc.story;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.loader.StoryGraph;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryEngineSessionGateTest {
    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000901");
    private static final ResourceLocation QUEST =
        ResourceLocation.parse("fireflymc:session-gated");
    private static final ResourceLocation EVENT =
        ResourceLocation.parse("fireflymc:gate-event");

    @Test
    void closedSessionBlocksDispatchButOpenSessionAdvances() {
        StoryEngine engine = new StoryEngine();
        StoryProgress closedProgress = activeProgress();
        StoryProgress openProgress = activeProgress();

        engine.dispatch(context(engine, closedProgress, false), customEvent());
        engine.dispatch(context(engine, openProgress, true), customEvent());

        assertEquals(0, closedProgress.counter(key()));
        assertEquals(1, openProgress.counter(key()));
    }

    @Test
    void closedSessionBlocksReconcileButOpenSessionActivatesInitialQuest() {
        StoryEngine engine = new StoryEngine();
        StoryProgress closedProgress = new StoryProgress();
        StoryProgress openProgress = new StoryProgress();

        engine.reconcileTransaction(context(engine, closedProgress, false));
        engine.reconcileTransaction(context(engine, openProgress, true));

        assertEquals(QuestState.LOCKED, closedProgress.getQuestState(QUEST));
        assertEquals(QuestState.ACTIVE, openProgress.getQuestState(QUEST));
    }

    @Test
    void serverTickFiltersClosedSessionContextsAndProgressesOpenSessions() {
        StoryEngine engine = new StoryEngine();
        StoryProgress closedProgress = new StoryProgress();
        StoryProgress openProgress = new StoryProgress();
        StoryEngine.TransactionContext<String> closed =
            context(engine, closedProgress, false);
        StoryEngine.TransactionContext<String> open =
            context(engine, openProgress, true);
        engine.markAllPlayersForReconcile();

        engine.onServerTick(new StoryEngine.TickContext<>(
            () -> true,
            0,
            List.of(closed, open),
            playerId -> "player",
            ignored -> {}
        ));

        assertEquals(QuestState.LOCKED, closedProgress.getQuestState(QUEST));
        assertEquals(QuestState.ACTIVE, openProgress.getQuestState(QUEST));
    }

    private static StoryProgress activeProgress() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        return progress;
    }

    private static StoryEvent customEvent() {
        return new StoryEvent(StoryEventType.CUSTOM_EVENT, EVENT, 1, Map.of());
    }

    private static firefly520.fireflymc.story.progress.RequirementProgressKey key() {
        return new firefly520.fireflymc.story.progress.RequirementProgressKey(QUEST, "event", 1);
    }

    private static StoryEngine.TransactionContext<String> context(
        StoryEngine engine,
        StoryProgress progress,
        boolean sessionOpen
    ) {
        return new StoryEngine.TransactionContext<>(
            PLAYER_ID,
            "player",
            progress,
            new WorldStoryState(),
            snapshot(),
            QuestEvaluator.CurrentState.empty(),
            new NoEffects(),
            ignored -> {},
            () -> {},
            () -> true,
            sessionOpen
        );
    }

    private static StoryRuntimeSnapshot snapshot() {
        QuestDefinition quest = new QuestDefinition(
            QUEST,
            ResourceLocation.parse("fireflymc:gate-chapter"),
            Component.empty(),
            Component.empty(),
            List.of(),
            null,
            true,
            List.of(new RequirementDefinition(
                "event",
                1,
                new ConditionTypes.CustomEventCondition(EVENT, 1))),
            List.of());
        Map<ResourceLocation, QuestDefinition> quests = new HashMap<>();
        quests.put(QUEST, quest);
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.copyOf(quests),
            InterestIndex.fromDeclarations(Map.of(
                new PreparedStoryData.IndexDeclaration(
                    QUEST,
                    "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                    EVENT.toString()),
                Set.of(EVENT))),
            StoryGraph.fromQuests(quests),
            "session-gate-test");
    }

    private record NoEffects() implements ActionExecutor.Effects<String> {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean setPlayerVar(
            String player,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            Integer min,
            Integer max
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlockQuest(String player, ResourceLocation quest) {
            throw new UnsupportedOperationException();
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
        public void sendMessage(String player, net.minecraft.network.chat.Component message) {
            throw new UnsupportedOperationException();
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
