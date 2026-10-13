package firefly520.fireflymc.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.client.ClientPayloadHandler;
import firefly520.fireflymc.client.story.ClientStoryState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class StoryClientMirrorTest {
    private static final String REVISION_A = "a".repeat(64);
    private static final String REVISION_B = "b".repeat(64);

    @BeforeEach
    void resetMirror() {
        ClientStoryState.clear();
    }

    @Test
    void emptyDefaultIsImmutableEmptySnapshot() {
        ClientStoryState.clear();

        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
        assertTrue(ClientStoryState.snapshot().completedQuests().isEmpty());
        assertTrue(ClientStoryState.snapshot().activeQuests().isEmpty());
        assertTrue(ClientStoryState.snapshot().playerFlags().isEmpty());
        assertTrue(ClientStoryState.snapshot().playerVars().isEmpty());
        assertTrue(ClientStoryState.snapshot().worldFlags().isEmpty());
        assertTrue(ClientStoryState.snapshot().worldVars().isEmpty());
        assertTrue(ClientStoryState.snapshot().counters().isEmpty());
        assertTrue(ClientStoryState.snapshot().satisfied().isEmpty());
        assertTrue(ClientStoryState.snapshot().trackedQuest().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .completedQuests().add(ResourceLocation.parse("fireflymc:mutation")));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .activeQuests().add(ResourceLocation.parse("fireflymc:mutation")));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .playerFlags().put("mutation", true));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .playerVars().put("mutation", 1));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .worldFlags().put("mutation", true));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .worldVars().put("mutation", 1));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .counters().add(Map.entry(new RequirementProgressKey(
                ResourceLocation.parse("fireflymc:mutation"), "counter", 1), 1)));
        assertThrows(UnsupportedOperationException.class, () -> ClientStoryState.EMPTY
            .satisfied().add(new RequirementProgressKey(
                ResourceLocation.parse("fireflymc:mutation"), "satisfied", 1)));
    }

    @Test
    void installReplacesOneVolatileReferenceAtomically() throws Exception {
        StoryProgressSnapshotPayload first = payload(REVISION_A, "fireflymc:first");
        StoryProgressSnapshotPayload second = payload(REVISION_B, "fireflymc:second");

        ClientStoryState.install(first);
        assertSame(first, ClientStoryState.snapshot());

        assertThrows(NullPointerException.class, () -> ClientStoryState.install(null));
        assertSame(first, ClientStoryState.snapshot());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AtomicBoolean stopped = new AtomicBoolean();
            Future<?> writer = executor.submit(() -> {
                for (int index = 0; index < 10_000 && !stopped.get(); index++) {
                    ClientStoryState.install(index % 2 == 0 ? first : second);
                }
            });
            Future<?> reader = executor.submit(() -> {
                for (int index = 0; index < 10_000; index++) {
                    StoryProgressSnapshotPayload observed = ClientStoryState.snapshot();
                    assertNotNull(observed);
                    assertTrue(
                        observed == first || observed == second,
                        "reader observed a mixed or unexpected snapshot generation"
                    );
                }
            });
            reader.get(5, TimeUnit.SECONDS);
            stopped.set(true);
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ClientStoryState.install(second);
        assertSame(second, ClientStoryState.snapshot());
        ClientStoryState.clear();
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
    }

    @Test
    void packetHandlerEnqueuesInstallAndDoesNotMutateOnContextThread() {
        StoryProgressSnapshotPayload payload = payload(REVISION_A, "fireflymc:first");
        List<Runnable> enqueuedTasks = new ArrayList<>();
        IPayloadContext context = recordingContext(enqueuedTasks);

        ClientPayloadHandler.handleStoryProgressSnapshot(payload, context);

        assertEquals(1, enqueuedTasks.size());
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
        enqueuedTasks.forEach(Runnable::run);
        assertSame(payload, ClientStoryState.snapshot());
    }

    @Test
    void clientDispatchUsesReflectionAndDedicatedServerBranchDoesNothing() {
        StoryProgressSnapshotPayload payload = payload(REVISION_A, "fireflymc:first");
        List<Runnable> enqueuedTasks = new ArrayList<>();
        IPayloadContext context = recordingContext(enqueuedTasks);

        ModNetwork.dispatchStoryProgressSnapshotOnClient(payload, context, false);
        assertTrue(enqueuedTasks.isEmpty());
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

        ModNetwork.dispatchStoryProgressSnapshotOnClient(payload, context, true);
        assertEquals(1, enqueuedTasks.size());
        enqueuedTasks.forEach(Runnable::run);
        assertSame(payload, ClientStoryState.snapshot());
    }

    @Test
    void dedicatedServerBranchNeverAsksForClientHandlerClass() {
        StoryProgressSnapshotPayload payload = payload(REVISION_A, "fireflymc:first");
        List<Runnable> enqueuedTasks = new ArrayList<>();
        List<Component> disconnects = new ArrayList<>();
        AtomicBoolean lookupInvoked = new AtomicBoolean();

        ModNetwork.dispatchStoryProgressSnapshotOnClient(
            payload,
            recordingContext(enqueuedTasks, disconnects),
            false,
            handlerName -> {
                lookupInvoked.set(true);
                return Object.class;
            },
            (message, exception) -> {
                throw new AssertionError("dedicated branch must not report a dispatch failure");
            }
        );

        assertFalse(lookupInvoked.get());
        assertTrue(enqueuedTasks.isEmpty());
        assertTrue(disconnects.isEmpty());
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
    }

    @Test
    void reflectionFailureDisconnectsWithDiagnosticAndLeavesMirrorEmpty() {
        StoryProgressSnapshotPayload payload = payload(REVISION_A, "fireflymc:first");
        List<Runnable> enqueuedTasks = new ArrayList<>();
        List<Component> disconnects = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        ModNetwork.dispatchStoryProgressSnapshotOnClient(
            payload,
            recordingContext(enqueuedTasks, disconnects),
            true,
            handlerName -> Object.class,
            (message, exception) -> diagnostics.add(
                message + ":" + exception.getClass().getSimpleName())
        );

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().contains("Failed to dispatch story progress snapshot"));
        assertTrue(diagnostics.getFirst().contains("NoSuchMethodException"));
        assertEquals(1, disconnects.size());
        assertNotNull(disconnects.getFirst());
        assertTrue(enqueuedTasks.isEmpty());
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
    }

    @Test
    void storySnapshotRegistersAsPlayClientboundPayloadWithNetworkVersion() throws Exception {
        Map<ConnectionProtocol, Set<ResourceLocation>> registrationsBefore = registeredPayloadIds();
        try {
            ModNetwork.registerPayloads(new RegisterPayloadHandlersEvent());

            Field registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            registrationsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> registrations =
                (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) registrationsField.get(null);
            PayloadRegistration<?> registration = registrations
                .get(ConnectionProtocol.PLAY)
                .get(StoryProgressSnapshotPayload.TYPE.id());

            assertNotNull(registration);
            assertSame(StoryProgressSnapshotPayload.TYPE, registration.type());
            assertSame(StoryProgressSnapshotPayload.STREAM_CODEC, registration.codec());
            assertEquals(Optional.of(PacketFlow.CLIENTBOUND), registration.flow());
            assertEquals(List.of(ConnectionProtocol.PLAY), registration.protocols());
            assertEquals("1.5.0", registration.version());
            assertEquals("1.5.0", ModNetwork.NETWORK_VERSION);

            // Invoke the exact handler stored by NeoForge. Its MAIN wrapper enqueues once;
            // the wrapped story dispatcher then enqueues installation on the client handler.
            StoryProgressSnapshotPayload payload = payload(REVISION_A, "fireflymc:first");
            List<Runnable> enqueuedTasks = new ArrayList<>();
            List<Component> disconnects = new ArrayList<>();
            @SuppressWarnings("unchecked")
            IPayloadHandler<StoryProgressSnapshotPayload> registeredHandler =
                (IPayloadHandler<StoryProgressSnapshotPayload>) registration.handler();
            IPayloadContext context = recordingContext(enqueuedTasks, disconnects);

            // A unit JVM reports the dedicated dist. Run only this invocation through the
            // package-private deterministic client gate so the production registration's
            // complete MAIN-wrapper path can be exercised without changing global state.
            ModNetwork.withClientDistOverrideForTest(true, () -> {
                registeredHandler.handle(payload, context);
                assertEquals(1, enqueuedTasks.size());
                assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

                enqueuedTasks.getFirst().run();
                assertEquals(2, enqueuedTasks.size());
                assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

                enqueuedTasks.getLast().run();
                assertSame(payload, ClientStoryState.snapshot());
                assertTrue(disconnects.isEmpty());
            });
        } finally {
            removePayloadsRegisteredAfter(registrationsBefore);
        }
        assertEquals(registrationsBefore, registeredPayloadIds());
    }

    @Test
    void clientLifecycleHandlersClearOnLogoutAndNewWorldTransition() throws Exception {
        Method logout = ClientPayloadHandler.class.getMethod(
            "onClientLoggedOut", ClientPlayerNetworkEvent.LoggingOut.class);
        Method worldTransition = ClientPayloadHandler.class.getMethod(
            "onClientWorldTransition", ClientPlayerNetworkEvent.LoggingIn.class);
        assertAnnotation(logout, SubscribeEvent.class);
        assertAnnotation(worldTransition, SubscribeEvent.class);
        EventBusSubscriber subscriber = ClientPayloadHandler.class.getAnnotation(EventBusSubscriber.class);
        assertNotNull(subscriber);
        assertEquals("fireflymc", subscriber.modid());
        assertEquals(1, subscriber.value().length);
        assertEquals(Dist.CLIENT, subscriber.value()[0]);

        Constructor<ClientPlayerNetworkEvent.LoggingOut> logoutConstructor =
            ClientPlayerNetworkEvent.LoggingOut.class.getDeclaredConstructor(
                net.minecraft.client.multiplayer.MultiPlayerGameMode.class,
                net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.network.Connection.class
            );
        logoutConstructor.setAccessible(true);
        Constructor<ClientPlayerNetworkEvent.LoggingIn> loginConstructor =
            ClientPlayerNetworkEvent.LoggingIn.class.getDeclaredConstructor(
                net.minecraft.client.multiplayer.MultiPlayerGameMode.class,
                net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.network.Connection.class
            );
        loginConstructor.setAccessible(true);

        StoryProgressSnapshotPayload serverA = disjointPayload(
            REVISION_A,
            "fireflymc:server_a_quest",
            "favor_a",
            "world_count_a",
            "counter_a",
            "sticky_a"
        );
        ClientStoryState.install(serverA);
        logout.invoke(null, logoutConstructor.newInstance(null, null, null));
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

        ClientStoryState.install(serverA);
        worldTransition.invoke(null, loginConstructor.newInstance(null, null, null));
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());
    }

    @Test
    void anotherServersSnapshotNeverSurvivesTransitionToServerB() throws Exception {
        StoryProgressSnapshotPayload serverA = disjointPayload(
            REVISION_A,
            "fireflymc:server_a_quest",
            "favor_a",
            "world_count_a",
            "counter_a",
            "sticky_a"
        );
        StoryProgressSnapshotPayload serverB = disjointPayload(
            REVISION_B,
            "fireflymc:server_b_quest",
            "favor_b",
            "world_count_b",
            "counter_b",
            "sticky_b"
        );
        Method logout = ClientPayloadHandler.class.getMethod(
            "onClientLoggedOut", ClientPlayerNetworkEvent.LoggingOut.class);
        Method worldTransition = ClientPayloadHandler.class.getMethod(
            "onClientWorldTransition", ClientPlayerNetworkEvent.LoggingIn.class);
        Constructor<ClientPlayerNetworkEvent.LoggingOut> logoutConstructor =
            ClientPlayerNetworkEvent.LoggingOut.class.getDeclaredConstructor(
                net.minecraft.client.multiplayer.MultiPlayerGameMode.class,
                net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.network.Connection.class
            );
        logoutConstructor.setAccessible(true);
        Constructor<ClientPlayerNetworkEvent.LoggingIn> loginConstructor =
            ClientPlayerNetworkEvent.LoggingIn.class.getDeclaredConstructor(
                net.minecraft.client.multiplayer.MultiPlayerGameMode.class,
                net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.network.Connection.class
            );
        loginConstructor.setAccessible(true);

        ClientStoryState.install(serverA);
        logout.invoke(null, logoutConstructor.newInstance(null, null, null));
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

        ClientStoryState.install(serverA);
        worldTransition.invoke(null, loginConstructor.newInstance(null, null, null));
        assertSame(ClientStoryState.EMPTY, ClientStoryState.snapshot());

        ClientStoryState.install(serverB);
        assertSame(serverB, ClientStoryState.snapshot());
        assertFalse(ClientStoryState.snapshot().completedQuests().contains(
            ResourceLocation.parse("fireflymc:server_a_quest")));
        assertEquals(REVISION_B, ClientStoryState.snapshot().storyRevision());
    }

    private static StoryProgressSnapshotPayload payload(String revision, String completedQuest) {
        Set<ResourceLocation> activeQuests = Set.of();
        Map<RequirementProgressKey, Integer> counters = Map.of(
            new RequirementProgressKey(ResourceLocation.parse("fireflymc:quest"), "visit", 1),
            1
        );
        return new StoryProgressSnapshotPayload(
            Set.of(ResourceLocation.parse(completedQuest)),
            activeQuests,
            Map.of("progress", true),
            Map.of("favor", 2),
            Map.of("world_open", false),
            Map.of("world_count", 1),
            counters,
            Set.of(new RequirementProgressKey(
                ResourceLocation.parse("fireflymc:quest"), "sticky", 1)),
            Optional.<ResourceLocation>empty(),
            revision
        );
    }

    private static StoryProgressSnapshotPayload disjointPayload(
        String revision,
        String questId,
        String variableName,
        String worldVariableName,
        String counterName,
        String satisfiedName
    ) {
        ResourceLocation quest = ResourceLocation.parse(questId);
        String questPath = questId.substring(questId.indexOf(':') + 1);
        return new StoryProgressSnapshotPayload(
            Set.of(quest),
            Set.of(),
            Map.of("progress_" + questPath, true),
            Map.of(variableName, 2),
            Map.of("world_" + questPath, false),
            Map.of(worldVariableName, 1),
            Map.of(new RequirementProgressKey(quest, counterName, 1), 1),
            Set.of(new RequirementProgressKey(quest, satisfiedName, 1)),
            Optional.<ResourceLocation>empty(),
            revision
        );
    }

    private static IPayloadContext recordingContext(List<Runnable> enqueuedTasks) {
        return recordingContext(enqueuedTasks, new ArrayList<>());
    }

    private static IPayloadContext recordingContext(
        List<Runnable> enqueuedTasks,
        List<Component> disconnects
    ) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            if ("enqueueWork".equals(method.getName())) {
                if (args.length == 1 && args[0] instanceof Runnable task) {
                    enqueuedTasks.add(task);
                } else if (args.length == 1 && args[0] instanceof java.util.function.Supplier<?> task) {
                    enqueuedTasks.add(task::get);
                }
                return CompletableFuture.completedFuture(null);
            }
            if ("disconnect".equals(method.getName())) {
                disconnects.add((Component) args[0]);
                return null;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType.isPrimitive() && returnType != void.class) {
                return 0;
            }
            return null;
        };
        return (IPayloadContext) Proxy.newProxyInstance(
            StoryClientMirrorTest.class.getClassLoader(),
            new Class<?>[] { IPayloadContext.class },
            handler
        );
    }

    private static Map<ConnectionProtocol, Set<ResourceLocation>> registeredPayloadIds()
        throws Exception {
        Field registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> registrations =
            (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) registrationsField.get(null);
        Map<ConnectionProtocol, Set<ResourceLocation>> snapshot = new HashMap<>();
        registrations.forEach((protocol, byId) -> snapshot.put(protocol, Set.copyOf(byId.keySet())));
        return snapshot;
    }

    private static void removePayloadsRegisteredAfter(
        Map<ConnectionProtocol, Set<ResourceLocation>> before
    ) throws Exception {
        Field registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> registrations =
            (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) registrationsField.get(null);
        registrations.forEach((protocol, byId) -> {
            Set<ResourceLocation> existing = before.get(protocol);
            if (existing == null) {
                byId.clear();
                return;
            }
            byId.keySet().retainAll(existing);
        });
    }

    private static void assertAnnotation(
        Method method,
        Class<? extends Annotation> annotationType
    ) {
        assertNotNull(method.getAnnotation(annotationType));
    }
}
