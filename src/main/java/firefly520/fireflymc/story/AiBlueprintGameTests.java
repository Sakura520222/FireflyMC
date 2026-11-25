package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.blueprint.BlueprintTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests real shared BlockState transformations without placing any blocks or loading optional mods. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiBlueprintGameTests {
    private AiBlueprintGameTests() {}

    @GameTest(template = "story_empty", batch = "ai-blueprint-transform", timeoutTicks = 20)
    public static void stairsMirrorShapeInTheRotatedSubregionFrame(GameTestHelper helper) {
        BlockState original = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT)
                .setValue(StairBlock.HALF, Half.TOP)
                .setValue(StairBlock.WATERLOGGED, true);
        BlockState clockwise = quarterTurn(Rotation.CLOCKWISE_90).transformState(original);
        BlockState counterclockwise = quarterTurn(Rotation.COUNTERCLOCKWISE_90).transformState(original);
        helper.assertTrue(clockwise.getValue(StairBlock.FACING) == Direction.WEST,
                "CW90 followed by local FRONT_BACK faces west");
        helper.assertTrue(counterclockwise.getValue(StairBlock.FACING) == Direction.EAST,
                "CCW90 followed by local FRONT_BACK faces east");
        for (BlockState result : new BlockState[] {clockwise, counterclockwise}) {
            helper.assertTrue(result.getValue(StairBlock.SHAPE) == StairsShape.INNER_RIGHT,
                    "The adjusted mirror swaps the inner stair shape");
            helper.assertTrue(result.getValue(StairBlock.HALF) == Half.TOP, "Top half is preserved");
            helper.assertTrue(result.getValue(StairBlock.WATERLOGGED), "Waterlogging is preserved");
        }
        helper.assertTrue(original.getValue(StairBlock.FACING) == Direction.NORTH
                && original.getValue(StairBlock.SHAPE) == StairsShape.INNER_LEFT, "Input state remains unchanged");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-blueprint-transform", timeoutTicks = 20)
    public static void doorHalvesRemainAlignedAndMirrorTheirHinge(GameTestHelper helper) {
        var transform = new BlueprintTransform(new Vec3i(1, 2, 1), BlockPos.ZERO,
                new BlockPos(10, 20, 30), Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE, Mirror.FRONT_BACK);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, true)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState transformedLower = transform.transformState(lower);
        BlockState transformedUpper = transform.transformState(upper);
        for (BlockState result : new BlockState[] {transformedLower, transformedUpper}) {
            helper.assertTrue(result.getValue(DoorBlock.FACING) == Direction.WEST, "Both door halves face west");
            helper.assertTrue(result.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT, "Mirror swaps door hinge");
            helper.assertTrue(result.getValue(DoorBlock.OPEN) && result.getValue(DoorBlock.POWERED),
                    "Open and powered properties are preserved");
        }
        helper.assertTrue(transformedLower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && transformedUpper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, "Door halves are preserved");
        helper.assertTrue(transform.worldPosition(0, 0, 0).equals(new BlockPos(10, 20, 30))
                && transform.worldPosition(0, 1, 0).equals(new BlockPos(10, 21, 30)),
                "Door cells remain exactly one block apart vertically");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-blueprint-transform", timeoutTicks = 20)
    public static void logsUseTheCombinedRotationAndKeepTheVerticalAxis(GameTestHelper helper) {
        var quarter = quarterTurn(Rotation.CLOCKWISE_90);
        var half = new BlueprintTransform(new Vec3i(1, 1, 1), BlockPos.ZERO, BlockPos.ZERO,
                Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE);
        var identity = new BlueprintTransform(new Vec3i(1, 1, 1), BlockPos.ZERO, BlockPos.ZERO,
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        BlockState x = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState y = x.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState z = x.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        helper.assertTrue(quarter.transformState(x).getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Z,
                "Quarter turn changes X logs to Z");
        helper.assertTrue(quarter.transformState(z).getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                "Quarter turn changes Z logs to X");
        helper.assertTrue(quarter.transformState(y).getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y,
                "Vertical logs keep Y");
        helper.assertTrue(half.transformState(x).getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                "Two quarter turns preserve the X axis");
        helper.assertTrue(identity.transformState(x) == x, "Identity transform preserves the original state");
        helper.succeed();
    }

    private static BlueprintTransform quarterTurn(Rotation globalRotation) {
        return new BlueprintTransform(new Vec3i(1, 1, 1), BlockPos.ZERO, BlockPos.ZERO,
                globalRotation, Mirror.NONE, Rotation.NONE, Mirror.FRONT_BACK);
    }
}
