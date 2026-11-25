package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

/** Native registry-based placement units. Counts describe operations, not the number of target cells. */
public final class PlacementMaterialResolver {
    public enum Kind { BLOCK, DOOR, BED, DOUBLE_PLANT, DOUBLE_SLAB }
    public record Group(Kind kind, BlueprintDocument.Position anchor, List<BlueprintDocument.Block> targets,
                        String itemId, int itemCount, boolean waterlogged) {
        public Group { targets = List.copyOf(targets); }
    }
    private PlacementMaterialResolver() {}

    /** Invoke on the game thread. This is a quote; the native placement path must still verify actual consumption. */
    public static Optional<Group> resolve(BlueprintDocument.Block target, Map<BlueprintDocument.Position, BlueprintDocument.Block> desired) {
        BlockState state = BlueprintBlockStateCodec.resolve(target.state());
        if (state.isAir()) return Optional.empty();
        var block = state.getBlock(); var item = block.asItem();
        if (!(item instanceof BlockItem) || item == Items.AIR) throw new IllegalArgumentException("PLACEMENT_ITEM_UNAVAILABLE");
        if (block instanceof GameMasterBlock || block instanceof StructureVoidBlock || block == Blocks.BARRIER || block == Blocks.LIGHT)
            throw new IllegalArgumentException("PLACEMENT_ADMIN_BLOCK_FORBIDDEN");
        Kind kind = Kind.BLOCK; int count = 1;
        var cells = new ArrayList<BlueprintDocument.Block>(); cells.add(target);
        if (block instanceof DoorBlock || block instanceof DoublePlantBlock) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            var otherPos = target.position().add(new BlueprintDocument.Position(0, half == DoubleBlockHalf.LOWER ? 1 : -1, 0));
            var other = desired.get(otherPos);
            if (other == null) throw new IllegalArgumentException("PLACEMENT_MULTIBLOCK_INCOMPLETE");
            BlockState otherState = BlueprintBlockStateCodec.resolve(other.state());
            if (!otherState.is(block) || otherState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == half)
                throw new IllegalArgumentException("PLACEMENT_MULTIBLOCK_MISMATCH");
            if (block instanceof DoorBlock && (otherState.getValue(DoorBlock.FACING) != state.getValue(DoorBlock.FACING)
                    || otherState.getValue(DoorBlock.HINGE) != state.getValue(DoorBlock.HINGE)
                    || otherState.getValue(DoorBlock.OPEN) != state.getValue(DoorBlock.OPEN)
                    || otherState.getValue(DoorBlock.POWERED) != state.getValue(DoorBlock.POWERED)))
                throw new IllegalArgumentException("PLACEMENT_MULTIBLOCK_MISMATCH");
            if (half == DoubleBlockHalf.UPPER) return Optional.empty();
            cells.add(other); kind = block instanceof DoorBlock ? Kind.DOOR : Kind.DOUBLE_PLANT;
        } else if (block instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING); BedPart part = state.getValue(BedBlock.PART);
            Direction toward = part == BedPart.FOOT ? facing : facing.getOpposite();
            var other = desired.get(target.position().add(new BlueprintDocument.Position(toward.getStepX(), 0, toward.getStepZ())));
            if (other == null) throw new IllegalArgumentException("PLACEMENT_MULTIBLOCK_INCOMPLETE");
            var otherState = BlueprintBlockStateCodec.resolve(other.state());
            if (!otherState.is(block) || otherState.getValue(BedBlock.PART) == part || otherState.getValue(BedBlock.FACING) != facing
                    || otherState.getValue(BedBlock.OCCUPIED) || state.getValue(BedBlock.OCCUPIED))
                throw new IllegalArgumentException("PLACEMENT_MULTIBLOCK_MISMATCH");
            if (part == BedPart.HEAD) return Optional.empty();
            cells.add(other); kind = Kind.BED;
        } else if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            if (state.getValue(SlabBlock.WATERLOGGED)) throw new IllegalArgumentException("PLACEMENT_STATE_UNREACHABLE");
            kind = Kind.DOUBLE_SLAB; count = 2;
        }
        return Optional.of(new Group(kind, target.position(), cells, BuiltInRegistries.ITEM.getKey(item).toString(), count,
                state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)));
    }

    /** An existing matching half slab needs one additional slab; a satisfied placement needs none. */
    public static int requiredItems(Group group, Map<BlueprintDocument.Position, BlockState> observed) {
        boolean all = true, any = false;
        for (var cell : group.targets()) {
            BlockState before = observed.get(cell.position());
            if (before == null) throw new IllegalStateException("PLACEMENT_WORLD_NOT_OBSERVED");
            boolean matches = before.equals(BlueprintBlockStateCodec.resolve(cell.state())); all &= matches; any |= matches;
        }
        if (all) return 0;
        if (group.targets().size() > 1 && any) throw new IllegalStateException("PLACEMENT_PARTIAL_MULTIBLOCK");
        if (group.kind() == Kind.DOUBLE_SLAB) {
            var before = observed.get(group.anchor()); var target = BlueprintBlockStateCodec.resolve(group.targets().getFirst().state());
            if (before.is(target.getBlock()) && before.hasProperty(SlabBlock.TYPE) && before.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) return 1;
        }
        return group.itemCount();
    }
}
