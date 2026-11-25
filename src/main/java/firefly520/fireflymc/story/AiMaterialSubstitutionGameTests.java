package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.blueprint.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiMaterialSubstitutionGameTests {
    @GameTest(template = "story_empty", batch = "ai-substitution", timeoutTicks = 60)
    public static void planksKeepNativePropertiesAndHazardsWhileFunctionalBlocksRemainExact(GameTestHelper helper) {
        var pos = helper.absolutePos(BlockPos.ZERO); var level = helper.getLevel();
        var planks = MaterialSubstitutionRules.assess(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS, level, pos);
        helper.assertTrue(planks.allowed(), "Two native wooden plank variants retain geometry/fire/strength properties");
        helper.assertValueEqual(planks.replacement().blockId(), "minecraft:spruce_planks", "Replacement retains the exact target ID");
        helper.assertTrue(!MaterialSubstitutionRules.assess(Blocks.FURNACE.defaultBlockState(), Blocks.BLAST_FURNACE, level, pos).allowed(), "Different processing machines are not cosmetic substitutes");
        helper.assertTrue(!MaterialSubstitutionRules.assess(Blocks.REDSTONE_BLOCK.defaultBlockState(), Blocks.RED_TERRACOTTA, level, pos).allowed(), "Similar color cannot replace redstone behavior");
        var nonflammable = MaterialSubstitutionRules.assess(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.WARPED_PLANKS, level, pos);
        helper.assertTrue(!nonflammable.allowed() && nonflammable.reason().equals("FIRE_BEHAVIOR_CHANGED"), "A different fire behavior cannot pass by tag or name alone");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-substitution", timeoutTicks = 60)
    public static void slabAndStairOrientationAndWaterloggingSurviveTheSubstitution(GameTestHelper helper) {
        var pos = helper.absolutePos(BlockPos.ZERO); var level = helper.getLevel();
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP).setValue(SlabBlock.WATERLOGGED, true);
        var changed = MaterialSubstitutionRules.assess(slab, Blocks.BIRCH_SLAB, level, pos);
        helper.assertTrue(changed.allowed(), "Equivalent native slabs retain their real properties");
        var after = BlueprintBlockStateCodec.resolve(changed.replacement());
        helper.assertTrue(after.getValue(SlabBlock.TYPE) == SlabType.TOP && after.getValue(SlabBlock.WATERLOGGED), "Upper half and water source are preserved");
        var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT);
        var next = MaterialSubstitutionRules.assess(stairs, Blocks.SPRUCE_STAIRS, level, pos);
        helper.assertTrue(next.allowed(), "Equivalent stair variant is accepted");
        helper.assertValueEqual(BlueprintBlockStateCodec.describe(stairs).properties(), next.replacement().properties(), "No facing/half/shape is reset to a default");
        helper.assertTrue(!MaterialSubstitutionRules.assess(stairs, Blocks.STONE, level, pos).allowed(), "Solid blocks cannot replace stair geometry");
        helper.succeed();
    }
}
