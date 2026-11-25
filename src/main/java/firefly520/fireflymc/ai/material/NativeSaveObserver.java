package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.lang.ref.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Observes only saves containing previously registered native AI effects. No save is invented,
 * no global save is forced, and integrated servers never create an observer or an IO worker.
 */
public final class NativeSaveObserver {
    private record Captured(NativeSaveObserver observer, NativeSaveBarrier.Snapshot snapshot, String destination) {}
    public record Target(Object identity, String destination) {
        public Target { Objects.requireNonNull(identity); Objects.requireNonNull(destination); }
        public static Target file(Object identity, Path path) { return new Target(identity, fileKey(path)); }
        public static Target chunk(Object identity, Path folder, long chunk) { return new Target(identity, chunkKey(folder, chunk)); }
        public static Target entity(Object identity, Path folder) { return new Target(identity, entityKey(folder)); }
    }
    public static final class WriteSnapshot {
        private final List<Captured> captured;
        private WriteSnapshot(List<Captured> captured) { this.captured = List.copyOf(captured); }
        public boolean empty() { return captured.isEmpty(); }
    }
    private static final WriteSnapshot EMPTY = new WriteSnapshot(List.of());
    private static final Map<MinecraftServer, NativeSaveObserver> ACTIVE = new ConcurrentHashMap<>();
    private static final ReferenceQueue<CompoundTag> RELEASED = new ReferenceQueue<>();
    private static final Map<TagKey, NativeSerializationCaptures<Captured>> SERIALIZED = new HashMap<>();
    private static final NativeEntityWriteProofs<Captured> ENTITY_WRITES = new NativeEntityWriteProofs<>(32768);
    private static final class TagKey extends WeakReference<CompoundTag> {
        final int hash;
        TagKey(CompoundTag tag, ReferenceQueue<CompoundTag> queue) { super(tag, queue); hash = System.identityHashCode(tag); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof TagKey key && get() != null && get() == key.get();
        }
    }
    private final NativeSaveBarrier barrier = new NativeSaveBarrier();
    private final MinecraftServer server;
    private final NativeSaveDispatcher dispatcher;
    private final Map<Object, String> destinations = new IdentityHashMap<>();
    private final Map<Object, net.minecraft.world.entity.Entity> entityWitnesses = new IdentityHashMap<>();
    private static final class HeldWrite { String canonicalProof; }
    private final Map<UUID, IdentityHashMap<Object, HeldWrite>> heldWrites = new HashMap<>();
    private record EntityRegion(String folder, long position) {}
    private final Map<EntityRegion, IdentityHashMap<Object, UUID>> deletedEntities = new HashMap<>();
    private final ExecutorService receipts = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16384), Thread.ofPlatform().daemon(true).name("fireflymc-native-save").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final NativeReceiptIndex journalReadiness = new NativeReceiptIndex();
    private NativeSaveObserver(MinecraftServer server) { this.server = server; this.dispatcher = new NativeSaveDispatcher(server, this); }
    public static NativeSaveObserver forServer(MinecraftServer server) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        return ACTIVE.computeIfAbsent(server, NativeSaveObserver::new);
    }
    public void requireCapacity() {
        barrier.requireCapacity();
        if (dispatcher.metrics().queuedTargets() >= NativeSaveDispatchQueue.MAX_TARGETS)
            throw new IllegalStateException("NATIVE_SAVE_DISPATCH_BACKLOG");
    }
    public void requireCapacity(Collection<Target> targets) {
        barrier.requireCapacity(targets.stream().map(Target::identity).toList()); dispatcher.requireCapacity(targets);
    }
    /** Drains only an observer that already exists; disabled/integrated servers do not initialize AI. */
    public static void tickExisting(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        var observer = ACTIVE.get(server); if (observer != null) observer.dispatcher.tick();
    }
    public NativeSaveDispatchQueue.Metrics dispatchMetrics() { return dispatcher.metrics(); }
    /** A receipt failure must not discard an already-known native return or fabricate an empty extraction. */
    public NativeSaveBarrier.Receipt changedOrUnconfirmed(Collection<Target> targets) {
        try { return changed(targets); }
        catch (RuntimeException failure) {
            firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Native save observation unavailable after a known effect ({})", failure.getClass().getSimpleName());
            return null;
        }
    }
    public synchronized NativeSaveBarrier.Receipt changed(Collection<Target> targets) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        dispatcher.requireCapacity(targets);
        for (var target : targets) {
            String previous = destinations.get(target.identity());
            if (previous != null && !previous.equals(target.destination())) throw new IllegalStateException("NATIVE_SAVE_TARGET_MOVED");
        }
        var receipt = barrier.changed(targets.stream().map(Target::identity).toList());
        targets.forEach(target -> { destinations.put(target.identity(), target.destination()); dispatcher.offer(target); });
        return receipt;
    }
    public int pendingCount() { return barrier.pendingCount(); }
    public synchronized boolean pending(Object store) { return !barrier.snapshot(store).empty(); }
    /** Dispatch alias only. The witness's own exact native-tag matcher remains the proof authority. */
    public synchronized NativeSaveBarrier.Receipt changedEntityWitness(Object identity, net.minecraft.world.entity.Entity entity) {
        if (!server.isSameThread() || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)
                || level.getServer() != server || entity.isRemoved()) throw new IllegalStateException("NATIVE_ENTITY_WITNESS_CONTEXT");
        Objects.requireNonNull(identity);
        var previous = entityWitnesses.get(identity);
        if (previous != null && previous != entity) throw new IllegalStateException("NATIVE_ENTITY_WITNESS_CHANGED");
        entityWitnesses.put(identity, entity);
        try { return changed(List.of(new Target(identity, entity(entity).destination()))); }
        catch (RuntimeException failure) { if (previous == null) entityWitnesses.remove(identity); throw failure; }
    }
    synchronized net.minecraft.world.entity.Entity witnessEntity(Object identity) { return entityWitnesses.get(identity); }
    /** Suspend only fresh dependencies before a scoped native transition; prior handoffs remain chained. */
    public synchronized WriteSnapshot suspendEntity(Object store) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var fresh = new ArrayList<NativeSaveBarrier.Entry>();
        for (var entry : barrier.snapshot(store).entries()) {
            var held = heldWrites.computeIfAbsent(entry.receipt(), ignored -> new IdentityHashMap<>());
            if (!held.containsKey(store)) { held.put(store, new HeldWrite()); fresh.add(entry); }
        }
        return fresh.isEmpty() ? EMPTY : new WriteSnapshot(List.of(new Captured(this, new NativeSaveBarrier.Snapshot(fresh), destinations.get(store))));
    }
    /** A canceled/unchanged native transition may use the original exact canonical serialization. */
    public void unchanged(WriteSnapshot snapshot) {
        for (var captured : snapshot.captured) {
            var observer = captured.observer();
            for (var entry : captured.snapshot().entries()) {
                String proof = null;
                synchronized (observer) {
                    var byTarget = observer.heldWrites.get(entry.receipt());
                    var held = byTarget == null ? null : byTarget.remove(entry.target());
                    if (held != null) proof = held.canonicalProof;
                    if (byTarget != null && byTarget.isEmpty()) observer.heldWrites.remove(entry.receipt());
                }
                if (proof != null) acknowledge(new Captured(observer, new NativeSaveBarrier.Snapshot(List.of(entry)), captured.destination()), proof);
            }
        }
    }
    /** Only a positively observed native transition can replace an orb's pending physical store. */
    public void handoff(WriteSnapshot snapshot, NativeSaveBarrier.Receipt replacement, String nativeObservation) {
        Objects.requireNonNull(replacement); Objects.requireNonNull(nativeObservation);
        replacement.saved().whenComplete((proof, failure) -> {
            if (failure != null || proof == null || !proof.matches("[0-9a-f]{64}")) return;
            String transferred = AiJson.digest("native-entity-handoff:" + nativeObservation + ":" + replacement.id() + ":" + proof);
            for (var captured : snapshot.captured) {
                var observer = captured.observer();
                for (var entry : captured.snapshot().entries()) {
                    synchronized (observer) {
                        var byTarget = observer.heldWrites.get(entry.receipt());
                        if (byTarget == null || byTarget.remove(entry.target()) == null) continue;
                        if (byTarget.isEmpty()) observer.heldWrites.remove(entry.receipt());
                        observer.barrier.written(new NativeSaveBarrier.Snapshot(List.of(entry)), transferred);
                        if (observer.barrier.snapshot(entry.target()).empty()) {
                            observer.destinations.remove(entry.target()); observer.entityWitnesses.remove(entry.target());
                        }
                    }
                }
            }
        });
    }
    /** Exact native removal identity, not a later world-absence or matching-count inference. */
    public synchronized NativeSaveBarrier.Receipt entityRemoved(net.minecraft.world.entity.Entity entity) {
        if (!server.isSameThread() || !entity.isRemoved() || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)
                || level.getServer() != server) throw new IllegalStateException("NATIVE_ENTITY_REMOVAL_UNCONFIRMED");
        Object identity = new Object(); long position = entity.chunkPosition().toLong();
        String folder = dimensionFolder(level).resolve("entities").toAbsolutePath().normalize().toString();
        var receipt = changed(List.of(new Target(identity, chunkKey(Path.of(folder), position))));
        deletedEntities.computeIfAbsent(new EntityRegion(folder, position), ignored -> new IdentityHashMap<>()).put(identity, entity.getUUID());
        return receipt;
    }
    /** A scoped native null deletion needs a distinct post-transition payload identity. */
    public static CompoundTag entityRegionSubmission(net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.ChunkPos position, CompoundTag data) {
        CompoundTag submitted = data;
        if (submitted == null && entityDeletionPending(level, position.toLong())) {
            // This is the native EntityStorage format, including its data version. An old queued null
            // write has no identity and can never borrow the post-removal snapshot attached below.
            submitted = net.minecraft.nbt.NbtUtils.addCurrentDataVersion(new CompoundTag());
            submitted.put("Entities", new net.minecraft.nbt.ListTag());
            submitted.putIntArray("Position", new int[] {position.x, position.z});
        }
        if (submitted != null) entityRegionSerialized(level, position, submitted);
        return submitted;
    }
    /** Called at native EntityStorage serialization, before its exact write is submitted. */
    public static WriteSnapshot entityRegionSerialized(net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.ChunkPos position, CompoundTag data) {
        var observer = ACTIVE.get(level.getServer());
        if (observer == null || !level.getServer().isSameThread()) return EMPTY;
        String folder = dimensionFolder(level).resolve("entities").toAbsolutePath().normalize().toString();
        synchronized (observer) {
            var removals = observer.deletedEntities.get(new EntityRegion(folder, position.toLong()));
            if (removals == null) return EMPTY;
            var present = new HashSet<UUID>();
            if (data != null && !entityIds(data.getList("Entities", Tag.TAG_COMPOUND), present, 0)) return EMPTY;
            var entries = new ArrayList<NativeSaveBarrier.Entry>();
            removals.entrySet().removeIf(entry -> observer.barrier.snapshot(entry.getKey()).empty());
            if (removals.isEmpty()) observer.deletedEntities.remove(new EntityRegion(folder, position.toLong()));
            for (var removal : removals.entrySet()) if (!present.contains(removal.getValue()))
                entries.addAll(observer.barrier.snapshot(removal.getKey()).entries());
            if (entries.isEmpty() || data == null) return EMPTY;
            var captures = List.of(new Captured(observer, new NativeSaveBarrier.Snapshot(entries), chunkKey(Path.of(folder), position.toLong())));
            // The exact object is the identity carried through SimpleRegionStorage and IOWorker.
            // Replaced/coalesced payloads cannot inherit these fixed receipt entries.
            return ENTITY_WRITES.submitted(data, Path.of(folder), position.toLong(), captures) ? new WriteSnapshot(captures) : EMPTY;
        }
    }
    /** Runs on the native writer, and inspects the exact tag actually selected after IOWorker coalescing. */
    public static WriteSnapshot entityRegionDeletionWrite(Path actualFolder, long position, CompoundTag actualData) {
        var captures = ENTITY_WRITES.written(actualData, actualFolder, position);
        if (captures.isEmpty()) return EMPTY;
        var present = new HashSet<UUID>();
        if (!entityIds(actualData.getList("Entities", Tag.TAG_COMPOUND), present, 0)) return EMPTY;
        var exact = new ArrayList<Captured>(); String folder = actualFolder.toAbsolutePath().normalize().toString();
        for (var capture : captures) synchronized (capture.observer()) {
            var removals = capture.observer().deletedEntities.get(new EntityRegion(folder, position));
            if (removals == null) continue;
            var absent = capture.snapshot().entries().stream().filter(entry -> {
                UUID removed = removals.get(entry.target()); return removed != null && !present.contains(removed);
            }).toList();
            if (!absent.isEmpty()) exact.add(new Captured(capture.observer(), new NativeSaveBarrier.Snapshot(absent), capture.destination()));
        }
        return exact.isEmpty() ? EMPTY : new WriteSnapshot(exact);
    }
    public static boolean entityDeletionPending(net.minecraft.server.level.ServerLevel level, long position) {
        var observer = ACTIVE.get(level.getServer());
        if (observer == null || !level.getServer().isSameThread()) return false;
        String folder = dimensionFolder(level).resolve("entities").toAbsolutePath().normalize().toString();
        synchronized (observer) {
            var key = new EntityRegion(folder, position); var removals = observer.deletedEntities.get(key);
            if (removals == null) return false;
            removals.entrySet().removeIf(entry -> observer.barrier.snapshot(entry.getKey()).empty());
            if (removals.isEmpty()) { observer.deletedEntities.remove(key); return false; }
            return true;
        }
    }
    private static boolean entityIds(net.minecraft.nbt.ListTag tags, Set<UUID> ids, int depth) {
        if (depth > 32 || tags.size() > 16384 || ids.size() + tags.size() > 32768) return false;
        for (int index = 0; index < tags.size(); index++) {
            var tag = tags.getCompound(index); if (tag.hasUUID("UUID")) ids.add(tag.getUUID("UUID"));
            if (!entityIds(tag.getList("Passengers", Tag.TAG_COMPOUND), ids, depth + 1)) return false;
        }
        return true;
    }
    /** The original native entity write and its native flush must both have completed successfully. */
    public static void writtenEntityRegion(WriteSnapshot snapshot, Path actualFolder, long position) {
        String destination = chunkKey(actualFolder, position);
        for (var captured : snapshot.captured) if (destination.equals(captured.destination()))
            acknowledge(captured, AiJson.digest("native-entity-removal:" + destination + ":" + UUID.randomUUID()));
    }
    public void record(MaterialLedger ledger, Actor actor, UUID operation, String kind, String witness, NativeSaveBarrier.Receipt receipt) {
        record(operation, receipt, proof -> ledger.nativeSaved(actor, operation, kind, witness, proof));
    }
    @FunctionalInterface public interface ProofWriter { void write(String proof) throws IOException; }
    /** Only immutable journal IO belongs here; native/world access stays on the server thread. */
    public void record(UUID operation, NativeSaveBarrier.Receipt receipt, ProofWriter writer) {
        if (receipt == null) return; // Missing coverage remains a durable obligation, never a successful save.
        var persisted = new CompletableFuture<Void>();
        try { journalReadiness.register(operation, persisted); }
        catch (RuntimeException unavailable) { persisted.completeExceptionally(unavailable); }
        receipt.saved().whenComplete((proof, failure) -> {
            if (failure != null) { persisted.completeExceptionally(failure); return; }
            try { receipts.execute(() -> {
                try { writer.write(proof); persisted.complete(null); }
                catch (IOException | RuntimeException journalFailure) {
                    persisted.completeExceptionally(journalFailure);
                    firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Native save receipt could not be recorded for operation {} ({})", operation, journalFailure.getClass().getSimpleName());
                }
            }); } catch (RejectedExecutionException unavailable) {
                persisted.completeExceptionally(unavailable);
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn("[AI4] Native save receipt queue unavailable for operation {}", operation);
            }
        });
    }
    /** Completes after all currently known native receipts for this operation are durably in its journal. */
    public CompletionStage<Void> awaitJournalled(UUID operation) { return journalReadiness.awaitKnown(operation); }
    /** False plus an unsaved operation means missing coverage, not a successfully completed empty wait. */
    public boolean hasPendingJournalWrites(UUID operation) { return journalReadiness.pendingFor(operation) > 0; }
    /** Called at serialization, before an async writer can race a later main-thread effect. */
    public static void serialized(Object nativeStore, CompoundTag data) {
        serializedAdditional(nativeStore, data);
    }
    /** Subtype witnesses share a payload with base stores; an old tag never acquires a newer scope epoch. */
    public static void serializedAdditional(Object nativeStore, CompoundTag data) {
        if (data == null) return;
        var snapshot = snapshot(nativeStore); if (snapshot.empty()) return;
        synchronized (SERIALIZED) {
            cleanReferences();
            var key = new TagKey(data, null); var captures = SERIALIZED.get(key);
            if (captures == null) {
                if (SERIALIZED.size() >= 32768) return;
                captures = new NativeSerializationCaptures<>(); SERIALIZED.put(new TagKey(data, RELEASED), captures);
            }
            for (var captured : snapshot.captured) {
                var byTarget = new IdentityHashMap<Object, List<NativeSaveBarrier.Entry>>();
                for (var entry : captured.snapshot().entries()) byTarget.computeIfAbsent(entry.target(), ignored -> new ArrayList<>()).add(entry);
                for (var entries : byTarget.entrySet()) captures.add(captured.observer(), entries.getKey(), captured.destination(),
                        entries.getValue().stream().map(NativeSaveBarrier.Entry::receipt).toList(),
                        new Captured(captured.observer(), new NativeSaveBarrier.Snapshot(entries.getValue()), captured.destination()));
            }
        }
    }
    public static WriteSnapshot snapshot(Object nativeStore) {
        if (ACTIVE.isEmpty()) return EMPTY;
        var captured = new ArrayList<Captured>();
        for (var observer : ACTIVE.values()) {
            if (!observer.server.isSameThread()) continue; // Async serialization cannot prove a coherent main-thread state.
            synchronized (observer) {
                var snapshot = observer.barrier.snapshot(nativeStore);
                if (!snapshot.empty()) captured.add(new Captured(observer, snapshot, observer.destinations.get(nativeStore)));
            }
        }
        return new WriteSnapshot(captured);
    }
    public static void written(WriteSnapshot snapshot, Path path) throws IOException {
        if (snapshot.empty()) return;
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) { file.force(true); }
        String evidence = AiJson.digest(path.toAbsolutePath().normalize() + ":" + UUID.randomUUID());
        for (var capture : snapshot.captured) if (capture.destination().equals(fileKey(path))) acknowledge(capture, evidence);
    }
    private static void cleanReferences() {
        Reference<? extends CompoundTag> key;
        while ((key = RELEASED.poll()) != null) SERIALIZED.remove(key);
    }
    private static List<Captured> snapshots(CompoundTag data, boolean remove) {
        if (data == null) return List.of();
        synchronized (SERIALIZED) {
            cleanReferences(); if (SERIALIZED.isEmpty()) return List.of();
            var key = new TagKey(data, null);
            var own = remove ? SERIALIZED.remove(key) : SERIALIZED.get(key);
            var result = new ArrayList<>(own == null ? List.<Captured>of() : own.evidence());
            // Entity regions contain independently serialized entities and passengers.
            for (String name : List.of("Entities", "Passengers", "block_entities")) {
                var children = data.getList(name, Tag.TAG_COMPOUND);
                for (int i = 0; i < Math.min(children.size(), 16384); i++) collectEntity(children.getCompound(i), remove, result, 0);
            }
            // POI is an independent region store. Only individually successful section encodings
            // carry captures; the enclosing column alone never proves an omitted/partial section.
            var sections = data.getCompound("Sections");
            if (sections.size() <= 4096) for (String name : sections.getAllKeys()) {
                if (!sections.contains(name, Tag.TAG_COMPOUND)) continue;
                var sectionKey = new TagKey(sections.getCompound(name), null);
                var capture = remove ? SERIALIZED.remove(sectionKey) : SERIALIZED.get(sectionKey);
                if (capture != null) result.addAll(capture.evidence());
            }
            return result;
        }
    }
    private static void collectEntity(CompoundTag tag, boolean remove, List<Captured> result, int depth) {
        if (depth > 32 || result.size() > 32768) return;
        var key = new TagKey(tag, null); var own = remove ? SERIALIZED.remove(key) : SERIALIZED.get(key);
        if (own != null) result.addAll(own.evidence());
        var passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(passengers.size(), 128); i++) collectEntity(passengers.getCompound(i), remove, result, depth + 1);
    }
    public static boolean tracked(CompoundTag data) { return !snapshots(data, false).isEmpty(); }
    public static boolean trackedRegion(CompoundTag data, Path folder, long position) {
        var destinations = Set.of(chunkKey(folder, position), entityKey(folder));
        return snapshots(data, false).stream().anyMatch(snapshot -> destinations.contains(snapshot.destination()));
    }
    private static synchronized void acknowledge(Captured capture, String evidence) {
        var observer = capture.observer();
        synchronized (observer) {
            var allowed = new ArrayList<NativeSaveBarrier.Entry>();
            for (var entry : capture.snapshot().entries()) {
                var byTarget = observer.heldWrites.get(entry.receipt());
                var held = byTarget == null ? null : byTarget.get(entry.target());
                if (held == null) allowed.add(entry); else held.canonicalProof = evidence;
            }
            observer.barrier.written(new NativeSaveBarrier.Snapshot(allowed), evidence);
            for (var entry : capture.snapshot().entries())
                if (observer.barrier.snapshot(entry.target()).empty()) {
                    observer.destinations.remove(entry.target());
                    observer.entityWitnesses.remove(entry.target());
                }
        }
    }
    public static void writtenFile(CompoundTag data, Path path) { written(data, Set.of(fileKey(path))); }
    public static void writtenRegion(CompoundTag data, Path folder, long position) {
        written(data, Set.of(chunkKey(folder, position), entityKey(folder)));
    }
    private static void written(CompoundTag data, Set<String> destinations) {
        String evidence = AiJson.digest(destinations.stream().sorted().toList() + ":" + UUID.randomUUID());
        // A backup/export to a different path is not the canonical native store.
        for (var captured : snapshots(data, false)) if (destinations.contains(captured.destination())) acknowledge(captured, evidence);
    }
    private static String fileKey(Path path) { return "file:" + path.toAbsolutePath().normalize(); }
    private static String chunkKey(Path path, long chunk) { return "chunk:" + path.toAbsolutePath().normalize() + ":" + chunk; }
    private static String entityKey(Path path) { return "entities:" + path.toAbsolutePath().normalize(); }
    public static Target player(net.minecraft.server.level.ServerPlayer player) {
        return Target.file(player, player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat"));
    }
    public static Target chunk(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        return Target.chunk(chunk, dimensionFolder(level).resolve("region"), chunk.getPos().toLong());
    }
    public static Target blockEntity(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.block.entity.BlockEntity entity) {
        return Target.chunk(entity, dimensionFolder(level).resolve("region"), new net.minecraft.world.level.ChunkPos(entity.getBlockPos()).toLong());
    }
    /** Call only after the native POI update has run; setBlock may enqueue that update for later. */
    public static Target poi(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos position) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var sections = ((firefly520.fireflymc.mixin.AiPoiStorageAccess) level.getPoiManager()).fireflymc$loadedSections();
        var section = sections.get(net.minecraft.core.SectionPos.asLong(position));
        if (section == null || section.isEmpty()) throw new IllegalStateException("NATIVE_POI_SECTION_NOT_LOADED");
        return Target.chunk(section.get(), dimensionFolder(level).resolve("poi"), new net.minecraft.world.level.ChunkPos(position).toLong());
    }
    public static Target entity(net.minecraft.world.entity.Entity entity) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) throw new IllegalArgumentException("NATIVE_ENTITY_SERVER_REQUIRED");
        return Target.entity(entity, dimensionFolder(level).resolve("entities"));
    }
    public static Path dimensionFolder(net.minecraft.server.level.ServerLevel level) {
        return net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
    }
    synchronized boolean pendingDeletionTarget(Target target, Path folder, long position) {
        var known = deletedEntities.get(new EntityRegion(folder.toAbsolutePath().normalize().toString(), position));
        return known != null && known.containsKey(target.identity());
    }
    /** Player saves use NbtIo, which closes but does not fsync the temporary file. */
    public static void forceTemporary(CompoundTag data, Path path) throws IOException {
        if (!tracked(data)) return;
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) { file.force(true); }
    }
    public static void serverStopped(MinecraftServer server) {
        var observer = ACTIVE.remove(server); if (observer == null) return;
        observer.dispatcher.close();
        observer.barrier.close(); observer.receipts.shutdown();
        synchronized (observer) { observer.destinations.clear(); observer.entityWitnesses.clear(); observer.heldWrites.clear(); observer.deletedEntities.clear(); }
        ENTITY_WRITES.removeIf(capture -> capture.observer() == observer);
        synchronized (SERIALIZED) {
            SERIALIZED.values().forEach(captures -> captures.removeObserver(observer));
            SERIALIZED.entrySet().removeIf(row -> row.getValue().empty());
            cleanReferences();
        }
        // ServerStopped is after native save/close, before the dedicated process may exit. Only
        // immutable journal writes remain; a daemon/virtual waiter would be killed by System.exit.
        try {
            if (observer.receipts.awaitTermination(30, TimeUnit.SECONDS)) { observer.journalReadiness.close(); observer.stopped.complete(null); }
            else observer.stopped.completeExceptionally(new IllegalStateException("NATIVE_SAVE_JOURNAL_NOT_DRAINED"));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); observer.stopped.completeExceptionally(interrupted); }
    }
    public boolean awaitStopped(long millis) throws InterruptedException {
        try { stopped.get(millis, TimeUnit.MILLISECONDS); return true; }
        catch (ExecutionException | TimeoutException unavailable) { return false; }
    }
}
