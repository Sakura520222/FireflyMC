package firefly520.fireflymc.story;

import com.mojang.logging.LogUtils;
import firefly520.fireflymc.story.adapter.StoryGameEvents;
import firefly520.fireflymc.story.adapter.StorySessionGate;
import firefly520.fireflymc.story.action.ActionExecutor;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.ContinuousWatchSet;
import firefly520.fireflymc.story.engine.QuestEvaluator;
import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.engine.StorySignal;
import firefly520.fireflymc.story.engine.SyncSink;
import firefly520.fireflymc.story.engine.TransitionQueue;
import firefly520.fireflymc.story.loader.StoryReloadListener;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import firefly520.fireflymc.story.sync.StorySyncService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Server-authoritative gameplay facade. The immutable snapshot may be read from any thread, while
 * all mutable runtime state is confined to the active server thread.
 *
 * <p>The static singleton retains only UUID-keyed lightweight runtime state and never retains a
 * player, level, or server object. Package-private lifecycle cleanup is supplied for Task 8.</p>
 */
public final class StoryEngine {
    private static final StoryEngine INSTANCE = new StoryEngine();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private volatile StoryRuntimeSnapshot snapshot = StoryRuntimeSnapshot.empty();
    private final AtomicBoolean pendingReconcile = new AtomicBoolean();
    private final QuestEvaluator evaluator = new QuestEvaluator();
    private final ActionExecutor actionExecutor = new ActionExecutor();
    private final Map<UUID, ContinuousWatchSet> playerWatches = new LinkedHashMap<>();
    private final Set<UUID> dirtyPlayers = new LinkedHashSet<>();
    private volatile boolean storyEnabled = true;
    private volatile boolean worldDirty;
    private volatile SyncSink syncSink = player -> {};
    private volatile Predicate<ServerPlayer> sessionOpen = player -> true;
    private UUID activeTransactionPlayer;
    private TransitionQueue activeTransactionQueue;
    private boolean activeTransactionPlayerMutated;
    private boolean activeTransactionWorldMutated;
    private final Deque<PendingEvent> pendingEvents = new ArrayDeque<>();

    StoryEngine() {}

    static StoryEngine instance() {
        return INSTANCE;
    }

    static StoryGameEvents.GameplayTarget<ServerPlayer> gameplayTarget() {
        return ProductionGameplayTarget.INSTANCE;
    }

    public enum CommandResult {
        PERFORMED,
        UNCHANGED,
        ALREADY_COMPLETED,
        LOCKED,
        UNKNOWN,
        UNKNOWN_EVENT,
        TARGET_OFFLINE,
        NESTED_TRANSACTION,
        DISABLED,
        SESSION_CLOSED,
        FAILED,
        NO_OP
    }

    public static void install(StoryRuntimeSnapshot candidate) {
        instance().installSnapshot(candidate);
    }

    public static void register(IEventBus modEventBus) {
        register(modEventBus, StorySessionGate::isOpen);
    }

    static void register(IEventBus modEventBus, Predicate<ServerPlayer> sessionGate) {
        Objects.requireNonNull(modEventBus, "modEventBus");
        Objects.requireNonNull(sessionGate, "sessionGate");
        if (!REGISTERED.compareAndSet(false, true)) {
            throw new IllegalStateException("StoryEngine is already registered");
        }

        StoryAttachments.ATTACHMENT_TYPES.register(modEventBus);
        instance().setSessionGate(sessionGate);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) ->
            onAddReloadListeners(event));
        // The adapter owns the single GAME-bus registration for gameplay events and server-tick
        // translation. It delegates ticks back here, preserving engine-before-sync ordering.
        StoryGameEvents.registerProduction(gameplayTarget());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
            onServerStopping(event));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
            onServerStarted(event));
        // Bind the transport through the existing registration lifecycle without widening the
        // gameplay facade. StorySyncService's tick listener is installed immediately afterward,
        // preserving engine-before-sync ordering.
        instance().setSyncSink(StorySyncService.instance());
        StorySyncService.attachSnapshotSource(instance()::snapshot);
    }

    public static void onConfigReload(boolean enabled) {
        instance().setStoryEnabled(enabled);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        Objects.requireNonNull(event, "event");
        requireServerThread(event.getServer()::isSameThread);
        instance().onServerStarted(StorySyncService.isInstalled());
    }

    void onServerStarted(boolean storySyncInstalled) {
        if (storySyncInstalled) {
            setSyncSink(StorySyncService.instance());
        }
    }

    void setSyncSink(SyncSink syncSink) {
        this.syncSink = syncSink == null ? player -> {} : syncSink;
    }

    void setSessionGate(Predicate<ServerPlayer> gate) {
        this.sessionOpen = Objects.requireNonNull(gate, "gate");
    }

    public StoryRuntimeSnapshot snapshot() {
        return snapshot;
    }

    /** Command-facing context for scoped operator mutations; retained only for one call. */
    public record StoryCommandContext<P>(
        UUID playerId,
        P player,
        StoryProgress progress,
        WorldStoryState world,
        StoryRuntimeSnapshot snapshot,
        QuestEvaluator.CurrentState currentState,
        ActionExecutor.Effects<? super P> effects,
        Consumer<P> markPlayerDirty,
        Runnable markWorldDirty,
        BooleanSupplier onServerThread,
        boolean sessionOpen
    ) {
        public StoryCommandContext {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(currentState, "currentState");
            Objects.requireNonNull(effects, "effects");
            Objects.requireNonNull(markPlayerDirty, "markPlayerDirty");
            Objects.requireNonNull(markWorldDirty, "markWorldDirty");
            Objects.requireNonNull(onServerThread, "onServerThread");
            // A context is an explicit capability; false is a valid, tested state.
        }

        TransactionContext<P> transaction() {
            return new TransactionContext<>(
                playerId,
                player,
                progress,
                world,
                snapshot,
                currentState,
                effects,
                markPlayerDirty,
                markWorldDirty,
                onServerThread,
                true
            );
        }
    }

    <P> CommandResult setFlag(
        StoryCommandContext<P> context,
        StoryCondition.Scope scope,
        String flag,
        boolean value
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(flag, "flag");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        if (!StoryCondition.isValidStateName(flag)) {
            throw new IllegalArgumentException("invalid story state name: " + flag);
        }

        boolean changed = context.effects().setFlag(context.player(), scope, flag, value);
        if (!changed) {
            return CommandResult.UNCHANGED;
        }
        markScopeDirty(context, scope);
        runTransaction(
            context.transaction(),
            candidates(
                context.snapshot(),
                StoryEventType.FLAG_CHANGED,
                scope,
                ResourceLocation.parse(flag)
            ),
            false
        );
        return CommandResult.PERFORMED;
    }

    <P> CommandResult setVar(
        StoryCommandContext<P> context,
        StoryCondition.Scope scope,
        String variable,
        ActionTypes.SetVarAction.Operation operation,
        int amount
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(operation, "operation");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        if (!StoryCondition.isValidStateName(variable)) {
            throw new IllegalArgumentException("invalid story state name: " + variable);
        }

        boolean changed = switch (scope) {
            case PLAYER -> context.effects().setPlayerVar(
                context.player(), variable, operation, amount, null, null);
            case WORLD -> switch (operation) {
                case SET -> context.world().setVar(
                    variable, amount, Integer.MIN_VALUE, Integer.MAX_VALUE);
                case ADD -> context.world().addVar(
                    variable, amount, Integer.MIN_VALUE, Integer.MAX_VALUE);
            };
        };
        if (!changed) {
            return CommandResult.UNCHANGED;
        }
        markScopeDirty(context, scope);
        runTransaction(
            context.transaction(),
            candidates(
                context.snapshot(),
                StoryEventType.VAR_CHANGED,
                scope,
                ResourceLocation.parse(variable)
            ),
            false
        );
        return CommandResult.PERFORMED;
    }

    <P> CommandResult unlock(
        StoryCommandContext<P> context,
        ResourceLocation quest
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(quest, "quest");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        if (!context.snapshot().quests().containsKey(quest)) {
            return CommandResult.UNKNOWN;
        }
        if (context.progress().getQuestState(quest) != QuestState.LOCKED) {
            return CommandResult.UNCHANGED;
        }

        context.effects().unlockQuest(context.player(), quest);
        context.markPlayerDirty().accept(context.player());
        activateWatch(context.transaction(), quest);
        runTransaction(context.transaction(), Set.of(quest), true);
        return CommandResult.PERFORMED;
    }

    <P> CommandResult complete(
        StoryCommandContext<P> context,
        ResourceLocation quest
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(quest, "quest");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        QuestDefinition definition = context.snapshot().quests().get(quest);
        if (definition == null) {
            return CommandResult.UNKNOWN;
        }
        QuestState state = context.progress().getQuestState(quest);
        if (state == QuestState.COMPLETED) {
            return CommandResult.ALREADY_COMPLETED;
        }
        if (state != QuestState.ACTIVE) {
            return CommandResult.LOCKED;
        }

        var transaction = context.transaction();
        TransitionQueue queue = new TransitionQueue((candidateId, workQueue) ->
            processQuest(transaction, candidateId, workQueue));
        ProgressFingerprint before = ProgressFingerprint.of(context.progress());
        activeTransactionPlayer = context.playerId();
        activeTransactionQueue = queue;
        activeTransactionPlayerMutated = true;
        activeTransactionWorldMutated = false;
        Set<ResourceLocation> work = new LinkedHashSet<>();
        try {
            context.progress().complete(quest);
            watches(context.playerId()).deactivate(context.playerId(), quest);
            List<StorySignal> signals = actionExecutor.executeAll(
                quest,
                definition.onComplete(),
                context.player(),
                context.effects()
            );
            replaySignals(transaction, signals, queue);
            for (ResourceLocation unlocked : context.snapshot().graph().outgoing(quest)) {
                if (context.progress().getQuestState(unlocked) == QuestState.ACTIVE) {
                    activateWatch(transaction, unlocked);
                    work.add(unlocked);
                }
            }
            work.forEach(queue::enqueue);
            if (!queue.drain()) {
                LOGGER.error(
                    "Story command transaction aborted after {} work items; player={} revision={}",
                    TransitionQueue.MAX_WORK_ITEMS_PER_TRANSACTION,
                    context.playerId(),
                    context.snapshot().revision()
                );
            }
        } finally {
            activeTransactionPlayer = null;
            activeTransactionQueue = null;
            boolean playerMutated = activeTransactionPlayerMutated;
            boolean worldMutated = activeTransactionWorldMutated;
            activeTransactionPlayerMutated = false;
            activeTransactionWorldMutated = false;
            playerMutated |= !before.equals(ProgressFingerprint.of(context.progress()));
            if (playerMutated) {
                context.markPlayerDirty().accept(context.player());
            }
            if (worldMutated) {
                context.markWorldDirty().run();
            }
        }
        return CommandResult.PERFORMED;
    }

    <P> CommandResult counter(
        StoryCommandContext<P> context,
        ResourceLocation quest,
        String requirementId,
        ActionTypes.SetVarAction.Operation operation,
        int amount
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(operation, "operation");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        QuestDefinition definition = context.snapshot().quests().get(quest);
        if (definition == null) {
            return CommandResult.UNKNOWN;
        }
        RequirementDefinition requirement = definition.requirements().stream()
            .filter(candidate -> candidate.id().equals(requirementId))
            .findFirst()
            .orElse(null);
        if (requirement == null) {
            return CommandResult.UNKNOWN;
        }
        if (context.progress().getQuestState(quest) != QuestState.ACTIVE) {
            return CommandResult.LOCKED;
        }

        RequirementProgressKey key = new RequirementProgressKey(
            quest,
            requirementId,
            requirement.stateVersion()
        );
        boolean changed = switch (operation) {
            case SET -> context.progress().setCounter(key, amount);
            case ADD -> context.progress().addCounter(key, amount);
        };
        if (!changed) {
            return CommandResult.UNCHANGED;
        }
        context.markPlayerDirty().accept(context.player());
        runTransaction(context.transaction(), Set.of(quest), true);
        return CommandResult.PERFORMED;
    }

    CommandResult resetPlayer(StoryCommandContext<?> context) {
        Objects.requireNonNull(context, "context");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        cleanupPlayer(context.playerId(), context.onServerThread());
        context.progress().clearAndBootstrap(context.snapshot());
        watches(context.playerId())
            .rebuild(context.playerId(), context.snapshot(), context.progress());
        runTransaction(context.transaction(), context.progress().activeQuests(), true);
        return CommandResult.PERFORMED;
    }

    CommandResult resetWorld(
        WorldStoryState world,
        Runnable markWorldDirty,
        BooleanSupplier onServerThread
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(markWorldDirty, "markWorldDirty");
        Objects.requireNonNull(onServerThread, "onServerThread");
        requireServerThread(onServerThread);
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        world.clear();
        markWorldDirty.run();
        return CommandResult.PERFORMED;
    }

    <P> CommandResult trigger(
        StoryCommandContext<P> context,
        ResourceLocation eventId
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(eventId, "eventId");
        requireServerThread(context.onServerThread());
        if (hasActiveTransaction()) {
            return CommandResult.NESTED_TRANSACTION;
        }
        if (!storyEnabled) {
            return CommandResult.DISABLED;
        }
        if (!context.sessionOpen()) {
            return CommandResult.SESSION_CLOSED;
        }
        Set<ResourceLocation> candidates = context.snapshot()
            .interestIndex()
            .candidates(StoryEventType.CUSTOM_EVENT, eventId);
        if (candidates.isEmpty()) {
            return CommandResult.NO_OP;
        }

        ProgressFingerprint progressBefore = ProgressFingerprint.of(context.progress());
        java.util.Map<String, Boolean> worldFlagsBefore = Map.copyOf(context.world().flags());
        java.util.Map<String, Integer> worldVarsBefore = Map.copyOf(context.world().vars());
        try {
            runTransaction(
                context.transaction(),
                evaluator.onEvent(
                    context.progress(),
                    context.world(),
                    context.snapshot(),
                    new StoryEvent(
                        StoryEventType.CUSTOM_EVENT,
                        eventId,
                        1,
                        Map.of()
                    ),
                    context.currentState()
                ),
                false
            );
            return CommandResult.PERFORMED;
        } catch (RuntimeException exception) {
            if (!progressBefore.equals(ProgressFingerprint.of(context.progress()))) {
                context.markPlayerDirty().accept(context.player());
            }
            boolean worldChanged =
                !worldFlagsBefore.equals(context.world().flags())
                    || !worldVarsBefore.equals(context.world().vars());
            if (worldChanged) {
                context.markWorldDirty().run();
            }
            LOGGER.error(
                "Story command trigger failed safely; player={} event={}",
                context.playerId(),
                eventId,
                exception
            );
            return CommandResult.FAILED;
        }
    }

    void installSnapshot(StoryRuntimeSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        snapshot = candidate;
        markAllPlayersForReconcile();
    }

    boolean hasActiveTransaction() {
        return activeTransactionPlayer != null;
    }

    void markAllPlayersForReconcile() {
        pendingReconcile.set(true);
    }

    boolean hasPendingReconcile() {
        return pendingReconcile.get();
    }

    boolean consumePendingReconcile() {
        return pendingReconcile.compareAndSet(true, false);
    }

    void setStoryEnabled(boolean enabled) {
        storyEnabled = enabled;
        if (enabled) {
            markAllPlayersForReconcile();
        }
    }

    boolean storyEnabled() {
        return storyEnabled;
    }

    public void dispatch(ServerPlayer player, StoryEvent event) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(event, "event");
        requireServerThread(player.server);
        if (!storyEnabled) {
            return;
        }

        try {
            TransactionContext<ServerPlayer> context = productionContext(player);
            if (!context.sessionOpen()) {
                return;
            }
            dispatch(context, event);
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Story dispatch failed; quest transaction stopped safely: player={} event={}",
                player.getUUID(),
                event.type(),
                exception
            );
        }
    }

    public void reconcile(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread(player.server);
        if (!storyEnabled) {
            return;
        }

        try {
            TransactionContext<ServerPlayer> context = productionContext(player);
            if (!context.sessionOpen()) {
                return;
            }
            reconcileTransaction(context);
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Story reconcile failed; player={} revision={}",
                player.getUUID(),
                snapshot.revision(),
                exception
            );
        }
    }

    void cleanupPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread(player.server);
        cleanupPlayer(player.getUUID(), () -> true);
    }

    void cleanupPlayer(UUID playerId, BooleanSupplier onServerThread) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(onServerThread, "onServerThread");
        requireServerThread(onServerThread);
        playerWatches.remove(playerId);
        dirtyPlayers.remove(playerId);
        pendingEvents.removeIf(pending -> pending.playerId().equals(playerId));
    }

    public void onServerTick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        List<TransactionContext<ServerPlayer>> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TransactionContext<ServerPlayer> context = productionContext(player);
            if (context.sessionOpen()) {
                players.add(context);
            }
        }
        SyncSink currentSink = syncSink;
        onServerTick(new TickContext<>(
            server::isSameThread,
            server.getTickCount(),
            players,
            playerId -> server.getPlayerList().getPlayer(playerId),
            currentSink::markDirty
        ));
    }

    <P> void onServerTick(TickContext<P> tick) {
        Objects.requireNonNull(tick, "tick");
        requireServerThread(tick.onServerThread());
        try {
            drainPendingEvents(tick);
            if (consumePendingReconcile()) {
                for (TransactionContext<P> player : tick.players()) {
                    if (storyEnabled) {
                        reconcileTransaction(player);
                    }
                }
            }
            if (!storyEnabled) {
                return;
            }
            for (TransactionContext<P> player : tick.players()) {
                if (!player.sessionOpen()) {
                    continue;
                }
                runTransaction(
                    player,
                    watches(player.playerId()).tickDue(player.playerId(), tick.serverTick())
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Story server tick failed; player transactions stopped safely: revision={}",
                snapshot.revision(),
                exception
            );
        } finally {
            flushDirty(tick);
        }
    }

    /** Package-private cleanup hook; Task 8 owns registering the actual server lifecycle event. */
    void onServerStopping(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        onServerStopping(server::isSameThread);
    }

    void onServerStopping(BooleanSupplier onServerThread) {
        Objects.requireNonNull(onServerThread, "onServerThread");
        requireServerThread(onServerThread);
        playerWatches.clear();
        dirtyPlayers.clear();
        pendingEvents.clear();
        activeTransactionPlayer = null;
        activeTransactionQueue = null;
        activeTransactionPlayerMutated = false;
        activeTransactionWorldMutated = false;
        snapshot = StoryRuntimeSnapshot.empty();
        storyEnabled = true;
        worldDirty = false;
        pendingReconcile.set(false);
        syncSink = player -> {};
    }

    /**
     * Pure transaction port used by production and focused tests. It never exposes a fake
     * {@link ServerPlayer} boundary and holds no static player or server reference.
     */
    record TransactionContext<P>(
        UUID playerId,
        P player,
        StoryProgress progress,
        WorldStoryState world,
        StoryRuntimeSnapshot snapshot,
        QuestEvaluator.CurrentState currentState,
        ActionExecutor.Effects<? super P> effects,
        Consumer<P> markPlayerDirty,
        Runnable markWorldDirty,
        BooleanSupplier onServerThread,
        boolean sessionOpen
    ) {
        TransactionContext(
            UUID playerId,
            P player,
            StoryProgress progress,
            WorldStoryState world,
            StoryRuntimeSnapshot snapshot,
            QuestEvaluator.CurrentState currentState,
            ActionExecutor.Effects<? super P> effects,
            Consumer<P> markPlayerDirty,
            Runnable markWorldDirty,
            BooleanSupplier onServerThread
        ) {
            this(
            playerId,
            player,
            progress,
            world,
            snapshot,
            currentState,
            effects,
            markPlayerDirty,
            markWorldDirty,
            onServerThread,
            true
        );
        }

        TransactionContext {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(currentState, "currentState");
            Objects.requireNonNull(effects, "effects");
            Objects.requireNonNull(markPlayerDirty, "markPlayerDirty");
            Objects.requireNonNull(markWorldDirty, "markWorldDirty");
            Objects.requireNonNull(onServerThread, "onServerThread");
        }
    }

    record TickContext<P>(
        BooleanSupplier onServerThread,
        long serverTick,
        List<TransactionContext<P>> players,
        Function<UUID, P> playerById,
        Consumer<P> markDirty
    ) {
        TickContext {
            Objects.requireNonNull(onServerThread, "onServerThread");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(playerById, "playerById");
            Objects.requireNonNull(markDirty, "markDirty");
            players = List.copyOf(players);
        }
    }

    private record PendingEvent(UUID playerId, StoryEvent event) {
        PendingEvent {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(event, "event");
        }
    }

    private record ProgressFingerprint(
        Set<ResourceLocation> completedQuests,
        Set<ResourceLocation> activeQuests,
        Map<String, Boolean> flags,
        Map<String, Integer> vars,
        Map<RequirementProgressKey, Integer> counters,
        Set<RequirementProgressKey> satisfied,
        Optional<ResourceLocation> trackedQuest
    ) {
        private static ProgressFingerprint of(StoryProgress progress) {
            return new ProgressFingerprint(
                Set.copyOf(progress.completedQuests()),
                Set.copyOf(progress.activeQuests()),
                Map.copyOf(progress.flags()),
                Map.copyOf(progress.vars()),
                Map.copyOf(progress.counters()),
                Set.copyOf(progress.satisfied()),
                progress.trackedQuest()
            );
        }
    }

    <P> void dispatch(TransactionContext<P> context, StoryEvent event) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(event, "event");
        requireServerThread(context.onServerThread());
        if (!storyEnabled) {
            return;
        }
        if (!context.sessionOpen()) {
            return;
        }
        if (activeTransactionPlayer != null) {
            if (activeTransactionPlayer.equals(context.playerId())) {
                ProgressFingerprint before = ProgressFingerprint.of(context.progress());
                var candidates = evaluator.onEvent(
                    context.progress(),
                    context.world(),
                    context.snapshot(),
                    event,
                    context.currentState()
                );
                if (!before.equals(ProgressFingerprint.of(context.progress()))) {
                    activeTransactionPlayerMutated = true;
                }
                candidates.forEach(activeTransactionQueue::enqueue);
            } else {
                pendingEvents.addLast(new PendingEvent(context.playerId(), event));
            }
            return;
        }

        ProgressFingerprint before = ProgressFingerprint.of(context.progress());
        runTransaction(
            context,
            evaluator.onEvent(
                context.progress(),
                context.world(),
                context.snapshot(),
                event,
                context.currentState()
            ),
            !before.equals(ProgressFingerprint.of(context.progress()))
        );
    }

    void reconcileTransaction(TransactionContext<?> context) {
        Objects.requireNonNull(context, "context");
        if (!storyEnabled) {
            return;
        }
        if (!context.sessionOpen()) {
            return;
        }

        StoryProgress progress = context.progress();
        StoryRuntimeSnapshot currentSnapshot = context.snapshot();
        ProgressFingerprint before = ProgressFingerprint.of(progress);
        progress.reconcile(currentSnapshot);
        for (ResourceLocation initial : currentSnapshot.graph().initialQuests()) {
            if (currentSnapshot.quests().containsKey(initial)
                && progress.getQuestState(initial) == QuestState.LOCKED) {
                progress.activate(initial);
            }
        }
        watches(context.playerId())
            .rebuild(context.playerId(), currentSnapshot, progress);
        runTransaction(
            context,
            progress.activeQuests(),
            !before.equals(ProgressFingerprint.of(progress))
        );
    }

    private <P> void drainPendingEvents(TickContext<P> tick) {
        int count = pendingEvents.size();
        for (int index = 0; index < count; index++) {
            PendingEvent pending = pendingEvents.removeFirst();
            TransactionContext<P> context = tick.players()
                .stream()
                .filter(candidate -> candidate.playerId().equals(pending.playerId()))
                .findFirst()
                .orElse(null);
            if (context == null || tick.playerById().apply(pending.playerId()) == null) {
                continue;
            }
            dispatch(context, pending.event());
        }
    }

    int pendingDispatchCount() {
        return pendingEvents.size();
    }

    <P> void runTransaction(
        TransactionContext<P> context,
        Iterable<ResourceLocation> candidates,
        boolean progressAlreadyMutated
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidates, "candidates");
        if (!storyEnabled) {
            return;
        }

        List<ResourceLocation> work = new ArrayList<>();
        candidates.forEach(work::add);
        if (work.isEmpty() && !progressAlreadyMutated) {
            return;
        }

        TransitionQueue queue = new TransitionQueue((questId, workQueue) ->
            processQuest(context, questId, workQueue));
        work.forEach(queue::enqueue);
        ProgressFingerprint before = ProgressFingerprint.of(context.progress());
        activeTransactionPlayer = context.playerId();
        activeTransactionQueue = queue;
        activeTransactionPlayerMutated = progressAlreadyMutated;
        activeTransactionWorldMutated = false;
        try {
            if (!queue.drain()) {
                LOGGER.error(
                    "Story transaction aborted after {} work items; player={} revision={}",
                    TransitionQueue.MAX_WORK_ITEMS_PER_TRANSACTION,
                    context.playerId(),
                    context.snapshot().revision()
                );
            }
        } finally {
            activeTransactionPlayer = null;
            activeTransactionQueue = null;
            boolean playerMutated = activeTransactionPlayerMutated;
            boolean worldMutated = activeTransactionWorldMutated;
            activeTransactionPlayerMutated = false;
            activeTransactionWorldMutated = false;
            playerMutated |= !before.equals(ProgressFingerprint.of(context.progress()));
            if (playerMutated) {
                context.markPlayerDirty().accept(context.player());
            }
            if (worldMutated) {
                context.markWorldDirty().run();
            }
        }
    }

    <P> void runTransaction(
        TransactionContext<P> context,
        Iterable<ResourceLocation> candidates
    ) {
        runTransaction(context, candidates, false);
    }

    private <P> void processQuest(
        TransactionContext<P> context,
        ResourceLocation questId,
        TransitionQueue queue
    ) {
        StoryRuntimeSnapshot currentSnapshot = context.snapshot();
        QuestDefinition quest = currentSnapshot.quests().get(questId);
        if (quest == null || context.progress().getQuestState(questId) != QuestState.ACTIVE) {
            return;
        }
        try {
            if (!evaluator.evaluate(
                quest,
                context.progress(),
                context.world(),
                context.currentState()
            )) {
                return;
            }

            // State is finalized before best-effort actions and is never rolled back.
            context.progress().complete(questId);
            activeTransactionPlayerMutated = true;
            watches(context.playerId()).deactivate(context.playerId(), questId);
            List<StorySignal> signals = actionExecutor.executeAll(
                questId,
                quest.onComplete(),
                context.player(),
                context.effects()
            );
            replaySignals(context, signals, queue);
            for (ResourceLocation unlocked : currentSnapshot.graph().outgoing(questId)) {
                if (context.progress().getQuestState(unlocked) == QuestState.ACTIVE) {
                    activateWatch(context, unlocked);
                    queue.enqueue(unlocked);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Story quest transaction failed; completed state is not rolled back: "
                    + "quest={} player={} revision={}",
                questId,
                context.playerId(),
                currentSnapshot.revision(),
                exception
            );
        }
    }

    private <P> void replaySignals(
        TransactionContext<P> context,
        List<StorySignal> signals,
        TransitionQueue queue
    ) {
        for (StorySignal signal : signals) {
            switch (signal) {
                case StorySignal.FlagChanged flag -> {
                    if (flag.scope() == StoryCondition.Scope.WORLD) {
                        activeTransactionWorldMutated = true;
                    }
                    enqueueCandidates(
                        context,
                        queue,
                        StoryEventType.FLAG_CHANGED,
                        flag.scope(),
                        ResourceLocation.parse(flag.flag())
                    );
                }
                case StorySignal.VarChanged var -> enqueueCandidates(
                    context,
                    queue,
                    StoryEventType.VAR_CHANGED,
                    StoryCondition.Scope.PLAYER,
                    ResourceLocation.parse(var.var())
                );
                case StorySignal.InventoryChanged ignored ->
                {
                    activeTransactionPlayerMutated = true;
                    watches(context.playerId())
                        .watched(context.playerId())
                        .forEach(queue::enqueue);
                }
                case StorySignal.LocationChanged ignored -> {
                    enqueueAllCandidates(context, queue, StoryEventType.DIMENSION_ENTER);
                    enqueueAllCandidates(context, queue, StoryEventType.BIOME_ENTER);
                    enqueueAllCandidates(context, queue, StoryEventType.STRUCTURE_ENTER);
                    watches(context.playerId())
                        .watched(context.playerId())
                        .forEach(queue::enqueue);
                }
            }
        }
    }

    private static <P> void enqueueCandidates(
        TransactionContext<P> context,
        TransitionQueue queue,
        StoryEventType type,
        StoryCondition.Scope scope,
        ResourceLocation key
    ) {
        context.snapshot()
            .interestIndex()
            .candidates(type, scope, key)
            .forEach(queue::enqueue);
    }

    private static <P> void enqueueAllCandidates(
        TransactionContext<P> context,
        TransitionQueue queue,
        StoryEventType type
    ) {
        for (ResourceLocation key : context.snapshot().interestIndex().keys(type)) {
            context.snapshot()
                .interestIndex()
                .candidates(type, key)
                .forEach(queue::enqueue);
        }
    }

    private static <P> void markScopeDirty(
        StoryCommandContext<P> context,
        StoryCondition.Scope scope
    ) {
        if (scope == StoryCondition.Scope.PLAYER) {
            context.markPlayerDirty().accept(context.player());
        } else {
            context.markWorldDirty().run();
        }
    }

    private static Set<ResourceLocation> candidates(
        StoryRuntimeSnapshot snapshot,
        StoryEventType type,
        StoryCondition.Scope scope,
        ResourceLocation key
    ) {
        return snapshot.interestIndex().candidates(type, scope, key);
    }

    private <P> boolean addSignalCandidates(
        StoryCommandContext<P> context,
        List<StorySignal> signals,
        Set<ResourceLocation> work
    ) {
        StoryRuntimeSnapshot snapshot = context.snapshot();
        Set<ResourceLocation> watched = watches(context.playerId())
            .watched(context.playerId());
        boolean worldMutated = false;
        for (StorySignal signal : signals) {
            if (signal instanceof StorySignal.FlagChanged flag) {
                worldMutated |= flag.scope() == StoryCondition.Scope.WORLD;
                work.addAll(candidates(
                    snapshot,
                    StoryEventType.FLAG_CHANGED,
                    flag.scope(),
                    ResourceLocation.parse(flag.flag())
                ));
            } else if (signal instanceof StorySignal.VarChanged variable) {
                work.addAll(candidates(
                    snapshot,
                    StoryEventType.VAR_CHANGED,
                    variable.scope(),
                    ResourceLocation.parse(variable.var())
                ));
            } else if (signal instanceof StorySignal.LocationChanged) {
                for (StoryEventType type : Set.of(
                    StoryEventType.DIMENSION_ENTER,
                    StoryEventType.BIOME_ENTER,
                    StoryEventType.STRUCTURE_ENTER
                )) {
                    for (ResourceLocation key : snapshot.interestIndex().keys(type)) {
                        work.addAll(snapshot.interestIndex().candidates(type, key));
                    }
                }
                work.addAll(watched);
            } else if (signal instanceof StorySignal.InventoryChanged) {
                work.addAll(watched);
            }
        }
        return worldMutated;
    }

    private TransactionContext<ServerPlayer> productionContext(ServerPlayer player) {
        StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
        WorldStoryState world = WorldStoryState.get(player.server);
        UUID playerId = player.getUUID();
        return new TransactionContext<>(
            playerId,
            player,
            progress,
            world,
            snapshot,
            new PlayerCurrentState(player),
            ActionExecutor.minecraftEffects(progress, world),
            ignored -> dirtyPlayers.add(playerId),
            () -> worldDirty = true,
            player.server::isSameThread,
            sessionOpen.test(player)
        );
    }

    StoryCommandContext<ServerPlayer> commandContext(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread(player.server);
        TransactionContext<ServerPlayer> context = productionContext(player);
        return new StoryCommandContext<>(
            context.playerId(),
            context.player(),
            context.progress(),
            context.world(),
            context.snapshot(),
            context.currentState(),
            context.effects(),
            context.markPlayerDirty(),
            context.markWorldDirty(),
            context.onServerThread(),
            sessionOpen.test(player)
        );
    }

    /** Narrow command bridge that keeps gameplay methods out of the public engine facade. */
    public static final class CommandApi {
        private static final CommandApi COMMANDS = new CommandApi(instance());

        private final StoryEngine engine;

        private CommandApi(StoryEngine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        public static CommandApi production() {
            return COMMANDS;
        }

        public static CommandApi of(StoryEngine engine) {
            return new CommandApi(engine);
        }

        public StoryRuntimeSnapshot snapshot() {
            return engine.snapshot;
        }

        public StoryEngine isolated() {
            return new StoryEngine();
        }

        public <P> CommandResult setFlag(
            StoryCommandContext<P> context,
            StoryCondition.Scope scope,
            String flag,
            boolean value
        ) {
            return engine.setFlag(context, scope, flag, value);
        }

        public <P> CommandResult setVar(
            StoryCommandContext<P> context,
            StoryCondition.Scope scope,
            String variable,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        ) {
            return engine.setVar(context, scope, variable, operation, amount);
        }

        public <P> CommandResult unlock(
            StoryCommandContext<P> context,
            ResourceLocation quest
        ) {
            return engine.unlock(context, quest);
        }

        public <P> CommandResult complete(
            StoryCommandContext<P> context,
            ResourceLocation quest
        ) {
            return engine.complete(context, quest);
        }

        public <P> CommandResult counter(
            StoryCommandContext<P> context,
            ResourceLocation quest,
            String requirementId,
            ActionTypes.SetVarAction.Operation operation,
            int amount
        ) {
            return engine.counter(context, quest, requirementId, operation, amount);
        }

        public CommandResult resetPlayer(StoryCommandContext<?> context) {
            return engine.resetPlayer(context);
        }

        public CommandResult resetWorld(
            WorldStoryState world,
            Runnable markWorldDirty,
            BooleanSupplier onServerThread
        ) {
            return engine.resetWorld(world, markWorldDirty, onServerThread);
        }

        public StoryCommandContext<ServerPlayer> context(ServerPlayer player) {
            return engine.commandContext(player);
        }

        public boolean hasActiveTransaction() {
            return engine.hasActiveTransaction();
        }

        public boolean hasPendingReconcile() {
            return engine.hasPendingReconcile();
        }

        public <P> CommandResult trigger(
            StoryCommandContext<P> context,
            ResourceLocation eventId
        ) {
            return engine.trigger(context, eventId);
        }

        public void scheduleReconcile() {
            engine.markAllPlayersForReconcile();
        }
    }

    void activateWatch(TransactionContext<?> context, ResourceLocation questId) {
        watches(context.playerId())
            .activate(context.playerId(), questId, context.snapshot(), context.progress());
    }

    private ContinuousWatchSet watches(UUID playerId) {
        return playerWatches.computeIfAbsent(playerId, ignored -> new ContinuousWatchSet());
    }

    Set<ResourceLocation> watched(UUID playerId) {
        return watches(playerId).watched(playerId);
    }

    int watchPhase(UUID playerId) {
        return watches(playerId).phase(playerId);
    }

    void requestPlayerDirty(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        dirtyPlayers.add(playerId);
    }

    void requestWorldDirty() {
        worldDirty = true;
    }

    SyncSink installedSink() {
        return syncSink;
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        StoryReloadListener.register(event);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        instance().onServerStopping(event.getServer());
    }

    <P> void flushDirty(TickContext<P> tick) {
        Objects.requireNonNull(tick, "tick");
        requireServerThread(tick.onServerThread());
        Consumer<P> sink = tick.markDirty();
        Set<UUID> targets = new LinkedHashSet<>(dirtyPlayers);
        if (worldDirty) {
            tick.players().forEach(player -> targets.add(player.playerId()));
        }
        dirtyPlayers.clear();
        worldDirty = false;
        for (UUID playerId : targets) {
            P player = tick.playerById().apply(playerId);
            if (player == null) {
                continue;
            }
            try {
                sink.accept(player);
            } catch (RuntimeException exception) {
                LOGGER.error(
                    "Failed to mark story snapshot dirty; player={}",
                    playerId,
                    exception
                );
            }
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        requireServerThread(server::isSameThread);
    }

    private static void requireServerThread(BooleanSupplier onServerThread) {
        if (!onServerThread.getAsBoolean()) {
            throw new IllegalThreadStateException(
                "StoryEngine gameplay access is confined to the server thread");
        }
    }

    private record PlayerCurrentState(ServerPlayer player) implements QuestEvaluator.CurrentState {
        PlayerCurrentState {
            Objects.requireNonNull(player, "player");
        }

        @Override
        public int inventoryCount(ResourceLocation item) {
            return BuiltInRegistries.ITEM.getHolder(item)
                .map(holder -> player.getInventory().countItem(holder.value()))
                .orElse(0);
        }

        @Override
        public boolean inDimension(ResourceLocation id) {
            return player.serverLevel().dimension().location().equals(id);
        }

        @Override
        public boolean inBiome(ResourceLocation id) {
            Holder<Biome> biome = player.serverLevel().getBiome(player.blockPosition());
            return biome.unwrapKey()
                .map(key -> key.location().equals(id))
                .orElse(false);
        }

        @Override
        public boolean inBiomeTag(ResourceLocation id) {
            return player.serverLevel()
                .getBiome(player.blockPosition())
                .is(TagKey.create(Registries.BIOME, id));
        }

        @Override
        public boolean inStructure(ResourceLocation id) {
            ServerLevel level = player.serverLevel();
            Registry<Structure> registry = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
            Structure structure = registry.get(id);
            if (structure != null) {
                return level.structureManager()
                    .getStructureWithPieceAt(player.blockPosition(), structure)
                    .isValid();
            }
            return false;
        }

        @Override
        public boolean inStructureTag(ResourceLocation id) {
            ServerLevel level = player.serverLevel();
            return level.structureManager()
                .getStructureWithPieceAt(
                    player.blockPosition(),
                    TagKey.create(Registries.STRUCTURE, id)
                )
                .isValid();
        }

        @Override
        public boolean atLocation(
            ResourceLocation dimension,
            double x,
            double y,
            double z,
            float radius
        ) {
            if (!inDimension(dimension)) {
                return false;
            }
            double deltaX = player.getX() - x;
            double deltaY = player.getY() - y;
            double deltaZ = player.getZ() - z;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                <= (double) radius * (double) radius;
        }

        @Override
        public boolean advancementDone(ResourceLocation id) {
            AdvancementHolder advancement = player.server.getAdvancements().get(id);
            return advancement != null
                && player.getAdvancements().getOrStartProgress(advancement).isDone();
        }
    }

    private enum ProductionGameplayTarget implements StoryGameEvents.GameplayTarget<ServerPlayer> {
        INSTANCE;

        @Override
        public void dispatch(ServerPlayer player, firefly520.fireflymc.story.engine.StoryEvent event) {
            instance().dispatch(player, event);
        }

        @Override
        public void reconcile(ServerPlayer player) {
            instance().reconcile(player);
        }

        @Override
        public void sendNow(ServerPlayer player) {
            StorySyncService.instance().sendNow(player);
        }

        @Override
        public boolean sessionOpen(ServerPlayer player) {
            return instance().sessionOpen.test(player);
        }

        @Override
        public void cleanup(ServerPlayer player) {
            instance().cleanupPlayer(player);
        }

        @Override
        public void tick(MinecraftServer server) {
            instance().onServerTick(server);
        }
    }
}
