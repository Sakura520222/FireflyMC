package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Native click parameters only. The placement event must independently match the approved resulting state. */
public record PlacementPose(Direction face, double x, double y, double z, float yaw, float pitch) {
    public static PlacementPose forState(BlockState target, BlockState before) {
        boolean merge = before.getBlock() instanceof SlabBlock && target.hasProperty(SlabBlock.TYPE) && target.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
        var pose = forState(target, merge);
        if (merge) return new PlacementPose(before.getValue(SlabBlock.TYPE) == SlabType.TOP ? Direction.DOWN : Direction.UP,
                pose.x(), .5, pose.z(), pose.yaw(), pose.pitch());
        return pose;
    }
    public static PlacementPose forState(BlockState target, boolean mergeSlab) {
        Direction face = Direction.UP; double x = .5, y = .5, z = .5; float yaw = 0, pitch = 0;
        var block = target.getBlock();
        if (target.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = target.getValue(BlockStateProperties.HORIZONTAL_FACING);
            boolean followsPlayer = block instanceof StairBlock || block instanceof DoorBlock || block instanceof BedBlock || block instanceof FenceGateBlock;
            yaw = (followsPlayer ? facing : facing.getOpposite()).toYRot();
            if (block instanceof DoorBlock) {
                boolean left = target.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT;
                double offset = left ? .25 : -.25;
                x += facing.getStepZ() * offset; z -= facing.getStepX() * offset;
            }
        }
        if (target.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = target.getValue(BlockStateProperties.FACING);
            yaw = facing.getOpposite().toYRot();
            pitch = facing == Direction.UP ? 90 : facing == Direction.DOWN ? -90 : 0;
        }
        if (target.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = target.getValue(BlockStateProperties.AXIS);
            face = axis == Direction.Axis.X ? Direction.EAST : axis == Direction.Axis.Z ? Direction.SOUTH : Direction.UP;
        }
        boolean top = target.hasProperty(BlockStateProperties.HALF) && target.getValue(BlockStateProperties.HALF) == Half.TOP
                || target.hasProperty(SlabBlock.TYPE) && target.getValue(SlabBlock.TYPE) == SlabType.TOP;
        if (top) { face = Direction.DOWN; y = .75; }
        if (mergeSlab) { face = Direction.UP; y = 1; }
        return new PlacementPose(face, x, y, z, yaw, pitch);
    }
}
