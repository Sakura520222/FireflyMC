package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.mixin.AiPoiStorageAccess;
import firefly520.fireflymc.mixin.AiSavedDataCacheAccess;
import java.io.File;
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;

/** Saves only stores enlisted by accepted effects through their actual native canonical writers. */
final class NativeSaveDispatcher implements AutoCloseable {
    private final MinecraftServer server;
    private final NativeSaveObserver observer;
    private final NativeSaveDispatchQueue<NativeSaveObserver.Target> queue = new NativeSaveDispatchQueue<>();
    private static final Consumer<EntityAccess> KEEP_ENTITY = entity -> { };
    private static final class Abi {
        static final MethodHandle PLAYER_IO = getter(PlayerList.class, "playerIo", PlayerDataStorage.class);
        static final MethodHandle STATS = getter(PlayerList.class, "stats", Map.class);
        static final MethodHandle ADVANCEMENTS = getter(PlayerList.class, "advancements", Map.class);
        static final MethodHandle CHUNK_SAVE = method(ChunkMap.class, "save", boolean.class, ChunkAccess.class);
        static final MethodHandle VISIBLE_CHUNK = method(ChunkMap.class, "getVisibleChunkIfPresent", ChunkHolder.class, long.class);
        static final MethodHandle ENTITY_MANAGER = getter(ServerLevel.class, "entityManager", PersistentEntitySectionManager.class);
        static final MethodHandle ENTITY_SECTIONS = getter(PersistentEntitySectionManager.class, "sectionStorage", EntitySectionStorage.class);
        static final MethodHandle ENTITY_STORE = method(PersistentEntitySectionManager.class, "storeChunkSections", boolean.class, long.class, Consumer.class);
        static final MethodHandle ENTITY_LOAD = method(PersistentEntitySectionManager.class, "ensureChunkQueuedForLoad", void.class, long.class);
        static final MethodHandle POI_DIRTY = method(SectionStorage.class, "setDirty", void.class, long.class);
        static final MethodHandle DATA_FILE = method(DimensionDataStorage.class, "getDataFile", File.class, String.class);
        static final MethodHandle DATA_REGISTRY = getter(DimensionDataStorage.class, "registries", HolderLookup.Provider.class);
        private static MethodHandle getter(Class<?> owner, String name, Class<?> type) {
            try { return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findGetter(owner, name, type); }
            catch (ReflectiveOperationException invalid) { throw new IllegalStateException("NATIVE_SAVE_DISPATCH_ABI_UNAVAILABLE", invalid); }
        }
        private static MethodHandle method(Class<?> owner, String name, Class<?> result, Class<?>... args) {
            try { return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVirtual(owner, name, MethodType.methodType(result, args)); }
            catch (ReflectiveOperationException invalid) { throw new IllegalStateException("NATIVE_SAVE_DISPATCH_ABI_UNAVAILABLE", invalid); }
        }
    }
    NativeSaveDispatcher(MinecraftServer server, NativeSaveObserver observer) {
        this.server = server; this.observer = observer;
    }
    void requireCapacity(Collection<NativeSaveObserver.Target> targets) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        queue.requireCapacity(targets.stream().map(NativeSaveObserver.Target::identity).toList());
    }
    void offer(NativeSaveObserver.Target target) { queue.offer(target.identity(), target); }
    void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        queue.tick(server.getTickCount(), System::nanoTime, target -> observer.pending(target.identity()), this::resolve);
    }
    NativeSaveDispatchQueue.Metrics metrics() { return queue.metrics(); }
    private NativeSaveDispatchQueue.Work resolve(NativeSaveObserver.Target target) {
        if (!NativeSaveSubmissionScope.Hook.class.isAssignableFrom(net.minecraft.world.level.chunk.storage.ChunkStorage.class)
                || !NativeSaveSubmissionScope.Hook.class.isAssignableFrom(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class)
                || !NativeSaveSubmissionScope.Hook.class.isAssignableFrom(net.minecraft.world.level.chunk.storage.EntityStorage.class)
                || !NativeSaveSubmissionScope.Hook.class.isAssignableFrom(net.neoforged.neoforge.common.IOUtilities.class))
            return waiting("NATIVE_SAVE_DISPATCH_HOOK_UNAVAILABLE");
        Object store = target.identity();
        Entity witness = observer.witnessEntity(store);
        if (witness != null) {
            if (!(witness.level() instanceof ServerLevel level) || level.getServer() != server || witness.isRemoved())
                return waiting("NATIVE_SAVE_WITNESS_ENTITY_NOT_CURRENT");
            requireDestination(target, NativeSaveObserver.entity(witness).destination());
            return entities(level, witness.chunkPosition(), "chunk:" + NativeSaveObserver.dimensionFolder(level).resolve("entities").toAbsolutePath().normalize()
                    + ":" + witness.chunkPosition().toLong());
        }
        if (store instanceof ServerPlayer player) {
            if (player.server != server || server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection == null)
                return waiting("NATIVE_SAVE_PLAYER_NOT_CURRENT");
            requireDestination(target, NativeSaveObserver.player(player).destination());
            return save(NativeSaveDispatchQueue.StoreKind.PLAYER_NBT, target.destination(), () -> ((PlayerDataStorage) invoke(Abi.PLAYER_IO, server.getPlayerList())).save(player));
        }
        if (store instanceof ServerStatsCounter stats) {
            UUID owner = fileOwner(target, LevelResource.PLAYER_STATS_DIR, ".json");
            var cached = (Map<?, ?>) invoke(Abi.STATS, server.getPlayerList());
            if (cached.get(owner) != stats) return waiting("NATIVE_SAVE_STATS_NOT_CURRENT");
            return save(NativeSaveDispatchQueue.StoreKind.STATS_JSON, target.destination(), stats::save);
        }
        if (store instanceof PlayerAdvancements advancements) {
            UUID owner = fileOwner(target, LevelResource.PLAYER_ADVANCEMENTS_DIR, ".json");
            var cached = (Map<?, ?>) invoke(Abi.ADVANCEMENTS, server.getPlayerList());
            if (cached.get(owner) != advancements) return waiting("NATIVE_SAVE_ADVANCEMENTS_NOT_CURRENT");
            return save(NativeSaveDispatchQueue.StoreKind.ADVANCEMENTS_JSON, target.destination(), advancements::save);
        }
        int checked = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (++checked > 256) return waiting("NATIVE_SAVE_DIMENSION_LIMIT");
            Path root = NativeSaveObserver.dimensionFolder(level);
            if (store instanceof SavedData data && target.destination().startsWith("file:")) {
                Path file = Path.of(target.destination().substring(5)).toAbsolutePath().normalize();
                Path base = root.resolve("data").toAbsolutePath().normalize();
                if (!file.startsWith(base) || !file.toString().endsWith(".dat")) continue;
                String relative = base.relativize(file).toString();
                String id = relative.substring(0, relative.length() - 4).replace('\\', '/');
                if (id.length() > 160 || !id.matches("[A-Za-z0-9_./-]+")) return waiting("NATIVE_SAVE_DATA_ID_UNSUPPORTED");
                var storage = level.getDataStorage();
                if (((AiSavedDataCacheAccess) storage).fireflymc$existingStores().get(id) != data)
                    return waiting("NATIVE_SAVE_DATA_NOT_CURRENT");
                File canonical = (File) invoke(Abi.DATA_FILE, storage, id);
                requireDestination(target, NativeSaveObserver.Target.file(data, canonical.toPath()).destination());
                return save(NativeSaveDispatchQueue.StoreKind.SAVED_DATA, target.destination(), () -> {
                    // Dirtying only requests another canonical serialization. It is not a save receipt.
                    data.setDirty(); data.save(canonical, (HolderLookup.Provider) invoke(Abi.DATA_REGISTRY, storage));
                });
            }
            Long chunkPosition = chunkPosition(target.destination(), root.resolve("region"));
            if (chunkPosition != null) return chunk(level, new ChunkPos(chunkPosition), target);
            Long poiPosition = chunkPosition(target.destination(), root.resolve("poi"));
            if (poiPosition != null) return poi(level, new ChunkPos(poiPosition), target);
            Long deletion = chunkPosition(target.destination(), root.resolve("entities"));
            if (deletion != null) {
                if (!observer.pendingDeletionTarget(target, root.resolve("entities"), deletion)) return waiting("NATIVE_SAVE_ENTITY_DELETION_NOT_ENLISTED");
                return entities(level, new ChunkPos(deletion), target.destination());
            }
            String entityFolder = "entities:" + root.resolve("entities").toAbsolutePath().normalize();
            if (target.destination().equals(entityFolder) && store instanceof Entity entity && entity.level() == level) {
                if (entity.isRemoved()) return waiting("NATIVE_SAVE_ENTITY_REMOVAL_REQUIRES_HANDOFF");
                return entities(level, entity.chunkPosition(), "chunk:" + root.resolve("entities").toAbsolutePath().normalize() + ":" + entity.chunkPosition().toLong());
            }
        }
        return waiting("NATIVE_SAVE_TARGET_UNSUPPORTED");
    }
    private NativeSaveDispatchQueue.Work chunk(ServerLevel level, ChunkPos pos, NativeSaveObserver.Target target) {
        ChunkAccess current = latestChunk(level, pos);
        Object identity = target.identity();
        if (identity instanceof net.minecraft.world.level.block.entity.BlockEntity block) {
            var loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (loaded == null || block.getLevel() != level || loaded.getBlockEntity(block.getBlockPos()) != block)
                return waiting("NATIVE_SAVE_BLOCK_ENTITY_NOT_CURRENT");
            current = loaded;
        } else if (identity instanceof ChunkAccess access) {
            if (current != access) return waiting("NATIVE_SAVE_CHUNK_NOT_CURRENT");
        } else return waiting("NATIVE_SAVE_REGION_IDENTITY_UNSUPPORTED");
        if (current == null || current.getSectionsCount() > 256) return waiting("NATIVE_SAVE_CHUNK_UNAVAILABLE_OR_LIMIT");
        ChunkAccess accepted = current;
        return save(NativeSaveDispatchQueue.StoreKind.CHUNK_NBT, target.destination(), () -> {
            if (latestChunk(level, pos) != accepted) throw new IllegalStateException("NATIVE_SAVE_CHUNK_NOT_CURRENT");
            accepted.setUnsaved(true);
            if (!(boolean) invoke(Abi.CHUNK_SAVE, level.getChunkSource().chunkMap, accepted))
                throw new IllegalStateException("NATIVE_SAVE_CHUNK_DECLINED");
        });
    }
    private ChunkAccess latestChunk(ServerLevel level, ChunkPos pos) {
        var direct = level.getChunkSource().getChunkNow(pos.x, pos.z); if (direct != null) return direct;
        var holder = (ChunkHolder) invoke(Abi.VISIBLE_CHUNK, level.getChunkSource().chunkMap, pos.toLong());
        if (holder == null) return null;
        var latest = holder.getLatestChunk();
        return latest instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : latest;
    }
    private NativeSaveDispatchQueue.Work poi(ServerLevel level, ChunkPos pos, NativeSaveObserver.Target target) {
        if (!(target.identity() instanceof PoiSection)) return waiting("NATIVE_SAVE_POI_IDENTITY_UNSUPPORTED");
        var manager = level.getPoiManager(); var loaded = ((AiPoiStorageAccess) manager).fireflymc$loadedSections();
        Long section = null;
        if (level.getSectionsCount() > 256) return waiting("NATIVE_SAVE_POI_SECTION_LIMIT");
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            long key = SectionPos.asLong(pos.x, y, pos.z); var optional = loaded.get(key);
            if (optional != null && optional.orElse(null) == target.identity()) { section = key; break; }
        }
        if (section == null) return waiting("NATIVE_SAVE_POI_NOT_CURRENT");
        long accepted = section;
        return save(NativeSaveDispatchQueue.StoreKind.POI_COLUMN, target.destination(), () -> { invoke(Abi.POI_DIRTY, manager, accepted); manager.flush(pos); });
    }
    private NativeSaveDispatchQueue.Work entities(ServerLevel level, ChunkPos pos, String group) {
        var manager = (PersistentEntitySectionManager<?>) invoke(Abi.ENTITY_MANAGER, level);
        if (!manager.areEntitiesLoaded(pos.toLong()))
            return NativeSaveDispatchQueue.Work.prepare(group, () -> submit(() -> invoke(Abi.ENTITY_LOAD, manager, pos.toLong())));
        var sections = (EntitySectionStorage<?>) invoke(Abi.ENTITY_SECTIONS, manager);
        long count = sections.getExistingSectionsInChunk(pos.toLong()).flatMap(section -> section.getEntities()).limit(1025).count();
        if (count > 1024) return waiting("NATIVE_SAVE_ENTITY_SECTION_LIMIT");
        return save(NativeSaveDispatchQueue.StoreKind.ENTITY_CHUNK, group, () -> {
            if (!(boolean) invoke(Abi.ENTITY_STORE, manager, pos.toLong(), KEEP_ENTITY))
                throw new IllegalStateException("NATIVE_SAVE_ENTITY_SECTION_NOT_READY");
        });
    }
    private UUID fileOwner(NativeSaveObserver.Target target, LevelResource folder, String extension) {
        if (!target.destination().startsWith("file:")) throw new IllegalStateException("NATIVE_SAVE_FILE_IDENTITY_CHANGED");
        Path file = Path.of(target.destination().substring(5)).toAbsolutePath().normalize();
        Path parent = server.getWorldPath(folder).toAbsolutePath().normalize();
        String name = file.getFileName().toString();
        if (!Objects.equals(file.getParent(), parent) || !name.endsWith(extension)) throw new IllegalStateException("NATIVE_SAVE_FILE_IDENTITY_CHANGED");
        return UUID.fromString(name.substring(0, name.length() - extension.length()));
    }
    private static Long chunkPosition(String destination, Path folder) {
        String prefix = "chunk:" + folder.toAbsolutePath().normalize() + ":";
        if (!destination.startsWith(prefix)) return null;
        return Long.parseLong(destination.substring(prefix.length()));
    }
    private static void requireDestination(NativeSaveObserver.Target target, String expected) {
        if (!target.destination().equals(expected)) throw new IllegalStateException("NATIVE_SAVE_CANONICAL_TARGET_CHANGED");
    }
    private static NativeSaveDispatchQueue.Work waiting(String reason) { return NativeSaveDispatchQueue.Work.waiting(reason); }
    private static NativeSaveDispatchQueue.Work save(NativeSaveDispatchQueue.StoreKind kind, String group, Runnable nativeSave) {
        return NativeSaveDispatchQueue.Work.save(kind, group, () -> submit(nativeSave));
    }
    private static CompletionStage<?> submit(Runnable nativeSave) {
        try (var submitted = NativeSaveSubmissionScope.begin()) {
            try { nativeSave.run(); return submitted.completion(); }
            catch (RuntimeException | Error failure) {
                // A native callback may throw after it already submitted a write. Hold its
                // physical store until those submissions finish; this is not a save proof.
                return submitted.completion().handle((ignored, writeFailure) -> {
                    if (writeFailure != null && writeFailure != failure) failure.addSuppressed(writeFailure);
                    throw failure;
                });
            }
        }
    }
    private static Object invoke(MethodHandle handle, Object... arguments) {
        try { return handle.invokeWithArguments(arguments); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("NATIVE_SAVE_DISPATCH_CALL_FAILED", failure); }
    }
    @Override public void close() { queue.close(); }
}
