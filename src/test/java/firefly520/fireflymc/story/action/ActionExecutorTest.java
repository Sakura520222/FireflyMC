package firefly520.fireflymc.story.action;

import static firefly520.fireflymc.story.action.ActionTypes.ExecuteCommandAction;
import static firefly520.fireflymc.story.action.ActionTypes.GiveItemAction;
import static firefly520.fireflymc.story.action.ActionTypes.PlaySoundAction;
import static firefly520.fireflymc.story.action.ActionTypes.SendMessageAction;
import static firefly520.fireflymc.story.action.ActionTypes.SetFlagAction;
import static firefly520.fireflymc.story.action.ActionTypes.SetVarAction;
import static firefly520.fireflymc.story.action.ActionTypes.ShowTitleAction;
import static firefly520.fireflymc.story.action.ActionTypes.TeleportAction;
import static firefly520.fireflymc.story.action.ActionTypes.UnlockQuestAction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.engine.StorySignal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ActionExecutorTest {
    private static final ResourceLocation QUEST = ResourceLocation.parse("fireflymc:test/quest");
    private static final ResourceLocation ITEM = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation SOUND =
        ResourceLocation.parse("minecraft:block.note_block.pling");
    private static final ResourceLocation DIMENSION =
        ResourceLocation.parse("minecraft:the_nether");
    private static final String PLAYER = "quest-player";

    private final ActionExecutor executor = new ActionExecutor();

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void setFlagProducesFlagChangedSignalAndPreservesScope() {
        FakeEffects effects = new FakeEffects();

        List<StorySignal> signals = executor.execute(
            new SetFlagAction("firefly.met", true, StoryCondition.Scope.PLAYER),
            PLAYER,
            effects
        );

        assertEquals(
            List.of(new StorySignal.FlagChanged(
                "firefly.met",
                StoryCondition.Scope.PLAYER)),
            signals);
        assertEquals(new FlagCall(StoryCondition.Scope.PLAYER, "firefly.met", true),
            effects.flagCalls.getFirst());
    }

    @Test
    void unchangedFlagDoesNotEmitInvalidationSignal() {
        FakeEffects effects = new FakeEffects();
        effects.flagChanged = false;

        List<StorySignal> signals = executor.execute(
            new SetFlagAction("firefly.met", false, StoryCondition.Scope.WORLD),
            PLAYER,
            effects
        );

        assertTrue(signals.isEmpty());
        assertEquals(StoryCondition.Scope.WORLD, effects.flagCalls.getFirst().scope());
    }

    @Test
    void setVarAddStartsAtZeroThenClampsAfterEachOperation() {
        FakeEffects effects = new FakeEffects();

        List<StorySignal> first = executor.execute(
            new SetVarAction("firefly.reputation", SetVarAction.Operation.ADD, 7, 0, 10),
            PLAYER,
            effects
        );
        List<StorySignal> second = executor.execute(
            new SetVarAction("firefly.reputation", SetVarAction.Operation.ADD, 8, 0, 10),
            PLAYER,
            effects
        );

        assertEquals(
            List.of(new StorySignal.VarChanged(
                "firefly.reputation",
                StoryCondition.Scope.PLAYER)),
            first);
        assertEquals(
            List.of(new StorySignal.VarChanged(
                "firefly.reputation",
                StoryCondition.Scope.PLAYER)),
            second);
        assertEquals(10, effects.playerVars.get("firefly.reputation"));
    }

    @Test
    void unchangedSetVarDoesNotEmitInvalidationSignal() {
        FakeEffects effects = new FakeEffects();

        List<StorySignal> signals = executor.execute(
            new SetVarAction("firefly.reputation", SetVarAction.Operation.SET, 0, null, null),
            PLAYER,
            effects
        );

        assertTrue(signals.isEmpty());
        assertEquals(0, effects.playerVars.get("firefly.reputation"));
    }

    @Test
    void giveItemDropsOnlyRemainderAndNeverEmitsPickupSignal() {
        FakeEffects effects = new FakeEffects();
        ItemStack remainder = new ItemStack(Items.STONE, 3);
        effects.inventoryRemainder = remainder;

        List<StorySignal> signals = executor.execute(
            new GiveItemAction(ITEM, 10),
            PLAYER,
            effects
        );

        assertEquals(10, effects.inventoryAttempt.getCount());
        assertSame(remainder, effects.dropped);
        assertEquals(3, effects.dropped.getCount());
        assertEquals(List.of(new StorySignal.InventoryChanged()), signals);
        assertFalse(signals.stream().anyMatch(signal -> signal.kind().name().contains("PICKUP")));
    }

    @Test
    void fullInventoryDropsTheEntireRemainingStack() {
        FakeEffects effects = new FakeEffects();
        ItemStack remainder = new ItemStack(Items.STONE, 8);
        effects.inventoryRemainder = remainder;

        executor.execute(new GiveItemAction(ITEM, 8), PLAYER, effects);

        assertSame(remainder, effects.dropped);
        assertEquals(8, effects.dropped.getCount());
    }

    @Test
    void fullyInsertedItemDropsNothingButStillSignalsInventoryDirty() {
        FakeEffects effects = new FakeEffects();
        effects.inventoryRemainder = ItemStack.EMPTY;

        List<StorySignal> signals = executor.execute(
            new GiveItemAction(ITEM, 4),
            PLAYER,
            effects
        );

        assertNull(effects.dropped);
        assertEquals(List.of(new StorySignal.InventoryChanged()), signals);
    }

    @Test
    void unlockQuestDelegatesWithoutInventingAStateSignal() {
        FakeEffects effects = new FakeEffects();
        ResourceLocation unlocked = ResourceLocation.parse("fireflymc:test/next");

        List<StorySignal> signals = executor.execute(
            new UnlockQuestAction(unlocked),
            PLAYER,
            effects
        );

        assertEquals(List.of(unlocked), effects.unlockedQuests);
        assertTrue(signals.isEmpty());
    }

    @Test
    void playSoundDelegatesTheValidatedReference() {
        FakeEffects effects = new FakeEffects();

        List<StorySignal> signals = executor.execute(
            new PlaySoundAction(SOUND, 0.5F, 1.25F),
            PLAYER,
            effects
        );

        assertEquals(List.of(new SoundCall(SOUND, 0.5F, 1.25F)), effects.soundCalls);
        assertTrue(signals.isEmpty());
    }

    @Test
    void showTitleDelegatesComponentsAndTiming() {
        FakeEffects effects = new FakeEffects();
        Component title = Component.literal("Chapter One");
        Component subtitle = Component.literal("A new road");

        executor.execute(new ShowTitleAction(title, subtitle, 10, 40, 20), PLAYER, effects);

        assertEquals(1, effects.titleCalls.size());
        TitleCall call = effects.titleCalls.getFirst();
        assertSame(title, call.title());
        assertSame(subtitle, call.subtitle());
        assertEquals(10, call.fadeIn());
        assertEquals(40, call.stay());
        assertEquals(20, call.fadeOut());
    }

    @Test
    void showTitleWithoutSubtitleLeavesResetDecisionToProductionEffect() {
        FakeEffects effects = new FakeEffects();

        executor.execute(
            new ShowTitleAction(Component.literal("Chapter One"), null, 10, 40, 20),
            PLAYER,
            effects
        );

        assertNull(effects.titleCalls.getFirst().subtitle());
    }

    @Test
    void sendMessageDelegatesComponent() {
        FakeEffects effects = new FakeEffects();
        Component message = Component.literal("continue");

        executor.execute(new SendMessageAction(message), PLAYER, effects);

        assertSame(message, effects.messages.getFirst());
    }

    @Test
    void executeCommandPassesStaticTextAndTheQuestPlayerWithoutInterpolation() {
        FakeEffects effects = new FakeEffects();
        String command = "say @s ${context}";

        executor.execute(new ExecuteCommandAction(command), PLAYER, effects);

        assertEquals(PLAYER, effects.commandPlayer);
        assertEquals(command, effects.command);
    }

    @Test
    void commandPermissionContractIsFixedAtTwo() {
        assertEquals(2, ActionExecutor.COMMAND_PERMISSION_LEVEL);
    }

    @Test
    void teleportSignalsLocationChangedForStateReevaluation() {
        FakeEffects effects = new FakeEffects();

        List<StorySignal> signals = executor.execute(
            new TeleportAction(DIMENSION, 12.5D, 64.0D, -3.25D),
            PLAYER,
            effects
        );

        assertEquals(List.of(new TeleportCall(DIMENSION, 12.5D, 64.0D, -3.25D)),
            effects.teleportCalls);
        assertEquals(List.of(new StorySignal.LocationChanged()), signals);
    }

    @Test
    void executeAllRunsIndependentlyInDeclarationOrderAndKeepsEarlierAndLaterSignals() {
        FakeEffects effects = new FakeEffects();
        effects.failMessages.add("boom");

        List<StorySignal> signals = executor.executeAll(
            QUEST,
            List.of(
                new SetFlagAction("firefly.met", true, StoryCondition.Scope.PLAYER),
                new SendMessageAction(Component.literal("boom")),
                new SetFlagAction("firefly.continued", true, StoryCondition.Scope.PLAYER)
            ),
            PLAYER,
            effects
        );

        assertEquals(
            List.of(
                new StorySignal.FlagChanged("firefly.met", StoryCondition.Scope.PLAYER),
                new StorySignal.FlagChanged("firefly.continued", StoryCondition.Scope.PLAYER)
            ),
            signals
        );
        assertEquals(List.of("flag", "flag"), effects.executed);
    }

    @Test
    void failureLoggingContextDoesNotUsePlayerToString() {
        String logId = ActionExecutor.safePlayerId(new IpLikePlayer());

        assertFalse(logId.contains("203.0.113.7"));
        assertEquals("IpLikePlayer", logId);
    }

    @Test
    void executeRejectsOffThreadEffectsBeforeAnySideEffect() {
        FakeEffects effects = new FakeEffects();
        effects.serverThread = false;

        org.junit.jupiter.api.Assertions.assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> executor.executeAll(
                QUEST,
                List.of(new SetFlagAction("firefly.met", true, StoryCondition.Scope.PLAYER)),
                PLAYER,
                effects
            )
        );
        assertTrue(effects.executed.isEmpty());
    }

    private static final class IpLikePlayer {
        @Override
        public String toString() {
            return "203.0.113.7";
        }
    }

    private static final class FakeEffects implements ActionExecutor.Effects<String> {
        private final List<String> executed = new ArrayList<>();
        private final List<FlagCall> flagCalls = new ArrayList<>();
        private final Map<String, Integer> playerVars = new HashMap<>();
        private final List<ResourceLocation> unlockedQuests = new ArrayList<>();
        private final List<SoundCall> soundCalls = new ArrayList<>();
        private final List<TitleCall> titleCalls = new ArrayList<>();
        private final List<Component> messages = new ArrayList<>();
        private final List<TeleportCall> teleportCalls = new ArrayList<>();
        private final List<String> failMessages = new ArrayList<>();

        private boolean serverThread = true;
        private boolean flagChanged = true;
        private ItemStack inventoryRemainder = ItemStack.EMPTY;
        private ItemStack inventoryAttempt = ItemStack.EMPTY;
        private ItemStack dropped;
        private String commandPlayer;
        private String command;

        @Override
        public boolean isServerThread(String player) {
            return serverThread;
        }

        @Override
        public boolean setFlag(
            String player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            flagCalls.add(new FlagCall(scope, flag, value));
            executed.add("flag");
            return flagChanged;
        }

        @Override
        public boolean setPlayerVar(
            String player,
            String var,
            SetVarAction.Operation operation,
            int amount,
            Integer min,
            Integer max
        ) {
            int previous = playerVars.getOrDefault(var, 0);
            long raw = operation == SetVarAction.Operation.ADD ? previous + (long) amount : amount;
            int lower = min == null ? Integer.MIN_VALUE : min;
            int upper = max == null ? Integer.MAX_VALUE : max;
            int next = (int) Math.max(lower, Math.min(upper, raw));
            playerVars.put(var, next);
            executed.add("var");
            return next != previous;
        }

        @Override
        public void unlockQuest(String player, ResourceLocation quest) {
            unlockedQuests.add(quest);
            executed.add("unlock");
        }

        @Override
        public ItemStack addToInventory(String player, ResourceLocation item, int count) {
            inventoryAttempt = new ItemStack(Items.STONE, count);
            executed.add("item");
            return inventoryRemainder;
        }

        @Override
        public void dropAtFeet(String player, ItemStack remaining) {
            dropped = remaining;
            executed.add("drop");
        }

        @Override
        public void playSound(String player, ResourceLocation sound, float volume, float pitch) {
            soundCalls.add(new SoundCall(sound, volume, pitch));
            executed.add("sound");
        }

        @Override
        public void showTitle(
            String player,
            Component title,
            Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            titleCalls.add(new TitleCall(title, subtitle, fadeIn, stay, fadeOut));
            executed.add("title");
        }

        @Override
        public void sendMessage(String player, Component message) {
            if (failMessages.remove(message.getString())) {
                throw new IllegalStateException("synthetic action failure");
            }
            messages.add(message);
            executed.add("message");
        }

        @Override
        public void executeCommand(String player, String command) {
            commandPlayer = player;
            this.command = command;
            executed.add("command");
        }

        @Override
        public void teleport(
            String player,
            ResourceLocation dimension,
            double x,
            double y,
            double z
        ) {
            teleportCalls.add(new TeleportCall(dimension, x, y, z));
            executed.add("teleport");
        }
    }

    private record FlagCall(StoryCondition.Scope scope, String flag, boolean value) {}

    private record SoundCall(ResourceLocation sound, float volume, float pitch) {}

    private record TitleCall(
        Component title,
        Component subtitle,
        int fadeIn,
        int stay,
        int fadeOut
    ) {}

    private record TeleportCall(ResourceLocation dimension, double x, double y, double z) {}
}
