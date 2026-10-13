package firefly520.fireflymc.story.adapter;

import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Translates NeoForge game events into standardized story signals. This class deliberately has
 * no access to quest definitions or progress; all progression decisions belong to StoryEngine.
 */
public final class StoryGameEvents {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean REGISTRATION_FAILED = new AtomicBoolean();
    private static volatile GameplayTarget<ServerPlayer> productionTarget;

    private StoryGameEvents() {}

    private static final class NeoForgeRegistrar implements Registrar {
        @Override
        public <T extends Event> void addListener(
            Class<T> eventType,
            Consumer<T> listener
        ) {
            NeoForge.EVENT_BUS.addListener(eventType, listener);
        }
    }

    /** Narrow registration port so once-only registration is testable without a live server. */
    @FunctionalInterface
    public interface Registrar {
        <T extends Event> void addListener(Class<T> eventType, Consumer<T> listener);
    }

    /** Server-side gameplay operations consumed by the adapter; P avoids fake players in tests. */
    public interface GameplayTarget<P> {
        void dispatch(P player, StoryEvent event);

        void reconcile(P player);

        void sendNow(P player);

        /** Authoritative session gate; false before dedicated auth/rules have completed. */
        boolean sessionOpen(P player);

        void cleanup(P player);

        void tick(MinecraftServer server);
    }

    public static void registerProduction(GameplayTarget<ServerPlayer> target) {
        registerProductionOnce(new NeoForgeRegistrar(), target);
    }

    static void registerProductionForTest(
        Registrar registrar,
        GameplayTarget<ServerPlayer> target
    ) {
        registerProductionOnce(registrar, target);
    }

    static GameplayTarget<ServerPlayer> productionTargetForTest() {
        return productionTarget;
    }

    static boolean productionRegistrationFailedForTest() {
        return REGISTRATION_FAILED.get();
    }

    private static void registerProductionOnce(
        Registrar registrar,
        GameplayTarget<ServerPlayer> target
    ) {
        if (REGISTRATION_FAILED.get()) {
            throw new IllegalStateException(
                "StoryGameEvents has a prior partial registration; restart is required");
        }
        if (REGISTERED.get()) {
            throw new IllegalStateException("StoryGameEvents is already registered");
        }
        // Install the dispatch target first. If bus registration throws midway, catch below
        // clears it so already-registered listeners fail closed instead of progressing story
        // state. This is not rollback: partial listeners can remain and retry is refused.
        productionTarget = target;
        try {
            register(registrar, target, REGISTERED);
        } catch (RuntimeException exception) {
            onProductionRegistrationFailure();
            throw exception;
        }
    }

    private static void onProductionRegistrationFailure() {
        productionTarget = null;
        REGISTRATION_FAILED.set(true);
    }

    static <P> void register(
        Registrar registrar,
        GameplayTarget<P> target,
        AtomicBoolean registered
    ) {
        Objects.requireNonNull(registrar, "registrar");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(registered, "registered");
        if (!registered.compareAndSet(false, true)) {
            throw new IllegalStateException("StoryGameEvents is already registered");
        }

        try {
            registrar.addListener(ItemEntityPickupEvent.Post.class,
                StoryGameEvents::onItemPickup);
            registrar.addListener(LivingDeathEvent.class, StoryGameEvents::onLivingDeath);
            registrar.addListener(PlayerEvent.PlayerChangedDimensionEvent.class,
                StoryGameEvents::onPlayerChangedDimension);
            registrar.addListener(AdvancementEvent.AdvancementEarnEvent.class,
                StoryGameEvents::onAdvancementEarned);
            registrar.addListener(PlayerEvent.PlayerLoggedInEvent.class,
                StoryGameEvents::onPlayerLoggedInEvent);
            registrar.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                StoryGameEvents::onPlayerLoggedOutEvent);
            registrar.addListener(PlayerEvent.PlayerRespawnEvent.class,
                StoryGameEvents::onPlayerRespawnEvent);
            registrar.addListener(ServerTickEvent.Post.class, StoryGameEvents::onServerTickEvent);
        } catch (RuntimeException exception) {
            // Registration is intentionally not rolled back. The flag remains set so a retry
            // cannot silently duplicate the listeners that were installed before the failure.
            throw exception;
        }
    }

    static Optional<StoryEvent> pickupEvent(
        ResourceLocation item,
        int originalCount,
        int currentCount
    ) {
        Objects.requireNonNull(item, "item");
        int pickedUp = originalCount - currentCount;
        if (originalCount <= 0 || currentCount < 0 || pickedUp <= 0) {
            return Optional.empty();
        }
        return Optional.of(
            new StoryEvent(StoryEventType.ITEM_PICKUP, item, pickedUp, Map.of()));
    }

    static Optional<StoryEvent> killEvent(boolean killerIsServerPlayer, ResourceLocation entity) {
        Objects.requireNonNull(entity, "entity");
        if (!killerIsServerPlayer) {
            return Optional.empty();
        }
        return Optional.of(
            new StoryEvent(StoryEventType.ENTITY_KILL, entity, 1, Map.of()));
    }

    static Optional<StoryEvent> advancementEvent(ResourceLocation advancement) {
        Objects.requireNonNull(advancement, "advancement");
        return Optional.of(
            new StoryEvent(StoryEventType.ADVANCEMENT_EARNED, advancement, 1, Map.of()));
    }

    static Optional<StoryEvent> dimensionEvent(ResourceLocation destination) {
        Objects.requireNonNull(destination, "destination");
        return Optional.of(
            new StoryEvent(StoryEventType.DIMENSION_ENTER, destination, 1, Map.of()));
    }

    static <P> void onPlayerLoggedIn(
        P player,
        boolean dedicatedServer,
        GameplayTarget<P> target
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        // Dedicated servers have a separate mod handshake/rule/password completion point. Cleanup
        // is safe, but no reconcile, action, or snapshot side effect may run before that point.
        target.cleanup(player);
        if (!dedicatedServer) {
            onJoinCompleted(player, target);
        }
    }

    static <P> void onJoinCompleted(P player, GameplayTarget<P> target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        if (!target.sessionOpen(player)) {
            return;
        }
        target.reconcile(player);
        target.sendNow(player);
    }

    public static void onJoinCompleted(ServerPlayer player) {
        GameplayTarget<ServerPlayer> target = productionTarget;
        if (target == null || !target.sessionOpen(player)) {
            return;
        }
        onJoinCompleted(player, target);
    }

    static <P> void onPlayerRespawned(P player, GameplayTarget<P> target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        if (!target.sessionOpen(player)) {
            return;
        }
        target.sendNow(player);
    }

    static <P> void onPlayerLoggedOut(P player, GameplayTarget<P> target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        target.cleanup(player);
    }

    private static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        pickupEvent(
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getOriginalStack().getItem()),
            event.getOriginalStack().getCount(),
            event.getCurrentStack().getCount()
        ).ifPresent(storyEvent -> productionTarget.dispatch(player, storyEvent));
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        killEvent(
            true,
            net.minecraft.world.entity.EntityType.getKey(event.getEntity().getType())
        ).ifPresent(storyEvent -> productionTarget.dispatch(player, storyEvent));
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        dimensionEvent(event.getTo().location())
            .ifPresent(storyEvent -> productionTarget.dispatch(player, storyEvent));
    }

    private static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        advancementEvent(event.getAdvancement().id())
            .ifPresent(storyEvent -> productionTarget.dispatch(player, storyEvent));
    }

    private static void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StoryGameEvents.onPlayerLoggedIn(
            player,
            player.server.isDedicatedServer(),
            productionTarget
        );
    }

    private static void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        productionTarget.cleanup(player);
    }

    private static void onPlayerRespawnEvent(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StoryGameEvents.onPlayerRespawned(player, productionTarget);
    }

    private static void onServerTickEvent(ServerTickEvent.Post event) {
        productionTarget.tick(event.getServer());
    }

}
