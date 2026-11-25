package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.*;

/** Native observation only. No workspace, lifecycle service, disk path or world mutation. */
public final class OwnershipNativeHooks {
    private static final StackWalker CALLERS = StackWalker.getInstance();
    private static final Set<String> SECTION_READERS = Set.of(
            "net.minecraft.world.level.chunk.ChunkAccess#getSection",
            "net.minecraft.world.level.chunk.ChunkAccess#getHighestFilledSectionIndex",
            "net.minecraft.world.level.chunk.storage.ChunkSerializer#write",
            "net.minecraft.server.level.ServerLevel#tickChunk",
            "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData#calculateChunkSize",
            "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData#extractChunkData",
            "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData#calculateChunkSize",
            "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData#extractChunkData");
    private OwnershipNativeHooks() {}
    public static void bind(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || !level.getServer().isDedicatedServer()) return;
        var sections = ((OwnershipAccess.Chunk) chunk).fireflymc$ownershipSections();
        for (int index = 0; index < sections.length; index++)
            ((OwnershipAccess.Section) sections[index]).fireflymc$ownershipSection().bind(chunk, chunk.getSectionYFromSectionIndex(index));
    }
    public static void sectionsReturned(ChunkAccess access) {
        if (!(access instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)
                || !level.getServer().isDedicatedServer()) return;
        String caller = callerAfter("net.minecraft.world.level.chunk.ChunkAccess", "getSections", true);
        if (!SECTION_READERS.contains(caller) && !caller.startsWith("net.minecraft.server.level.ThreadedLevelLightEngine#lambda$initializeLight$")) {
            var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership(); boolean metadata = state.hasMetadata();
            NativeOwnership.revokeChunk(chunk); state.unknown("OWNERSHIP_SECTION_ARRAY_ESCAPED");
            NativeOwnership.unobserved(chunk); if (metadata) chunk.setUnsaved(true);
        }
    }
    public static void storageChanged(OwnershipSectionBinding binding, int index, String method) {
        if (binding == null) return;
        binding.changed(index);
        if (!NativeOwnership.observing()) return;
        String caller = callerAfter("net.minecraft.util.SimpleBitStorage", method, false);
        if (!caller.equals("net.minecraft.world.level.chunk.PalettedContainer#getAndSet")
                && !caller.equals("net.minecraft.world.level.chunk.PalettedContainer#set")) {
            var chunk = binding.chunk(); if (chunk != null) NativeOwnership.unobserved(chunk);
        }
    }
    private static final java.lang.reflect.Field PALETTE_DATA = paletteDataField();
    private static java.lang.reflect.Field paletteDataField() {
        try { var field = PalettedContainer.class.getDeclaredField("data"); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    /** The private nested Data type is inaccessible to a typed Java shadow; resolve once, fail closed. */
    public static void bindBits(PalettedContainer<?> palette, OwnershipSectionBinding binding) {
        if (binding == null) return;
        try {
            if (PALETTE_DATA == null || !(PALETTE_DATA.get(palette) instanceof OwnershipAccess.PaletteData data)) {
                binding.escape(); return;
            }
            var storage = data.fireflymc$ownershipBits();
            if (storage instanceof net.minecraft.util.ZeroBitStorage) return; // immutable, copy() returns itself
            if (storage instanceof OwnershipAccess.Storage access) access.fireflymc$bindOwnershipStorage(binding);
            else binding.escape();
        } catch (IllegalAccessException | RuntimeException unavailable) { binding.escape(); }
    }
    /** Native Data serialization reads the array; network read replaces it and is separately invalidated. */
    public static boolean rawStorageReader() {
        return Set.of("net.minecraft.world.level.chunk.PalettedContainer$Data#getSerializedSize",
                "net.minecraft.world.level.chunk.PalettedContainer$Data#write",
                "net.minecraft.world.level.chunk.PalettedContainer#read")
                .contains(callerAfter("net.minecraft.util.SimpleBitStorage", "getRaw", false));
    }
    /** Native unpack creates a fresh array; copy clones. Other supplied long[] aliases are untracked. */
    public static boolean ownsConstructorArray() {
        return Set.of("net.minecraft.world.level.chunk.PalettedContainer#unpack", "net.minecraft.util.SimpleBitStorage#copy")
                .contains(callerAfter("net.minecraft.util.SimpleBitStorage", "<init>", false));
    }
    private static String callerAfter(String type, String method, boolean skipWrapper) {
        return CALLERS.walk(frames -> {
            var iterator = frames.iterator(); boolean found = false;
            while (iterator.hasNext()) {
                var frame = iterator.next();
                if (!found) { if (frame.getClassName().equals(type) && frame.getMethodName().equals(method)) found = true; continue; }
                if (frame.getClassName().equals(type) && frame.getMethodName().equals(method)) continue;
                if (skipWrapper && frame.getClassName().equals("net.minecraft.world.level.chunk.ImposterProtoChunk")
                        && frame.getMethodName().equals("getSections")) continue;
                return frame.getClassName() + "#" + frame.getMethodName();
            }
            return "";
        });
    }
    public static void write(ServerLevel level, ChunkAccess access, CompoundTag payload) {
        LevelChunk chunk = unwrap(access);
        if (chunk == null || !level.getServer().isDedicatedServer()) return;
        var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership();
        if (!state.hasMetadata()) return;
        bind(chunk); var snapshot = state.snapshot();
        payload.put(OwnershipNbt.FIELD, OwnershipNbt.write(snapshot, chunk.getPos().x, chunk.getPos().z,
                level.dimension().location().toString(), CanonicalChunkPayload.digest(payload)));
    }
    public static void read(ServerLevel level, ChunkAccess access, CompoundTag payload) {
        LevelChunk chunk = unwrap(access);
        if (chunk == null || !level.getServer().isDedicatedServer()) return;
        if (!payload.contains(OwnershipNbt.FIELD)) { NativeOwnership.missingMarker(level, chunk.getPos()); return; }
        bind(chunk); var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership();
        try {
            var stored = OwnershipNbt.readCanonical(payload, chunk.getPos().x, chunk.getPos().z, level.dimension().location().toString());
            boolean altered = Arrays.stream(((OwnershipAccess.Chunk) chunk).fireflymc$ownershipSections())
                    .anyMatch(section -> ((OwnershipAccess.Section) section).fireflymc$ownershipSection().mutations() != 0);
            NativeOwnership.restore(level, chunk, state, stored, altered);
        } catch (RuntimeException malformed) {
            // Bad optional AI metadata cannot prevent a server/world from opening.
            var marker = payload.getCompound(OwnershipNbt.FIELD);
            if (marker.hasUUID("chunkNonce")) NativeOwnership.revokeStoredNonce(level, marker.getUUID("chunkNonce"));
            NativeOwnership.missingMarker(level, chunk.getPos());
            state.invalidateAll("OWNERSHIP_CANONICAL_PAYLOAD_UNVERIFIED");
        }
        chunk.setUnsaved(true);
    }
    private static LevelChunk unwrap(ChunkAccess access) {
        return access instanceof LevelChunk chunk ? chunk : access instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : null;
    }
}
