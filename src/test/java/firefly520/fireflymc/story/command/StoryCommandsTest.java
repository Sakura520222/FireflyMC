package firefly520.fireflymc.story.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import firefly520.fireflymc.story.StoryEngine;
import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.QuestEvaluator;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoryCommandsTest {
    private static final ResourceLocation QUEST =
        ResourceLocation.parse("fireflymc:forced_complete");
    private static final ResourceLocation UNKNOWN_QUEST =
        ResourceLocation.parse("fireflymc:unknown");
    private static final ResourceLocation EVENT =
        ResourceLocation.parse("fireflymc:custom");
    private static final UUID EXECUTOR = UUID.randomUUID();
    private static final UUID OTHER_EXECUTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void registersTheExactStoryTreeWithCommandPermissions() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryCommands.register(dispatcher, new StoryResetTokens(), disabledOperations());

        CommandNode<CommandSourceStack> firefly = dispatcher.getRoot().getChild("fireflymc");
        CommandNode<CommandSourceStack> story = firefly.getChild("story");
        assertNotNull(firefly);
        assertNotNull(story);
        assertEquals(Set.of("story"), firefly.getChildren().stream()
            .map(CommandNode::getName)
            .filter("story"::equals)
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
            Set.of("status", "setflag", "setvar", "unlock", "complete", "counter",
                "trigger", "reset"),
            childNames(story));
        assertEquals(2, permission(story));
        assertEquals(4, permission(story.getChild("reset")));
        for (String name : List.of("status", "setflag", "setvar", "unlock",
            "complete", "counter", "trigger")) {
            assertEquals(2, permission(story.getChild(name)), name);
        }
        assertEquals(
            Set.of("player", "world", "confirm"),
            childNames(story.getChild("reset")));
    }

    @Test
    void commandRegistrationHappensOnlyOncePerDispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryResetTokens tokens = new StoryResetTokens();
        StoryCommands.register(dispatcher, tokens, disabledOperations());
        StoryCommands.register(dispatcher, tokens, disabledOperations());

        assertEquals(1, dispatcher.getRoot().getChild("fireflymc").getChildren()
            .stream()
            .filter(node -> "story".equals(node.getName()))
            .count());
    }

    @Test
    void storyAttachesToAnExistingFireflyRootWithoutReplacingIt() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder
            .<CommandSourceStack>literal("fireflymc")
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<CommandSourceStack>literal("existing")));
        StoryCommands.register(dispatcher, new StoryResetTokens(), disabledOperations());

        CommandNode<CommandSourceStack> firefly = dispatcher.getRoot().getChild("fireflymc");
        assertEquals(Set.of("existing", "story"), childNames(firefly));
        assertEquals(1, firefly.getChildren().stream()
            .filter(node -> "story".equals(node.getName()))
            .count());
    }

    @Test
    void level2SourceCannotReachResetButCanUseOtherStoryCommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StoryCommands.register(dispatcher, new StoryResetTokens(), disabledOperations());
        RecordingSource commandSource = new RecordingSource();

        assertThrowsExactly(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> dispatcher.execute("fireflymc story reset world", source(2, commandSource)));
        commandSource.messages.clear();
        assertEquals(0, assertDoesNotThrow(() -> dispatcher.execute(
            "fireflymc story status", source(2, commandSource))));
        assertEquals(List.of("An online target player is required"), commandSource.messages);
    }

    @Test
    void commandOperationsRejectCrossThreadExecutionBeforeMutatingState() {
        RecordingOperations operations = new RecordingOperations();
        operations.onServerThread = false;
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(0, commands.setFlag(source(2, output),
            TARGET, StoryCondition.Scope.PLAYER, "flag", true));

        assertEquals(List.of("Story commands must run on the server thread"), output.messages);
        assertEquals(List.of(), operations.calls);
    }

    @Test
    void invalidStateIdsAndOfflineTargetsAreOperatorErrorsWithoutOperations() {
        RecordingOperations operations = new RecordingOperations();
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(0, commands.setFlag(source(2, output), TARGET,
            StoryCondition.Scope.PLAYER, "Bad Flag", true));
        assertEquals(0, commands.setVar(source(2, output), null,
            StoryCondition.Scope.PLAYER, "var",
            ActionTypes.SetVarAction.Operation.SET, 1));
        assertEquals(0, commands.complete(source(2, output), null, QUEST));

        assertEquals(List.of(
            "Invalid story state name: Bad Flag",
            "An online target player is required",
            "An online target player is required"), output.messages);
        assertEquals(List.of(), operations.calls);
    }

    @Test
    void unknownQuestAndRequirementReferencesAreReportedWithoutMutation() {
        RecordingOperations operations = new RecordingOperations();
        operations.quests.put(QUEST, quest(QUEST, List.of(), List.of()));
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(0, commands.unlock(source(2, output), TARGET, UNKNOWN_QUEST));
        assertEquals(0, commands.complete(source(2, output), TARGET, UNKNOWN_QUEST));
        assertEquals(0, commands.counter(source(2, output), TARGET, QUEST,
            "missing", ActionTypes.SetVarAction.Operation.ADD, 1));
        assertEquals(0, commands.trigger(source(2, output), TARGET,
            ResourceLocation.tryParse("bad id")));

        assertEquals(List.of(
            "Unknown story quest: fireflymc:unknown",
            "Unknown story quest: fireflymc:unknown",
            "Unknown story requirement: missing",
            "Invalid resource location: bad id"), output.messages);
        assertEquals(List.of(), operations.calls);
    }

    @Test
    void knownCustomEventTriggerIsRoutedToTheResolvedTarget() {
        RecordingOperations operations = new RecordingOperations();
        operations.eventExists = true;
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(1, commands.trigger(source(2, output), TARGET, EVENT));

        assertEquals(List.of("trigger=" + TARGET + ":" + EVENT), operations.calls);
    }

    @Test
    void activeStoryTransactionFailsCommandsClosedBeforeTokensOrOperations() {
        RecordingOperations operations = new RecordingOperations();
        operations.activeTransaction = true;
        StoryResetTokens tokens = new StoryResetTokens();
        StoryCommands commands = new StoryCommands(tokens, operations, () -> 10_000);
        RecordingSource output = new RecordingSource();

        assertEquals(0, commands.setFlag(source(2, output), TARGET,
            StoryCondition.Scope.PLAYER, "flag", true));
        assertEquals(0, commands.resetIssue(source(4, output), EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER));

        assertEquals(List.of(
            "Story command is inside a story transaction",
            "Story command is inside a story transaction"), output.messages);
        assertEquals(List.of(), operations.calls);
        assertEquals(null, tokens.issued("anything", 10_000));
    }

    @Test
    void actualCounterCommandPassesValidatedRequirementToProductionOperation() {
        RecordingOperations operations = new RecordingOperations();
        operations.quests.put(QUEST, quest(QUEST, List.of(new RequirementDefinition(
            "steps",
            1,
            new firefly520.fireflymc.story.condition.ConditionTypes.CustomEventCondition(
                EVENT,
                2))), List.of()));
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(1, commands.counter(source(2, output), TARGET, QUEST, "steps",
            ActionTypes.SetVarAction.Operation.ADD, 1));

        assertEquals(
            List.of("counter=" + TARGET + ":" + QUEST + ":steps:1"),
            operations.calls);
    }

    @Test
    void offlineProductionTargetGetsAFriendlyFailureWithoutStateChange() {
        RecordingOperations operations = new RecordingOperations();
        operations.quests.put(QUEST, quest(QUEST, List.of(), List.of()));
        operations.result = StoryEngine.CommandResult.TARGET_OFFLINE;
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(0, commands.unlock(source(2, output), TARGET, QUEST));

        assertEquals(List.of("Target player is offline"), output.messages);
        assertEquals(List.of("unlock=" + TARGET + ":" + QUEST), operations.calls);
    }

    @Test
    void triggerReportsSynchronousDeliveryDisabledSessionNoOpAndFailure() {
        RecordingOperations operations = new RecordingOperations();
        operations.eventExists = true;
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        operations.result = StoryEngine.CommandResult.DISABLED;
        assertEquals(0, commands.trigger(source(2, output), TARGET, EVENT));
        operations.result = StoryEngine.CommandResult.SESSION_CLOSED;
        assertEquals(0, commands.trigger(source(2, output), TARGET, EVENT));
        operations.result = StoryEngine.CommandResult.NO_OP;
        assertEquals(0, commands.trigger(source(2, output), TARGET, EVENT));
        operations.result = StoryEngine.CommandResult.FAILED;
        assertEquals(0, commands.trigger(source(2, output), TARGET, EVENT));

        assertEquals(List.of(
            "Story trigger is disabled",
            "Story trigger target has no open story session",
            "Story trigger had no listening quests",
            "Story trigger failed"), output.messages);
        assertEquals(4, operations.calls.size());
    }

    @Test
    void productionStatusFormatsCountersSatisfiedKeysAndTrackedQuestSafely() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.setFlag("player_flag", true);
        progress.setVar("player_var", 3, Integer.MIN_VALUE, Integer.MAX_VALUE);
        RequirementProgressKey counterKey = new RequirementProgressKey(QUEST, "steps", 2);
        progress.setCounter(counterKey, 4);
        progress.markSatisfied(counterKey);
        progress.setTrackedQuest(QUEST);
        WorldStoryState world = new WorldStoryState();
        world.setFlag("world_flag", false);
        world.setVar("world_var", -2, Integer.MIN_VALUE, Integer.MAX_VALUE);

        StoryCommands.Operations.StatusView view =
            StoryCommands.ProductionOperations.statusView(
                progress,
                world,
        new StoryRuntimeSnapshot(
            Map.of(),
            Map.of(),
            InterestIndex.empty(),
            firefly520.fireflymc.story.loader.StoryGraph.fromQuests(Map.of()),
            "revision-2"));

        assertEquals("revision-2", view.revision());
        String output = String.join("\n", view.lines());
        assertTrue(output.contains("counters={" + QUEST + ":steps:v2=4}"));
        assertTrue(output.contains("satisfied=[" + QUEST + ":steps:v2]"));
        assertTrue(output.contains("tracked=" + QUEST));
        assertFalse(output.contains("RequirementProgressKey"));
        view.lines().forEach(line -> {
            assertFalse(line.contains("\n"));
            assertFalse(line.contains("\r"));
        });
    }

    @Test
    void worldProductionMutationMarksWorldDirtyAndFansOutReconciliation() {
        List<String> worldDirty = new ArrayList<>();
        WorldStoryState world = new WorldStoryState();
        StoryCommands.TestProductionOperations production = StoryCommands.productionOperationsForTest(
            ignored -> worldDirty.add("world"),
            world
        );
        StoryCommands.Operations operations = production.operations();

        assertEquals(StoryEngine.CommandResult.PERFORMED, operations.setFlag(
            null, null, StoryCondition.Scope.WORLD, "gate", true));

        assertTrue(world.getFlag("gate"));
        assertEquals(List.of("world"), worldDirty);
        assertTrue(production.hasPendingReconcile().getAsBoolean());
    }

    @Test
    void productionWorldDirtyCallbackReceivesTheRealServerOnlyOnMutation() {
        record WorldDirty(Object server) {}
        List<WorldDirty> worldDirty = new ArrayList<>();
        WorldStoryState world = new WorldStoryState();
        world.setFlag("existing", false);
        Object server = new Object();
        StoryCommands.TestProductionOperations production =
            StoryCommands.productionOperationsForTest(
                (Object callback) -> worldDirty.add(new WorldDirty(callback)),
                world
            );

        assertEquals(StoryEngine.CommandResult.UNCHANGED,
            production.production().setWorldFlagForTest(
                server, world, "existing", false));
        assertEquals(List.of(), worldDirty);

        assertEquals(StoryEngine.CommandResult.PERFORMED,
            production.production().setWorldFlagForTest(server, world, "gate", true));

        assertTrue(world.getFlag("gate"));
        assertEquals(1, worldDirty.size());
        assertSame(server, worldDirty.getFirst().server());
        assertNotNull(worldDirty.getFirst().server());
    }

    @Test
    void nonPlayerExecutorIdentityIsSeparatedByName() {
        UUID console = StoryCommands.executorId(
            sourceNamed("Server", new RecordingSource()));
        UUID rcon = StoryCommands.executorId(
            sourceNamed("Rcon", new RecordingSource()));

        assertNotEquals(console, rcon);
        assertNotEquals(StoryResetTokens.WORLD_TARGET, console);
        assertNotEquals(StoryResetTokens.WORLD_TARGET, rcon);
    }

    @Test
    void statusUsesTheResolvedTargetAndSendsOnlyOperatorSafeState() {
        RecordingOperations operations = new RecordingOperations();
        operations.quests.put(QUEST, quest(QUEST, List.of(), List.of()));
        operations.status = new StoryCommands.Operations.StatusView(
            "revision", List.of("revision=revision", QUEST + "=ACTIVE"));
        StoryCommands commands = commands(operations);
        RecordingSource output = new RecordingSource();

        assertEquals(1, commands.status(source(2, output), TARGET));

        assertEquals(List.of("revision=revision", QUEST + "=ACTIVE"), output.messages);
        assertEquals(List.of("status=" + TARGET), operations.calls);
    }

    @Test
    void resetIssuesAConfirmationAndOnlyAStoredExactMatchExecutesIt() {
        StoryResetTokens tokens = new StoryResetTokens();
        RecordingOperations operations = new RecordingOperations();
        StoryCommands commands = new StoryCommands(tokens, operations, () -> 10_000);
        RecordingSource output = new RecordingSource();
        String token = tokens.issue(EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 10_000);

        assertEquals(1, commands.resetIssue(source(4, output), EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER));
        assertTrue(output.messages.getFirst().startsWith(
            "Run /fireflymc story reset confirm "));

        assertEquals(0, commands.resetConfirm(source(4, output), EXECUTOR,
            "not-a-token"));
        assertEquals(0, commands.resetConfirm(source(4, output), OTHER_EXECUTOR,
            token));
        assertEquals(List.of(), operations.calls);

        assertEquals(1, commands.resetConfirm(source(4, output), EXECUTOR, token));
        assertEquals(List.of("reset=" + TARGET + ":PLAYER"), operations.calls);
    }

    private static StoryCommands commands(StoryCommands.Operations operations) {
        return new StoryCommands(new StoryResetTokens(), operations, () -> 10_000);
    }

    private static StoryCommands.Operations disabledOperations() {
        return new RecordingOperations();
    }

    private static CommandSourceStack source(int level, RecordingSource commandSource) {
        return sourceNamed("operator", level, commandSource);
    }

    private static CommandSourceStack sourceNamed(
        String name,
        RecordingSource commandSource
    ) {
        return sourceNamed(name, 2, commandSource);
    }

    private static CommandSourceStack sourceNamed(
        String name,
        int level,
        RecordingSource commandSource
    ) {
        return new CommandSourceStack(
            commandSource,
            net.minecraft.world.phys.Vec3.ZERO,
            net.minecraft.world.phys.Vec2.ZERO,
            null,
            level,
            name,
            Component.literal(name),
            null,
            null);
    }

    private static int permission(CommandNode<CommandSourceStack> node) {
        RecordingSource low = new RecordingSource();
        RecordingSource high = new RecordingSource();
        for (int level = 0; level <= 4; level++) {
            if (node.getRequirement().test(source(level, low))) {
                return level;
            }
        }
        throw new AssertionError("Command is never permitted");
    }

    private static Set<String> childNames(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static QuestDefinition quest(
        ResourceLocation id,
        List<RequirementDefinition> requirements,
        List<? extends firefly520.fireflymc.story.action.StoryAction> onComplete
    ) {
        return new QuestDefinition(
            id,
            ResourceLocation.parse("fireflymc:chapter"),
            Component.empty(),
            Component.empty(),
            List.of(),
            null,
            false,
            List.copyOf(requirements),
            List.copyOf(onComplete));
    }

    private static final class RecordingSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static final class RecordingOperations implements StoryCommands.Operations {
        private final List<String> calls = new ArrayList<>();
        private final Map<ResourceLocation, QuestDefinition> quests = new HashMap<>();
        private boolean eventExists;
        private StatusView status = new StatusView("empty", List.of());
        private boolean onServerThread = true;
        private boolean activeTransaction;
        private StoryEngine.CommandResult result = StoryEngine.CommandResult.PERFORMED;

        @Override
        public boolean onServerThread(@Nullable MinecraftServer server) {
            return onServerThread;
        }

        @Override
        public boolean hasActiveTransaction(@Nullable MinecraftServer server) {
            return activeTransaction;
        }

        @Override
        public boolean questExists(@Nullable MinecraftServer server, ResourceLocation id) {
            return quests.containsKey(id);
        }

        @Override
        public boolean eventExists(@Nullable MinecraftServer server, ResourceLocation eventId) {
            return eventExists;
        }

        @Override
        public Optional<Integer> requirementVersion(
            @Nullable MinecraftServer server,
            ResourceLocation questId,
            String requirementId
        ) {
            QuestDefinition quest = quests.get(questId);
            if (quest == null) {
                return Optional.empty();
            }
            return quest.requirements().stream()
                .filter(requirement -> requirement.id().equals(requirementId))
                .map(RequirementDefinition::stateVersion)
                .findFirst();
        }

        @Override
        public StatusView status(@Nullable MinecraftServer server, UUID target) {
            calls.add("status=" + target);
            return status;
        }

        @Override
        public StoryEngine.CommandResult setFlag(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String name,
            boolean value
        ) {
            calls.add("setflag=" + target + ":" + scope + ":" + name + ":" + value);
            return result;
        }

        @Override
        public StoryEngine.CommandResult setVar(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String name,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        ) {
            calls.add("setvar=" + target + ":" + scope + ":" + name + ":" + operation + ":" + amount);
            return result;
        }

        @Override
        public StoryEngine.CommandResult unlock(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        ) {
            calls.add("unlock=" + target + ":" + quest);
            return result;
        }

        @Override
        public StoryEngine.CommandResult complete(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        ) {
            calls.add("complete=" + target + ":" + quest);
            return result;
        }

        @Override
        public StoryEngine.CommandResult counter(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest,
            String requirement,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        ) {
            calls.add("counter=" + target + ":" + quest + ":" + requirement + ":" + amount);
            return result;
        }

        @Override
        public StoryEngine.CommandResult trigger(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation event
        ) {
            calls.add("trigger=" + target + ":" + event);
            return result;
        }

        @Override
        public StoryEngine.CommandResult reset(
            @Nullable MinecraftServer server,
            UUID target,
            StoryResetTokens.Scope scope
        ) {
            calls.add("reset=" + target + ":" + scope);
            return result;
        }
    }

}
