package firefly520.fireflymc.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.engine.QuestEvaluator;
import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoryTriggersTest {
    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000008");
    private static final ResourceLocation EVENT_ID =
        ResourceLocation.parse("fireflymc:external_trigger");
    private static final ResourceLocation QUEST_ID =
        ResourceLocation.parse("fireflymc:persisted_quest");

    @Test
    void offThreadCustomEventIsScheduledBeforeDispatch() {
        List<Runnable> scheduled = new ArrayList<>();
        List<String> dispatched = new ArrayList<>();

        StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> false,
            () -> true,
            PLAYER_ID,
            ignored -> "original",
            (player, event) -> dispatched.add(player),
            "original",
            EVENT_ID,
            1);

        assertEquals(1, scheduled.size());
        assertEquals(List.of(), dispatched);

        scheduled.getFirst().run();

        assertEquals(List.of("original"), dispatched);
    }

    @Test
    void replacementPlayerIdentityIsRejectedWhenScheduledEventRuns() {
        List<Runnable> scheduled = new ArrayList<>();
        List<String> dispatched = new ArrayList<>();

        StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> false,
            () -> true,
            PLAYER_ID,
            ignored -> "replacement",
            (player, event) -> dispatched.add(player),
            "original",
            EVENT_ID,
            1);

        scheduled.getFirst().run();

        assertEquals(List.of(), dispatched);
    }

    @Test
    void invalidEventIdAndAmountFailBeforeScheduling() {
        List<Runnable> scheduled = new ArrayList<>();

        assertThrowsExactly(NullPointerException.class, () -> StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> false,
            () -> true,
            PLAYER_ID,
            ignored -> "original",
            (player, event) -> {},
            "original",
            null,
            1));
        assertThrowsExactly(IllegalArgumentException.class, () -> StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> false,
            () -> true,
            PLAYER_ID,
            ignored -> "original",
            (player, event) -> {},
            "original",
            EVENT_ID,
            -1));

        assertEquals(List.of(), scheduled);
    }

    @Test
    void stoppedServerIsRejectedBeforeScheduling() {
        List<Runnable> scheduled = new ArrayList<>();

        StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> true,
            () -> true,
            PLAYER_ID,
            ignored -> "original",
            (player, event) -> {},
            "original",
            EVENT_ID,
            1);

        assertEquals(List.of(), scheduled);
    }

    @Test
    void serverStoppingBeforeScheduledExecutionSkipsLookupAndDispatch() {
        List<Runnable> scheduled = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger lookups = new AtomicInteger();

        StoryTriggers.fireCustomEvent(
            scheduled::add,
            stopped::get,
            () -> true,
            PLAYER_ID,
            id -> {
                lookups.incrementAndGet();
                return "original";
            },
            (player, event) -> {},
            "original",
            EVENT_ID,
            1);
        stopped.set(true);
        scheduled.getFirst().run();

        assertEquals(0, lookups.get());
    }

    @Test
    void foreignThreadExecutionSkipsLookupAndDispatch() {
        List<Runnable> scheduled = new ArrayList<>();
        AtomicInteger lookups = new AtomicInteger();

        StoryTriggers.fireCustomEvent(
            scheduled::add,
            () -> false,
            () -> false,
            PLAYER_ID,
            id -> {
                lookups.incrementAndGet();
                return "original";
            },
            (player, event) -> {},
            "original",
            EVENT_ID,
            1);
        scheduled.getFirst().run();

        assertEquals(0, lookups.get());
    }

    @Test
    void serverStoppingClearsPendingRuntimeStateButKeepsProgress() throws Exception {
        StoryEngine engine = new StoryEngine();
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST_ID);
        setActiveTransactionPlayer(engine, PLAYER_ID);
        StoryEngine.TransactionContext<String> context = new StoryEngine.TransactionContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000009"),
            "other-player",
            progress,
            new WorldStoryState(),
            StoryRuntimeSnapshot.empty(),
            QuestEvaluator.CurrentState.empty(),
            new NoOpEffects(),
            ignored -> {},
            () -> {},
            () -> true);

        engine.dispatch(context, customEvent(1));
        assertEquals(1, engine.pendingDispatchCount());

        engine.onServerStopping(() -> true);

        assertEquals(0, engine.pendingDispatchCount());
        assertFalse(engine.hasPendingReconcile());
        assertEquals(QuestState.ACTIVE, progress.getQuestState(QUEST_ID));
    }

    @Test
    void reenablingStorySchedulesOneReconcile() {
        StoryEngine engine = new StoryEngine();
        engine.setStoryEnabled(false);
        assertFalse(engine.hasPendingReconcile());

        engine.setStoryEnabled(true);

        assertTrue(engine.hasPendingReconcile());
    }

    @Test
    void secondRegistrationFailsClosedWithoutTouchingTheEventBus() throws Exception {
        Field field = StoryEngine.class.getDeclaredField("REGISTERED");
        field.setAccessible(true);
        AtomicBoolean registrationFlag = (AtomicBoolean) field.get(null);
        boolean previous = registrationFlag.get();
        registrationFlag.set(true);
        AtomicInteger busCalls = new AtomicInteger();
        IEventBus bus = (IEventBus) Proxy.newProxyInstance(
            IEventBus.class.getClassLoader(),
            new Class<?>[] {IEventBus.class},
            (proxy, method, arguments) -> {
                busCalls.incrementAndGet();
                return null;
            });

        try {
            assertThrowsExactly(IllegalStateException.class, () -> StoryEngine.register(bus));
        } finally {
            registrationFlag.set(previous);
        }

        assertEquals(0, busCalls.get());
    }

    private static StoryEvent customEvent(int amount) {
        return new StoryEvent(StoryEventType.CUSTOM_EVENT, EVENT_ID, amount, Map.of());
    }

    private static void setActiveTransactionPlayer(
        StoryEngine engine,
        UUID playerId
    ) throws ReflectiveOperationException {
        Field field = StoryEngine.class.getDeclaredField("activeTransactionPlayer");
        field.setAccessible(true);
        field.set(engine, playerId);
    }

    private static final class NoOpEffects implements ActionExecutor.Effects<String> {
        @Override
        public boolean isServerThread(String player) {
            return true;
        }

        @Override
        public boolean setFlag(
            String player,
            firefly520.fireflymc.story.condition.StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            return false;
        }

        @Override
        public boolean setPlayerVar(
            String player,
            String var,
            firefly520.fireflymc.story.action.ActionTypes.SetVarAction.Operation operation,
            int amount,
            @Nullable Integer min,
            @Nullable Integer max
        ) {
            return false;
        }

        @Override
        public void unlockQuest(String player, ResourceLocation quest) {
        }

        @Override
        public ItemStack addToInventory(String player, ResourceLocation item, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public void dropAtFeet(String player, ItemStack remaining) {
        }

        @Override
        public void playSound(
            String player,
            ResourceLocation sound,
            float volume,
            float pitch
        ) {
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
        }

        @Override
        public void sendMessage(String player, Component message) {
        }

        @Override
        public void executeCommand(String player, String command) {
        }

        @Override
        public void teleport(
            String player,
            ResourceLocation dimension,
            double x,
            double y,
            double z
        ) {
        }
    }
}
