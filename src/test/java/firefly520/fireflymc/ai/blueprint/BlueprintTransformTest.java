package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTransformTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 64, 200);
    private static final BlockPos SUB_ORIGIN = new BlockPos(5, 2, -3);
    private static final Vec3i SIGNED_SIZE = new Vec3i(-3, 2, -2);

    @Test void negativeSizesStartAtTheMinimumCornerWithoutReversingContainerIndices() {
        var transform = base(Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        assertEquals(new Vec3i(3, 2, 2), transform.containerSize());
        assertEquals(12, transform.volume());
        assertEquals(new BlockPos(103, 66, 196), transform.worldPosition(0, 0, 0));
        assertEquals(new BlockPos(105, 67, 197), transform.worldPosition(new Vec3i(2, 1, 1)));

        var negativeY = new BlueprintTransform(new Vec3i(2, -3, 1), SUB_ORIGIN, ORIGIN,
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        assertEquals(new BlockPos(105, 64, 197), negativeY.worldPosition(0, 0, 0));
        assertEquals(new BlockPos(106, 66, 197), negativeY.worldPosition(1, 2, 0));

        var signedSingleBlock = new BlueprintTransform(new Vec3i(-1, -1, -1), SUB_ORIGIN, ORIGIN,
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        assertEquals(new BlockPos(105, 66, 197), signedSingleBlock.worldPosition(0, 0, 0));
    }

    @Test void fixedPlacementVectorsDistinguishGlobalAndSubregionTransformOrder() {
        assertEquals(new BlockPos(104, 66, 203),
                base(Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE, Mirror.NONE).worldPosition(0, 0, 0));
        assertEquals(new BlockPos(96, 66, 203),
                base(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT, Rotation.NONE, Mirror.NONE).worldPosition(0, 0, 0));
        assertEquals(new BlockPos(102, 66, 203),
                base(Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE, Mirror.FRONT_BACK).worldPosition(0, 0, 0));
        assertEquals(new BlockPos(105, 66, 206),
                base(Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE).worldPosition(0, 0, 0));
        assertEquals(new BlockPos(95, 66, 204),
                base(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT, Rotation.COUNTERCLOCKWISE_90,
                        Mirror.FRONT_BACK).worldPosition(0, 0, 0));
    }

    @Test void currentSubregionPositionIsTranslatedOnlyByTheGlobalTransform() {
        var moved = new BlueprintTransform(SIGNED_SIZE, new BlockPos(-7, 2, 6), ORIGIN,
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        assertEquals(new BlockPos(91, 66, 205), moved.worldPosition(0, 0, 0));
        var localRotation = new BlueprintTransform(new Vec3i(1, 1, 1), SUB_ORIGIN, ORIGIN,
                Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK);
        assertEquals(new BlockPos(103, 66, 205), localRotation.worldPosition(0, 0, 0),
                "The subregion origin must not be rotated or mirrored by the local transform");
    }

    @Test void eachHorizontalOperationHasAnIndependentlyCheckableAxisResult() {
        record Example(Rotation rotation, Mirror mirror, BlockPos expected) {}
        for (Example example : List.of(
                new Example(Rotation.NONE, Mirror.NONE, new BlockPos(2, 0, 3)),
                new Example(Rotation.NONE, Mirror.LEFT_RIGHT, new BlockPos(2, 0, -3)),
                new Example(Rotation.NONE, Mirror.FRONT_BACK, new BlockPos(-2, 0, 3)),
                new Example(Rotation.CLOCKWISE_90, Mirror.NONE, new BlockPos(-3, 0, 2)),
                new Example(Rotation.CLOCKWISE_180, Mirror.NONE, new BlockPos(-2, 0, -3)),
                new Example(Rotation.COUNTERCLOCKWISE_90, Mirror.NONE, new BlockPos(3, 0, -2)),
                new Example(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT, new BlockPos(3, 0, 2)))) {
            var transform = new BlueprintTransform(new Vec3i(3, 1, 4), BlockPos.ZERO, BlockPos.ZERO,
                    example.rotation(), example.mirror(), Rotation.NONE, Mirror.NONE);
            assertEquals(example.expected(), transform.worldPosition(2, 0, 3), example.toString());
        }
    }

    @Test void constructionSnapshotsMutableInputs() {
        var size = new BlockPos.MutableBlockPos(-3, 2, -2);
        var subOrigin = new BlockPos.MutableBlockPos(5, 2, -3);
        var origin = new BlockPos.MutableBlockPos(100, 64, 200);
        var transform = new BlueprintTransform(size, subOrigin, origin,
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        size.set(1000, 1000, 1000);
        subOrigin.set(0, 0, 0);
        origin.set(-1000, -1000, -1000);
        assertEquals(new BlockPos(103, 66, 196), transform.worldPosition(0, 0, 0));
        assertEquals(new Vec3i(3, 2, 2), transform.containerSize());
    }

    @Test void zeroDimensionsUnrepresentableAbsoluteSizesAndVolumeOverflowAreRejected() {
        for (Vec3i size : List.of(new Vec3i(0, 1, 1), new Vec3i(1, 0, 1), new Vec3i(1, 1, 0),
                new Vec3i(Integer.MIN_VALUE, 1, 1), new Vec3i(1, Integer.MIN_VALUE, 1),
                new Vec3i(1, 1, Integer.MIN_VALUE), new Vec3i(Integer.MAX_VALUE, Integer.MAX_VALUE, 3))) {
            assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(size,
                    BlockPos.ZERO, BlockPos.ZERO, Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE));
        }
    }

    @Test void theWholeRegionMustFitEvenWhenItsFirstCellWouldBeRepresentable() {
        assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(new Vec3i(3, 1, 1),
                BlockPos.ZERO, new BlockPos(Integer.MAX_VALUE - 1, 0, 0),
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(new Vec3i(-2, 1, 1),
                BlockPos.ZERO, new BlockPos(Integer.MIN_VALUE, 0, 0),
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(new Vec3i(1, 2, 1),
                BlockPos.ZERO, new BlockPos(0, Integer.MAX_VALUE, 0),
                Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(new Vec3i(2, 1, 1),
                BlockPos.ZERO, new BlockPos(0, 0, Integer.MAX_VALUE),
                Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE, Mirror.NONE));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintTransform(new Vec3i(1, 1, 1),
                new BlockPos(Integer.MIN_VALUE, 0, 0), BlockPos.ZERO,
                Rotation.NONE, Mirror.FRONT_BACK, Rotation.NONE, Mirror.NONE));
    }

    @Test void longArithmeticAvoidsIntermediateNegationAndVolumeWrapping() {
        var cancellation = new BlueprintTransform(new Vec3i(1, 1, 1),
                new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(-1, 0, 0),
                Rotation.NONE, Mirror.FRONT_BACK, Rotation.NONE, Mirror.NONE);
        assertEquals(new BlockPos(Integer.MAX_VALUE, 0, 0), cancellation.worldPosition(0, 0, 0));
        var large = new BlueprintTransform(new Vec3i(2_000_000, 2_000_000, 1),
                BlockPos.ZERO, BlockPos.ZERO, Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        assertEquals(4_000_000_000_000L, large.volume());
        assertEquals(new BlockPos(1_999_999, 1_999_999, 0), large.worldPosition(1_999_999, 1_999_999, 0));
    }

    @Test void indicesAreCheckedAgainstAbsoluteDimensionsOnAllAxes() {
        var transform = base(Rotation.NONE, Mirror.NONE, Rotation.NONE, Mirror.NONE);
        for (Vec3i index : List.of(new Vec3i(-1, 0, 0), new Vec3i(0, -1, 0), new Vec3i(0, 0, -1),
                new Vec3i(3, 0, 0), new Vec3i(0, 2, 0), new Vec3i(0, 0, 2))) {
            assertThrows(IndexOutOfBoundsException.class, () -> transform.worldPosition(index));
        }
        assertThrows(NullPointerException.class, () -> transform.worldPosition(null));
        assertThrows(NullPointerException.class, () -> transform.transformState(null));
    }

    private static BlueprintTransform base(Rotation globalRotation, Mirror globalMirror,
                                           Rotation subRotation, Mirror subMirror) {
        return new BlueprintTransform(SIGNED_SIZE, SUB_ORIGIN, ORIGIN,
                globalRotation, globalMirror, subRotation, subMirror);
    }
}
