package firefly520.fireflymc.ai.blueprint;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/** Preserves a real existing source through native placement; it never creates or drains a fluid. */
public final class PlacementWaterPolicy {
    private PlacementWaterPolicy() {}

    public static boolean waterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    public static boolean preservesSource(BlockState before, BlockState target) {
        return waterlogged(target) && target.getBlock() instanceof SimpleWaterloggedBlock
                && before.is(Blocks.WATER) && before.getFluidState().getType() == Fluids.WATER
                && before.getFluidState().isSource();
    }

    /** Invoke for every changed target before material reservation and again immediately before native dispatch. */
    public static void requireReachable(BlockState before, BlockState target) {
        if (before.equals(target)) return;
        if (waterlogged(target)) {
            NativePlacementProfiles.require(target);
            if (!preservesSource(before, target)) throw new IllegalStateException("BUILD_EXISTING_SOURCE_WATER_REQUIRED");
        } else if (!before.getFluidState().isEmpty() || !target.getFluidState().isEmpty()) {
            throw new IllegalStateException("BUILD_FLUID_CHANGE_ADAPTER_REQUIRED");
        }
    }
}
