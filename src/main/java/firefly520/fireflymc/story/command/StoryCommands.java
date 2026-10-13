package firefly520.fireflymc.story.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.story.StoryEngine;
import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import firefly520.fireflymc.story.sync.StorySyncService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/** Server-only operator command tree for the approved Story Engine v1 surface. */
@EventBusSubscriber(modid = FireflyMCMod.MODID)
public final class StoryCommands {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final StoryResetTokens TOKENS = new StoryResetTokens();
    private static final StoryCommands PRODUCTION = new StoryCommands(
        TOKENS,
        ProductionOperations.INSTANCE,
        System::currentTimeMillis
    );

    private final StoryResetTokens tokens;
    private final Operations operations;
    private final java.util.function.LongSupplier clock;

    StoryCommands(
        StoryResetTokens tokens,
        Operations operations,
        java.util.function.LongSupplier clock
    ) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public interface Operations {
        record StatusView(String revision, List<String> lines) {
            public StatusView {
                Objects.requireNonNull(revision, "revision");
                lines = List.copyOf(lines);
            }
        }

        boolean onServerThread(@Nullable MinecraftServer server);

        boolean hasActiveTransaction(@Nullable MinecraftServer server);

        boolean questExists(@Nullable MinecraftServer server, ResourceLocation questId);

        boolean eventExists(@Nullable MinecraftServer server, ResourceLocation eventId);

        Optional<Integer> requirementVersion(
            @Nullable MinecraftServer server,
            ResourceLocation questId,
            String requirementId
        );

        StatusView status(@Nullable MinecraftServer server, UUID target);

        StoryEngine.CommandResult setFlag(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        );

        StoryEngine.CommandResult setVar(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        );

        StoryEngine.CommandResult unlock(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        );

        StoryEngine.CommandResult complete(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        );

        StoryEngine.CommandResult counter(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest,
            String requirement,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        );

        StoryEngine.CommandResult trigger(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation event
        );

        StoryEngine.CommandResult reset(
            @Nullable MinecraftServer server,
            UUID target,
            StoryResetTokens.Scope scope
        );
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        PRODUCTION.register(event.getDispatcher());
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        TOKENS.clear();
    }

    static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        StoryResetTokens tokens,
        Operations operations
    ) {
        new StoryCommands(tokens, operations, System::currentTimeMillis)
            .register(dispatcher);
    }

    record TestProductionOperations(
        Operations operations,
        ProductionOperations production,
        java.util.function.BooleanSupplier hasPendingReconcile
    ) {}

    static TestProductionOperations productionOperationsForTest(
        java.util.function.Consumer<Object> markWorldDirty,
        WorldStoryState world
    ) {
        StoryEngine.CommandApi api = StoryEngine.CommandApi.of(
            StoryEngine.CommandApi.production().isolated());
        ProductionOperations production =
            new ProductionOperations(api, markWorldDirty, world);
        return new TestProductionOperations(
            production,
            production,
            api::hasPendingReconcile
        );
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        LiteralArgumentBuilder<CommandSourceStack> story = Commands.literal("story")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("status")
                .requires(source -> source.hasPermission(2))
                .executes(context -> status(context.getSource(), null))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> status(context.getSource(),
                        EntityArgument.getPlayer(context, "player").getUUID()))))
            .then(Commands.literal("setflag")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("player")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(flagArguments(true))))
                .then(Commands.literal("world")
                    .then(flagArguments(false))))
            .then(Commands.literal("setvar")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("player")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(varArguments(true))))
                .then(Commands.literal("world")
                    .then(varArguments(false))))
            .then(Commands.literal("unlock")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("quest", StringArgumentType.string())
                    .executes(context -> unlock(context, null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> unlock(context,
                            EntityArgument.getPlayer(context, "player").getUUID())))))
            .then(Commands.literal("complete")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("quest", StringArgumentType.string())
                    .executes(context -> complete(context, null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> complete(context,
                            EntityArgument.getPlayer(context, "player").getUUID())))))
            .then(Commands.literal("counter")
                .requires(source -> source.hasPermission(2))
                .then(counterArguments()))
            .then(Commands.literal("trigger")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("event", StringArgumentType.string())
                    .executes(context -> trigger(context, null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> trigger(context,
                            EntityArgument.getPlayer(context, "player").getUUID())))))
            .then(Commands.literal("reset")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("player")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> resetIssue(
                            context,
                            EntityArgument.getPlayer(context, "player").getUUID(),
                            StoryResetTokens.Scope.PLAYER))))
                .then(Commands.literal("world")
                    .executes(context -> resetIssue(
                        context,
                        StoryResetTokens.WORLD_TARGET,
                        StoryResetTokens.Scope.WORLD)))
                .then(Commands.literal("confirm")
                    .then(Commands.argument("token", StringArgumentType.word())
                        .executes(context -> resetConfirm(
                            context,
                            StringArgumentType.getString(context, "token"))))));

        LiteralArgumentBuilder<CommandSourceStack> firefly = Commands.literal("fireflymc")
            .then(story);
        var existingFirefly = dispatcher.getRoot().getChild("fireflymc");
        if (existingFirefly == null) {
            dispatcher.register(firefly);
            return;
        }
        if (existingFirefly.getChild("story") == null) {
            existingFirefly.addChild(story.build());
        }
    }

    private ArgumentBuilder<CommandSourceStack, ?> flagArguments(boolean player) {
        return Commands.argument("flag", StringArgumentType.string())
            .then(valueLiteral(true, player, true))
            .then(valueLiteral(true, player, false));
    }

    private LiteralArgumentBuilder<CommandSourceStack> valueLiteral(
        boolean flag,
        boolean player,
        boolean value
    ) {
        return Commands.literal(Boolean.toString(value))
            .executes(context -> flag
                ? this.setFlag(
                    context.getSource(),
                    EntityArgument.getPlayer(context, "player").getUUID(),
                    StoryCondition.Scope.PLAYER,
                    StringArgumentType.getString(context, "flag"),
                    value)
                : this.setFlag(
                    context.getSource(),
                    null,
                    StoryCondition.Scope.WORLD,
                    StringArgumentType.getString(context, "flag"),
                    value));
    }

    private ArgumentBuilder<CommandSourceStack, ?> varArguments(boolean player) {
        return Commands.argument("variable", StringArgumentType.string())
            .then(operationLiteral("set")
                .then(amount(player, ActionTypes.SetVarAction.Operation.SET)))
            .then(operationLiteral("add")
                .then(amount(player, ActionTypes.SetVarAction.Operation.ADD)));
    }

    private ArgumentBuilder<CommandSourceStack, ?> amount(
        boolean player,
        ActionTypes.SetVarAction.Operation operation
    ) {
        return Commands.argument("amount", IntegerArgumentType.integer())
            .executes(context -> this.setVar(
                context.getSource(),
                player ? EntityArgument.getPlayer(context, "player").getUUID() : null,
                player ? StoryCondition.Scope.PLAYER : StoryCondition.Scope.WORLD,
                StringArgumentType.getString(context, "variable"),
                operation,
                IntegerArgumentType.getInteger(context, "amount")));
    }

    private ArgumentBuilder<CommandSourceStack, ?> amountAndTarget(
        java.util.function.BiFunction<
            CommandContext<CommandSourceStack>,
            UUID,
            Integer
        > handler
    ) {
        return Commands.argument("amount", IntegerArgumentType.integer())
            .executes(context -> handler.apply(context, null))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(context -> handler.apply(
                    context,
                    EntityArgument.getPlayer(context, "player").getUUID())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> operationLiteral(String name) {
        return Commands.literal(name);
    }

    private ArgumentBuilder<CommandSourceStack, ?> counterArguments() {
        ArgumentBuilder<CommandSourceStack, ?> set = operationLiteral("set")
            .then(amountAndTarget((context, target) ->
                counter(context, target, ActionTypes.SetVarAction.Operation.SET)));
        ArgumentBuilder<CommandSourceStack, ?> add = operationLiteral("add")
            .then(amountAndTarget((context, target) ->
                counter(context, target, ActionTypes.SetVarAction.Operation.ADD)));
        return Commands.argument("quest", StringArgumentType.string())
            .then(Commands.argument("requirement", StringArgumentType.string())
                .then(set)
                .then(add));
    }

    private static int optionalTarget(
        CommandContext<CommandSourceStack> context,
        String name,
        java.util.function.BiFunction<CommandSourceStack, UUID, Integer> handler
    ) {
        try {
            boolean hasPlayerArgument = context.getNodes().stream()
                .anyMatch(node -> name.equals(node.getNode().getName()));
            UUID target = hasPlayerArgument
                ? EntityArgument.getPlayer(context, name).getUUID()
                : context.getSource().getPlayerOrException().getUUID();
            return handler.apply(context.getSource(), target);
        } catch (CommandSyntaxException exception) {
            return fail(context.getSource(), "An online target player is required");
        }
    }

    int status(CommandSourceStack source, @Nullable UUID targetId) {
        if (!hasAccess(source)) {
            return 0;
        }
        UUID target = targetId;
        if (target == null) {
            try {
                target = source.getPlayerOrException().getUUID();
            } catch (CommandSyntaxException exception) {
                return fail(source, "An online target player is required");
            }
        }
        if (source.getServer() != null
            && source.getServer().getPlayerList().getPlayer(target) == null) {
            return fail(source, "Target player is offline");
        }
        try {
            Operations.StatusView view = operations.status(source.getServer(), target);
            for (String line : view.lines()) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "status", exception);
        }
    }

    int setFlag(
        CommandSourceStack source,
        @Nullable UUID target,
        StoryCondition.Scope scope,
        String name,
        boolean value
    ) {
        if (!hasAccess(source)
            || (scope == StoryCondition.Scope.PLAYER && !requireTarget(source, target))) {
            return 0;
        }
        if (!StoryCondition.isValidStateName(name)) {
            return fail(source, "Invalid story state name: " + name);
        }
        try {
            return report(
                source,
                operations.setFlag(source.getServer(), target, scope, name, value)
            );
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "setflag", exception);
        }
    }

    int setVar(
        CommandSourceStack source,
        @Nullable UUID target,
        StoryCondition.Scope scope,
        String name,
        ActionTypes.SetVarAction.Operation operation,
        int amount
    ) {
        if (!hasAccess(source)
            || (scope == StoryCondition.Scope.PLAYER && !requireTarget(source, target))) {
            return 0;
        }
        if (!StoryCondition.isValidStateName(name)) {
            return fail(source, "Invalid story state name: " + name);
        }
        try {
            return report(source, operations.setVar(
                source.getServer(), target, scope, name, operation, amount));
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "setvar", exception);
        }
    }

    private int unlock(CommandContext<CommandSourceStack> context, @Nullable UUID target) {
        return optionalTarget(context, "player", (source, resolved) ->
            unlock(source, resolved, parseId(StringArgumentType.getString(context, "quest"))));
    }

    int unlock(CommandSourceStack source, @Nullable UUID target, @Nullable ResourceLocation quest) {
        if (!hasAccess(source) || !requireTarget(source, target)) {
            return 0;
        }
        if (quest == null) {
            return fail(source, "Invalid resource location");
        }
        if (!operations.questExists(source.getServer(), quest)) {
            return fail(source, "Unknown story quest: " + quest);
        }
        try {
            return report(source, operations.unlock(source.getServer(), target, quest));
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "unlock", exception);
        }
    }

    private int complete(CommandContext<CommandSourceStack> context, @Nullable UUID target) {
        return optionalTarget(context, "player", (source, resolved) ->
            complete(source, resolved, parseId(StringArgumentType.getString(context, "quest"))));
    }

    int complete(CommandSourceStack source, @Nullable UUID target, @Nullable ResourceLocation quest) {
        if (!hasAccess(source) || !requireTarget(source, target)) {
            return 0;
        }
        if (quest == null) {
            return fail(source, "Invalid resource location");
        }
        if (!operations.questExists(source.getServer(), quest)) {
            return fail(source, "Unknown story quest: " + quest);
        }
        try {
            return report(source, operations.complete(source.getServer(), target, quest));
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "complete", exception);
        }
    }

    private int counter(
        CommandContext<CommandSourceStack> context,
        @Nullable UUID target,
        ActionTypes.SetVarAction.Operation operation
    ) {
        return optionalTarget(context, "player", (source, resolved) -> {
            ResourceLocation quest = parseId(StringArgumentType.getString(context, "quest"));
            String requirement = StringArgumentType.getString(context, "requirement");
            int amount = IntegerArgumentType.getInteger(context, "amount");
            return counter(
                source,
                resolved,
                quest,
                requirement,
                operation,
                amount
            );
        });
    }

    int counter(
        CommandSourceStack source,
        @Nullable UUID target,
        @Nullable ResourceLocation quest,
        String requirement,
        ActionTypes.SetVarAction.Operation operation,
        int amount
    ) {
        if (!hasAccess(source) || !requireTarget(source, target)) {
            return 0;
        }
        if (quest == null) {
            return fail(source, "Invalid resource location");
        }
        if (!operations.questExists(source.getServer(), quest)) {
            return fail(source, "Unknown story quest: " + quest);
        }
        if (operations.requirementVersion(source.getServer(), quest, requirement).isEmpty()) {
            return fail(source, "Unknown story requirement: " + requirement);
        }
        try {
            return report(source, operations.counter(
                source.getServer(), target, quest, requirement, operation, amount));
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "counter", exception);
        }
    }

    private int trigger(CommandContext<CommandSourceStack> context, @Nullable UUID target) {
        return optionalTarget(context, "player", (source, resolved) ->
            trigger(source, resolved, parseId(StringArgumentType.getString(context, "event"))));
    }

    int trigger(
        CommandSourceStack source,
        @Nullable UUID target,
        @Nullable ResourceLocation event
    ) {
        if (!hasAccess(source) || !requireTarget(source, target)) {
            return 0;
        }
        if (event == null) {
            return fail(source, "Invalid resource location: bad id");
        }
        if (!operations.eventExists(source.getServer(), event)) {
            return fail(source, "Unknown story event: " + event);
        }
        try {
            return reportTrigger(
                source,
                operations.trigger(source.getServer(), target, event)
            );
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "trigger", exception);
        }
    }

    private int resetIssue(
        CommandContext<CommandSourceStack> context,
        UUID target,
        StoryResetTokens.Scope scope
    ) {
        CommandSourceStack source = context.getSource();
        UUID executor = executorId(source);
        return resetIssue(source, executor, target, scope);
    }

    int resetIssue(
        CommandSourceStack source,
        UUID executor,
        UUID target,
        StoryResetTokens.Scope scope
    ) {
        if (!hasAccess(source) || !source.hasPermission(4)) {
            return 0;
        }
        try {
            String token = tokens.issue(executor, target, scope, clock.getAsLong());
            source.sendSuccess(() -> Component.literal(
                "Run /fireflymc story reset confirm " + token), false);
            return 1;
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "reset.issue", exception);
        }
    }

    private int resetConfirm(CommandContext<CommandSourceStack> context, String token) {
        return resetConfirm(context.getSource(), executorId(context.getSource()), token);
    }

    int resetConfirm(CommandSourceStack source, UUID executor, String token) {
        if (!hasAccess(source) || !source.hasPermission(4)) {
            return 0;
        }
        long now = clock.getAsLong();
        StoryResetTokens.IssuedToken issued = tokens.issued(token, now);
        if (issued == null
            || now >= issued.expiresAt()
            || !issued.executorId().equals(executor)) {
            return fail(source, "Reset confirmation expired or invalid");
        }
        if (issued.scope() == StoryResetTokens.Scope.PLAYER
            && source.getServer() != null
            && source.getServer().getPlayerList().getPlayer(issued.targetId()) == null) {
            return fail(source, "Target player is offline");
        }
        if (!tokens.consume(
            token,
            executor,
            issued.targetId(),
            issued.scope(),
            now
        )) {
            return fail(source, "Reset confirmation expired or invalid");
        }
        try {
            return report(source, operations.reset(
                source.getServer(), issued.targetId(), issued.scope()));
        } catch (RuntimeException exception) {
            return runtimeFailure(source, "reset.confirm", exception);
        }
    }

    private boolean hasAccess(CommandSourceStack source) {
        if (!source.hasPermission(2)
            || !operations.onServerThread(source.getServer())) {
            fail(source, "Story commands must run on the server thread");
            return false;
        }
        if (operations.hasActiveTransaction(source.getServer())) {
            fail(source, "Story command is inside a story transaction");
            return false;
        }
        return true;
    }

    private boolean requireTarget(CommandSourceStack source, @Nullable UUID target) {
        if (target == null) {
            fail(source, "An online target player is required");
            return false;
        }
        return true;
    }

    private int report(CommandSourceStack source, StoryEngine.CommandResult result) {
        return switch (result) {
            case PERFORMED -> {
                source.sendSuccess(() -> Component.literal("Story command completed"), false);
                yield 1;
            }
            case UNCHANGED -> {
                source.sendSuccess(() -> Component.literal("Story state was unchanged"), false);
                yield 1;
            }
            case ALREADY_COMPLETED -> {
                source.sendSuccess(() -> Component.literal("Quest is already completed"), false);
                yield 1;
            }
            case LOCKED -> fail(source, "Quest is not ACTIVE");
            case UNKNOWN -> fail(source, "Unknown story quest or requirement");
            case UNKNOWN_EVENT -> fail(source, "Unknown story event");
            case TARGET_OFFLINE -> fail(source, "Target player is offline");
            case NESTED_TRANSACTION -> fail(
                source, "Story command is inside a story transaction");
            case DISABLED -> fail(source, "Story commands are disabled");
            case SESSION_CLOSED -> fail(
                source, "Story target has no open story session");
            case FAILED -> fail(source, "Story command failed");
            case NO_OP -> fail(source, "Story command had no matching state");
        };
    }

    private int reportTrigger(CommandSourceStack source, StoryEngine.CommandResult result) {
        return switch (result) {
            case PERFORMED -> {
                source.sendSuccess(
                    () -> Component.literal("Story trigger delivered"), false);
                yield 1;
            }
            case NO_OP -> fail(source, "Story trigger had no listening quests");
            case DISABLED -> fail(source, "Story trigger is disabled");
            case SESSION_CLOSED -> fail(
                source, "Story trigger target has no open story session");
            case FAILED -> fail(source, "Story trigger failed");
            case NESTED_TRANSACTION -> fail(
                source, "Story command is inside a story transaction");
            case TARGET_OFFLINE -> fail(source, "Target player is offline");
            case UNKNOWN, UNKNOWN_EVENT -> fail(source, "Unknown story event");
            case UNCHANGED, ALREADY_COMPLETED, LOCKED -> fail(
                source, "Story trigger had no matching active quest");
        };
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private int runtimeFailure(
        CommandSourceStack source,
        String operation,
        RuntimeException exception
    ) {
        LOGGER.error(
            "Story command failed; operation={} sourceName={} targetPresent={}",
            operation,
            source.getTextName(),
            source.getEntity() != null,
            exception
        );
        return fail(source, "Story command failed");
    }

    static UUID executorId(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return player.getUUID();
        }
        String identity = "fireflymc:non-player:" + source.getTextName();
        return UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Nullable
    private static ResourceLocation parseId(String value) {
        return value == null ? null : ResourceLocation.tryParse(value);
    }

    static final class ProductionOperations implements Operations {
        private static final ProductionOperations INSTANCE = new ProductionOperations();
        private final StoryEngine.CommandApi engine;
        private final java.util.function.Consumer<Object> markWorldDirty;
        @Nullable
        private final WorldStoryState injectedWorld;

        private ProductionOperations() {
            this(
                StoryEngine.CommandApi.production(),
                serverIdentity -> StorySyncService.instance()
                    .markWorldDirty((MinecraftServer) serverIdentity),
                null
            );
        }

        ProductionOperations(
            StoryEngine.CommandApi engine,
            java.util.function.Consumer<MinecraftServer> markWorldDirty
        ) {
            this(
                engine,
                serverIdentity -> markWorldDirty.accept((MinecraftServer) serverIdentity),
                null
            );
        }

        ProductionOperations(
            StoryEngine.CommandApi engine,
            java.util.function.Consumer<Object> markWorldDirty,
            @Nullable WorldStoryState injectedWorld
        ) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.markWorldDirty = Objects.requireNonNull(markWorldDirty, "markWorldDirty");
            this.injectedWorld = injectedWorld;
        }

        @Override
        public boolean onServerThread(@Nullable MinecraftServer server) {
            return server != null && server.isSameThread();
        }

        @Override
        public boolean hasActiveTransaction(@Nullable MinecraftServer server) {
            return engine.hasActiveTransaction();
        }

        @Override
        public boolean questExists(@Nullable MinecraftServer server, ResourceLocation questId) {
            return engine.snapshot()
                .quests()
                .containsKey(questId);
        }

        @Override
        public boolean eventExists(@Nullable MinecraftServer server, ResourceLocation eventId) {
            return engine.snapshot()
                .interestIndex()
                .keys(StoryEventType.CUSTOM_EVENT)
                .contains(eventId);
        }

        @Override
        public Optional<Integer> requirementVersion(
            @Nullable MinecraftServer server,
            ResourceLocation questId,
            String requirementId
        ) {
            QuestDefinition quest = engine.snapshot()
                .quests()
                .get(questId);
            if (quest == null) {
                return Optional.empty();
            }
            return quest.requirements().stream()
                .filter(requirement -> requirement.id().equals(requirementId))
                .map(firefly520.fireflymc.story.definition.RequirementDefinition::stateVersion)
                .findFirst();
        }

        @Override
        public StatusView status(@Nullable MinecraftServer server, UUID target) {
            ServerPlayer player = server == null || target == null
                ? null
                : server.getPlayerList().getPlayer(target);
            if (player == null) {
                throw new IllegalArgumentException("Target player is offline");
            }
            StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
            WorldStoryState world = WorldStoryState.get(player.server);
            return statusView(progress, world, engine.snapshot());
        }

        static StatusView statusView(
            StoryProgress progress,
            WorldStoryState world,
            StoryRuntimeSnapshot snapshot
        ) {
            List<String> lines = new ArrayList<>();
            lines.add("revision=" + snapshot.revision());
            List<String> quests = snapshot.quests().keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .map(quest -> quest + "=" + progress.getQuestState(quest))
                .toList();
            lines.add("quests=[" + String.join(", ", quests) + "]");
            lines.add("player_flags=" + sorted(progress.flags()));
            lines.add("player_vars=" + sorted(progress.vars()));
            lines.add("counters=" + sortedCounters(progress.counters()));
            lines.add("satisfied=" + sortedKeys(progress.satisfied()));
            lines.add("tracked=" + progress.trackedQuest().map(Object::toString).orElse("none"));
            lines.add("world_flags=" + sorted(world.flags()));
            lines.add("world_vars=" + sorted(world.vars()));
            return new StatusView(snapshot.revision(), lines);
        }

        @Override
        public StoryEngine.CommandResult setFlag(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            if (scope == StoryCondition.Scope.PLAYER) {
                ServerPlayer player = onlinePlayer(server, target);
                if (player == null) {
                    return StoryEngine.CommandResult.TARGET_OFFLINE;
                }
                return engine.setFlag(context(player), scope, flag, value);
            }
            StoryEngine.CommandResult result = engine.setFlag(
                worldContext(server, world(server)), scope, flag, value);
            if (result != StoryEngine.CommandResult.NESTED_TRANSACTION) {
                engine.scheduleReconcile();
            }
            return result;
        }

        @Override
        public StoryEngine.CommandResult setVar(
            @Nullable MinecraftServer server,
            @Nullable UUID target,
            StoryCondition.Scope scope,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        ) {
            if (scope == StoryCondition.Scope.PLAYER) {
                ServerPlayer player = onlinePlayer(server, target);
                if (player == null) {
                    return StoryEngine.CommandResult.TARGET_OFFLINE;
                }
                return engine.setVar(
                    context(player),
                    scope,
                    variable,
                    operation,
                    amount);
            }
            StoryEngine.CommandResult result = engine.setVar(
                worldContext(server, world(server)),
                scope,
                variable,
                operation,
                amount);
            if (result != StoryEngine.CommandResult.NESTED_TRANSACTION) {
                engine.scheduleReconcile();
            }
            return result;
        }

        @Override
        public StoryEngine.CommandResult unlock(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        ) {
            ServerPlayer player = onlinePlayer(server, target);
            if (player == null) {
                return StoryEngine.CommandResult.TARGET_OFFLINE;
            }
            return engine.unlock(context(player), quest);
        }

        @Override
        public StoryEngine.CommandResult complete(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation quest
        ) {
            ServerPlayer player = onlinePlayer(server, target);
            if (player == null) {
                return StoryEngine.CommandResult.TARGET_OFFLINE;
            }
            return engine.complete(context(player), quest);
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
            ServerPlayer player = onlinePlayer(server, target);
            if (player == null) {
                return StoryEngine.CommandResult.TARGET_OFFLINE;
            }
            return engine.counter(
                context(player),
                quest,
                requirement,
                operation,
                amount);
        }

        @Override
        public StoryEngine.CommandResult trigger(
            @Nullable MinecraftServer server,
            UUID target,
            ResourceLocation event
        ) {
            ServerPlayer player = onlinePlayer(server, target);
            if (player == null) {
                return StoryEngine.CommandResult.TARGET_OFFLINE;
            }
            return engine.trigger(context(player), event);
        }

        @Override
        public StoryEngine.CommandResult reset(
            @Nullable MinecraftServer server,
            UUID target,
            StoryResetTokens.Scope scope
        ) {
            if (scope == StoryResetTokens.Scope.PLAYER) {
                ServerPlayer player = onlinePlayer(server, target);
                if (player == null) {
                    return StoryEngine.CommandResult.TARGET_OFFLINE;
                }
                return engine.resetPlayer(context(player));
            }
            StoryEngine.CommandResult result = engine.resetWorld(
                world(server),
                () -> markWorldDirty.accept(server),
                server::isSameThread
            );
            if (result != StoryEngine.CommandResult.NESTED_TRANSACTION) {
                engine.scheduleReconcile();
            }
            return result;
        }

        private StoryEngine.StoryCommandContext<ServerPlayer> context(ServerPlayer player) {
            return engine.context(player);
        }

        private WorldStoryState world(@Nullable MinecraftServer server) {
            return injectedWorld != null ? injectedWorld : WorldStoryState.get(server);
        }

        private StoryEngine.StoryCommandContext<Object> worldContext(
            Object serverIdentity,
            WorldStoryState world
        ) {
            return new StoryEngine.StoryCommandContext<>(
                StoryResetTokens.WORLD_TARGET,
                new Object(),
                new StoryProgress(),
                world,
                engine.snapshot(),
                firefly520.fireflymc.story.engine.QuestEvaluator.CurrentState.empty(),
                new WorldCommandEffects(world),
                ignored -> { },
                () -> markWorldDirty.accept(serverIdentity),
                () -> true,
                true
            );
        }

        StoryEngine.CommandResult setWorldFlagForTest(
            Object serverIdentity,
            WorldStoryState world,
            String flag,
            boolean value
        ) {
            return engine.setFlag(
                worldContext(serverIdentity, world),
                StoryCondition.Scope.WORLD,
                flag,
                value
            );
        }

        @Nullable
        private static ServerPlayer onlinePlayer(MinecraftServer server, UUID target) {
            return server == null || target == null
                ? null
                : server.getPlayerList().getPlayer(target);
        }

        private static String sortedCounters(
            java.util.Map<RequirementProgressKey, Integer> values
        ) {
            TreeMap<String, String> formatted = new TreeMap<>();
            values.forEach((key, value) -> formatted.put(formatKey(key), value.toString()));
            return formatted.toString();
        }

        private static String sortedKeys(java.util.Set<RequirementProgressKey> values) {
            TreeMap<String, String> formatted = new TreeMap<>();
            values.forEach(key -> formatted.put(formatKey(key), ""));
            return java.util.Collections.unmodifiableSet(formatted.keySet()).toString();
        }

        private static String formatKey(RequirementProgressKey key) {
            return key.questId() + ":" + key.requirementId() + ":v" + key.stateVersion();
        }

        private static String sorted(java.util.Map<String, ?> values) {
            return new TreeMap<>(values).toString();
        }
    }

    private static final class WorldCommandEffects
        implements ActionExecutor.Effects<Object> {
        private final WorldStoryState world;

        private WorldCommandEffects(WorldStoryState world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        @Override
        public boolean isServerThread(Object player) {
            return true;
        }

        @Override
        public boolean setFlag(
            Object player,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            if (scope != StoryCondition.Scope.WORLD) {
                throw new IllegalArgumentException("World command context has no player");
            }
            return world.setFlag(flag, value);
        }

        @Override
        public boolean setPlayerVar(
            Object player,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount,
            @Nullable Integer min,
            @Nullable Integer max
        ) {
            int lower = min == null ? Integer.MIN_VALUE : min;
            int upper = max == null ? Integer.MAX_VALUE : max;
            return switch (operation) {
                case SET -> world.setVar(variable, amount, lower, upper);
                case ADD -> world.addVar(variable, amount, lower, upper);
            };
        }

        @Override
        public void unlockQuest(Object player, ResourceLocation quest) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public net.minecraft.world.item.ItemStack addToInventory(
            Object player,
            ResourceLocation item,
            int count
        ) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void dropAtFeet(
            Object player,
            net.minecraft.world.item.ItemStack remaining
        ) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void playSound(
            Object player,
            ResourceLocation sound,
            float volume,
            float pitch
        ) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void showTitle(
            Object player,
            Component title,
            @Nullable Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut
        ) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void sendMessage(Object player, Component message) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void executeCommand(Object player, String command) {
            throw new IllegalArgumentException("World command context has no player");
        }

        @Override
        public void teleport(
            Object player,
            ResourceLocation dimension,
            double x,
            double y,
            double z
        ) {
            throw new IllegalArgumentException("World command context has no player");
        }
    }
}
