package firefly520.fireflymc.ai.blueprint.ownership;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.chunk.LevelChunk;

/** Also counts object-local edits during native deserialization before the chunk becomes live. */
public final class OwnershipSectionBinding {
    private final AtomicLong mutations = new AtomicLong();
    private volatile WeakReference<LevelChunk> chunk = new WeakReference<>(null);
    private volatile int baseY;
    private volatile boolean escaped;
    public long mutations() { return mutations.get(); }
    public LevelChunk chunk() { return chunk.get(); }
    public int baseY() { return baseY; }
    public boolean escaped() { return escaped; }
    public synchronized void bind(LevelChunk value, int sectionY) {
        var previous = chunk.get();
        if (previous != null && previous != value) {
            escaped = true;
            NativeOwnership.revokeChunk(previous); NativeOwnership.revokeChunk(value);
            ((OwnershipAccess.Chunk) previous).fireflymc$ownership().unknown("OWNERSHIP_SHARED_SECTION_UNSUPPORTED");
            ((OwnershipAccess.Chunk) value).fireflymc$ownership().unknown("OWNERSHIP_SHARED_SECTION_UNSUPPORTED");
        }
        chunk = new WeakReference<>(value); baseY = sectionY * 16;
        if (escaped) {
            NativeOwnership.revokeChunk(value);
            ((OwnershipAccess.Chunk) value).fireflymc$ownership().unknown("OWNERSHIP_MUTABLE_REFERENCE_ESCAPED");
        }
    }
    public void changed(int index) {
        mutations.incrementAndGet(); var value = chunk.get();
        if (value != null) {
            var pos = position(value, index);
            var state = ((OwnershipAccess.Chunk) value).fireflymc$ownership(); boolean metadata = state.hasMetadata();
            if (state.revision() == Long.MAX_VALUE) NativeOwnership.revokeChunk(value);
            NativeOwnership.revokeCell(value, pos.asLong()); state.changed(pos.asLong());
            if (metadata) value.setUnsaved(true);
        }
    }
    public void transition(int index, Object before, Object after) {
        if (before == after) return;
        changed(index); var value = chunk.get();
        if (value != null) NativeOwnership.observed(value, position(value, index), before, after);
    }
    public void bulk() {
        mutations.incrementAndGet(); var value = chunk.get();
        if (value != null) {
            var state = ((OwnershipAccess.Chunk) value).fireflymc$ownership(); boolean metadata = state.hasMetadata();
            NativeOwnership.revokeChunk(value); state.invalidateAll("OWNERSHIP_PALETTE_REPLACED");
            NativeOwnership.unobserved(value); if (metadata) value.setUnsaved(true);
        }
    }
    public void escape() {
        escaped = true; mutations.incrementAndGet(); var value = chunk.get();
        if (value != null) {
            var state = ((OwnershipAccess.Chunk) value).fireflymc$ownership(); boolean metadata = state.hasMetadata();
            NativeOwnership.revokeChunk(value); state.unknown("OWNERSHIP_MUTABLE_REFERENCE_ESCAPED");
            NativeOwnership.unobserved(value); if (metadata) value.setUnsaved(true);
        }
    }
    private net.minecraft.core.BlockPos position(LevelChunk value, int index) {
        return new net.minecraft.core.BlockPos(value.getPos().getMinBlockX() + (index & 15),
                baseY + (index >>> 8), value.getPos().getMinBlockZ() + ((index >>> 4) & 15));
    }
}
