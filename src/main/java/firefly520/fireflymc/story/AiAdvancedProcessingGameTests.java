package firefly520.fireflymc.story;

import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.processing.advanced.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registers only when the real optional mod is loaded. Synthetic recipes use that mod's real implementation. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiAdvancedProcessingGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> actualInstalledAdapters() {
        var tests = new ArrayList<TestFunction>();
        if (ModList.get().isLoaded("apothic_enchanting")) {
            add(tests, "infusion_materials_and_bounds", AiAdvancedProcessingGameTests::infusionMaterials);
            add(tests, "infusion_preserves_component_patch", AiAdvancedProcessingGameTests::infusionComponents);
            add(tests, "enchanting_native_seed_and_cap", AiAdvancedProcessingGameTests::enchantingSeed);
        }
        if (ModList.get().isLoaded("apotheosis")) {
            add(tests, "salvage_combines_native_rules", AiAdvancedProcessingGameTests::salvageRules);
            add(tests, "salvage_seed_and_damage", AiAdvancedProcessingGameTests::salvageSeed);
            add(tests, "gem_cutting_exact_sized_deltas", AiAdvancedProcessingGameTests::gemCutting);
            add(tests, "sized_smithing_consumes_exact_addition", AiAdvancedProcessingGameTests::sizedSmithing);
            add(tests, "reforging_native_context_and_seed", AiAdvancedProcessingGameTests::reforging);
            add(tests, "malice_matches_native_component_transform", AiAdvancedProcessingGameTests::malice);
            add(tests, "apotheosis_shared_reference_is_exact", AiAdvancedProcessingGameTests::sharedReference);
            add(tests, "gem_purity_and_socket_material_conservation", AiAdvancedProcessingGameTests::gemPurityAndSockets);
            add(tests, "apotheosis_default_name_is_detached", AiAdvancedProcessingGameTests::defaultName);
        }
        if (ModList.get().isLoaded("apothic_spawners")) {
            add(tests, "spawner_existing_item_and_no_change", AiAdvancedProcessingGameTests::spawnerItem);
            add(tests, "spawner_reversible_quartz_is_real", AiAdvancedProcessingGameTests::spawnerQuartz);
        }
        if (ModList.get().isLoaded("psi")) {
            add(tests, "psi_trick_component_isolation", AiAdvancedProcessingGameTests::psiTrick);
            add(tests, "psi_trick_respects_actual_dimension", AiAdvancedProcessingGameTests::psiDimension);
            add(tests, "psi_bullet_upgrade_preserves_components", AiAdvancedProcessingGameTests::psiBullet);
            add(tests, "psi_drive_duplicates_real_medium_once", AiAdvancedProcessingGameTests::psiDrive);
            add(tests, "psi_spell_rejects_mutable_aliases", AiAdvancedProcessingGameTests::psiSpellAliases);
            add(tests, "psi_sensor_preserves_real_equipment", AiAdvancedProcessingGameTests::psiSensor);
            add(tests, "psi_cad_scavenge_requires_real_assembly", AiAdvancedProcessingGameTests::psiCadScavenge);
            add(tests, "psi_colorizer_retains_cad_state", AiAdvancedProcessingGameTests::psiColorizer);
            add(tests, "psi_bullet_drive_keeps_source_medium", AiAdvancedProcessingGameTests::psiBulletDrive);
        }
        return tests;
    }
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> body) {
        tests.add(new TestFunction("ai-advanced", "fireflymc." + name, "fireflymc:story_empty", 120, 0, true, helper -> {
            try { body.accept(helper); }
            catch (firefly520.fireflymc.ai.processing.RecipeInputIsolation.IsolationException problem) {
                throw new IllegalStateException(problem.code() + ":component=" + problem.componentType());
            }
        }));
    }
    private static ServerPlayer actor(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "AiAdvancedTest"), ClientInformation.createDefault());
    }
    private static Class<?> type(String name) {
        try { return Class.forName(name); } catch (ReflectiveOperationException failure) { throw new IllegalStateException("REAL_MOD_CLASS_MISSING", failure); }
    }
    private static Object construct(String name, Class<?>[] types, Object... args) {
        try { return type(name).getConstructor(types).newInstance(args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("REAL_MOD_CONSTRUCTOR_MISMATCH", failure); }
    }
    private static Object call(Object value, String name, Class<?>[] types, Object... args) {
        try { return value.getClass().getMethod(name, types).invoke(value, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("REAL_MOD_METHOD_MISMATCH", failure); }
    }
    private static RecipeHolder<?> holder(String name, Object recipe) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("fireflymc", "test_advanced_" + name), (Recipe<?>) recipe);
    }
    private static void recipes(GameTestHelper helper, List<RecipeHolder<?>> added, Runnable operation) {
        var manager = helper.getLevel().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try {
            var all = new ArrayList<RecipeHolder<?>>(original); all.addAll(added); manager.replaceRecipes(all);
            operation.run();
        } finally { manager.replaceRecipes(original); }
    }
    private static void reject(GameTestHelper helper, Runnable operation, String code) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException failure) { rejected = failure.getMessage().equals(code); }
        helper.assertTrue(rejected, "Rejected with " + code);
    }
    private static Object stats(float e, float q, float a) {
        return construct("dev.shadowsoffire.apothic_enchanting.table.EnchantingStatRegistry$Stats",
                new Class<?>[]{float.class, float.class, float.class, float.class, int.class}, 100f, e, q, a, 0);
    }
    private static RecipeHolder<?> infusion(boolean components) {
        String recipe = "dev.shadowsoffire.apothic_enchanting.table.infusion." + (components ? "KeepNBTInfusionRecipe" : "InfusionRecipe");
        Class<?> stats = type("dev.shadowsoffire.apothic_enchanting.table.EnchantingStatRegistry$Stats");
        return holder(components ? "infusion_components" : "infusion", construct(recipe,
                new Class<?>[]{ItemStack.class, Ingredient.class, stats, stats}, new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL),
                stats(1, 9.65f, 58.75f), stats(1, 11, 60)));
    }
    private static void infusionMaterials(GameTestHelper helper) {
        var holder = infusion(false); var player = actor(helper);
        recipes(helper, List.of(holder), () -> {
            var result = ApothicInfusionAdapter.verify(holder, List.of(new ItemStack(Items.COAL), new ItemStack(Items.LAPIS_LAZULI, 3)), player);
            helper.assertValueEqual(result.consumedBySlot(), List.of(1, 3), "Three real catalysts are consumed despite free XP");
            helper.assertTrue(result.outputs().getFirst().is(Items.DIAMOND), "Dedicated native overload produces output");
            helper.assertValueEqual(result.contextRequirements().get("quanta"), "9.65", "Virtual stats do not exceed the bounded interval");
            helper.assertValueEqual(player.experienceLevel, 0, "No XP payment or balance gate");
            reject(helper, () -> ApothicInfusionAdapter.verify(holder, List.of(new ItemStack(Items.COAL), new ItemStack(Items.LAPIS_LAZULI, 2)), player), "INFUSION_REAL_MATERIAL_MISSING");
        }); helper.succeed();
    }
    private static void infusionComponents(GameTestHelper helper) {
        var holder = infusion(true); var player = actor(helper);
        ItemStack source = new ItemStack(Items.COAL); source.set(DataComponents.CUSTOM_NAME, Component.literal("Retained source name"));
        source.remove(DataComponents.RARITY);
        recipes(helper, List.of(holder), () -> {
            var result = ApothicInfusionAdapter.verify(holder, List.of(source, new ItemStack(Items.LAPIS_LAZULI, 3)), player).outputs().getFirst();
            helper.assertValueEqual(result.getHoverName().getString(), "Retained source name", "Native patch copies name");
            helper.assertTrue(!result.has(DataComponents.RARITY), "Native patch removal stays removed");
            helper.assertTrue(source.is(Items.COAL) && source.getCount() == 1, "Preview leaves real input untouched");
        }); helper.succeed();
    }
    private static void enchantingSeed(GameTestHelper helper) {
        var player = actor(helper); int seed = player.getEnchantmentSeed();
        var inputs = List.of(new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.LAPIS_LAZULI, 3));
        var first = ApothicEnchantingMechanism.verify(inputs, player, 2);
        var second = ApothicEnchantingMechanism.verify(inputs, player, 2);
        helper.assertTrue(ItemStack.matches(first.outputs().getFirst(), second.outputs().getFirst()), "Seeded previews cannot reroll");
        helper.assertTrue(first.outputs().getFirst().isEnchanted(), "Native enchantment list and application produce actual components");
        helper.assertValueEqual(player.getEnchantmentSeed(), seed, "Preview never advances native seed");
        helper.assertTrue(player.totalExperience == 0 && !inputs.getFirst().isEnchanted(), "Free XP policy does not modify source equipment");
        helper.succeed();
    }
    private static RecipeHolder<?> salvage(String name, net.minecraft.world.item.Item output, int min, int max) {
        Object result = construct("dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe$OutputData",
                new Class<?>[]{ItemStack.class, int.class, int.class}, new ItemStack(output), min, max);
        return holder(name, construct("dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe",
                new Class<?>[]{Ingredient.class, List.class}, Ingredient.of(Items.IRON_PICKAXE), List.of(result)));
    }
    private static void salvageRules(GameTestHelper helper) {
        var a = salvage("salvage_a", Items.DIAMOND, 1, 2); var b = salvage("salvage_b", Items.EMERALD, 2, 3);
        recipes(helper, List.of(a, b), () -> {
            var result = ApotheosisSalvageAdapter.verify(a, new ItemStack(Items.IRON_PICKAXE), helper.getLevel());
            helper.assertTrue(result.backingRecipes().stream().anyMatch(ref -> ref.id().equals(a.id()))
                    && result.backingRecipes().stream().anyMatch(ref -> ref.id().equals(b.id())), "All matching native rules are bound");
            helper.assertTrue(result.outputs().stream().anyMatch(stack -> stack.is(Items.DIAMOND) && stack.getCount() == 2)
                    && result.outputs().stream().anyMatch(stack -> stack.is(Items.EMERALD) && stack.getCount() == 3), "Both rule maxima are reserved");
        }); helper.succeed();
    }
    private static void salvageSeed(GameTestHelper helper) {
        var recipe = salvage("salvage_seed", Items.DIAMOND, 0, 8);
        ItemStack source = new ItemStack(Items.IRON_PICKAXE); source.setDamageValue(source.getMaxDamage() / 2);
        recipes(helper, List.of(recipe), () -> {
            Set<Integer> counts = new HashSet<>();
            for (int seed = 0; seed < 64; seed++) {
                long spread = seed * 0x9e3779b97f4a7c15L ^ 0x51a49eL;
                var first = ApotheosisSalvageAdapter.sample(recipe, source, helper.getLevel(), spread);
                var second = ApotheosisSalvageAdapter.sample(recipe, source, helper.getLevel(), spread);
                helper.assertValueEqual(first.outputs().size(), second.outputs().size(), "Same seed retains output count");
                for (int i = 0; i < first.outputs().size(); i++) helper.assertTrue(ItemStack.matches(first.outputs().get(i), second.outputs().get(i)), "Same seed retains exact output");
                int diamonds = first.outputs().stream().filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(diamonds >= 0 && diamonds <= 4, "Native damage scaling constrains maximum"); counts.add(diamonds);
            }
            helper.assertTrue(counts.contains(0) && counts.contains(4), "Native uniform sampling includes zero and scaled maximum");
        }); helper.succeed();
    }
    private static void gemCutting(GameTestHelper helper) {
        var recipe = holder("gem_cutting", construct("dev.shadowsoffire.apotheosis.socket.gem.cutting.BasicGemCuttingRecipe",
                new Class<?>[]{Ingredient.class, List.class, List.class, List.class, ItemStack.class}, Ingredient.of(Items.DIAMOND),
                List.of(new SizedIngredient(Ingredient.of(Items.EMERALD), 2)), List.of(new SizedIngredient(Ingredient.of(Items.REDSTONE), 3)),
                List.of(new SizedIngredient(Ingredient.of(Items.IRON_INGOT), 4), new SizedIngredient(Ingredient.of(Items.GOLD_INGOT), 1)), new ItemStack(Items.NETHER_STAR)));
        recipes(helper, List.of(recipe), () -> {
            var inputs = List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD, 2), new ItemStack(Items.REDSTONE, 3), new ItemStack(Items.GOLD_INGOT));
            var result = ApotheosisGemCuttingAdapter.verify(recipe, inputs, helper.getLevel());
            helper.assertValueEqual(result.consumedBySlot(), List.of(1, 2, 3, 1), "Base is replaced, only matching sized alternatives consumed");
            helper.assertTrue(result.outputs().getFirst().is(Items.NETHER_STAR), "Native assembly result retained");
            helper.assertValueEqual(inputs.get(1).getCount(), 2, "Native decrement was isolated");
        }); helper.succeed();
    }
    private static void sizedSmithing(GameTestHelper helper) {
        var recipe = holder("sized_smithing", construct("dev.shadowsoffire.apotheosis.util.SizedUpgradeRecipe",
                new Class<?>[]{Ingredient.class, Ingredient.class, SizedIngredient.class, ItemStack.class}, Ingredient.EMPTY,
                Ingredient.of(Items.DIAMOND_SWORD), new SizedIngredient(Ingredient.of(Items.GOLD_INGOT), 4), new ItemStack(Items.NETHERITE_SWORD)));
        recipes(helper, List.of(recipe), () -> {
            ItemStack base = new ItemStack(Items.DIAMOND_SWORD); base.set(DataComponents.CUSTOM_NAME, Component.literal("Real blade"));
            var result = ApotheosisSmithingAdapter.verify(recipe, List.of(ItemStack.EMPTY, base, new ItemStack(Items.GOLD_INGOT, 4)), actor(helper));
            helper.assertValueEqual(result.consumedBySlot(), List.of(0, 1, 4), "Native extra onCraft addition is included once");
            helper.assertValueEqual(result.outputs().getFirst().getHoverName().getString(), "Real blade", "Equipment components survive native transform");
        }); helper.succeed();
    }
    private static void reforging(GameTestHelper helper) {
        var player = actor(helper); player.getPersistentData().putInt(ApotheosisReforgingAdapter.SEED_KEY, 0x1234abcd);
        RecipeHolder<?> recipe = helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> ApotheosisReforgingAdapter.supports(holder.value()))
                .filter(holder -> holder.id().getPath().equals("reforging/common")).findFirst().orElseThrow();
        var requirements = ApotheosisReforgingAdapter.inputRequirements(recipe, player, 2);
        var material = BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(requirements.get(1).alternatives().getFirst().matches()).findFirst().orElseThrow();
        material.setCount(requirements.get(1).alternatives().getFirst().count());
        var inputs = List.of(new ItemStack(Items.DIAMOND_SWORD), material, ItemStack.EMPTY);
        var first = ApotheosisReforgingAdapter.verify(recipe, inputs, player, 2, 7);
        var second = ApotheosisReforgingAdapter.verify(recipe, inputs, player, 2, 99);
        helper.assertTrue(ItemStack.matches(first.outputs().getFirst(), second.outputs().getFirst()), "Existing native seed overrides initialization and fixes result");
        helper.assertValueEqual(first.consumedBySlot().get(1), 3, "Third choice costs three times the actual common material");
        helper.assertValueEqual(first.consumedBySlot().get(2), 0, "Common rarity does not invent a sigil cost");
        helper.assertValueEqual(player.getPersistentData().getInt(ApotheosisReforgingAdapter.SEED_KEY), 0x1234abcd, "Preview does not advance native seed");
        helper.assertValueEqual(player.experienceLevel, 0, "Reforging requires no XP");
        helper.succeed();
    }
    private static void malice(GameTestHelper helper) {
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "AiMaliceTest"), ClientInformation.createDefault()) {
            @Override public void sendSystemMessage(Component message) { /* Native comparison fixture keeps notifications local to the assertion. */ }
        };
        int seed = 0x714fea;
        player.getPersistentData().putInt(ApotheosisReforgingAdapter.SEED_KEY, seed);
        RecipeHolder<?> reforge = helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> ApotheosisReforgingAdapter.supports(holder.value()))
                .filter(holder -> holder.id().getPath().equals("reforging/epic")).findFirst().orElseThrow();
        var requirements = ApotheosisReforgingAdapter.inputRequirements(reforge, player, 0);
        var inputs = new ArrayList<>(Collections.nCopies(3, ItemStack.EMPTY)); inputs.set(0, new ItemStack(Items.DIAMOND_SWORD));
        for (var requirement : requirements) if (requirement.slot() != 0) {
            var option = requirement.alternatives().getFirst();
            ItemStack material = BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(option.matches()).findFirst().orElseThrow();
            material.setCount(option.count()); inputs.set(requirement.slot(), material);
        }
        ItemStack equipment = ApotheosisReforgingAdapter.verify(reforge, inputs, player, 0, 1).outputs().getFirst();
        var recipe = helper.getLevel().getRecipeManager().byKey(ResourceLocation.parse("apotheosis:malice")).orElseThrow();
        ItemStack sigil = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("apotheosis:sigil_of_malice")));
        ItemStack calculated = ApotheosisSmithingAdapter.verify(recipe, List.of(ItemStack.EMPTY, equipment, sigil), player).outputs().getFirst();
        helper.assertValueEqual(player.getPersistentData().getInt(ApotheosisReforgingAdapter.SEED_KEY), seed, "Preview does not advance malice seed");
        ItemStack nativeResult = firefly520.fireflymc.ai.processing.RecipeInputIsolation.copy(equipment, player.registryAccess());
        try {
            type("dev.shadowsoffire.apotheosis.affix.AffixHelper").getMethod("applyMalice", net.minecraft.world.entity.player.Player.class, ItemStack.class)
                    .invoke(null, player, nativeResult);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("NATIVE_MALICE_COMPARISON_FAILED", failure); }
        helper.assertTrue(ItemStack.matches(calculated, nativeResult), "Material-only transform exactly matches native malice affix builder output");
        helper.succeed();
    }
    private static void sharedReference(GameTestHelper helper) {
        try {
            var components = type("dev.shadowsoffire.apotheosis.Apoth$Components");
            var rarityType = (net.minecraft.core.component.DataComponentType<?>) components.getField("RARITY").get(null);
            Object registry = type("dev.shadowsoffire.apotheosis.loot.RarityRegistry").getField("INSTANCE").get(null);
            Object rarity = call(registry, "holder", new Class<?>[]{ResourceLocation.class}, ResourceLocation.parse("apotheosis:common"));
            helper.assertTrue(ApotheosisComponentReferences.trustedSharedReference(rarityType, ResourceLocation.parse("apotheosis:rarity"), rarity), "Exact native codec registry reference accepted");
            helper.assertTrue(!ApotheosisComponentReferences.trustedSharedReference(DataComponents.CUSTOM_DATA, ResourceLocation.parse("apotheosis:rarity"), rarity), "Wrong component cannot borrow the registry exception");
            helper.assertTrue(!ApotheosisComponentReferences.trustedSharedReference(rarityType, ResourceLocation.parse("apotheosis:affixes"), rarity), "Unreviewed component cannot borrow the registry exception");
            Object emptyAffixes = type("dev.shadowsoffire.apotheosis.affix.ItemAffixes").getField("EMPTY").get(null);
            helper.assertTrue(!ApotheosisComponentReferences.trustedSharedReference(rarityType, ResourceLocation.parse("apotheosis:rarity"), emptyAffixes), "Mutable object or different native type remains rejected");
            var gemsType = (net.minecraft.core.component.DataComponentType<?>) components.getField("SOCKETED_GEMS").get(null);
            helper.assertTrue(ApotheosisComponentReferences.trustedSharedReference(gemsType, ResourceLocation.parse("apotheosis:socketed_gems"),
                    net.minecraft.world.item.component.ItemContainerContents.EMPTY), "Exact zero-slot native singleton has no mutable inner item graph");
            helper.assertTrue(!ApotheosisComponentReferences.trustedSharedReference(gemsType, ResourceLocation.parse("apotheosis:socketed_gems"),
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND)))), "Nonempty containers never share this exception");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        helper.succeed();
    }
    private static void defaultName(GameTestHelper helper) {
        ItemStack source = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("apotheosis:sigil_of_malice")));
        var prototype = source.getItem().components().get(DataComponents.ITEM_NAME);
        helper.assertTrue(prototype != null && source.get(DataComponents.ITEM_NAME) == prototype, "Fixture is the actual native shared ITEM_NAME default");
        String text = prototype.getString(); var style = prototype.getStyle();
        var copied = firefly520.fireflymc.ai.processing.RecipeInputIsolation.copy(source, helper.getLevel().registryAccess());
        var name = (net.minecraft.network.chat.MutableComponent) copied.get(DataComponents.ITEM_NAME);
        helper.assertTrue(name != prototype, "The virtual prototype has a separate mutable default-name object");
        helper.assertTrue(source.isComponentsPatchEmpty() == copied.isComponentsPatchEmpty(), "Default name isolation does not invent a component patch");
        var before = firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(source, helper.getLevel().registryAccess());
        helper.assertValueEqual(before, firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(copied, helper.getLevel().registryAccess()), "Copied default name is canonically stable across repeat isolation");
        name.append(" fixture mutation").withStyle(net.minecraft.ChatFormatting.BLUE);
        helper.assertValueEqual(prototype.getString(), text, "Changing detached name does not mutate source or global item prototype");
        helper.assertValueEqual(prototype.getStyle(), style, "Detached style does not alter prototype style");
        helper.assertValueEqual(source.get(DataComponents.ITEM_NAME).getString(), text, "Original item retains original name");
        helper.succeed();
    }
    private static void gemPurityAndSockets(GameTestHelper helper) {
        try {
            RecipeHolder<?> cutting = helper.getLevel().getRecipeManager().getRecipes().stream()
                    .filter(holder -> holder.value().getClass().getName().equals("dev.shadowsoffire.apotheosis.socket.gem.cutting.PurityUpgradeRecipe"))
                    .findFirst().orElseThrow();
            Object purity = call(cutting.value(), "purity", new Class<?>[0]);
            Object gemRegistry = type("dev.shadowsoffire.apotheosis.socket.gem.GemRegistry").getField("INSTANCE").get(null);
            ItemStack base = null;
            for (Object gem : (Collection<?>) call(gemRegistry, "getValues", new Class<?>[0])) {
                ItemStack candidate = (ItemStack) type("dev.shadowsoffire.apotheosis.socket.gem.GemItem")
                        .getMethod("createStack", type("dev.shadowsoffire.apotheosis.socket.gem.Gem"), purity.getClass()).invoke(null, gem, purity);
                Object view = type("dev.shadowsoffire.apotheosis.socket.gem.UnsocketedGem").getMethod("of", ItemStack.class).invoke(null, candidate);
                if ((boolean) call(view, "isValid", new Class<?>[0]) && (boolean) call(view, "canApplyTo", new Class<?>[]{ItemStack.class}, new ItemStack(Items.DIAMOND_SWORD))) {
                    base = candidate; break;
                }
            }
            if (base == null) throw new IllegalStateException("NATIVE_COMPATIBLE_GEM_REQUIRED");
            base.set(DataComponents.CUSTOM_NAME, Component.literal("Original gem component"));
            SizedIngredient left = ((List<SizedIngredient>) call(cutting.value(), "left", new Class<?>[0])).stream().filter(value -> value.ingredient().getItems().length > 0).findFirst().orElseThrow();
            SizedIngredient right = ((List<SizedIngredient>) call(cutting.value(), "right", new Class<?>[0])).stream().filter(value -> value.ingredient().getItems().length > 0).findFirst().orElseThrow();
            var materials = List.of(base, base.copy(), left.ingredient().getItems()[0].copyWithCount(left.count()), right.ingredient().getItems()[0].copyWithCount(right.count()));
            var upgrade = ApotheosisGemCuttingAdapter.verify(cutting, materials, helper.getLevel());
            helper.assertValueEqual(upgrade.consumedBySlot(), List.of(1, 1, left.count(), right.count()), "Real base, same native gem and exact sized costs consumed once");
            ItemStack improved = upgrade.outputs().getFirst();
            Object newPurity = type("dev.shadowsoffire.apotheosis.socket.gem.GemItem").getMethod("getPurity", ItemStack.class).invoke(null, improved);
            helper.assertValueEqual(newPurity, call(purity, "next", new Class<?>[0]), "Native next purity produced");
            helper.assertValueEqual(improved.getHoverName().getString(), "Original gem component", "Purity transformation preserves original components");
            var add = holder("add_socket", construct("dev.shadowsoffire.apotheosis.socket.AddSocketsRecipe", new Class<?>[]{Ingredient.class, int.class}, Ingredient.of(Items.DIAMOND), 2));
            var owner = actor(helper);
            recipes(helper, List.of(add), () -> {
                ItemStack target = ApotheosisSmithingAdapter.verify(add, List.of(ItemStack.EMPTY, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND)), owner).outputs().getFirst();
                var socketing = helper.getLevel().getRecipeManager().getRecipes().stream().filter(value -> value.value().getClass().getName().equals("dev.shadowsoffire.apotheosis.socket.SocketingRecipe")).findFirst().orElseThrow();
                ItemStack inserted = ApotheosisSmithingAdapter.verify(socketing, List.of(ItemStack.EMPTY, target, improved), owner).outputs().getFirst();
                var withdrawal = helper.getLevel().getRecipeManager().getRecipes().stream().filter(value -> value.value().getClass().getName().equals("dev.shadowsoffire.apotheosis.socket.WithdrawalRecipe")).findFirst().orElseThrow();
                ItemStack sigil = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("apotheosis:sigil_of_withdrawal")));
                var withdrawn = ApotheosisSmithingAdapter.verify(withdrawal, List.of(ItemStack.EMPTY, inserted, sigil), owner);
                helper.assertValueEqual(withdrawn.outputs().size(), 2, "Equipment and actual withdrawn gem are both escrow outputs");
                helper.assertValueEqual(firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(withdrawn.outputs().get(1), helper.getLevel().registryAccess()),
                        firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(improved, helper.getLevel().registryAccess()), "Withdrawn gem components and quantity remain exact");
                helper.assertTrue(owner.getInventory().isEmpty(), "Preview never invokes native onCraft that inserts/drops a gem");
            });
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("NATIVE_GEM_API_MISMATCH", failure); }
        helper.succeed();
    }
    private static RecipeHolder<?> spawnerRecipe(GameTestHelper helper, String path) {
        // Apotheosis deliberately disables the original campfire rule and supplies its own real rune rule.
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> ApothicSpawnerAdapter.supports(holder.value()))
                .filter(holder -> holder.id().getPath().equals("spawner_modifiers/" + path))
                .filter(holder -> ((Ingredient) call(holder.value(), "getOffhandInput", new Class<?>[0])).isEmpty()).findFirst().orElseThrow();
    }
    private static void spawnerItem(GameTestHelper helper) {
        var recipe = spawnerRecipe(helper, "burning"); ItemStack target = new ItemStack(Items.SPAWNER);
        ItemStack modifier = ((Ingredient) call(recipe.value(), "getMainhandInput", new Class<?>[0])).getItems()[0].copyWithCount(1);
        target.set(DataComponents.CUSTOM_NAME, Component.literal("Owned target"));
        var result = ApothicSpawnerAdapter.verify(recipe, List.of(target, modifier, ItemStack.EMPTY), helper.getLevel());
        helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1, 0), "A real spawner and modifier are required");
        helper.assertValueEqual(result.outputs().getFirst().getHoverName().getString(), "Owned target", "Unrelated target components stay intact");
        helper.assertTrue(!target.has(DataComponents.BLOCK_ENTITY_DATA), "Preview never mutates original spawner item");
        reject(helper, () -> ApothicSpawnerAdapter.verify(recipe, List.of(result.outputs().getFirst(), modifier, ItemStack.EMPTY), helper.getLevel()), "SPAWNER_MODIFIER_NO_CHANGE");
        reject(helper, () -> ApothicSpawnerAdapter.verify(recipe, List.of(new ItemStack(Items.STONE), modifier, ItemStack.EMPTY), helper.getLevel()), "EXISTING_SPAWNER_ITEM_REQUIRED");
        helper.succeed();
    }
    private static void spawnerQuartz(GameTestHelper helper) {
        RecipeHolder<?> inverse = helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> ApothicSpawnerAdapter.supports(holder.value()))
                .filter(holder -> ((Ingredient) call(holder.value(), "getOffhandInput", new Class<?>[0])).test(new ItemStack(Items.QUARTZ)))
                .filter(holder -> !(boolean) call(holder.value(), "consumesOffhand", new Class<?>[0])).findFirst().orElseThrow();
        Ingredient main = (Ingredient) call(inverse.value(), "getMainhandInput", new Class<?>[0]);
        ItemStack material = main.getItems()[0].copyWithCount(1);
        // Build a real native state by first applying its forward rule with the same main-hand material.
        RecipeHolder<?> forward = helper.getLevel().getRecipeManager().getRecipes().stream().filter(holder -> ApothicSpawnerAdapter.supports(holder.value()))
                .filter(holder -> ((Ingredient) call(holder.value(), "getMainhandInput", new Class<?>[0])).test(material))
                .filter(holder -> ((Ingredient) call(holder.value(), "getOffhandInput", new Class<?>[0])).isEmpty()).findFirst().orElseThrow();
        ItemStack upgraded = ApothicSpawnerAdapter.verify(forward, List.of(new ItemStack(Items.SPAWNER), material, ItemStack.EMPTY), helper.getLevel()).outputs().getFirst();
        ItemStack quartz = new ItemStack(Items.QUARTZ); quartz.set(DataComponents.CUSTOM_NAME, Component.literal("Actual reusable quartz"));
        var reversed = ApothicSpawnerAdapter.verify(inverse, List.of(upgraded, material, quartz), helper.getLevel());
        helper.assertTrue(ItemStack.matches(quartz, reversed.remaindersBySlot().get(2)), "Retained offhand material returns once with exact components");
        reject(helper, () -> ApothicSpawnerAdapter.verify(inverse, List.of(upgraded, material, ItemStack.EMPTY), helper.getLevel()), "SPAWNER_MODIFIER_MATERIAL_MISMATCH");
        helper.succeed();
    }
    private static RecipeHolder<?> trick(boolean dimension) {
        Class<?> piece = type("vazkii.psi.api.spell.piece.PieceCraftingTrick");
        ItemStack output = new ItemStack(Items.WRITTEN_BOOK, 2); output.set(DataComponents.CUSTOM_NAME, Component.literal("Exact native output"));
        Object recipe = dimension ? construct("vazkii.psi.common.crafting.recipe.DimensionTrickRecipe",
                new Class<?>[]{piece, Ingredient.class, ItemStack.class, ItemStack.class, ResourceLocation.class}, null,
                Ingredient.of(Items.GOLD_INGOT), output, ItemStack.EMPTY, ResourceLocation.parse("minecraft:the_nether"))
                : construct("vazkii.psi.common.crafting.recipe.TrickRecipe", new Class<?>[]{piece, Ingredient.class, ItemStack.class, ItemStack.class},
                null, Ingredient.of(Items.GOLD_INGOT), output, ItemStack.EMPTY);
        return holder(dimension ? "psi_dimension" : "psi_trick", recipe);
    }
    private static void psiTrick(GameTestHelper helper) {
        var holder = trick(false); var player = actor(helper);
        recipes(helper, List.of(holder), () -> {
            var result = PsiTrickAdapter.verify(holder, new ItemStack(Items.GOLD_INGOT), player);
            helper.assertValueEqual(result.outputs().getFirst().getCount(), 2, "Native output count retained");
            helper.assertValueEqual(result.outputs().getFirst().getHoverName().getString(), "Exact native output", "Output components retained");
            helper.assertTrue(player.getInventory().isEmpty(), "No CAD or other virtual helper enters inventory");
            helper.assertValueEqual(result.contextRequirements().get("psiCost"), "0", "Psi charge is waived only in adapter");
        }); helper.succeed();
    }
    private static void psiDimension(GameTestHelper helper) {
        var holder = trick(true);
        recipes(helper, List.of(holder), () -> reject(helper, () -> PsiTrickAdapter.verify(holder, new ItemStack(Items.GOLD_INGOT), actor(helper)), "PSI_INPUT_OR_DIMENSION_MISMATCH"));
        helper.succeed();
    }
    private static void psiBullet(GameTestHelper helper) {
        RecipeHolder<?> selected = helper.getLevel().getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.value().getClass().getName().equals("vazkii.psi.common.crafting.recipe.BulletUpgradeRecipe")).findFirst().orElseThrow();
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); int slot = 0;
        for (Ingredient ingredient : selected.value().getIngredients()) if (!ingredient.isEmpty()) grid.set(slot++, ingredient.getItems()[0].copyWithCount(1));
        ItemStack bullet = grid.stream().filter(stack -> stack.getItem().getClass().getName().contains("ItemSpellBullet")).findFirst().orElseThrow();
        bullet.set(DataComponents.CUSTOM_NAME, Component.literal("Existing spell bullet"));
        var result = AdvancedCraftingAdapter.verify(selected, grid, actor(helper));
        helper.assertValueEqual(result.outputs().getFirst().getHoverName().getString(), "Existing spell bullet", "Native transmuteCopy preserves component-bearing input");
        helper.assertValueEqual(bullet.getCount(), 1, "Preview does not consume original bullet");
        helper.succeed();
    }
    private static void psiDrive(GameTestHelper helper) {
        var recipe = holder("psi_drive", construct("vazkii.psi.common.crafting.recipe.DriveDuplicateRecipe",
                new Class<?>[]{CraftingBookCategory.class}, CraftingBookCategory.MISC));
        var driveItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:spell_drive"));
        ItemStack source = new ItemStack(driveItem), blank = new ItemStack(driveItem);
        Object spell = construct("vazkii.psi.api.spell.Spell", new Class<?>[0]);
        try {
            spell.getClass().getField("name").set(spell, "Existing player spell");
            type("vazkii.psi.common.item.ItemSpellDrive").getMethod("setSpell", ItemStack.class, spell.getClass()).invoke(null, source, spell);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(4, source); grid.set(5, blank);
        recipes(helper, List.of(recipe), () -> {
            var result = AdvancedCraftingAdapter.verify(recipe, grid, actor(helper));
            helper.assertValueEqual(result.outputs().getFirst().getCount(), 1, "One real blank drive becomes one recorded drive");
            helper.assertValueEqual(firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(source, helper.getLevel().registryAccess()),
                    firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(result.remaindersBySlot().get(4), helper.getLevel().registryAccess()),
                    "Original spell medium is returned exactly once at original grid offset");
            helper.assertTrue(result.remaindersBySlot().get(5).isEmpty(), "Consumed blank drive is not duplicated");
            source.setCount(2);
            reject(helper, () -> AdvancedCraftingAdapter.verify(recipe, grid, actor(helper)), "DYNAMIC_CRAFTING_UNIT_GRID_REQUIRED");
        }); helper.succeed();
    }
    @SuppressWarnings("unchecked")
    private static void psiSpellAliases(GameTestHelper helper) {
        try {
            Class<?> spellType = type("vazkii.psi.api.spell.Spell"), gridType = type("vazkii.psi.api.spell.SpellGrid"), pieceType = type("vazkii.psi.api.spell.SpellPiece");
            Object original = construct(spellType.getName(), new Class<?>[0]);
            Object piece = construct("vazkii.psi.common.spell.constant.PieceConstantNumber", new Class<?>[]{spellType}, original);
            var lookup = java.lang.invoke.MethodHandles.publicLookup();
            lookup.findSetter(piece.getClass(), "valueStr", String.class).invoke(piece, "7");
            lookup.findSetter(pieceType, "x", int.class).invoke(piece, 4); lookup.findSetter(pieceType, "y", int.class).invoke(piece, 4);
            Object grid = lookup.findGetter(spellType, "grid", gridType).invoke(original);
            var arrayType = java.lang.reflect.Array.newInstance(pieceType, 0, 0).getClass();
            Object rows = lookup.findGetter(gridType, "gridData", arrayType).invoke(grid);
            java.lang.reflect.Array.set(java.lang.reflect.Array.get(rows, 4), 4, piece);
            Object holder = type("vazkii.psi.common.item.base.ModDataComponents").getField("SPELL").get(null);
            var component = (net.minecraft.core.component.DataComponentType<Object>) call(holder, "get", new Class<?>[0]);
            var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            var encoded = component.codec().encodeStart(ops, original).getOrThrow();
            Object copied = component.codec().parse(ops, encoded.copy()).getOrThrow();
            helper.assertTrue(PsiSpellComponents.equivalentDetached(component, original, copied, helper.getLevel().registryAccess()), "Equal native codec and independent mutable graph are accepted");
            spellType.getField("name").set(copied, "Changed private spell");
            helper.assertTrue(!PsiSpellComponents.equivalentDetached(component, original, copied, helper.getLevel().registryAccess()), "Changed spell contents are rejected");
            copied = component.codec().parse(ops, encoded.copy()).getOrThrow();
            Object copiedGrid = lookup.findGetter(spellType, "grid", gridType).invoke(copied);
            lookup.findSetter(gridType, "gridData", arrayType).invoke(copiedGrid, rows);
            helper.assertTrue(!PsiSpellComponents.equivalentDetached(component, original, copied, helper.getLevel().registryAccess()), "Shared mutable grid arrays are rejected even if serialized bytes match");
            copied = component.codec().parse(ops, encoded.copy()).getOrThrow();
            copiedGrid = lookup.findGetter(spellType, "grid", gridType).invoke(copied);
            Object copiedRows = lookup.findGetter(gridType, "gridData", arrayType).invoke(copiedGrid);
            java.lang.reflect.Array.set(java.lang.reflect.Array.get(copiedRows, 4), 4, piece);
            helper.assertTrue(!PsiSpellComponents.equivalentDetached(component, original, copied, helper.getLevel().registryAccess()), "Shared mutable spell pieces and wrong parent binding are rejected");
            lookup.findSetter(gridType, "gridData", arrayType).invoke(copiedGrid, java.lang.reflect.Array.newInstance(pieceType, 9, 8));
            helper.assertTrue(!PsiSpellComponents.equivalentDetached(component, original, copied, helper.getLevel().registryAccess()), "Malformed grid is rejected");
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException problem) throw problem;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("PSI_ALIAS_TEST_FAILED", failure);
        }
        helper.succeed();
    }
    private static RecipeHolder<?> psiDynamic(GameTestHelper helper, String type) {
        return helper.getLevel().getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.value().getClass().getName().equals("vazkii.psi.common.crafting.recipe." + type)).findFirst().orElseThrow();
    }
    private static void psiSensor(GameTestHelper helper) {
        Class<?> holdable = type("vazkii.psi.api.exosuit.ISensorHoldable"), sensorType = type("vazkii.psi.api.exosuit.IExosuitSensor");
        ItemStack equipment = BuiltInRegistries.ITEM.stream().filter(holdable::isInstance).map(ItemStack::new).findFirst().orElseThrow();
        ItemStack sensor = BuiltInRegistries.ITEM.stream().filter(sensorType::isInstance).map(ItemStack::new).findFirst().orElseThrow();
        equipment.set(DataComponents.CUSTOM_NAME, Component.literal("Existing exosuit"));
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, equipment); grid.set(1, sensor);
        var player = actor(helper);
        var attached = AdvancedCraftingAdapter.verify(psiDynamic(helper, "SensorAttachRecipe"), grid, player);
        ItemStack changed = attached.outputs().getFirst();
        helper.assertValueEqual(changed.getHoverName().getString(), "Existing exosuit", "Actual target components preserved");
        grid.set(0, changed); grid.set(1, ItemStack.EMPTY);
        var removed = AdvancedCraftingAdapter.verify(psiDynamic(helper, "SensorRemoveRecipe"), grid, player);
        helper.assertTrue(removed.remaindersBySlot().stream().anyMatch(stack -> stack.is(sensor.getItem()) && stack.getCount() == 1), "Exactly one actual sensor returned by native recipe");
        helper.assertTrue(removed.outputs().getFirst().is(equipment.getItem()), "Original equipment remains the target");
        helper.assertTrue(!PsiComponentReferences.trustedSharedReference(DataComponents.CUSTOM_DATA, ResourceLocation.parse("psi:sensor"), sensor.getItem()), "Wrong component cannot borrow item registry exception");
        helper.succeed();
    }
    private static void psiCadScavenge(GameTestHelper helper) {
        ItemStack assembly = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:cad_assembly_iron")));
        ItemStack cad = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:cad")));
        try {
            type("vazkii.psi.common.item.ItemCAD").getMethod("setComponent", ItemStack.class, ItemStack.class).invoke(null, cad, assembly);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, cad);
        var result = AdvancedCraftingAdapter.verify(psiDynamic(helper, "AssemblyScavengeRecipe"), grid, actor(helper));
        helper.assertTrue(result.outputs().getFirst().is(assembly.getItem()) && result.outputs().getFirst().getCount() == 1, "Actual installed CAD assembly is returned once");
        helper.assertValueEqual(result.consumedBySlot().getFirst(), 1, "Real CAD target is consumed despite auxiliary CAD exemption");
        helper.succeed();
    }
    @SuppressWarnings("unchecked")
    private static void psiColorizer(GameTestHelper helper) {
        var colors = BuiltInRegistries.ITEM.stream().filter(type("vazkii.psi.api.cad.ICADColorizer")::isInstance).map(ItemStack::new).limit(2).toList();
        helper.assertValueEqual(colors.size(), 2, "Two actual native colorizers exist");
        ItemStack cad = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:cad")));
        Object vector = construct("vazkii.psi.api.internal.Vector3", new Class<?>[]{double.class, double.class, double.class}, 1d, 2d, 3d);
        Object data = construct("vazkii.psi.common.core.handler.capability.CADData$Data", new Class<?>[]{int.class, int.class, List.class}, 7, 23, List.of(vector));
        net.minecraft.core.component.DataComponentType<Object> dataComponent;
        try {
            type("vazkii.psi.common.item.ItemCAD").getMethod("setComponent", ItemStack.class, ItemStack.class).invoke(null, cad, colors.getFirst());
            Object holder = type("vazkii.psi.common.item.base.ModDataComponents").getField("CAD_DATA").get(null);
            dataComponent = (net.minecraft.core.component.DataComponentType<Object>) call(holder, "get", new Class<?>[0]);
            cad.set(dataComponent, data);
            ItemStack bullet = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:spell_bullet")));
            Object spellHolder = type("vazkii.psi.common.item.base.ModDataComponents").getField("SPELL").get(null);
            bullet.set((net.minecraft.core.component.DataComponentType<Object>) call(spellHolder, "get", new Class<?>[0]),
                    construct("vazkii.psi.api.spell.Spell", new Class<?>[0]));
            Object bulletsHolder = type("vazkii.psi.common.item.base.ModDataComponents").getField("BULLETS").get(null);
            cad.set((net.minecraft.core.component.DataComponentType<net.minecraft.world.item.component.ItemContainerContents>) call(bulletsHolder, "get", new Class<?>[0]),
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(bullet)));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, cad); grid.set(1, colors.get(1));
        var result = AdvancedCraftingAdapter.verify(psiDynamic(helper, "ColorizerChangeRecipe"), grid, actor(helper));
        helper.assertTrue(result.remaindersBySlot().get(1).is(colors.getFirst().getItem()), "Native old colorizer returns at replacement input slot");
        Object outputData = result.outputs().getFirst().get(dataComponent);
        try {
            helper.assertValueEqual(outputData.getClass().getField("battery").getInt(outputData), 23, "No Psi removed from actual CAD target");
            var vectors = (List<?>) outputData.getClass().getField("vectors").get(outputData);
            vectors.getFirst().getClass().getField("x").setDouble(vectors.getFirst(), 99);
            helper.assertValueEqual(vector.getClass().getField("x").getDouble(vector), 1d, "Mutable CAD vector state is deeply isolated");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        helper.succeed();
    }
    @SuppressWarnings("unchecked")
    private static void psiBulletDrive(GameTestHelper helper) {
        ItemStack bullet = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:spell_bullet")));
        ItemStack drive = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("psi:spell_drive")));
        Object spell = construct("vazkii.psi.api.spell.Spell", new Class<?>[0]);
        try {
            spell.getClass().getField("name").set(spell, "Existing bullet spell");
            Object holder = type("vazkii.psi.common.item.base.ModDataComponents").getField("SPELL").get(null);
            bullet.set((net.minecraft.core.component.DataComponentType<Object>) call(holder, "get", new Class<?>[0]), spell);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, bullet); grid.set(1, drive);
        var result = AdvancedCraftingAdapter.verify(psiDynamic(helper, "BulletToDriveRecipe"), grid, actor(helper));
        helper.assertTrue(result.outputs().getFirst().is(drive.getItem()) && result.outputs().getFirst().getCount() == 1, "One real blank drive receives existing spell");
        helper.assertValueEqual(firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(result.remaindersBySlot().getFirst(), helper.getLevel().registryAccess()),
                firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(bullet, helper.getLevel().registryAccess()), "Original real bullet and components returned exactly once");
        helper.succeed();
    }
}
