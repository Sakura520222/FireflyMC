package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.processing.special.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Tests load native recipe classes only when that actual mod is present. Missing mods are not passing tests. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiSpecialProcessingGameTests {
    private AiSpecialProcessingGameTests() {}

    @GameTest(template = "story_empty")
    public static void fluidComponentsAreDetachedAndQuantityIsNotEnergy(GameTestHelper helper) {
        FluidStack input = new FluidStack(Fluids.WATER, 1000);
        CompoundTag data = new CompoundTag(); data.putString("source", "real-fluid");
        input.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        FluidMaterial saved = FluidMaterial.capture(input, helper.getLevel().registryAccess());
        input.setAmount(1); input.remove(DataComponents.CUSTOM_DATA);
        FluidStack restored = saved.restore(helper.getLevel().registryAccess());
        helper.assertValueEqual(restored.getAmount(), 1000, "Raw fluid volume is preserved separately from waived fuel");
        helper.assertValueEqual(restored.get(DataComponents.CUSTOM_DATA).copyTag().getString("source"), "real-fluid", "Native fluid components are detached");
        restored.setAmount(2);
        helper.assertValueEqual(saved.restore(helper.getLevel().registryAccess()).getAmount(), 1000, "Restoring cannot mutate the stored escrow value");
        helper.succeed();
    }

    @GameTestGenerator
    public static Collection<TestFunction> installedSpecialRecipes() {
        var tests = new ArrayList<TestFunction>();
        add(tests, "aether", "aether_repair_native_components", AiSpecialProcessingGameTests::aetherRepair);
        add(tests, "aether", "aether_material_container_not_fuel", AiSpecialProcessingGameTests::aetherContainer);
        add(tests, "aether", "aether_incubation_prepared_entity", AiSpecialProcessingGameTests::aetherIncubation);
        add(tests, "aether", "aether_placement_ban_is_not_production", AiSpecialProcessingGameTests::aetherWorldRule);
        add(tests, "deep_aether", "deep_combiner_distinct_material_units", AiSpecialProcessingGameTests::deepCombiner);
        add(tests, "twilightforest", "twilight_uncrafting_real_batch", AiSpecialProcessingGameTests::twilightUncrafting);
        add(tests, "twilightforest", "twilight_drying_native_result", AiSpecialProcessingGameTests::twilightDrying);
        add(tests, "twilightforest", "twilight_recrafting_materials_and_enchantment", AiSpecialProcessingGameTests::twilightRecrafting);
        add(tests, "cataclysm", "cataclysm_fusion_component_precedence", AiSpecialProcessingGameTests::cataclysmFusion);
        add(tests, "cataclysm", "cataclysm_amethyst_actual_holder", AiSpecialProcessingGameTests::cataclysmAmethyst);
        add(tests, "touhou_little_maid", "maid_altar_item_without_power", AiSpecialProcessingGameTests::maidItem);
        add(tests, "touhou_little_maid", "maid_box_is_two_native_entities", AiSpecialProcessingGameTests::maidBox);
        add(tests, "irons_spellbooks", "irons_brew_real_fluid_quantities", AiSpecialProcessingGameTests::ironsBrew);
        add(tests, "irons_spellbooks", "irons_brew_native_byproduct_counts", AiSpecialProcessingGameTests::ironsBrewByproducts);
        add(tests, "irons_spellbooks", "irons_brew_actual_item_only_recipes", AiSpecialProcessingGameTests::ironsItemOnlyBrew);
        add(tests, "irons_spellbooks", "irons_native_fluid_batch_and_disallow", AiSpecialProcessingGameTests::ironsFluidLimits);
        add(tests, "irons_spellbooks", "irons_scroll_native_precedence", AiSpecialProcessingGameTests::ironsScrollPrecedence);
        add(tests, "irons_spellbooks", "irons_fill_empty_container_cycle", AiSpecialProcessingGameTests::ironsContainerCycle);
        add(tests, "irons_spellbooks", "irons_potion_fluid_fallback", AiSpecialProcessingGameTests::ironsPotionFallback);
        add(tests, "irons_spellbooks", "irons_scroll_success_and_failure_materials", AiSpecialProcessingGameTests::ironsScroll);
        add(tests, "irons_spellbooks", "irons_contained_fluid_conservation", AiSpecialProcessingGameTests::ironsContained);
        add(tests, "the_bumblezone", "bumble_container_deduction", AiSpecialProcessingGameTests::bumbleContainer);
        add(tests, "the_bumblezone", "bumble_nbt_keep_actual_count", AiSpecialProcessingGameTests::bumbleNbt);
        add(tests, "the_bumblezone", "bumble_candle_native_components_and_seed", AiSpecialProcessingGameTests::bumbleCandle);
        return tests;
    }

    private static void add(List<TestFunction> tests, String mod, String name, Consumer<GameTestHelper> action) {
        if (ModList.get().isLoaded(mod)) tests.add(new TestFunction("ai-special-" + mod, "fireflymc." + name, "fireflymc:story_empty", 160, 0, true, action));
    }

    private static RecipeHolder<?> find(GameTestHelper helper, Predicate<RecipeHolder<?>> predicate) {
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(predicate).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_SPECIAL_RECIPE_NOT_FOUND"));
    }
    private static List<ItemStack> grid(ItemStack... items) {
        var result = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < items.length; i++) result.set(i, items[i]);
        return result;
    }
    private static NonNullList<Ingredient> ingredients(Ingredient... ingredients) {
        NonNullList<Ingredient> result = NonNullList.create(); result.addAll(List.of(ingredients)); return result;
    }
    private static Recipe<?> construct(String name, Class<?>[] types, Object... arguments) {
        try { return (Recipe<?>) Class.forName(name).getConstructor(types).newInstance(arguments); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("NATIVE_SPECIAL_FIXTURE_API_MISMATCH", failure); }
    }
    private static void fixture(GameTestHelper helper, String id, Recipe<?> recipe, Consumer<RecipeHolder<?>> verify) {
        var manager = helper.getLevel().getRecipeManager(); var previous = List.copyOf(manager.getRecipes());
        var holder = new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("fireflymc", "special_test_" + id), recipe);
        try {
            var recipes = new ArrayList<RecipeHolder<?>>(previous); recipes.add(holder); manager.replaceRecipes(recipes);
            verify.accept(holder);
        } finally { manager.replaceRecipes(previous); }
    }
    private static void refused(GameTestHelper helper, Runnable action, String reason) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, reason);
    }

    private static void aetherRepair(GameTestHelper helper) {
        var holder = find(helper, h -> h.value().getClass().getName().endsWith(".AltarRepairRecipe")
                && h.value().getIngredients().getFirst().getItems().length > 0
                && h.value().getIngredients().getFirst().getItems()[0].isDamageableItem());
        ItemStack input = holder.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
        input.setDamageValue(Math.min(9, input.getMaxDamage() - 1));
        input.set(DataComponents.CUSTOM_NAME, Component.literal("private repair target"));
        input.set(DataComponents.REPAIR_COST, 4);
        var original = ItemSnapshotCodec.capture(input, helper.getLevel().registryAccess());
        var result = SpecialItemProcessingAdapter.verify(holder, List.of(input), helper.getLevel());
        ItemStack output = result.outputs().getFirst();
        helper.assertTrue(output.is(input.getItem()) && output.getDamageValue() == 0, "Actual altar repair restores the real item");
        helper.assertValueEqual(output.get(DataComponents.CUSTOM_NAME), input.get(DataComponents.CUSTOM_NAME), "Name is inherited from input, not ingredient preview");
        helper.assertValueEqual(output.get(DataComponents.REPAIR_COST), 4, "Input repair component is retained");
        helper.assertValueEqual(ItemSnapshotCodec.capture(input, helper.getLevel().registryAccess()), original, "Planning is read-only");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1), "The target equipment remains a real consumed input");
        helper.succeed();
    }

    private static void aetherContainer(GameTestHelper helper) {
        try {
            Class<?> category = Class.forName("com.aetherteam.aether.recipe.AetherBookCategory");
            Recipe<?> recipe = construct("com.aetherteam.aether.recipe.recipes.item.EnchantingRecipe",
                    new Class<?>[]{String.class, category, Ingredient.class, ItemStack.class, float.class, int.class},
                    "", category.getEnumConstants()[0], Ingredient.of(Items.LAVA_BUCKET), new ItemStack(Items.OBSIDIAN), 0.5f, 400);
            fixture(helper, "aether_bucket", recipe, holder -> {
                var result = SpecialItemProcessingAdapter.verify(holder, List.of(new ItemStack(Items.LAVA_BUCKET)), helper.getLevel());
                helper.assertTrue(result.remaindersBySlot().getFirst().is(Items.BUCKET), "A real raw-material bucket returns exactly once");
                helper.assertValueEqual(result.remaindersBySlot().getFirst().getCount(), 1, "No virtual fuel bucket is fabricated");
                helper.assertValueEqual(result.experience().perOperation(), 0.5f, "Declared normal reward survives the processing fee waiver");
            });
        } catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
        helper.succeed();
    }

    private static void aetherIncubation(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
        var holder = find(helper, h -> EntityProcessingAdapter.kind(h.value()) == EntityProcessingAdapter.Kind.AETHER_INCUBATION);
        var player = session.player;
        ItemStack egg = holder.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
        egg.set(DataComponents.CUSTOM_NAME, Component.literal("hatched from real egg"));
        var target = helper.absolutePos(new BlockPos(1, 2, 1));
        EntityProcessingAdapter.SpawnGuard guard = (actor, plan, bounds) -> {
            if (!actor.getUUID().equals(player.getUUID()) || !plan.target().equals(target)) throw new IllegalStateException("TEST_REGION_DENIED");
        };
        var plan = EntityProcessingAdapter.plan(holder, List.of(egg), player, target, UUID.randomUUID(), guard);
        var prepared = EntityProcessingAdapter.prepareAfterEscrow(holder, plan, player, guard);
        var entity = EntityProcessingAdapter.restorePrepared(prepared, player, guard);
        helper.assertValueEqual(entity.getCustomName(), egg.get(DataComponents.CUSTOM_NAME), "Native default-stack configuration retains egg name");
        helper.assertTrue(helper.getLevel().getEntity(entity.getUUID()) == null, "Preparation has not inserted a world entity");
        helper.assertTrue(!EntityProcessingAdapter.verifyPresent(prepared, helper.getLevel()), "Prepared NBT alone is never successful spawn evidence");
        helper.assertValueEqual(plan.consumed().getFirst().count(), 1, "Exactly one real egg backs the output");
        helper.succeed();
        }
    }

    private static void aetherWorldRule(GameTestHelper helper) {
        var holder = find(helper, h -> AetherWorldRuleAdapter.profile(h.value()) != null
                && AetherWorldRuleAdapter.profile(h.value()).role() == AetherWorldRuleAdapter.Role.BLOCK_BAN);
        var target = helper.absolutePos(new BlockPos(1, 1, 1)); var before = helper.getLevel().getBlockState(target);
        var query = AetherWorldRuleAdapter.inspect(holder, target, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), null, helper.getLevel());
        helper.assertTrue(!query.isItemProduction() && query.role() == AetherWorldRuleAdapter.Role.BLOCK_BAN, "A placement prohibition remains a world rule");
        helper.assertTrue(!SpecialItemProcessingAdapter.supports(holder.value()), "No item adapter interprets the rule as an empty-input recipe");
        helper.assertValueEqual(helper.getLevel().getBlockState(target), before, "Read-only rule inspection does not ban/replace a world block");
        helper.succeed();
    }

    private static void deepCombiner(GameTestHelper helper) {
        try {
            Class<?> category = Class.forName("io.github.razordevs.deep_aether.recipe.DABookCategory");
            Recipe<?> recipe = construct("io.github.razordevs.deep_aether.recipe.combiner.CombinerRecipe",
                    new Class<?>[]{String.class, category, List.class, ItemStack.class, float.class, int.class}, "", category.getEnumConstants()[0],
                    List.of(Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)), new ItemStack(Items.STICK, 2), 0f, 400);
            fixture(helper, "combiner", recipe, holder -> {
                refused(helper, () -> SpecialItemProcessingAdapter.verify(holder, List.of(new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY, ItemStack.EMPTY), helper.getLevel()), "One slot cannot satisfy three native overlapping ingredients");
                var result = SpecialItemProcessingAdapter.verify(holder, List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS)), helper.getLevel());
                helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1, 1), "Three independent material assignments are consumed");
                helper.assertValueEqual(result.outputs().getFirst().getCount(), 2, "Native complete output is retained");
            });
        } catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
        helper.succeed();
    }

    private static void twilightUncrafting(GameTestHelper helper) {
        var holder = helper.getLevel().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("stick")).orElseThrow();
        var result = TwilightUncraftingAdapter.verify(holder, new ItemStack(Items.STICK, 4), 0, helper.getLevel());
        helper.assertValueEqual(result.consumedBySlot(), List.of(4), "Reverse crafting consumes the entire original recipe output batch");
        helper.assertValueEqual(result.outputs().stream().mapToInt(ItemStack::getCount).sum(), 2, "The actual stick recipe returns two selected planks");
        refused(helper, () -> TwilightUncraftingAdapter.verify(holder, new ItemStack(Items.STICK, 1), 0, helper.getLevel()), "One stick cannot uncraft a four-stick recipe");
        helper.assertValueEqual(result.experience().perOperation(), 0f, "XP charge is waived without inventing an XP reward");
        helper.succeed();
    }

    private static void twilightDrying(GameTestHelper helper) {
        var holder = find(helper, h -> h.value().getClass().getName().equals("twilightforest.item.recipe.DryingRecipe"));
        var input = holder.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
        var result = SpecialItemProcessingAdapter.verify(holder, List.of(input), helper.getLevel());
        @SuppressWarnings("unchecked") var nativeRecipe = (Recipe<SingleRecipeInput>) holder.value();
        helper.assertValueEqual(ItemSnapshotCodec.capture(result.outputs().getFirst(), helper.getLevel().registryAccess()),
                ItemSnapshotCodec.capture(nativeRecipe.assemble(new SingleRecipeInput(input.copy()), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()), "Drying uses the actual selected native recipe");
        helper.assertTrue(result.callbackPlan().requiredStages().isEmpty(), "Drying racks do not invent crafting/XP callbacks");
        helper.succeed();
    }

    private static void twilightRecrafting(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
        var manager = helper.getLevel().getRecipeManager();
        var original = manager.byKey(ResourceLocation.withDefaultNamespace("wooden_pickaxe")).orElseThrow();
        var target = manager.byKey(ResourceLocation.withDefaultNamespace("diamond_pickaxe")).orElseThrow();
        var player = session.player;
        ItemStack source = new ItemStack(Items.WOODEN_PICKAXE);
        var efficiency = helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
        source.enchant(efficiency, 2);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("not inherited by native recrafting"));
        var additions = grid(new ItemStack(Items.DIAMOND), new ItemStack(Items.DIAMOND), new ItemStack(Items.DIAMOND));
        var result = TwilightRecraftingAdapter.verify(original, source, 0, target, additions, player);
        helper.assertTrue(result.outputs().getFirst().is(Items.DIAMOND_PICKAXE), "The fixed target recipe determines the replacement equipment");
        helper.assertValueEqual(result.outputs().getFirst().getEnchantments().getLevel(efficiency), 2, "Compatible native input enchantment is merged");
        helper.assertTrue(!result.outputs().getFirst().has(DataComponents.CUSTOM_NAME), "Recrafting does not invent inheritance of unrelated components");
        helper.assertValueEqual(result.consumedBySlot().stream().mapToInt(Integer::intValue).sum(), 4, "One real old tool and three real new diamonds back the result");
        helper.assertValueEqual(result.backingRecipes().size(), 2, "Both uncrafting and target recipe references are pinned");
        helper.succeed();
        }
    }

    private static void cataclysmFusion(GameTestHelper helper) {
        ItemStack configured = new ItemStack(Items.NETHERITE_SWORD); configured.set(DataComponents.CUSTOM_NAME, Component.literal("configured result name"));
        Recipe<?> recipe = construct("com.github.L_Ender.cataclysm.crafting.WeaponfusionRecipe",
                new Class<?>[]{Ingredient.class, Ingredient.class, ItemStack.class}, Ingredient.of(Items.DIAMOND_SWORD), Ingredient.of(Items.NETHERITE_INGOT), configured);
        var retained = new RecipeHolder<?>[1];
        fixture(helper, "fusion", recipe, holder -> {
            retained[0] = holder;
            ItemStack base = new ItemStack(Items.DIAMOND_SWORD); base.setDamageValue(11); base.set(DataComponents.REPAIR_COST, 6);
            base.set(DataComponents.CUSTOM_NAME, Component.literal("old input name"));
            var result = SpecialItemProcessingAdapter.verify(holder, List.of(base, new ItemStack(Items.NETHERITE_INGOT)), helper.getLevel());
            var output = result.outputs().getFirst();
            helper.assertTrue(output.is(Items.NETHERITE_SWORD) && output.getDamageValue() == 11, "Native fusion inherits base component state");
            helper.assertValueEqual(output.get(DataComponents.REPAIR_COST), 6, "Non-overridden base components survive");
            helper.assertValueEqual(output.get(DataComponents.CUSTOM_NAME), configured.get(DataComponents.CUSTOM_NAME), "Recipe result patch overrides the base name");
            helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1), "No nonexistent template is charged");
        });
        refused(helper, () -> SpecialItemProcessingAdapter.verify(retained[0], List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.NETHERITE_INGOT)), helper.getLevel()), "Removed/reloaded native holders cannot execute an old plan");
        helper.succeed();
    }

    private static void cataclysmAmethyst(GameTestHelper helper) {
        var holder = find(helper, h -> h.value().getClass().getName().endsWith(".AltarOfAmethystRecipe"));
        var input = holder.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
        var result = SpecialItemProcessingAdapter.verify(holder, List.of(input), helper.getLevel());
        helper.assertValueEqual(result.outputs().getFirst().getCount(), holder.value().getResultItem(helper.getLevel().registryAccess()).getCount(), "Altar yields the full declared stack");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1), "Exactly one raw input is used without a physical altar");
        helper.assertTrue(result.callbackPlan().requiredStages().isEmpty(), "Altar output does not fabricate smithing events");
        helper.succeed();
    }

    private static Recipe<?> altar(NonNullList<Ingredient> ingredients, ItemStack output, String entity) {
        return construct("com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe",
                new Class<?>[]{String.class, CraftingBookCategory.class, NonNullList.class, float.class, ItemStack.class, ResourceLocation.class, String.class},
                "", CraftingBookCategory.MISC, ingredients, 1_000_000f, output, ResourceLocation.parse(entity), "test");
    }

    private static void maidItem(GameTestHelper helper) {
        fixture(helper, "maid_item", altar(ingredients(Ingredient.of(Items.MILK_BUCKET)), new ItemStack(Items.DIAMOND, 2), "minecraft:item"), holder -> {
            var result = SpecialItemProcessingAdapter.verify(holder, grid(new ItemStack(Items.MILK_BUCKET)), helper.getLevel());
            helper.assertValueEqual(result.outputs().getFirst().getCount(), 2, "Item altar delivers its native item result without paying power");
            helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty), "Native altar clears material slots; it does not additionally return generic buckets");
            helper.assertValueEqual(result.consumedBySlot().stream().mapToInt(Integer::intValue).sum(), 1, "Only one allocated real input is consumed");
        });
        helper.succeed();
    }

    private static void maidBox(GameTestHelper helper) {
        fixture(helper, "maid_box", altar(ingredients(Ingredient.of(Items.CAKE)), new ItemStack(Items.PAPER), "touhou_little_maid:box"), holder -> {
            try (var session = new PlayerSession(helper)) {
            helper.assertTrue(!SpecialItemProcessingAdapter.supports(holder.value()), "An entity placeholder is never accepted by the item executor");
            var player = session.player; var target = helper.absolutePos(new BlockPos(1, 2, 1));
            EntityProcessingAdapter.SpawnGuard guard = (actor, plan, bounds) -> {
                if (!plan.owner().equals(player.getUUID()) || !target.equals(plan.target())) throw new IllegalStateException("TEST_REGION_DENIED");
            };
            var plan = EntityProcessingAdapter.plan(holder, grid(new ItemStack(Items.CAKE)), player, target, UUID.randomUUID(), guard);
            var prepared = EntityProcessingAdapter.prepareAfterEscrow(holder, plan, player, guard);
            helper.assertValueEqual(prepared.members().size(), 2, "Native box recipe creates a box plus riding maid");
            var root = EntityProcessingAdapter.restorePrepared(prepared, player, guard);
            helper.assertValueEqual(root.getPassengers().size(), 1, "Recorded native NBT retains the passenger relation");
            helper.assertTrue(!EntityProcessingAdapter.verifyPresent(prepared, helper.getLevel()), "No preparation is misreported as a committed spawn");
            }
        });
        helper.succeed();
    }

    private static void ironsBrew(GameTestHelper helper) {
        Recipe<?> recipe = construct("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe",
                new Class<?>[]{FluidStack.class, Ingredient.class, List.class, Optional.class}, new FluidStack(Fluids.WATER, 1000),
                Ingredient.of(Items.GOLD_INGOT), List.of(new FluidStack(BuiltInRegistries.FLUID.get(ResourceLocation.parse("irons_spellbooks:blood")), 500), new FluidStack(Fluids.WATER, 250)), Optional.of(new ItemStack(Items.GOLD_NUGGET)));
        fixture(helper, "irons_brew", recipe, holder -> {
            refused(helper, () -> IronCauldronAdapter.verify(holder, new ItemStack(Items.GOLD_INGOT), new FluidStack(Fluids.WATER, 999), helper.getLevel()), "Native identity-only matches does not bypass raw mB requirements");
            var result = IronCauldronAdapter.verify(holder, new ItemStack(Items.GOLD_INGOT), new FluidStack(Fluids.WATER, 1000), helper.getLevel());
            helper.assertValueEqual(result.consumedFluids().getFirst().amount(), 1000, "Raw material fluid is fully charged");
            helper.assertValueEqual(result.producedFluids().size(), 2, "Every native fluid output is retained");
            helper.assertValueEqual(result.producedFluids().stream().mapToInt(FluidMaterial::amount).sum(), 750, "Amounts come from the actual recipe, not energy conversion");
            helper.assertValueEqual(result.producedItems().getFirst().count(), 1, "The real byproduct is retained");
        });
        helper.succeed();
    }

    private static void ironsBrewByproducts(GameTestHelper helper) {
        var level = helper.getLevel();
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        var original = level.getBlockState(position);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("irons_spellbooks:alchemist_cauldron"));
        helper.assertTrue(block != net.minecraft.world.level.block.Blocks.AIR, "The native cauldron block is loaded");
        var bounds = new net.minecraft.world.phys.AABB(position).inflate(2);
        for (int count : List.of(1, 2, 5)) {
            ItemStack configured = new ItemStack(Items.GOLD_NUGGET, count);
            configured.set(DataComponents.CUSTOM_NAME, Component.literal("native byproduct " + count));
            var configuredBefore = ItemSnapshotCodec.capture(configured, level.registryAccess());
            Recipe<?> recipe = construct("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe",
                    new Class<?>[]{FluidStack.class, Ingredient.class, List.class, Optional.class}, new FluidStack(Fluids.WATER, 1000),
                    Ingredient.of(Items.STRUCTURE_VOID), List.of(), Optional.of(configured));
            fixture(helper, "irons_byproduct_" + count, recipe, holder -> {
                var result = IronCauldronAdapter.verify(holder, new ItemStack(Items.STRUCTURE_VOID), new FluidStack(Fluids.WATER, 1000), level);
                Set<UUID> previousDrops = new HashSet<>();
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds).forEach(entity -> previousDrops.add(entity.getUUID()));
                try {
                    level.setBlock(position, block.defaultBlockState(), 3);
                    var tile = level.getBlockEntity(position);
                    helper.assertTrue(tile != null && tile instanceof net.minecraft.world.Container, "A real native cauldron owns the input");
                    var container = (net.minecraft.world.Container) tile;
                    var fluidHandler = (net.neoforged.neoforge.fluids.capability.IFluidHandler) tile.getClass().getField("fluidInventory").get(tile);
                    helper.assertValueEqual(fluidHandler.fill(new FluidStack(Fluids.WATER, 1000), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE), 1000,
                            "Native setup inserts one actual finite fluid batch");
                    container.setItem(0, new ItemStack(Items.STRUCTURE_VOID));
                    tile.getClass().getMethod("tryMeltInput", ItemStack.class).invoke(tile, container.getItem(0));
                    var nativeItems = new ArrayList<ItemStack>();
                    for (int slot = 0; slot < container.getContainerSize(); slot++) if (!container.getItem(slot).isEmpty()) nativeItems.add(container.getItem(slot));
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds).stream()
                            .filter(entity -> !previousDrops.contains(entity.getUUID())).forEach(entity -> nativeItems.add(entity.getItem()));
                    helper.assertTrue(nativeItems.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(stack, configured)), "Native byproducts retain configured components");
                    helper.assertValueEqual(result.producedItems().stream().mapToInt(stack -> stack.count()).sum(), nativeItems.stream().mapToInt(ItemStack::getCount).sum(),
                            "Virtual output equals actual native slot and drop output, including configured counts above two");
                    helper.assertTrue(result.producedFluids().isEmpty(), "A byproduct-only brew fabricates no fluid output");
                    helper.assertValueEqual(ItemSnapshotCodec.capture(configured, level.registryAccess()), configuredBefore, "Native and virtual evaluation leave recipe components unchanged");
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("NATIVE_CAULDRON_FIXTURE_API_MISMATCH", failure); }
                finally {
                    level.setBlock(position, original, 3);
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds).stream()
                            .filter(entity -> !previousDrops.contains(entity.getUUID())).forEach(net.minecraft.world.entity.Entity::discard);
                }
            });
        }
        helper.succeed();
    }

    private static void ironsItemOnlyBrew(GameTestHelper helper) {
        for (String id : List.of("irons_spellbooks:alchemist_cauldron/soak_bloody_vellum", "minecraft:alchemist_cauldron/soak_crying_obsidian")) {
            var holder = helper.getLevel().getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow();
            var input = IronCauldronAdapter.itemIngredient(holder, helper.getLevel()).getItems()[0].copyWithCount(1);
            var fluid = IronCauldronAdapter.fluidIngredient(holder, helper.getLevel()).orElseThrow().restore(helper.getLevel().registryAccess());
            var result = IronCauldronAdapter.verify(holder, input, fluid, helper.getLevel());
            helper.assertTrue(result.producedFluids().isEmpty(), "Actual shipped soak recipe produces no fluid");
            helper.assertValueEqual(result.producedItems().stream().mapToInt(stack -> stack.count()).sum(), 1, "Actual shipped soak recipe retains its one item output");
            helper.assertValueEqual(result.consumedFluids().getFirst().amount(), fluid.getAmount(), "Soaking consumes the declared raw fluid quantity");
        }
        helper.succeed();
    }

    private static void ironsFluidLimits(GameTestHelper helper) {
        var variants = new ArrayList<FluidStack>();
        for (int i = 0; i < 5; i++) {
            var variant = new FluidStack(Fluids.WATER, 100);
            variant.set(DataComponents.CUSTOM_NAME, Component.literal("fluid variant " + i)); variants.add(variant);
        }
        List<FluidStack> inputs = List.of(new FluidStack(Fluids.WATER, 1001), new FluidStack(Fluids.LAVA, 250),
                new FluidStack(Fluids.WATER, 1000), new FluidStack(Fluids.WATER, 1000), new FluidStack(Fluids.WATER, 1000));
        List<List<FluidStack>> outputs = List.of(List.of(), List.of(), List.of(new FluidStack(Fluids.WATER, 1001)),
                List.of(new FluidStack(Fluids.LAVA, 250)), variants);
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            Recipe<?> recipe = construct("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe",
                    new Class<?>[]{FluidStack.class, Ingredient.class, List.class, Optional.class}, input,
                    Ingredient.of(Items.STRUCTURE_VOID), outputs.get(i), Optional.of(new ItemStack(Items.GOLD_NUGGET)));
            fixture(helper, "irons_limits_" + i, recipe, holder -> refused(helper,
                    () -> IronCauldronAdapter.verify(holder, new ItemStack(Items.STRUCTURE_VOID), input.copy(), helper.getLevel()),
                    "Device exemption preserves native disallowed fluids, total batch capacity, and variant slots"));
        }
        helper.succeed();
    }

    private static void ironsScrollPrecedence(GameTestHelper helper) {
        ItemStack scroll = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("irons_spellbooks:scroll")));
        var namedWater = new FluidStack(Fluids.WATER, 250);
        namedWater.set(DataComponents.CUSTOM_NAME, Component.literal("water still enters native recycling"));
        List<FluidStack> inputs = List.of(new FluidStack(Fluids.WATER, 249), new FluidStack(Fluids.WATER, 250),
                new FluidStack(BuiltInRegistries.FLUID.get(ResourceLocation.parse("irons_spellbooks:blood")), 250), namedWater);
        List<Boolean> recyclingPrecedes = List.of(false, true, false, true);
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i); boolean rejected = recyclingPrecedes.get(i);
            Recipe<?> recipe = construct("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe",
                    new Class<?>[]{FluidStack.class, Ingredient.class, List.class, Optional.class}, input,
                    Ingredient.of(scroll), List.of(), Optional.of(new ItemStack(Items.GOLD_NUGGET)));
            fixture(helper, "irons_scroll_precedence_" + i, recipe, holder -> {
                try {
                    var result = IronCauldronAdapter.verify(holder, scroll, input, helper.getLevel());
                    helper.assertTrue(!rejected && result.producedItems().getFirst().count() == 1,
                            "Only native scroll recycling conditions preempt the selected deterministic brew");
                } catch (IllegalArgumentException failure) {
                    helper.assertTrue(rejected && "CAULDRON_SCROLL_RECYCLING_TAKES_PRECEDENCE".equals(failure.getMessage()),
                            "250 mB tagged water, including named water, routes to separately journalled recycling");
                }
            });
        }
        helper.succeed();
    }

    private static void ironsContainerCycle(GameTestHelper helper) {
        String prefix = "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.";
        Recipe<?> fill = construct(prefix + "FillAlchemistCauldronRecipe", new Class<?>[]{Ingredient.class, ItemStack.class, FluidStack.class, boolean.class, Holder.class},
                Ingredient.of(Items.POTION), new ItemStack(Items.GLASS_BOTTLE), new FluidStack(Fluids.WATER, 250), false, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOTTLE_EMPTY));
        ItemStack waterBottle = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        fixture(helper, "irons_fill", fill, holder -> {
            var result = IronCauldronAdapter.verify(holder, waterBottle, FluidStack.EMPTY, helper.getLevel());
            helper.assertValueEqual(result.producedFluids().getFirst().amount(), 250, "A real bottle becomes 250 mB");
            helper.assertTrue(result.reserveAllOutputs() && result.nativeAllowsPartialFill(), "AI reserves all output even where native cauldron allows lossy partial filling");
            helper.assertValueEqual(result.producedItems().size(), 1, "Only the declared returned bottle is produced");
        });
        Recipe<?> empty = construct(prefix + "EmptyAlchemistCauldronRecipe", new Class<?>[]{Ingredient.class, ItemStack.class, FluidStack.class, Holder.class},
                Ingredient.of(Items.GLASS_BOTTLE), waterBottle, new FluidStack(Fluids.WATER, 250), BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOTTLE_FILL));
        fixture(helper, "irons_empty", empty, holder -> {
            var result = IronCauldronAdapter.verify(holder, new ItemStack(Items.GLASS_BOTTLE), new FluidStack(Fluids.WATER, 250), helper.getLevel());
            helper.assertValueEqual(result.consumedFluids().getFirst().amount(), 250, "Filling the bottle consumes the same raw fluid quantity");
            helper.assertValueEqual(result.producedItems().getFirst(), ItemSnapshotCodec.capture(waterBottle, helper.getLevel().registryAccess()), "The native potion component survives the round-trip");
        });
        helper.succeed();
    }

    private static void ironsPotionFallback(GameTestHelper helper) {
        ItemStack potion = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING);
        var pour = IronCauldronMechanisms.pourPotion(potion, helper.getLevel());
        helper.assertTrue(!pour.isRecipe() && pour.producedFluids().getFirst().amount() == 250, "Native potion fallback has a mechanism ID and real 250 mB output");
        var fluid = pour.producedFluids().getFirst().restore(helper.getLevel().registryAccess());
        var bottle = IronCauldronMechanisms.bottlePotion(new ItemStack(Items.GLASS_BOTTLE), fluid, helper.getLevel());
        helper.assertValueEqual(bottle.producedItems().getFirst(), ItemSnapshotCodec.capture(potion, helper.getLevel().registryAccess()), "Splash kind and healing components survive native liquid/bottle round-trip");
        refused(helper, () -> IronCauldronMechanisms.bottlePotion(new ItemStack(Items.GLASS_BOTTLE), fluid.copyWithAmount(249), helper.getLevel()), "No full potion is made from partial raw fluid");
        helper.succeed();
    }

    private static void ironsScroll(GameTestHelper helper) {
        try {
            Class<?> spells = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Object spell = ((java.util.function.Supplier<?>) spells.getField("MAGIC_MISSILE_SPELL").get(null)).get();
            Class<?> abstractSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            Class<?> container = Class.forName("io.redspace.ironsspellbooks.api.spells.ISpellContainer");
            ItemStack scroll = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("irons_spellbooks:scroll")));
            container.getMethod("createScrollContainer", abstractSpell, int.class, ItemStack.class).invoke(null, spell, 1, scroll);
            var water = new FluidStack(Fluids.WATER, 250);
            var preview = IronCauldronMechanisms.previewScroll(scroll, water, helper.getLevel());
            int success = 0, failure = 0;
            for (int trial = 0; trial < 64; trial++) {
                long seed = trial * 0x9e3779b97f4a7c15L;
                boolean expected = net.minecraft.util.RandomSource.create(seed).nextFloat() < preview.chance();
                var sampled = IronCauldronMechanisms.sampleScroll(scroll, water, seed, helper.getLevel());
                helper.assertValueEqual(!sampled.producedFluids().isEmpty(), expected, "Recorded result uses the same native random threshold");
                helper.assertValueEqual(sampled.consumedItems().getFirst().count(), 1, "Failed recycling still consumes exactly one real scroll");
                helper.assertValueEqual(sampled.consumedFluids().size(), expected ? 1 : 0, "Water is consumed only by the native successful branch");
                if (expected) success++; else failure++;
            }
            helper.assertTrue(preview.chance() == 0 || success > 0, "Actual seeds exercise allowed successful outcomes");
            helper.assertTrue(preview.chance() == 1 || failure > 0, "Actual seeds exercise allowed failed outcomes");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("SCROLL_NATIVE_FIXTURE_FAILED", failure); }
        helper.succeed();
    }

    private static void ironsContained(GameTestHelper helper) {
        Recipe<?> recipe = construct("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe",
                new Class<?>[]{FluidStack.class, Ingredient.class, List.class, Optional.class}, new FluidStack(Fluids.WATER, 1000),
                Ingredient.of(Items.REDSTONE), List.of(new FluidStack(Fluids.WATER, 1000)), Optional.of(new ItemStack(Items.GOLD_NUGGET)));
        fixture(helper, "contained_fluid", recipe, holder -> {
            var input = new ArrayList<>(Collections.nCopies(10, ItemStack.EMPTY));
            input.set(0, new ItemStack(Items.REDSTONE)); input.set(1, new ItemStack(Items.WATER_BUCKET));
            var original = input.stream().map(s -> ItemSnapshotCodec.capture(s, helper.getLevel().registryAccess())).toList();
            var result = ContainedFluidProcessingAdapter.verify(holder, input, helper.getLevel());
            helper.assertValueEqual(result.outputs().stream().filter(s -> s.is(Items.WATER_BUCKET)).mapToInt(ItemStack::getCount).sum(), 1,
                    "Exactly the real source bucket is reused to contain the native 1000 mB product");
            helper.assertTrue(result.outputs().stream().noneMatch(s -> s.is(Items.BUCKET)), "The reused recipient bucket is not refunded a second time");
            helper.assertValueEqual(result.outputs().stream().filter(s -> s.is(Items.GOLD_NUGGET)).mapToInt(ItemStack::getCount).sum(), 1, "The real native byproduct is retained");
            helper.assertValueEqual(result.consumedBySlot().stream().mapToInt(Integer::intValue).sum(), 2, "Redstone and the actual filled source bucket are real inputs");
            helper.assertValueEqual(input.stream().map(s -> ItemSnapshotCodec.capture(s, helper.getLevel().registryAccess())).toList(), original,
                    "Container simulation mutates only isolated native stacks");
            helper.assertValueEqual(result.contextRequirements().get("untrackedFluidMb"), "0", "No product is hidden in an untracked virtual tank");
        });
        helper.succeed();
    }

    private static void bumbleContainer(GameTestHelper helper) {
        Recipe<?> recipe = construct("com.telepathicgrunt.the_bumblezone.items.recipes.ContainerCraftingRecipe",
                new Class<?>[]{String.class, CraftingBookCategory.class, ItemStack.class, NonNullList.class}, "", CraftingBookCategory.MISC,
                new ItemStack(Items.HONEY_BOTTLE), ingredients(Ingredient.of(Items.HONEY_BOTTLE), Ingredient.of(Items.SUGAR)));
        fixture(helper, "bumble_container", recipe, holder -> {
            var result = SpecialItemProcessingAdapter.verify(holder, grid(new ItemStack(Items.HONEY_BOTTLE), new ItemStack(Items.SUGAR)), helper.getLevel());
            helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty), "A bottle represented in the output is not also refunded");
            helper.assertValueEqual(result.outputs().getFirst().getCount(), 1, "Only one container-bearing output is produced");
        });
        helper.succeed();
    }

    private static void bumbleNbt(GameTestHelper helper) {
        Recipe<?> recipe = construct("com.telepathicgrunt.the_bumblezone.items.recipes.NbtKeepingShapelessRecipe",
                new Class<?>[]{String.class, CraftingBookCategory.class, ItemStack.class, NonNullList.class, Item.class}, "", CraftingBookCategory.MISC,
                new ItemStack(Items.EMERALD, 3), ingredients(Ingredient.of(Items.DIAMOND), Ingredient.of(Items.SUGAR)), Items.DIAMOND);
        fixture(helper, "bumble_nbt", recipe, holder -> {
            ItemStack source = new ItemStack(Items.DIAMOND); source.set(DataComponents.CUSTOM_NAME, Component.literal("kept input identity"));
            var result = SpecialItemProcessingAdapter.verify(holder, grid(source, new ItemStack(Items.SUGAR)), helper.getLevel());
            helper.assertValueEqual(result.outputs().getFirst().getCount(), 1, "This actual native branch transmuteCopy fixes count at one");
            helper.assertValueEqual(result.outputs().getFirst().get(DataComponents.CUSTOM_NAME), source.get(DataComponents.CUSTOM_NAME), "Real input data is retained");
        });
        helper.succeed();
    }

    private static void bumbleCandle(GameTestHelper helper) {
        Recipe<?> recipe = construct(BumbleCandleAdapter.CLASS,
                new Class<?>[]{CraftingBookCategory.class, String.class, int.class, int.class, NonNullList.class, NonNullList.class, int.class, int.class, boolean.class, boolean.class, boolean.class, int.class},
                CraftingBookCategory.MISC, "", 2, 8, ingredients(Ingredient.of(Items.HONEYCOMB)), ingredients(), 1, 1, true, true, true, 10);
        fixture(helper, "bumble_candle", recipe, holder -> {
            var inputs = grid(new ItemStack(Items.HONEYCOMB), PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            var preview = BumbleCandleAdapter.preview(holder, inputs, helper.getLevel());
            helper.assertValueEqual(preview.outcomes().size(), 1, "One allowed effect is one possible whole-batch outcome");
            @SuppressWarnings("unchecked") var crafting = (Recipe<CraftingInput>) holder.value();
            ItemStack actualNative = crafting.assemble(CraftingInput.of(3, 3, inputs.stream().map(ItemStack::copy).toList()), helper.getLevel().registryAccess());
            helper.assertValueEqual(preview.outcomes().getFirst().stack(), ItemSnapshotCodec.capture(actualNative, helper.getLevel().registryAccess()), "Public native balancing/components equal native assemble for the singleton effect");
            inputs.set(2, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
            inputs.set(3, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
            var many = BumbleCandleAdapter.preview(holder, inputs, helper.getLevel());
            helper.assertValueEqual(many.outcomes().size(), 2, "Repeating swiftness does not add another selection bucket");
            helper.assertTrue(many.outcomes().stream().allMatch(o -> o.probability() == 0.5), "The two unique effect types remain equally likely");
            var sample = BumbleCandleAdapter.sample(holder, inputs, 192837465L, helper.getLevel());
            var repeated = BumbleCandleAdapter.sample(holder, inputs, 192837465L, helper.getLevel());
            helper.assertValueEqual(ItemSnapshotCodec.capture(sample.outputs().getFirst(), helper.getLevel().registryAccess()),
                    ItemSnapshotCodec.capture(repeated.outputs().getFirst(), helper.getLevel().registryAccess()), "The same persisted seed cannot reroll to another effect");
            helper.assertTrue(sample.samplingEvidence().algorithm().contains("whole-batch"), "Outputs are categorical batches, not independent Bernoulli units");
        });
        helper.succeed();
    }

    private static final class PlayerSession implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        PlayerSession(GameTestHelper helper) {
            var server = helper.getLevel().getServer(); UUID id = UUID.randomUUID();
            var cookie = new CommonListenerCookie(new GameProfile(id, "special-" + id.toString().substring(0, 8)), 0,
                    ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
        }
        @Override public void close() {
            player.server.getPlayerList().remove(player);
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            StoryEngine.instance().cleanupPlayer(player);
            channel.finishAndReleaseAll();
        }
    }
}
