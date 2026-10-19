package firefly520.fireflymc.story.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.adapter.StoryGameEvents.GameplayTarget;
import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.Test;

class StoryGameEventsTest {
    private static final ResourceLocation ITEM =
        ResourceLocation.parse("minecraft:echo_shard");
    private static final ResourceLocation ENTITY =
        ResourceLocation.parse("minecraft:zombie");
    private static final ResourceLocation ADVANCEMENT =
        ResourceLocation.parse("minecraft:story/mine_stone");
    private static final String PLAYER = "player";

    @Test
    void postPickupUsesOnlyTheQuantityMovedIntoInventory() {
        assertEquals(
            Optional.of(new StoryEvent(
                StoryEventType.ITEM_PICKUP,
                ITEM,
                4,
                Map.of())),
            StoryGameEvents.pickupEvent(ITEM, 10, 6));
    }

    @Test
    void unchangedPickupDoesNotCreateStoryEvent() {
        assertEquals(Optional.empty(), StoryGameEvents.pickupEvent(ITEM, 10, 10));
        assertEquals(Optional.empty(), StoryGameEvents.pickupEvent(ITEM, 0, 0));
    }

    @Test
    void onlyServerPlayerKillersCreateKillSignals() {
        assertEquals(
            Optional.of(new StoryEvent(
                StoryEventType.ENTITY_KILL,
                ENTITY,
                1,
                Map.of())),
            StoryGameEvents.killEvent(true, ENTITY));
        assertEquals(
            Optional.empty(),
            StoryGameEvents.killEvent(false, ENTITY));
    }

    @Test
    void earnedAdvancementCarriesItsResourceLocation() {
        assertEquals(
            Optional.of(new StoryEvent(
                StoryEventType.ADVANCEMENT_EARNED,
                ADVANCEMENT,
                1,
                Map.of())),
            StoryGameEvents.advancementEvent(ADVANCEMENT));
    }

    @Test
    void changedDimensionUsesTheDestinationId() {
        ResourceLocation destination = ResourceLocation.parse("minecraft:the_nether");
        assertEquals(
            Optional.of(new StoryEvent(
                StoryEventType.DIMENSION_ENTER,
                destination,
                1,
                Map.of())),
            StoryGameEvents.dimensionEvent(destination));
    }

    @Test
    void dedicatedLoginDefersStorySideEffectsUntilJoinCompletion() {
        RecordingTarget target = new RecordingTarget();
        StoryGameEvents.onPlayerLoggedIn(PLAYER, true, target);

        assertEquals(List.of("cleanup"), target.operations);
        assertNull(target.lastLifecyclePlayer);

        StoryGameEvents.onJoinCompleted(PLAYER, target);

        assertEquals(List.of("cleanup", "reconcile", "send"), target.operations);
        assertSame(PLAYER, target.lastLifecyclePlayer);
    }

    @Test
    void integratedLoginCanBootstrapAndSyncImmediately() {
        RecordingTarget target = new RecordingTarget();
        StoryGameEvents.onPlayerLoggedIn(PLAYER, false, target);

        assertEquals(List.of("cleanup", "reconcile", "send"), target.operations);
        assertSame(PLAYER, target.lastLifecyclePlayer);
    }

    @Test
    void respawnSendsAFreshSnapshotWithoutReconcilingAgain() {
        RecordingTarget target = new RecordingTarget();
        StoryGameEvents.onPlayerRespawned(PLAYER, target);

        assertEquals(List.of("send"), target.operations);
    }

    @Test
    void respawnDoesNotSendWhenTheSessionGateIsClosed() {
        RecordingTarget target = new RecordingTarget();
        target.sessionOpen = false;

        StoryGameEvents.onPlayerRespawned(PLAYER, target);

        assertEquals(List.of(), target.operations);
    }

    @Test
    void joinCompletionRespectsTheSessionGateBeforeReconcileOrSend() {
        RecordingTarget closed = new RecordingTarget();
        closed.sessionOpen = false;
        RecordingTarget open = new RecordingTarget();

        StoryGameEvents.onJoinCompleted(PLAYER, closed);
        StoryGameEvents.onJoinCompleted(PLAYER, open);

        assertEquals(List.of(), closed.operations);
        assertEquals(List.of("reconcile", "send"), open.operations);
    }

    @Test
    void logoutCleansUpOnlyThatPlayerSessionRuntime() {
        RecordingTarget target = new RecordingTarget();
        StoryGameEvents.onPlayerLoggedOut(PLAYER, target);

        assertEquals(List.of("cleanup"), target.operations);
        assertNull(target.lastLifecyclePlayer);
        assertFalse(target.dispatched);
        assertFalse(target.reconciled);
        assertFalse(target.sent);
        assertFalse(target.ticked);
    }

    @Test
    void unverifiedLogoutStillCleansUpWithoutProgressOrSend() {
        RecordingTarget target = new RecordingTarget();
        target.sessionOpen = false;

        StoryGameEvents.onPlayerLoggedOut(PLAYER, target);

        assertEquals(List.of("cleanup"), target.operations);
        assertFalse(target.dispatched);
        assertFalse(target.reconciled);
        assertFalse(target.sent);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void gameEventAdaptersRegisterExactlyOnceOnTheirConcreteEvents() {
        RecordingTarget target = new RecordingTarget();
        List<Class<? extends Event>> eventTypes = new ArrayList<>();
        AtomicBoolean registered = new AtomicBoolean();
        StoryGameEvents.Registrar registrar = new StoryGameEvents.Registrar() {
            @Override
            public <T extends Event> void addListener(
                Class<T> eventType,
                java.util.function.Consumer<T> listener
            ) {
                eventTypes.add(eventType);
            }
        };

        StoryGameEvents.register(registrar, target, registered);
        assertThrowsExactly(IllegalStateException.class, () ->
            StoryGameEvents.register(registrar, target, registered));

        assertEquals(
            List.of(
                ItemEntityPickupEvent.Post.class,
                LivingDeathEvent.class,
                PlayerEvent.PlayerChangedDimensionEvent.class,
                AdvancementEvent.AdvancementEarnEvent.class,
                PlayerEvent.PlayerLoggedInEvent.class,
                PlayerEvent.PlayerLoggedOutEvent.class,
                PlayerEvent.PlayerRespawnEvent.class,
                ServerTickEvent.Post.class),
            eventTypes);
        assertTrue(registered.get());
    }

    @Test
    void partialRegistrationFailsClosedAndDoesNotPermitSilentRetry() {
        RecordingTarget target = new RecordingTarget();
        AtomicBoolean registered = new AtomicBoolean();
        StoryGameEvents.Registrar registrar = new StoryGameEvents.Registrar() {
            @Override
            public <T extends Event> void addListener(
                Class<T> eventType,
                java.util.function.Consumer<T> listener
            ) {
                throw new IllegalStateException("bus rejected " + eventType.getName());
            }
        };

        assertThrowsExactly(IllegalStateException.class, () ->
            StoryGameEvents.register(registrar, target, registered));
        assertThrowsExactly(IllegalStateException.class, () ->
            StoryGameEvents.register(registrar, target, registered));
        assertTrue(registered.get());
    }

    @Test
    void productionPartialRegistrationClearsTargetAndRefusesRetry() {
        NullGameplayTarget target = new NullGameplayTarget();
        StoryGameEvents.Registrar failingRegistrar = new StoryGameEvents.Registrar() {
            @Override
            public <T extends Event> void addListener(
                Class<T> eventType,
                java.util.function.Consumer<T> listener
            ) {
                throw new IllegalStateException("production bus rejected listener");
            }
        };

        assertThrowsExactly(IllegalStateException.class, () ->
            StoryGameEvents.registerProductionForTest(failingRegistrar, target));
        assertNull(StoryGameEvents.productionTargetForTest());
        assertTrue(StoryGameEvents.productionRegistrationFailedForTest());

        NullGameplayTarget retryTarget = new NullGameplayTarget();
        assertThrowsExactly(IllegalStateException.class, () ->
            StoryGameEvents.registerProductionForTest(
                new StoryGameEvents.Registrar() {
                    @Override
                    public <T extends Event> void addListener(
                        Class<T> eventType,
                        java.util.function.Consumer<T> listener
                    ) {}
                },
                retryTarget));
        assertNull(StoryGameEvents.productionTargetForTest());
    }

    private static final class RecordingTarget implements GameplayTarget<String> {
        private final List<String> operations = new ArrayList<>();
        private String lastLifecyclePlayer;
        private boolean sessionOpen = true;
        private boolean dispatched;
        private boolean reconciled;
        private boolean sent;
        private boolean ticked;

        @Override
        public void dispatch(String player, StoryEvent event) {
            operations.add("dispatch:" + event.type() + ":" + event.key() + ":" + event.amount());
            dispatched = true;
        }

        @Override
        public void reconcile(String player) {
            operations.add("reconcile");
            lastLifecyclePlayer = player;
            reconciled = true;
        }

        @Override
        public void sendNow(String player) {
            operations.add("send");
            lastLifecyclePlayer = player;
            sent = true;
        }

        @Override
        public boolean sessionOpen(String player) {
            return sessionOpen;
        }

        @Override
        public void cleanup(String player) {
            operations.add("cleanup");
        }

        @Override
        public void tick(net.minecraft.server.MinecraftServer server) {
            operations.add("tick");
            ticked = true;
        }
    }

    private static final class NullGameplayTarget
        implements GameplayTarget<net.minecraft.server.level.ServerPlayer> {
        @Override
        public void dispatch(
            net.minecraft.server.level.ServerPlayer player,
            StoryEvent event
        ) {}

        @Override
        public void reconcile(net.minecraft.server.level.ServerPlayer player) {}

        @Override
        public void sendNow(net.minecraft.server.level.ServerPlayer player) {}

        @Override
        public boolean sessionOpen(net.minecraft.server.level.ServerPlayer player) {
            return false;
        }

        @Override
        public void cleanup(net.minecraft.server.level.ServerPlayer player) {}

        @Override
        public void tick(net.minecraft.server.MinecraftServer server) {}
    }
}
