package firefly520.fireflymc.ai.blueprint.ownership;

/** Dedicated metadata injected into native objects; no client/runtime/workspace dependency. */
public final class OwnershipAccess {
    private OwnershipAccess() {}
    public interface Chunk {
        OwnershipChunkState fireflymc$ownership();
        net.minecraft.world.level.chunk.LevelChunkSection[] fireflymc$ownershipSections();
    }
    public interface Section { OwnershipSectionBinding fireflymc$ownershipSection(); }
    public interface Palette {
        OwnershipSectionBinding fireflymc$ownershipPalette();
        void fireflymc$bindOwnershipPalette(OwnershipSectionBinding binding);
    }
    public interface Storage {
        OwnershipSectionBinding fireflymc$ownershipStorage();
        void fireflymc$bindOwnershipStorage(OwnershipSectionBinding binding);
    }
    public interface PaletteData { net.minecraft.util.BitStorage fireflymc$ownershipBits(); }
}
