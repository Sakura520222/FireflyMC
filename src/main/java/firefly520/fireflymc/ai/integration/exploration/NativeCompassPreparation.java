package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Exact local 1.21.1 non-blocking native scheduler, limited to the zero-neighbour STRUCTURE_STARTS stage. */
final class NativeCompassPreparation {
    private static final TicketType<UUID> TICKET = TicketType.create("fireflymc_compass_preparation", UUID::compareTo);
    private final MinecraftServer server;
    private final NativeSaveObserver saves;
    private final Method schedule;
    private final Method saveWrapped;
    private final Set<Request> requests = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int distance = ChunkLevel.byStatus(ChunkStatus.FULL) - ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);
    record Prepared(ChunkAccess chunk, NativeSaveBarrier.Receipt save, String saveReason) { }
    final class Request {
        final ServerLevel level;
        final ChunkPos chunk;
        final UUID ticket = UUID.randomUUID();
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        boolean released;
        Request(ServerLevel level, ChunkPos chunk) { this.level = level; this.chunk = chunk; }
        CompletableFuture<ChunkResult<ChunkAccess>> future() { return future; }
        void release() {
            thread(); if (released) return;
            level.getChunkSource().removeRegionTicket(TICKET, chunk, distance, ticket); released = true; requests.remove(this);
        }
    }
    NativeCompassPreparation(MinecraftServer server, NativeSaveObserver saves) throws ReflectiveOperationException {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.saves = saves;
        // Public getChunkFuture also managedBlock()s when called on the server thread in this exact MC version.
        schedule = ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread", int.class, int.class, ChunkStatus.class, boolean.class);
        saveWrapped = ChunkMap.class.getDeclaredMethod("save", ChunkAccess.class);
        if (!schedule.trySetAccessible() || !saveWrapped.trySetAccessible() || distance >= 0) throw new IllegalStateException("COMPASS_ASYNC_PREPARATION_CONTRACT_UNAVAILABLE");
    }
    @SuppressWarnings("unchecked") Request dispatch(ServerLevel level, ChunkPos chunk) throws ReflectiveOperationException {
        thread();
        if (level.getServer() != server || requests.size() >= 32) throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_BACKLOG");
        var request = new Request(level, chunk); requests.add(request);
        // Region-ticket distance is 33-ticketLevel; negative distance is the exact native STRUCTURE_STARTS level,
        // not a FULL ticket. This stage's actual ChunkPyramid has zero neighbouring generation requirements.
        level.getChunkSource().addRegionTicket(TICKET, chunk, distance, request.ticket);
        try {
            Object value = schedule.invoke(level.getChunkSource(), chunk.x, chunk.z, ChunkStatus.STRUCTURE_STARTS, true);
            if (!(value instanceof CompletableFuture<?>)) throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_FUTURE_INVALID");
            request.future = (CompletableFuture<ChunkResult<ChunkAccess>>) value; return request;
        } catch (InvocationTargetException failure) {
            request.release();
            throw new ReflectiveOperationException("COMPASS_PREPARATION_DISPATCH_UNCERTAIN", failure.getCause());
        } catch (RuntimeException | ReflectiveOperationException failure) { request.release(); throw failure; }
    }
    Prepared completed(Request request) {
        thread();
        if (!requests.contains(request) || request.released || !request.future.isDone()) throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_NOT_READY");
        try {
            var result = request.future.resultNow(); var chunk = result.orElse(null);
            if (chunk == null || !chunk.getPos().equals(request.chunk) || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS))
                throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_RESULT_UNCERTAIN");
            ChunkAccess physical = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
            var receipt = saves.changed(List.of(NativeSaveObserver.Target.chunk(physical,
                    NativeSaveObserver.dimensionFolder(request.level).resolve("region"), request.chunk.toLong())));
            // Bounded persistence request: mark only the actual native instance dirty, then wait for its true
            // canonical serializer and native writer/flush. The flag is not a receipt and changes no terrain.
            physical.setUnsaved(true);
            String saveReason = "NATIVE_STRUCTURE_STARTS_AWAITING_CANONICAL_UNLOAD_SAVE";
            if (chunk instanceof ImposterProtoChunk) {
                // The holder may remain an ImposterProtoChunk at this ticket level. Its isUnsaved()
                // always returns false, so ordinary holder saving never serializes the wrapped object.
                // Invoke the exact native save path once on that existing LevelChunk, with no FULL
                // scheduling, custom NBT or generated receipt. The observer still requires its real
                // ChunkSerializer payload and successful region writer/flush acknowledgement.
                try {
                    saveReason = Boolean.TRUE.equals(saveWrapped.invoke(request.level.getChunkSource().chunkMap, physical))
                            ? "NATIVE_WRAPPED_FULL_CANONICAL_WRITE_SUBMITTED" : "NATIVE_WRAPPED_FULL_SAVE_NOT_ACCEPTED";
                } catch (ReflectiveOperationException | RuntimeException failed) { saveReason = "NATIVE_WRAPPED_FULL_SAVE_SUBMISSION_UNCERTAIN"; }
            }
            return new Prepared(chunk, receipt, saveReason);
        } finally { request.release(); }
    }
    void releaseAfterFailedFuture(Request request) { thread(); if (request.future.isDone()) request.release(); }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
}
