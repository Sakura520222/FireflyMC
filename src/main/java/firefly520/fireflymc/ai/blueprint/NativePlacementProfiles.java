package firefly520.fireflymc.ai.blueprint;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;

/** Reviewed native placement families; unknown subclass callbacks need their own effect/permission adapter. */
public final class NativePlacementProfiles {
    private static final Set<Class<?>> CLASSES = Set.of(Block.class, RotatedPillarBlock.class, SlabBlock.class, StairBlock.class,
            DoorBlock.class, BedBlock.class, DoublePlantBlock.class, FenceBlock.class, WallBlock.class, FenceGateBlock.class,
            IronBarsBlock.class, StainedGlassPaneBlock.class, Blocks.GLASS.getClass(), StainedGlassBlock.class, TintedGlassBlock.class,
            CraftingTableBlock.class, SmithingTableBlock.class, StonecutterBlock.class, ChestBlock.class, TrappedChestBlock.class,
            BarrelBlock.class, FurnaceBlock.class, BlastFurnaceBlock.class, SmokerBlock.class, Blocks.ENCHANTING_TABLE.getClass());
    private NativePlacementProfiles() {}
    public static void require(BlockState state) {
        if (!CLASSES.contains(state.getBlock().getClass())) throw new IllegalArgumentException("BUILD_NATIVE_EFFECT_ADAPTER_REQUIRED");
    }
}
