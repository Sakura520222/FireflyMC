package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Immutable geometry matching the inspected Forgematica 0.4.2 placement convention.
 * This shared component has no dependency on Forgematica or client classes and performs no world writes.
 * Actual world bounds, resource budgets, permissions and approval remain the caller's responsibility.
 */
public final class BlueprintTransform {
    private final Vec3i containerSize;
    private final long volume;
    private final HorizontalTransform horizontal;
    private final long baseX;
    private final long baseY;
    private final long baseZ;
    private final Mirror globalMirror;
    private final Mirror adjustedSubRegionMirror;
    private final Rotation combinedRotation;

    /**
     * @param signedSize original signed subregion dimensions; container indices remain nonnegative
     * @param subRegionPosition current subregion placement position, not its original schematic position
     * @param origin global placement origin
     * @throws IllegalArgumentException for zero/unrepresentable dimensions, volume overflow, or any
     *                                  transformed region corner outside the integer coordinate range
     */
    public BlueprintTransform(Vec3i signedSize, BlockPos subRegionPosition, BlockPos origin,
                              Rotation globalRotation, Mirror globalMirror,
                              Rotation subRegionRotation, Mirror subRegionMirror) {
        Objects.requireNonNull(signedSize, "signedSize");
        Objects.requireNonNull(subRegionPosition, "subRegionPosition");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(globalRotation, "globalRotation");
        this.globalMirror = Objects.requireNonNull(globalMirror, "globalMirror");
        Objects.requireNonNull(subRegionRotation, "subRegionRotation");
        Objects.requireNonNull(subRegionMirror, "subRegionMirror");

        int signedX = signedSize.getX();
        int signedY = signedSize.getY();
        int signedZ = signedSize.getZ();
        int sizeX = checkedSize(signedX);
        int sizeY = checkedSize(signedY);
        int sizeZ = checkedSize(signedZ);
        this.containerSize = new Vec3i(sizeX, sizeY, sizeZ);
        try {
            this.volume = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("BLUEPRINT_VOLUME_OVERFLOW", overflow);
        }

        HorizontalTransform global = HorizontalTransform.of(globalMirror, globalRotation);
        HorizontalTransform local = HorizontalTransform.of(subRegionMirror, subRegionRotation);
        this.horizontal = local.after(global);
        long offsetX = signedX < 0 ? (long) signedX + 1 : 0;
        long offsetY = signedY < 0 ? (long) signedY + 1 : 0;
        long offsetZ = signedZ < 0 ? (long) signedZ + 1 : 0;

        // world = origin + global(subregionPosition) + local(global(index + signedMinOffset)).
        // Use long before reflection/translation, so negating Integer.MIN_VALUE cannot wrap.
        this.baseX = (long) origin.getX() + global.x(subRegionPosition.getX(), subRegionPosition.getZ())
                + horizontal.x(offsetX, offsetZ);
        this.baseY = (long) origin.getY() + subRegionPosition.getY() + offsetY;
        this.baseZ = (long) origin.getZ() + global.z(subRegionPosition.getX(), subRegionPosition.getZ())
                + horizontal.z(offsetX, offsetZ);

        this.combinedRotation = globalRotation.getRotated(subRegionRotation);
        boolean globalQuarterTurn = globalRotation == Rotation.CLOCKWISE_90
                || globalRotation == Rotation.COUNTERCLOCKWISE_90;
        this.adjustedSubRegionMirror = globalQuarterTurn && subRegionMirror != Mirror.NONE
                ? (subRegionMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK)
                : subRegionMirror;

        // A signed permutation is affine: all extrema occur at these corners.
        // Validate the entire region before a caller starts importing its first block.
        for (int x : new int[] {0, sizeX - 1}) {
            for (int y : new int[] {0, sizeY - 1}) {
                for (int z : new int[] {0, sizeZ - 1}) {
                    checkedCoordinate(baseX + horizontal.x(x, z));
                    checkedCoordinate(baseY + y);
                    checkedCoordinate(baseZ + horizontal.z(x, z));
                }
            }
        }
    }

    public Vec3i containerSize() { return containerSize; }
    public long volume() { return volume; }

    public BlockPos worldPosition(Vec3i containerIndex) {
        Objects.requireNonNull(containerIndex, "containerIndex");
        return worldPosition(containerIndex.getX(), containerIndex.getY(), containerIndex.getZ());
    }

    /** @throws IndexOutOfBoundsException when any index is outside the absolute container dimensions */
    public BlockPos worldPosition(int x, int y, int z) {
        if (x < 0 || x >= containerSize.getX() || y < 0 || y >= containerSize.getY()
                || z < 0 || z >= containerSize.getZ()) {
            throw new IndexOutOfBoundsException("BLUEPRINT_INDEX_OUT_OF_BOUNDS");
        }
        return new BlockPos(checkedCoordinate(baseX + horizontal.x(x, z)),
                checkedCoordinate(baseY + y), checkedCoordinate(baseZ + horizontal.z(x, z)));
    }

    /**
     * Uses each block's actual shared mirror/rotate implementation, preserving properties such as
     * stair shape and door hinge. Call from the appropriate game thread for mod-provided blocks.
     */
    public BlockState transformState(BlockState state) {
        Objects.requireNonNull(state, "state");
        BlockState transformed = state;
        if (globalMirror != Mirror.NONE) transformed = transformed.mirror(globalMirror);
        if (adjustedSubRegionMirror != Mirror.NONE) transformed = transformed.mirror(adjustedSubRegionMirror);
        if (combinedRotation != Rotation.NONE) transformed = transformed.rotate(combinedRotation);
        return transformed;
    }

    private static int checkedSize(int size) {
        if (size == 0 || size == Integer.MIN_VALUE)
            throw new IllegalArgumentException("BLUEPRINT_SIZE_INVALID");
        return Math.abs(size);
    }

    private static int checkedCoordinate(long coordinate) {
        if (coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE)
            throw new IllegalArgumentException("BLUEPRINT_COORDINATE_OVERFLOW");
        return (int) coordinate;
    }

    /** A horizontal signed permutation; composition order is local after global. */
    private record HorizontalTransform(int xx, int xz, int zx, int zz) {
        long x(long x, long z) { return xx * x + xz * z; }
        long z(long x, long z) { return zx * x + zz * z; }

        HorizontalTransform after(HorizontalTransform before) {
            return new HorizontalTransform(
                    xx * before.xx + xz * before.zx,
                    xx * before.xz + xz * before.zz,
                    zx * before.xx + zz * before.zx,
                    zx * before.xz + zz * before.zz);
        }

        static HorizontalTransform of(Mirror mirror, Rotation rotation) {
            int xSign = mirror == Mirror.FRONT_BACK ? -1 : 1;
            int zSign = mirror == Mirror.LEFT_RIGHT ? -1 : 1;
            return switch (rotation) {
                case NONE -> new HorizontalTransform(xSign, 0, 0, zSign);
                case CLOCKWISE_90 -> new HorizontalTransform(0, -zSign, xSign, 0);
                case CLOCKWISE_180 -> new HorizontalTransform(-xSign, 0, 0, -zSign);
                case COUNTERCLOCKWISE_90 -> new HorizontalTransform(0, zSign, -xSign, 0);
            };
        }
    }
}
