package firefly520.fireflymc.story;

import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.advanced.BuildingDynamicRecipes;
import firefly520.fireflymc.mixin.AiSavedDataCacheAccess;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;

/** Loaded recipe implementations and real component inputs, without display-JSON output synthesis. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiBuildingDynamicGameTests {
    @GameTestGenerator public static Collection<TestFunction> installed() {
        var tests = new ArrayList<TestFunction>();
        if (ModList.get().isLoaded("quark")) add(tests, "quark_native_mixed_component_grid", AiBuildingDynamicGameTests::quarkMixed);
        if (ModList.get().isLoaded("supplementaries")) {
            add(tests, "supplementaries_native_safe_real_contents", AiBuildingDynamicGameTests::safe);
            add(tests, "supplementaries_native_present_dye_components", AiBuildingDynamicGameTests::presentDye);
        }
        if (ModList.get().isLoaded("map_atlases")) add(tests, "atlas_native_empty_maps_virtual_shears", AiBuildingDynamicGameTests::atlasEmptyMaps);
        if (ModList.get().isLoaded("map_atlases")) add(tests, "atlas_cached_header_only_no_map_allocation", AiBuildingDynamicGameTests::atlasFilledHeader);
        return tests;
    }
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        tests.add(new TestFunction("ai-building-dynamic", "fireflymc." + name, "fireflymc:story_empty", 120, 0, true, run));
    }
    private static ServerPlayer owner(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "AiDynamicTest"), ClientInformation.createDefault());
    }
    private static List<ItemStack> grid(ItemStack... stacks) {
        var result = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < stacks.length; i++) result.set(i, stacks[i]); return result;
    }
    private static ItemStack item(String id) { return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElseThrow().getDefaultInstance(); }
    private static RecipeHolder<?> recipe(GameTestHelper helper, String suffix) {
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> BuildingDynamicRecipes.supports(holder.value())
                && holder.value().getClass().getSimpleName().equals(suffix)).findFirst().orElseThrow();
    }
    private static RecipeHolder<?> recipe(GameTestHelper helper, String suffix, List<ItemStack> grid) {
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> BuildingDynamicRecipes.supports(holder.value())
                && holder.value().getClass().getSimpleName().equals(suffix)).filter(holder -> {
                    try { BuildingDynamicRecipes.verify(holder, grid, owner(helper)); return true; }
                    catch (IllegalStateException mismatch) { return false; }
                }).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static ItemStack nativeOutput(RecipeHolder<?> holder, List<ItemStack> grid, GameTestHelper helper) {
        var input = CraftingInput.ofPositioned(3, 3, RecipeInputIsolation.copyAll(grid, helper.getLevel().registryAccess())).input();
        var recipe = (Recipe<CraftingInput>) holder.value();
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "Actual installed native recipe accepts the concrete materials");
        return recipe.assemble(input, helper.getLevel().registryAccess());
    }
    private static void quarkMixed(GameTestHelper helper) {
        var grid = grid(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY, new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS));
        var holder = recipe(helper, "MixedExclusionRecipe", grid); var expected = nativeOutput(holder, grid, helper);
        var result = BuildingDynamicRecipes.verify(holder, grid, owner(helper));
        helper.assertTrue(ItemStack.matches(result.outputs().getFirst(), expected), "Mixed material recipe output comes from native matches/assemble");
        helper.assertValueEqual(result.consumedBySlot().stream().mapToInt(Integer::intValue).sum(), 8, "All eight actual boards are material inputs");
        helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty), "No device/fuel refund is invented"); helper.succeed();
    }
    private static void safe(GameTestHelper helper) {
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
        box.set(DataComponents.CUSTOM_NAME, Component.literal("Actual private contents"));
        var grid = grid(box, new ItemStack(Items.NETHERITE_INGOT));
        var holder = recipe(helper, "SafeRecipe", grid); var before = ItemSnapshotCodec.capture(box, helper.getLevel().registryAccess());
        var expected = nativeOutput(holder, grid, helper); var result = BuildingDynamicRecipes.verify(holder, grid, owner(helper));
        helper.assertTrue(ItemStack.matches(expected, result.outputs().getFirst()), "Actual shulker-to-safe transmute preserves native component rules");
        helper.assertTrue(result.outputs().getFirst().get(DataComponents.CONTAINER).nonEmptyItems().iterator().next().getCount() == 3,
                "Real contents are transferred rather than created or discarded");
        helper.assertValueEqual(ItemSnapshotCodec.capture(box, helper.getLevel().registryAccess()), before, "Preview does not change the real container"); helper.succeed();
    }
    private static void presentDye(GameTestHelper helper) {
        var present = item("supplementaries:present"); present.set(DataComponents.CUSTOM_NAME, Component.literal("Real present"));
        present.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 2))));
        var grid = grid(present, new ItemStack(Items.RED_DYE)); var holder = recipe(helper, "PresentDyeRecipe", grid);
        var expected = nativeOutput(holder, grid, helper); var result = BuildingDynamicRecipes.verify(holder, grid, owner(helper));
        helper.assertTrue(ItemStack.matches(expected, result.outputs().getFirst()), "Native dye transmute controls actual color and components");
        helper.assertTrue(result.outputs().getFirst().get(DataComponents.CONTAINER).nonEmptyItems().iterator().next().getCount() == 2, "Present payload is retained");
        helper.assertTrue(grid.getFirst().getHoverName().getString().equals("Real present"), "Original present metadata is untouched"); helper.succeed();
    }
    private static void atlasEmptyMaps(GameTestHelper helper) {
        var atlas = item("map_atlases:atlas"); atlas.set(DataComponents.CUSTOM_NAME, Component.literal("Private atlas"));
        var input = grid(atlas, new ItemStack(Items.MAP)); var holder = recipe(helper, "MapAtlasesAddRecipe");
        var before = ItemSnapshotCodec.capture(atlas, helper.getLevel().registryAccess());
        var added = BuildingDynamicRecipes.verify(holder, input, owner(helper));
        helper.assertValueEqual(ItemSnapshotCodec.capture(atlas, helper.getLevel().registryAccess()), before, "Native atlas component initialization never mutates the real/global default");
        var cutting = recipe(helper, "MapAtlasesCutExistingRecipe");
        var cut = BuildingDynamicRecipes.verify(cutting, grid(added.outputs().getFirst()), owner(helper));
        helper.assertTrue(cut.outputs().getFirst().is(Items.MAP), "A genuinely stored empty map is extracted");
        helper.assertTrue(cut.inputSnapshots().stream().noneMatch(stack -> stack.is(Items.SHEARS))
                && cut.remaindersBySlot().stream().noneMatch(stack -> stack.is(Items.SHEARS)), "Virtual shear is neither charged nor manufactured as a remainder");
        helper.assertTrue(cut.remaindersBySlot().stream().anyMatch(stack -> stack.is(atlas.getItem())), "Native atlas with remaining metadata is actually returned"); helper.succeed();
    }
    private static void atlasFilledHeader(GameTestHelper helper) {
        // A real pre-existing vanilla map is fixture setup. Adapter preview must not create another
        // ID, read/copy its pixels, or place a new SavedData object in the actual world cache.
        var map = MapItem.create(helper.getLevel(), 384, -256, (byte) 1, true, false);
        var id = map.get(DataComponents.MAP_ID);
        var cache = ((AiSavedDataCacheAccess) helper.getLevel().getServer().overworld().getDataStorage()).fireflymc$existingStores();
        var keys = Set.copyOf(cache.keySet()); var original = cache.get(id.key());
        var input = grid(new ItemStack(Items.BOOK), new ItemStack(Items.SLIME_BALL), map);
        var holder = recipe(helper, "MapAtlasCreateRecipe");
        var result = BuildingDynamicRecipes.verify(holder, input, owner(helper));
        helper.assertTrue(result.outputs().getFirst().is(item("map_atlases:atlas").getItem()), "The actual native filled-map atlas recipe runs with cached headers");
        helper.assertValueEqual(Set.copyOf(cache.keySet()), keys, "Preview allocates no real map ID or SavedData");
        helper.assertTrue(cache.get(id.key()) == original, "Original map data identity is retained");
        helper.assertTrue(input.get(2).get(DataComponents.MAP_ID).equals(id), "The real filled map ID is not changed");
        helper.assertTrue(result.contextRequirements().get("atlasHeaderDigest").matches("[0-9a-f]{64}"), "A source/version-bound header witness is recorded without pixels");
        helper.succeed();
    }
}
