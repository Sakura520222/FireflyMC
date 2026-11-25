package firefly520.fireflymc.ai.blueprint.ownership;

import firefly520.fireflymc.ai.blueprint.BlueprintBlockStateCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Current-object ownership only. It never places/removes blocks or creates an AI workspace. */
public final class NativeOwnership {
    private record Runtime(UUID id, OwnershipRevocations revocations) {}
    private static final Map<MinecraftServer, Runtime> ACTIVE = new IdentityHashMap<>();
    private static final ThreadLocal<Scope> WRITING = new ThreadLocal<>();
    private NativeOwnership() {}
    public static synchronized void install(MinecraftServer server, UUID runtime) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        Objects.requireNonNull(runtime);
        if (ACTIVE.putIfAbsent(server, new Runtime(runtime, new OwnershipRevocations(65536))) != null) throw new IllegalStateException("OWNERSHIP_ALREADY_INSTALLED");
    }
    public static synchronized void remove(MinecraftServer server, UUID runtime) {
        var value = ACTIVE.get(server); if (value != null && value.id().equals(runtime)) ACTIVE.remove(server);
    }
    private static synchronized UUID runtime(ServerLevel level) {
        if (!level.getServer().isDedicatedServer() || !level.getServer().isSameThread())
            throw new IllegalStateException("DEDICATED_SERVER_THREAD_REQUIRED");
        var runtime = ACTIVE.get(level.getServer());
        if (runtime == null) throw new IllegalStateException("OWNERSHIP_SERVICE_UNAVAILABLE");
        if (runtime.revocations().exhausted()) throw new IllegalStateException("OWNERSHIP_REVOCATION_INDEX_EXHAUSTED");
        return runtime.id();
    }
    /** Open before the audited native interaction; finish only after all callbacks were accepted. */
    public static Scope begin(ServerLevel level, Map<BlockPos, BlockState> before) {
        return new Scope(level, runtime(level), before);
    }
    /** Call in addition to the existing material, approval, protection and native-removal checks. */
    public static void require(ServerLevel level, OwnershipToken token) {
        UUID runtime = runtime(level);
        if (!token.runtimeSession().equals(runtime)) throw new IllegalStateException("OWNERSHIP_RESTART_CONTINUITY_UNPROVEN");
        if (!token.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("OWNERSHIP_DIMENSION_CHANGED");
        var position = BlockPos.of(token.position()); var chunk = loaded(level, position);
        OwnershipNativeHooks.bind(chunk);
        // Block entities, fluids and scheduled block/fluid ticks require separate audited undo adapters.
        requireSimple(level, position);
        synchronized (NativeOwnership.class) {
            var active = ACTIVE.get(level.getServer());
            if (active == null || !active.id().equals(runtime)) throw new IllegalStateException("OWNERSHIP_SERVICE_UNAVAILABLE");
            if (active.revocations().revoked(token)) throw new IllegalStateException("OWNERSHIP_NONCE_OR_GENERATION_CHANGED");
        }
        ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().require(token, runtime, describe(level.getBlockState(position)));
    }
    public static void retire(ServerLevel level, OwnershipToken token) {
        require(level, token); var chunk = loaded(level, BlockPos.of(token.position()));
        revokeCell(chunk, token.position());
        ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().changed(token.position()); chunk.setUnsaved(true);
    }
    public static synchronized void revokeCell(LevelChunk chunk, long position) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        var runtime = ACTIVE.get(level.getServer()); if (runtime == null) return;
        var token = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().claimAt(position);
        if (token != null) runtime.revocations().claim(token.claimNonce());
    }
    public static synchronized void revokeChunk(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        var runtime = ACTIVE.get(level.getServer()); if (runtime == null) return;
        runtime.revocations().chunk(((OwnershipAccess.Chunk) chunk).fireflymc$ownership().nonce());
    }
    static synchronized void restore(ServerLevel level, LevelChunk chunk, OwnershipChunkState state, OwnershipChunkState.Snapshot stored, boolean altered) {
        var runtime = ACTIVE.get(level.getServer());
        if (runtime != null) runtime.revocations().replacedSource(location(level, chunk.getPos()), stored.chunkNonce());
        // Loading callbacks can alter a chunk before its stored nonce has been installed. Retire
        // that source nonce too, otherwise a later same-JVM reread of the old disk tag could revive it.
        if (runtime != null && (altered || stored.unsafe() || state.snapshot().unsafe())) runtime.revocations().chunk(stored.chunkNonce());
        state.restoreWithinRuntime(stored, runtime == null || !runtime.revocations().chunkRevoked(stored.chunkNonce()), altered, runtime == null ? null : runtime.id(),
                token -> runtime == null || runtime.revocations().revoked(token));
    }
    static synchronized void revokeStoredNonce(ServerLevel level, UUID nonce) {
        var runtime = ACTIVE.get(level.getServer()); if (runtime != null) runtime.revocations().chunk(nonce);
    }
    static synchronized void missingMarker(ServerLevel level, net.minecraft.world.level.ChunkPos position) {
        var runtime = ACTIVE.get(level.getServer());
        if (runtime != null) runtime.revocations().missingMarker(location(level, position));
    }
    private static synchronized void revocationTarget(ServerLevel level, LevelChunk chunk, UUID nonce) {
        var runtime = ACTIVE.get(level.getServer());
        if (runtime == null) throw new IllegalStateException("OWNERSHIP_SERVICE_UNAVAILABLE");
        runtime.revocations().target(location(level, chunk.getPos()), nonce);
        if (runtime.revocations().exhausted()) throw new IllegalStateException("OWNERSHIP_REVOCATION_INDEX_EXHAUSTED");
    }
    private static String location(ServerLevel level, net.minecraft.world.level.ChunkPos position) {
        return level.dimension().location() + ":" + position.x + ":" + position.z;
    }
    public static String describe(BlockState state) {
        return firefly520.fireflymc.ai.storage.AiJson.canonical(BlueprintBlockStateCodec.describe(state));
    }
    private static LevelChunk loaded(ServerLevel level, BlockPos position) {
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("OWNERSHIP_CHUNK_NOT_LOADED");
        return chunk;
    }
    private static void requireSimple(ServerLevel level, BlockPos position) {
        var state = level.getBlockState(position);
        if (state.hasBlockEntity() || level.getBlockEntity(position) != null || !state.getFluidState().isEmpty()
                || level.getBlockTicks().hasScheduledTick(position, state.getBlock())
                || level.getFluidTicks().hasScheduledTick(position, state.getFluidState().getType()))
            throw new IllegalStateException("OWNERSHIP_SPECIAL_NATIVE_STATE_UNSUPPORTED");
    }
    static void observed(LevelChunk chunk, BlockPos pos, Object before, Object after) {
        var scope = WRITING.get();
        if (scope == null) return;
        if (chunk.getLevel() != scope.level || !(before instanceof BlockState old) || !(after instanceof BlockState next)) {
            scope.transitions.unobserved(); return;
        }
        scope.transitions.changed(pos.asLong(), describe(old), describe(next));
    }
    static void unobserved(LevelChunk chunk) {
        var scope = WRITING.get(); if (scope != null) scope.transitions.unobserved();
    }
    static boolean observing() { return WRITING.get() != null; }
    public static final class Scope implements AutoCloseable {
        private final ServerLevel level;
        private final UUID runtime;
        private final OwnershipTransitions transitions;
        private final Map<BlockPos, LevelChunk> chunks = new LinkedHashMap<>();
        private boolean finished, closed;
        private Scope(ServerLevel level, UUID runtime, Map<BlockPos, BlockState> before) {
            if (WRITING.get() != null) throw new IllegalStateException("OWNERSHIP_NESTED_NATIVE_SCOPE");
            this.level = level; this.runtime = runtime;
            var expected = new LinkedHashMap<Long, String>();
            for (var entry : before.entrySet()) {
                var position = entry.getKey().immutable(); var chunk = loaded(level, position);
                OwnershipNativeHooks.bind(chunk); requireSimple(level, position);
                if (level.getBlockState(position) != entry.getValue()) throw new IllegalStateException("OWNERSHIP_BEFORE_STATE_CHANGED");
                chunks.put(position, chunk); expected.put(position.asLong(), describe(entry.getValue()));
            }
            transitions = new OwnershipTransitions(expected); WRITING.set(this);
        }
        public List<OwnershipToken> finish(OwnershipToken.Owner owner, Map<BlockPos, BlockState> after) {
            if (closed || finished || WRITING.get() != this || !runtime.equals(runtime(level)))
                throw new IllegalStateException("OWNERSHIP_NATIVE_SCOPE_CLOSED");
            var actual = new LinkedHashMap<Long, String>();
            for (var entry : after.entrySet()) {
                var position = entry.getKey(); var chunk = loaded(level, position);
                if (chunks.get(position) != chunk || level.getBlockState(position) != entry.getValue())
                    throw new IllegalStateException("OWNERSHIP_AFTER_STATE_CHANGED");
                OwnershipNativeHooks.bind(chunk); requireSimple(level, position);
                var snapshot = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().snapshot();
                if (snapshot.unsafe()) throw new IllegalStateException(snapshot.reason());
                actual.put(position.asLong(), describe(entry.getValue()));
            }
            var witnessed = transitions.require(actual); var result = new ArrayList<OwnershipToken>();
            try {
                for (var entry : witnessed.entrySet()) {
                    var chunk = chunks.get(BlockPos.of(entry.getKey())); var value = entry.getValue();
                    var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership();
                    if (state.revision() == Long.MAX_VALUE) revokeChunk(chunk);
                    revocationTarget(level, chunk, state.nonce());
                    result.add(state.claim(runtime,
                            level.dimension().location().toString(), entry.getKey(), owner, UUID.randomUUID(), value.before(), value.after()));
                    chunk.setUnsaved(true);
                }
                finished = true; return List.copyOf(result);
            } catch (RuntimeException failure) {
                for (var token : result) {
                    var chunk = chunks.get(BlockPos.of(token.position())); revokeCell(chunk, token.position());
                    ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().changed(token.position());
                }
                throw failure;
            }
        }
        @Override public void close() { closed = true; if (WRITING.get() == this) WRITING.remove(); }
    }
}
