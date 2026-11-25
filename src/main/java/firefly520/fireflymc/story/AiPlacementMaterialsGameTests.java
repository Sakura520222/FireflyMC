package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.blueprint.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiPlacementMaterialsGameTests {
    private static BlueprintDocument.Block cell(int x, int y, int z, BlockState state) {
        return new BlueprintDocument.Block(new BlueprintDocument.Position(x, y, z), BlueprintBlockStateCodec.describe(state));
    }
    @GameTest(template = "story_empty", batch = "ai-placement-materials", timeoutTicks = 80)
    public static void doorAndBedChargeOneItemForTheirTwoNativeCells(GameTestHelper helper) {
        var lower = cell(0, 0, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST));
        var upper = cell(0, 1, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        var door = Map.of(lower.position(), lower, upper.position(), upper);
        var group = PlacementMaterialResolver.resolve(lower, door).orElseThrow();
        helper.assertValueEqual(group.itemCount(), 1, "Two door cells are one item");
        helper.assertValueEqual(group.targets().size(), 2, "Both door cells are bound to one operation");
        helper.assertTrue(PlacementMaterialResolver.resolve(upper, door).isEmpty(), "Upper half is not independently charged");
        var foot = cell(0, 0, 0, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH));
        var head = cell(0, 0, -1, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        var bed = Map.of(foot.position(), foot, head.position(), head);
        var bedGroup = PlacementMaterialResolver.resolve(foot, bed).orElseThrow();
        helper.assertValueEqual(bedGroup.itemCount(), 1, "A complete bed consumes one native bed item");
        helper.assertTrue(PlacementMaterialResolver.resolve(head, bed).isEmpty(), "Bed head is paired");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-placement-materials", timeoutTicks = 80)
    public static void doubleSlabChargesTwoFromAirOneFromHalfAndZeroWhenSatisfied(GameTestHelper helper) {
        var target = cell(0, 0, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        var group = PlacementMaterialResolver.resolve(target, Map.of(target.position(), target)).orElseThrow();
        helper.assertValueEqual(PlacementMaterialResolver.requiredItems(group, Map.of(target.position(), Blocks.AIR.defaultBlockState())), 2, "Double slab is two placements from air");
        helper.assertValueEqual(PlacementMaterialResolver.requiredItems(group, Map.of(target.position(), Blocks.OAK_SLAB.defaultBlockState())), 1, "Existing half slab is real material");
        helper.assertValueEqual(PlacementMaterialResolver.requiredItems(group, Map.of(target.position(), BlueprintBlockStateCodec.resolve(target.state()))), 0, "Satisfied target needs no new item");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-placement-materials", timeoutTicks = 80)
    public static void incompleteOrMismatchedMultiblocksAndAdministrativeBlocksAreRejected(GameTestHelper helper) {
        var lower = cell(0, 0, 0, Blocks.OAK_DOOR.defaultBlockState());
        rejects(helper, () -> PlacementMaterialResolver.resolve(lower, Map.of(lower.position(), lower)), "PLACEMENT_MULTIBLOCK_INCOMPLETE");
        var upper = cell(0, 1, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.WEST));
        rejects(helper, () -> PlacementMaterialResolver.resolve(lower, Map.of(lower.position(), lower, upper.position(), upper)), "PLACEMENT_MULTIBLOCK_MISMATCH");
        var command = cell(0, 0, 0, Blocks.COMMAND_BLOCK.defaultBlockState());
        rejects(helper, () -> PlacementMaterialResolver.resolve(command, Map.of(command.position(), command)), "PLACEMENT_ADMIN_BLOCK_FORBIDDEN");
        var wetDouble = cell(0, 0, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE).setValue(SlabBlock.WATERLOGGED, true));
        rejects(helper, () -> PlacementMaterialResolver.resolve(wetDouble, Map.of(wetDouble.position(), wetDouble)), "PLACEMENT_STATE_UNREACHABLE");
        helper.succeed();
    }
    private static void rejects(GameTestHelper helper, Runnable action, String code) {
        try { action.run(); helper.fail("Expected " + code); }
        catch (IllegalArgumentException exception) { helper.assertValueEqual(exception.getMessage(), code, "Exact refusal reason"); }
    }
}
