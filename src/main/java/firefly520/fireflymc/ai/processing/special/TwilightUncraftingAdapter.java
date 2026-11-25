package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;

/** Virtualizes the reviewed material half of UncraftingMenu/UncraftingSlot without creating a menu. */
public final class TwilightUncraftingAdapter {
    private static final String CONFIG = "twilightforest.config.TFConfig";
    private static final String RECIPE = "twilightforest.item.recipe.UncraftingRecipe";
    private static final String TAGS = "twilightforest.data.tags.ItemTagGenerator";
    public record Restrictions(boolean disabled, boolean onlyDisabled, boolean shapeless, boolean ingredientSwitchingDisabled, boolean reverseRecipes,
            List<String> recipeList, boolean reverseMods, List<String> modList) {
        public Restrictions { recipeList = List.copyOf(recipeList); modList = List.copyOf(modList); }
        public String digest() { return AiJson.digest(AiJson.canonical(this)); }
    }
    private TwilightUncraftingAdapter() {}

    public static boolean available() { return SpecialNative.version("twilightforest", "4.8.3345"); }

    public static Restrictions restrictions() {
        if (!available()) throw new IllegalArgumentException("UNSUPPORTED_UNCRAFTING_VERSION");
        return new Restrictions(flag("disableEntireTable"), flag("disableUncraftingOnly"), flag("allowShapelessUncrafting"), flag("disableIngredientSwitching"),
                flag("reverseRecipeBlacklist"), strings("disableUncraftingRecipes"), flag("flipUncraftingModIdList"), strings("blacklistedUncraftingModIds"));
    }

    /** Discovery evaluates only a specified holder; the caller must page/budget any global search. */
    public static boolean accepts(RecipeHolder<?> holder, ItemStack input, ServerLevel level) {
        SpecialNative.current(holder, level);
        Restrictions rules = restrictions();
        if (rules.disabled() || rules.onlyDisabled() || input == null || input.isEmpty() || input.is(tag("BANNED_UNCRAFTABLES"))) return false;
        Recipe<?> recipe = holder.value();
        if (recipe.getClass().getName().equals(RECIPE)) {
            if (!SpecialNative.id("twilightforest:uncrafting").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                    || !SpecialNative.id("twilightforest:uncrafting").equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))) return false;
            return (boolean) SpecialNative.call(recipe, "isItemStackAnIngredient", boolean.class, new Class<?>[]{ItemStack.class}, input);
        }
        if (!(recipe instanceof ShapedRecipe || rules.shapeless() && recipe instanceof CraftingRecipe)
                || recipe.isIncomplete() || !recipe.canCraftInDimensions(3, 3) || recipe.getIngredients().isEmpty()
                || recipe.getIngredients().size() > 9) return false;
        ItemStack output = recipe.getResultItem(level.registryAccess());
        return !output.isEmpty() && input.is(output.getItem()) && input.getCount() >= output.getCount()
                && UncraftingRules.listAllows(rules.reverseRecipes(), rules.recipeList().contains(holder.id().toString()))
                && UncraftingRules.listAllows(rules.reverseMods(), rules.modList().contains(holder.id().getNamespace()));
    }

    public static VerifiedTransformation verify(RecipeHolder<?> holder, ItemStack supplied, int variantCycle, ServerLevel level) {
        if (!accepts(holder, supplied, level)) throw new IllegalArgumentException("UNCRAFTING_DISABLED_OR_INPUT_MISMATCH");
        if (variantCycle != 0 && restrictions().ingredientSwitchingDisabled()) throw new IllegalArgumentException("UNCRAFTING_VARIANT_SWITCHING_DISABLED");
        Recipe<?> recipe = holder.value();
        int consumed = recipe.getClass().getName().equals(RECIPE) ? (int) SpecialNative.get(recipe, "getCount", int.class)
                : recipe.getResultItem(level.registryAccess()).getCount();
        if (consumed < 1 || consumed > supplied.getCount() || consumed > supplied.getMaxStackSize())
            throw new IllegalArgumentException("UNCRAFTING_REAL_BATCH_MISSING");
        ItemStack input = RecipeInputIsolation.copy(supplied.copyWithCount(consumed), level.registryAccess());
        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("UNCRAFTING_INGREDIENT_LIMIT");
        var matrix = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack[] values = ingredients.get(i).getItems();
            if (values.length > 4096) throw new IllegalArgumentException("UNCRAFTING_VARIANT_LIMIT");
            List<ItemStack> candidates = Arrays.stream(values).filter(s -> !s.is(tag("BANNED_UNCRAFTING_INGREDIENTS"))).toList();
            if (candidates.isEmpty()) continue;
            int slot = recipe instanceof ShapedRecipe ? i % width + (i / width) * 3 : i;
            if (slot > 8 || width < 1 || width > 3) throw new IllegalArgumentException("UNCRAFTING_GRID_LIMIT");
            matrix.set(slot, RecipeInputIsolation.copy(candidates.get(UncraftingRules.selectedVariant(variantCycle, candidates.size())).copyWithCount(1), level.registryAccess()));
        }
        int damageable = (int) matrix.stream().filter(s -> !s.isEmpty() && !s.is(tag("UNCRAFTING_IGNORES_COST"))).count();
        int lost = input.isDamaged() ? UncraftingRules.damagedParts(damageable, input.getDamageValue(), input.getMaxDamage()) : 0;
        var outputs = new ArrayList<ItemStack>();
        for (ItemStack ingredient : matrix) {
            if (ingredient.isEmpty()) continue;
            boolean damaged = lost > 0 && !ingredient.is(tag("UNCRAFTING_IGNORES_COST"));
            if (damaged) lost--;
            if (!damaged && !ingredient.hasCraftingRemainingItem() && !ingredient.is(Items.BARRIER)) outputs.add(ingredient);
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("UNCRAFTING_NO_RECOVERABLE_INGREDIENT");
        var contents = input.get(DataComponents.CONTAINER);
        if (contents != null) {
            // Native slot returns the contents once. A stack with copied container data may not
            // retain that same content in unconsumed units while receiving it again as a byproduct.
            if (supplied.getCount() != 1 || consumed != 1) throw new IllegalArgumentException("UNCRAFTING_STACKED_CONTAINER_UNSUPPORTED");
            for (ItemStack nested : contents.nonEmptyItems()) {
                if (outputs.size() >= 265) throw new IllegalArgumentException("UNCRAFTING_CONTENT_LIMIT");
                outputs.add(SpecialNative.stack(nested, level, false));
            }
        }
        return new Result(holder, input, outputs, restrictions(), variantCycle, level);
    }

    public static void callback(ItemStack consumed, ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread() || !available()) throw new IllegalStateException("UNCRAFTING_CALLBACK_CONTEXT");
        Object deferred = SpecialNative.field("twilightforest.init.TFAdvancements", "UNCRAFT_ITEM");
        Object trigger = ((java.util.function.Supplier<?>) deferred).get();
        SpecialNative.call(trigger, "trigger", void.class, new Class<?>[]{ServerPlayer.class, ItemStack.class}, player,
                RecipeInputIsolation.copy(consumed, player.registryAccess()));
    }

    @SuppressWarnings("unchecked") private static TagKey<Item> tag(String field) { return (TagKey<Item>) SpecialNative.field(TAGS, field); }
    private static boolean flag(String field) { return (boolean) SpecialNative.field(CONFIG, field); }
    private static List<String> strings(String field) {
        Object value = SpecialNative.field(CONFIG, field);
        if (!(value instanceof List<?> list) || list.size() > 16_384 || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalStateException("UNCRAFTING_CONFIG_INVALID");
        return list.stream().map(String.class::cast).toList();
    }

    private static final class Result extends VerifiedTransformation {
        Result(RecipeHolder<?> holder, ItemStack input, List<ItemStack> output, Restrictions restrictions, int cycle, ServerLevel level) {
            super(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()),
                    BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()), "twilightforest:uncrafting@4.8.3345",
                    List.of(input), List.of(input.getCount()), output, List.of(ItemStack.EMPTY), new ExperienceReward(0, 1),
                    new CallbackPlan("TWILIGHT_UNCRAFTING", List.of("uncraft_item_advancement"), List.of("UncraftingMenu#slotsChanged", "UncraftingSlot#onTake"), true),
                    SamplingEvidence.deterministic(), Map.of("configurationDigest", restrictions.digest(), "variantCycle", Integer.toString(cycle),
                            "stationRequired", "false", "xpCharge", "0", "nestedContentsReturnedOnce", "true"), level.registryAccess());
        }
    }
}
