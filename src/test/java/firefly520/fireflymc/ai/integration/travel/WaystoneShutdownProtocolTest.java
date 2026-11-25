package firefly520.fireflymc.ai.integration.travel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hook/drain lifecycle only; actual native fee/movement/save behavior belongs to the dedicated fixture. */
class WaystoneShutdownProtocolTest {
    private static final class Guard implements NativeWaystoneTravelHooks.Guard {
        final Object failure = new Object(); final AtomicInteger entered = new AtomicInteger();
        public Object beforeFees(Object destination) { entered.incrementAndGet(); return null; }
        public boolean beforeEntity(Object event) { entered.incrementAndGet(); return true; }
        public void refuse(String reason) {}
        public Set<ChunkPos> chunks() { return Set.of(new ChunkPos(0, 0)); }
        public Object failedResult() { return failure; }
    }
    private static final class EqualContext {
        @Override public boolean equals(Object other) { return other instanceof EqualContext; }
        @Override public int hashCode() { return 1; }
    }
    public static final class NativeEventShape {
        final Object context; boolean cancelled;
        NativeEventShape(Object context) { this.context = context; }
        public Object getContext() { return context; }
        public Optional<Object> getOverrideResult() { return Optional.of("would otherwise claim a successful teleport"); }
        public void setCanceled(boolean value) { cancelled = value; }
    }
    @Test void closingAnUnfinishedContextKeepsARefusalTombstoneWithoutWaitingForAnotherTick() {
        Object context = new Object(); var guard = new Guard(); var neverFinishesWithoutAnotherTick = new CompletableFuture<>();
        NativeWaystoneTravelHooks.register(context, guard);
        try {
            assertNull(NativeWaystoneTravelHooks.beforeFees(context, new Object())); assertEquals(1, guard.entered.get());
            NativeWaystoneTravelHooks.closeContext(context);
            assertSame(guard.failure, NativeWaystoneTravelHooks.beforeFees(context, new Object()));
            var event = new NativeEventShape(context); assertTrue(NativeWaystoneTravelHooks.entityOverride(event).isEmpty()); assertTrue(event.cancelled);
            assertEquals(1, guard.entered.get(), "closed hooks do not need a live service, player or completed native future");
            assertFalse(neverFinishesWithoutAnotherTick.isDone());
        } finally { NativeWaystoneTravelHooks.remove(context); }
    }
    @Test void equalNativeContextValuesCannotShareAnotherPlayersGuardOrTombstone() {
        var first = new EqualContext(); var second = new EqualContext(); var a = new Guard(); var b = new Guard();
        NativeWaystoneTravelHooks.register(first, a); NativeWaystoneTravelHooks.register(second, b);
        try {
            NativeWaystoneTravelHooks.closeContext(first);
            assertSame(a.failure, NativeWaystoneTravelHooks.beforeFees(first, new Object()));
            assertNull(NativeWaystoneTravelHooks.beforeFees(second, new Object()));
            assertEquals(0, a.entered.get()); assertEquals(1, b.entered.get());
        } finally { NativeWaystoneTravelHooks.remove(first); NativeWaystoneTravelHooks.remove(second); }
    }
    @Test void boundedEvidenceDrainWaitsForAcceptedIOButNotForUnfinishedMinecraftWork() throws Exception {
        var drain = new AcceptedTravelEvidence(); var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1); var nativeFuture = new CompletableFuture<>(); var writes = new AtomicInteger();
        try {
            drain.submit(executor, () -> {
                entered.countDown(); try { release.await(); writes.incrementAndGet(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertFalse(drain.await(5)); release.countDown();
            assertTrue(drain.await(1000)); assertEquals(1, writes.get()); assertFalse(nativeFuture.isDone());
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(1, TimeUnit.SECONDS); }
    }
    @Test void rejectedEvidenceSubmissionCannotLeakTheDrainCountOrRunAWriter() throws Exception {
        var drain = new AcceptedTravelEvidence(); var writes = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> drain.submit(action -> { throw new RejectedExecutionException(); }, writes::incrementAndGet));
        assertTrue(drain.await(0)); assertEquals(0, writes.get());
    }
}
