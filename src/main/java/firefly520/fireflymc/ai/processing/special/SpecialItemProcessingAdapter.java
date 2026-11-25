package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

/**
 * Exact-version item conversions. World rules, entity production, cauldron fluids and random
 * candles deliberately have distinct contracts; a RecipeSerializer is not a production capability.
 */
public final class SpecialItemProcessingAdapter {
    public enum InputKind { SINGLE, GRID, COMBINER, FUSION, SMITHING }
    public record Profile(String mod, String version, String className, String type, String serializer,
                          InputKind input, String callbacks) {}
    public record CallbackFacts(boolean onCraftedBy, boolean craftingEvent, boolean smeltedEvent,
                                boolean recipeAward, boolean altarAdvancement) {}
    private static final Map<String, Profile> PROFILES = profiles();
    private static final String AETHER = "com.aetherteam.aether.recipe.recipes.item.";
    private SpecialItemProcessingAdapter() {}

    private static Map<String, Profile> profiles() {
        var values = new LinkedHashMap<String, Profile>();
        add(values, "aether", "1.5.10", "com.aetherteam.aether.recipe.recipes.item.EnchantingRecipe", "aether:enchanting", "aether:enchanting", InputKind.SINGLE, "AETHER_FURNACE");
        add(values, "aether", "1.5.10", "com.aetherteam.aether.recipe.recipes.item.AltarRepairRecipe", "aether:enchanting", "aether:repairing", InputKind.SINGLE, "AETHER_FURNACE");
        add(values, "aether", "1.5.10", "com.aetherteam.aether.recipe.recipes.item.FreezingRecipe", "aether:freezing", "aether:freezing", InputKind.SINGLE, "AETHER_FURNACE");
        add(values, "deep_aether", "1.21.1-1.1.5.1", "io.github.razordevs.deep_aether.recipe.combiner.CombinerRecipe", "deep_aether:combining", "deep_aether:combining", InputKind.COMBINER, "DEEP_AETHER_COMBINER");
        add(values, "deep_aether", "1.21.1-1.1.5.1", "io.github.razordevs.deep_aether.recipe.FloatyScarfColoring", "minecraft:crafting", "deep_aether:floaty_scarf_coloring", InputKind.GRID, "CRAFTING_RESULT_SLOT");
        add(values, "cataclysm", "3.32", "com.github.L_Ender.cataclysm.crafting.WeaponfusionRecipe", "cataclysm:weapon_fusion", "cataclysm:weapon_fusion", InputKind.FUSION, "SMITHING_RESULT_SLOT");
        add(values, "cataclysm", "3.32", "com.github.L_Ender.cataclysm.crafting.AltarOfAmethystRecipe", "cataclysm:amethyst_bless", "cataclysm:amethyst_bless", InputKind.SINGLE, "NO_CALLBACK");
        add(values, "twilightforest", "4.8.3345", "twilightforest.item.recipe.DryingRecipe", "twilightforest:drying", "twilightforest:drying", InputKind.SINGLE, "NO_CALLBACK");
        String[] twilightClasses = {"CasketRepairRecipe", "EmperorsClothRecipe", "EssenceRepairRecipe", "MagicMapCloningRecipe", "MazeMapCloningRecipe", "MoonwormQueenRepairRecipe", "ScepterRepairRecipe", "travellers.TravellersGearModifierShapelessRecipe", "travellers.TravellersGearModifierShapedRecipe", "travellers.TravellersVestGlovesMergeRecipe"};
        String[] twilightSerializers = {"casket_repair_recipe", "emperors_cloth_recipe", "essence_repair_recipe", "magic_map_cloning_recipe", "maze_map_cloning_recipe", "moonworm_queen_repair_recipe", "scepter_repair", "travellers_gear_modifier_shapeless_recipe", "travellers_gear_modifier_shaped_recipe", "travellers_vest_gloves_merge_recipe"};
        for (int i = 0; i < twilightClasses.length; i++) add(values, "twilightforest", "4.8.3345", "twilightforest.item.recipe." + twilightClasses[i], "minecraft:crafting", "twilightforest:" + twilightSerializers[i], InputKind.GRID, "CRAFTING_RESULT_SLOT");
        add(values, "twilightforest", "4.8.3345", "twilightforest.item.recipe.NoTemplateSmithingRecipe", "minecraft:smithing", "twilightforest:no_template_smithing", InputKind.SMITHING, "SMITHING_RESULT_SLOT");
        add(values, "irons_spellbooks", "1.21.1-3.16.2", "io.redspace.ironsspellbooks.recipe_types.NoAdditionSmithingTransformRecipe", "minecraft:smithing", "irons_spellbooks:smithing_transform_no_addition", InputKind.SMITHING, "SMITHING_RESULT_SLOT");
        add(values, "touhou_little_maid", "1.5.3-neoforge+mc1.21.1", "com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe", "touhou_little_maid:altar_recipe", "touhou_little_maid:altar_recipe_serializers", InputKind.GRID, "MAID_ALTAR_ITEM");
        add(values, "the_bumblezone", "7.15.2+1.21.1-neoforge", "com.telepathicgrunt.the_bumblezone.items.recipes.ContainerCraftingRecipe", "minecraft:crafting", "the_bumblezone:container_shapeless_recipe_bz", InputKind.GRID, "CRAFTING_RESULT_SLOT");
        add(values, "the_bumblezone", "7.15.2+1.21.1-neoforge", "com.telepathicgrunt.the_bumblezone.items.recipes.NbtKeepingShapelessRecipe", "minecraft:crafting", "the_bumblezone:nbt_keeping_shapeless_recipe", InputKind.GRID, "CRAFTING_RESULT_SLOT");
        add(values, "the_bumblezone", "7.15.2+1.21.1-neoforge", "com.telepathicgrunt.the_bumblezone.items.recipes.ItemStackSmeltingRecipe", "minecraft:smelting", "the_bumblezone:itemstack_smelting_recipe", InputKind.SINGLE, "FURNACE_RESULT_SLOT");
        return Collections.unmodifiableMap(values);
    }

    private static void add(Map<String, Profile> values, String mod, String version, String cls, String type,
                            String serializer, InputKind input, String callbacks) {
        values.put(cls, new Profile(mod, version, cls, type, serializer, input, callbacks));
    }

    public static List<Profile> profilesAvailableForVerification() { return List.copyOf(PROFILES.values()); }

    public static Profile profile(Recipe<?> recipe) {
        if (recipe == null) return null;
        Profile profile = PROFILES.get(recipe.getClass().getName());
        if (profile == null || !SpecialNative.version(profile.mod(), profile.version())
                || !SpecialNative.id(profile.type()).equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                || !SpecialNative.id(profile.serializer()).equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))) return null;
        // An altar's entity placeholder is a display artifact, never an item that may be awarded.
        if (profile.callbacks().equals("MAID_ALTAR_ITEM") && !(boolean) SpecialNative.get(recipe, "isItemCraft", boolean.class)) return null;
        return profile;
    }

    public static boolean supports(Recipe<?> recipe) { return profile(recipe) != null; }

    public static CallbackFacts callbackFacts(Recipe<?> recipe) {
        Profile profile = profile(recipe);
        if (profile == null) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        return switch (profile.callbacks()) {
            case "NO_CALLBACK" -> new CallbackFacts(false, false, false, false, false);
            case "MAID_ALTAR_ITEM" -> new CallbackFacts(false, false, false, false, true);
            // CombinerBlockEntity is not an AbstractFurnaceBlockEntity and awardUsedRecipes is empty.
            case "DEEP_AETHER_COMBINER" -> new CallbackFacts(true, false, true, false, false);
            case "AETHER_FURNACE", "FURNACE_RESULT_SLOT" -> new CallbackFacts(true, false, true, true, false);
            case "CRAFTING_RESULT_SLOT" -> new CallbackFacts(true, true, false, !recipe.isSpecial(), false);
            case "SMITHING_RESULT_SLOT" -> new CallbackFacts(true, false, false, !recipe.isSpecial(), false);
            default -> throw new IllegalStateException("SPECIAL_CALLBACK_PROFILE_INVALID");
        };
    }

    /** Ordered ingredient roles for native non-grid recipes. Grid layouts use matches on a full nine-slot input. */
    public static List<Ingredient> ingredients(RecipeHolder<?> holder, ServerLevel level) {
        Profile profile = require(holder, level);
        if (profile.input() == InputKind.FUSION) return List.of(
                (Ingredient) SpecialNative.get(holder.value(), "getbaseIngredient", Ingredient.class),
                (Ingredient) SpecialNative.get(holder.value(), "getAdditionIngredient", Ingredient.class));
        if (profile.input() == InputKind.SMITHING) {
            if (profile.mod().equals("twilightforest")) return List.of(Ingredient.EMPTY,
                    (Ingredient) SpecialNative.get(holder.value(), "getBase", Ingredient.class),
                    (Ingredient) SpecialNative.get(holder.value(), "getAddition", Ingredient.class));
            return List.of((Ingredient) SpecialNative.get(holder.value(), "getTemplate", Ingredient.class),
                    (Ingredient) SpecialNative.get(holder.value(), "getBase", Ingredient.class), Ingredient.EMPTY);
        }
        return List.copyOf(holder.value().getIngredients());
    }

    public static boolean matches(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        Profile profile = require(holder, level);
        var inputs = SpecialNative.units(supplied, size(profile.input()), level);
        if (!rolesMatch(profile, holder, inputs, level)) return false;
        return recipe(holder).matches(input(profile, inputs), level);
    }

    public static VerifiedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        Profile profile = require(holder, level);
        var inputs = SpecialNative.units(supplied, size(profile.input()), level);
        if (!rolesMatch(profile, holder, inputs, level) || !recipe(holder).matches(input(profile, RecipeInputIsolation.copyAll(inputs, level.registryAccess())), level))
            throw new IllegalArgumentException("SPECIAL_PROCESSING_INPUT_MISMATCH");
        ItemStack output = SpecialNative.stack(recipe(holder).assemble(input(profile, RecipeInputIsolation.copyAll(inputs, level.registryAccess())), level.registryAccess()), level, false);
        var remainders = new ArrayList<>(Collections.nCopies(inputs.size(), ItemStack.EMPTY));
        if (profile.className().startsWith(AETHER)) {
            ItemStack source = inputs.getFirst();
            @SuppressWarnings("unchecked") TagKey<Item> keep = (TagKey<Item>) SpecialNative.field("com.aetherteam.aether.AetherTags$Items", "SAVE_NBT_IN_RECIPE");
            if (source.is(output.getItem()) || output.is(keep)) output = new ItemStack(output.getItemHolder(), 1, source.getComponentsPatch());
            if (source.is(output.getItem())) output.setDamageValue(0);
            if (source.hasCraftingRemainingItem() && !source.getCraftingRemainingItem().is(output.getCraftingRemainingItem().getItem()))
                remainders.set(0, SpecialNative.stack(source.getCraftingRemainingItem(), level, true));
        } else if (profile.input() == InputKind.GRID && !profile.callbacks().equals("MAID_ALTAR_ITEM")) {
            CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, RecipeInputIsolation.copyAll(inputs, level.registryAccess()));
            var leftovers = recipe(holder).getRemainingItems(positioned.input());
            if (leftovers.size() != positioned.input().size()) throw new IllegalStateException("SPECIAL_REMAINDER_SIZE");
            for (int row = 0; row < positioned.input().height(); row++) for (int column = 0; column < positioned.input().width(); column++) {
                int slot = column + positioned.left() + (row + positioned.top()) * 3;
                ItemStack remainder = SpecialNative.stack(leftovers.get(column + row * positioned.input().width()), level, true);
                if (inputs.get(slot).isEmpty() && !remainder.isEmpty()) throw new IllegalStateException("SPECIAL_REMAINDER_WITHOUT_INPUT");
                remainders.set(slot, remainder);
            }
        }
        float experience = holder.value() instanceof AbstractCookingRecipe cooking ? cooking.getExperience() : 0;
        return new Result(holder, profile, inputs, List.of(SpecialNative.stack(output, level, false)), remainders, experience, level);
    }

    private static boolean rolesMatch(Profile profile, RecipeHolder<?> holder, List<ItemStack> inputs, ServerLevel level) {
        if (profile.input() == InputKind.GRID) {
            long count = inputs.stream().filter(s -> !s.isEmpty()).count();
            return count > 0 && count <= (profile.callbacks().equals("MAID_ALTAR_ITEM") ? 6 : 9);
        }
        if (profile.input() == InputKind.COMBINER) {
            var ingredients = ingredients(holder, level);
            if (ingredients.size() != 3) return false;
            // Native matches can reuse a slot for overlapping ingredients. Enforce three assigned real units.
            for (int slot = 0; slot < 3; slot++) if (inputs.get(slot).isEmpty() || !ingredients.get(slot).test(inputs.get(slot))) return false;
            return true;
        }
        if (profile.input() == InputKind.SMITHING) {
            return profile.mod().equals("twilightforest") ? inputs.getFirst().isEmpty() && !inputs.get(1).isEmpty() && !inputs.get(2).isEmpty()
                    : !inputs.getFirst().isEmpty() && !inputs.get(1).isEmpty() && inputs.get(2).isEmpty();
        }
        return inputs.stream().noneMatch(ItemStack::isEmpty);
    }

    private static int size(InputKind input) { return switch (input) { case SINGLE -> 1; case GRID -> 9; case COMBINER, SMITHING -> 3; case FUSION -> 2; }; }

    private static RecipeInput input(Profile profile, List<ItemStack> inputs) {
        return switch (profile.input()) {
            case SINGLE -> new SingleRecipeInput(inputs.getFirst());
            case GRID -> CraftingInput.of(3, 3, inputs);
            case SMITHING -> new SmithingRecipeInput(inputs.get(0), inputs.get(1), inputs.get(2));
            case COMBINER -> (RecipeInput) SpecialNative.create("io.github.razordevs.deep_aether.recipe.combiner.CombinerRecipeInput", new Class<?>[]{List.class}, inputs);
            case FUSION -> (RecipeInput) SpecialNative.create("com.github.L_Ender.cataclysm.crafting.WeaponfusionRecipeInput", new Class<?>[]{ItemStack.class, ItemStack.class}, inputs.get(0), inputs.get(1));
        };
    }

    @SuppressWarnings("unchecked") private static Recipe<RecipeInput> recipe(RecipeHolder<?> holder) { return (Recipe<RecipeInput>) holder.value(); }
    private static Profile require(RecipeHolder<?> holder, ServerLevel level) {
        SpecialNative.current(holder, level);
        Profile profile = profile(holder.value());
        if (profile == null) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        return profile;
    }

    /** Only the altar-specific advancement. Standard crafting/smelting callbacks remain journaled by the executor. */
    public static void altarCallback(RecipeHolder<?> holder, ServerPlayer player) {
        Profile profile = require(holder, player.serverLevel());
        if (!profile.callbacks().equals("MAID_ALTAR_ITEM")) throw new IllegalArgumentException("SPECIAL_CALLBACK_MISMATCH");
        Object deferred = SpecialNative.field("com.github.tartaricacid.touhoulittlemaid.init.InitTrigger", "ALTAR_CRAFT");
        Object trigger = ((java.util.function.Supplier<?>) deferred).get();
        SpecialNative.call(trigger, "trigger", void.class, new Class<?>[]{ServerPlayer.class, ResourceLocation.class}, player, holder.id());
    }

    private static final class Result extends VerifiedTransformation {
        Result(RecipeHolder<?> holder, Profile profile, List<ItemStack> inputs, List<ItemStack> outputs,
               List<ItemStack> remainders, float experience, ServerLevel level) {
            super(holder.id(), SpecialNative.id(profile.type()), SpecialNative.id(profile.serializer()),
                    profile.mod() + ":" + profile.input().name().toLowerCase(java.util.Locale.ROOT) + "@" + profile.version(),
                    inputs, inputs.stream().map(s -> s.isEmpty() ? 0 : 1).toList(), outputs, remainders,
                    new ExperienceReward(experience, 1), new CallbackPlan(profile.callbacks(),
                    profile.callbacks().equals("NO_CALLBACK") ? List.of() : profile.callbacks().equals("MAID_ALTAR_ITEM")
                            ? List.of("altar_craft_advancement") : List.of("native_result_slot_callbacks"),
                    List.of(profile.className(), "special-processing-semantics-report.md"), true), SamplingEvidence.deterministic(),
                    Map.of("stationRequired", "false", "waitTicks", "0", "inputRoles", profile.input().name()), level.registryAccess());
        }
    }
}
