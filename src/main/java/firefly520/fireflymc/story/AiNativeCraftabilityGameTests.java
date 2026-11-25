package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.processing.NativeRecipeBookQuery;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiNativeCraftabilityGameTests {
    private AiNativeCraftabilityGameTests() {}
    @GameTest(template = "story_empty", batch = "ai-native-craftability", timeoutTicks = 40)
    public static void screenshotInventoryImmediatelyFindsBothPlanksWithoutConsumingLogs(GameTestHelper helper) {
        var oak = new ItemStack(Items.OAK_LOG, 6); var birch = new ItemStack(Items.BIRCH_LOG, 7);
        var query = new NativeRecipeBookQuery(List.of(oak, birch, new ItemStack(Items.BREAD, 27), new ItemStack(Items.TORCH, 64)));
        var outputs = new HashSet<String>(); int inspected = 0;
        for (var holder : helper.getLevel().getRecipeManager().getRecipes()) {
            inspected++;
            if (NativeRecipeBookQuery.supports(holder.value())) query.craftable(holder, helper.getLevel().registryAccess())
                    .ifPresent(entry -> outputs.add(entry.get("outputId").getAsString()));
        }
        helper.assertTrue(inspected > 1024, "The complete native catalog is considered in one query, beyond both old page boundaries");
        helper.assertTrue(outputs.containsAll(Set.of("minecraft:oak_planks", "minecraft:birch_planks")), "Both planks shown by the vanilla recipe book are found");
        helper.assertTrue(!outputs.contains("minecraft:crafting_table"), "Direct availability does not pretend logs have already been converted to planks");
        helper.assertValueEqual(oak.getCount(), 6, "Oak logs remain unchanged"); helper.assertValueEqual(birch.getCount(), 7, "Birch logs remain unchanged");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-native-craftability", timeoutTicks = 40)
    public static void nativeMatchingCountsRepeatedIngredientsAndAllowsThreeByThreeWithoutAPlacedWorkbench(GameTestHelper helper) {
        var table = helper.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:crafting_table")).orElseThrow();
        helper.assertTrue(new NativeRecipeBookQuery(List.of(new ItemStack(Items.OAK_PLANKS, 3))).craftable(table, helper.getLevel().registryAccess()).isEmpty(),
                "Three planks do not satisfy four repeated ingredient positions");
        var query = new NativeRecipeBookQuery(List.of(new ItemStack(Items.OAK_PLANKS, 8)));
        for (int repeat = 0; repeat < 3; repeat++) {
            var value = query.craftable(table, helper.getLevel().registryAccess()).orElseThrow();
            helper.assertValueEqual(value.get("outputCount").getAsInt(), 1, "Native recipe output count is retained");
            helper.assertValueEqual(value.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("item").get("count").getAsInt(), 4,
                    "One available stack supplies the four positions without double-counting");
        }
        var chest = helper.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:chest")).orElseThrow();
        helper.assertTrue(query.craftable(chest, helper.getLevel().registryAccess()).isPresent(), "The AI's virtual three-by-three crafting remains available");
        helper.succeed();
    }
}
