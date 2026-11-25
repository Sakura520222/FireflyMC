package firefly520.fireflymc.ai.integration.exploration;

import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Only explicitly bound AI workers are constrained. Ordinary native compass workers are left unchanged. */
public final class NativeCompassSearchScope {
    public interface Job {
        void started(Object worker);
        boolean work(Object worker, BooleanSupplier original);
        void biomeCheck(int blockX, int blockY, int blockZ);
        void structureCheck(Object worker, ChunkPos candidate);
        void structureMatch(ChunkPos candidate, Pair<BlockPos, Structure> found);
        default ChunkAccess structureChunk(ServerLevel level, int x, int z, ChunkStatus status) {
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null || !chunk.getPersistedStatus().isOrAfter(status)) throw new CompassSearchBudget.Stopped("COMPASS_UNLOADED_CHUNK_REQUIRED");
            return chunk;
        }
    }
    private static final ThreadLocal<Job> ACTIVE = new ThreadLocal<>();
    private static final Map<Object, Job> OWNED = new IdentityHashMap<>();
    private NativeCompassSearchScope() {}
    public static void captureStarted(Object worker) {
        var job = ACTIVE.get(); if (job == null) return;
        job.started(worker); synchronized (OWNED) {
            var existing = OWNED.putIfAbsent(worker, job);
            if (existing != null && existing != job) throw new IllegalStateException("COMPASS_WORKER_ALREADY_BOUND");
        }
    }
    public static boolean work(Object worker, BooleanSupplier original) {
        Job job; synchronized (OWNED) { job = OWNED.get(worker); }
        return job == null ? original.getAsBoolean() : job.work(worker, original);
    }
    public static void run(Job job, Runnable action) {
        if (ACTIVE.get() != null) throw new IllegalStateException("COMPASS_SCOPE_NESTED");
        ACTIVE.set(job); try { action.run(); } finally { ACTIVE.remove(); }
    }
    public static void unbind(Job job) { synchronized (OWNED) { OWNED.values().removeIf(value -> value == job); } }
    public static void beforeBiome(int quartX, int quartY, int quartZ) {
        var job = ACTIVE.get(); if (job != null) job.biomeCheck(Math.multiplyExact(quartX, 4), Math.multiplyExact(quartY, 4), Math.multiplyExact(quartZ, 4));
    }
    public static void beforeStructure(Object worker, ChunkPos candidate) {
        var job = ACTIVE.get(); if (job != null) job.structureCheck(worker, candidate);
    }
    public static void structureMatch(ChunkPos candidate, Pair<BlockPos, Structure> found) {
        var job = ACTIVE.get(); if (job != null && found != null) job.structureMatch(candidate, found);
    }
    public static boolean active() { return ACTIVE.get() != null; }
    public static ChunkAccess loadedChunk(ServerLevel level, int x, int z, ChunkStatus status) {
        var job = ACTIVE.get(); if (job == null) throw new IllegalStateException("COMPASS_OWNED_SCOPE_REQUIRED");
        return job.structureChunk(level, x, z, status);
    }
}
