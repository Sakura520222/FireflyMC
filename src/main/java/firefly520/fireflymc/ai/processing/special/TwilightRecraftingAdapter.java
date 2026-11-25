package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.Tags;

/** The equipment recrafting path keeps the original native grid and enchantment merge semantics. */
public final class TwilightRecraftingAdapter {
    private TwilightRecraftingAdapter() {}

    public static VerifiedTransformation verify(RecipeHolder<?> originalRecipe, ItemStack suppliedEquipment,
            int ingredientCycle, RecipeHolder<?> targetRecipe, List<ItemStack> suppliedAdditions, ServerPlayer player) {
        var level = player.serverLevel();
        SpecialNative.current(originalRecipe, level); SpecialNative.current(targetRecipe, level);
        if (!TwilightUncraftingAdapter.accepts(originalRecipe, suppliedEquipment, level))
            throw new IllegalArgumentException("RECRAFTING_ORIGINAL_RECIPE_REJECTED");
        if (ingredientCycle != 0 && TwilightUncraftingAdapter.restrictions().ingredientSwitchingDisabled())
            throw new IllegalArgumentException("UNCRAFTING_VARIANT_SWITCHING_DISABLED");
        if (!(targetRecipe.value().getClass() == ShapedRecipe.class || targetRecipe.value().getClass() == ShapelessRecipe.class))
            throw new IllegalArgumentException("RECRAFTING_DYNAMIC_RESULT_REQUIRES_ADAPTER");
        CraftingRecipe target = (CraftingRecipe) targetRecipe.value();
        if (level.getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) && !player.getRecipeBook().contains(targetRecipe.id()))
            throw new IllegalArgumentException("RECRAFTING_RECIPE_NOT_UNLOCKED");
        if (suppliedEquipment.getCount() != 1 || suppliedEquipment.has(DataComponents.CONTAINER))
            throw new IllegalArgumentException("RECRAFTING_EQUIPMENT_CONTEXT_REQUIRED");
        var additions = SpecialNative.units(suppliedAdditions, 9, level);
        if (additions.stream().noneMatch(s -> !s.isEmpty())) throw new IllegalArgumentException("RECRAFTING_ADDITION_REQUIRED");
        if (additions.stream().anyMatch(s -> s.hasCraftingRemainingItem() || s.has(DataComponents.CONTAINER)))
            throw new IllegalArgumentException("RECRAFTING_CONTAINER_REMAINDER_UNREVIEWED");
        ItemStack original = RecipeInputIsolation.copy(suppliedEquipment, level.registryAccess());
        Recipe<?> source = originalRecipe.value();
        int count = source.getClass().getName().equals("twilightforest.item.recipe.UncraftingRecipe")
                ? (int) SpecialNative.get(source, "getCount", int.class) : source.getResultItem(level.registryAccess()).getCount();
        if (count != 1) throw new IllegalArgumentException("RECRAFTING_EQUIPMENT_BATCH_INVALID");
        var parts = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        var marked = new boolean[9];
        int width = source instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        if (width < 1 || width > 3 || source.getIngredients().size() > 9) throw new IllegalArgumentException("RECRAFTING_GRID_LIMIT");
        for (int i = 0; i < source.getIngredients().size(); i++) {
            var candidates = source.getIngredients().get(i).getItems();
            if (candidates.length > 4096) throw new IllegalArgumentException("RECRAFTING_VARIANT_LIMIT");
            List<ItemStack> valid = Arrays.stream(candidates).filter(s -> !s.is(tag("BANNED_UNCRAFTING_INGREDIENTS"))).toList();
            if (valid.isEmpty()) continue;
            int slot = source instanceof ShapedRecipe ? i % width + i / width * 3 : i;
            if (slot > 8) throw new IllegalArgumentException("RECRAFTING_GRID_LIMIT");
            parts.set(slot, RecipeInputIsolation.copy(valid.get(UncraftingRules.selectedVariant(ingredientCycle, valid.size())).copyWithCount(1), level.registryAccess()));
        }
        int damageable = (int) parts.stream().filter(s -> !s.isEmpty() && !s.is(tag("UNCRAFTING_IGNORES_COST"))).count();
        int lost = original.isDamaged() ? UncraftingRules.damagedParts(damageable, original.getDamageValue(), original.getMaxDamage()) : 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack part = parts.get(slot);
            if (part.isEmpty()) continue;
            if (lost > 0 && !part.is(tag("UNCRAFTING_IGNORES_COST"))) { marked[slot] = true; lost--; }
            if (part.hasCraftingRemainingItem() || part.is(Items.BARRIER)) marked[slot] = true;
        }
        var combined = new ArrayList<ItemStack>(); boolean usesRecovered = false;
        for (int slot = 0; slot < 9; slot++) {
            if (!additions.get(slot).isEmpty()) combined.add(additions.get(slot));
            else if (!marked[slot]) { combined.add(parts.get(slot)); usesRecovered |= !parts.get(slot).isEmpty(); }
            else throw new IllegalArgumentException("RECRAFTING_LOST_PART_MUST_BE_REPLACED");
        }
        if (!usesRecovered) throw new IllegalArgumentException("RECRAFTING_NO_RECOVERED_PART_USED");
        CraftingInput combinedInput = CraftingInput.of(3, 3, RecipeInputIsolation.copyAll(combined, level.registryAccess()));
        if (!target.matches(combinedInput, level)) throw new IllegalArgumentException("RECRAFTING_TARGET_MISMATCH");
        if (target.matches(CraftingInput.of(3, 3, RecipeInputIsolation.copyAll(additions, level.registryAccess())), level))
            throw new IllegalArgumentException("RECRAFTING_SOURCE_NOT_NEEDED_USE_CRAFTING");
        ItemStack output = SpecialNative.stack(target.assemble(combinedInput, level.registryAccess()), level, false);
        if (!sameEquipmentFamily(original, output)) throw new IllegalArgumentException("RECRAFTING_DIFFERENT_EQUIPMENT_FAMILY");
        if (output.getItem().isEnchantable(output)) {
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(original.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
            output.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet().forEach(e -> enchantments.set(e.getKey(), e.getIntValue()));
            enchantments.removeIf(enchantment -> !output.supportsEnchantment(enchantment));
            output.remove(DataComponents.ENCHANTMENTS);
            EnchantmentHelper.setEnchantments(output, enchantments.toImmutable());
        }
        if (target.getRemainingItems(combinedInput).stream().anyMatch(s -> !s.isEmpty()))
            throw new IllegalArgumentException("RECRAFTING_SPECIAL_REMAINDER_UNREVIEWED");
        var inputs = new ArrayList<ItemStack>(); inputs.add(original); inputs.addAll(additions);
        return new Result(originalRecipe, targetRecipe, inputs, output, ingredientCycle, player);
    }

    private static boolean sameEquipmentFamily(ItemStack source, ItemStack target) {
        for (TagKey<Item> tag : List.of(ItemTags.PICKAXES, ItemTags.AXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SWORDS,
                Tags.Items.TOOLS_BOW, Tags.Items.TOOLS_CROSSBOW, Tags.Items.TOOLS_FISHING_ROD))
            if (source.is(tag) && target.is(tag)) return true;
        return source.getItem() instanceof ArmorItem left && target.getItem() instanceof ArmorItem right && left.getEquipmentSlot() == right.getEquipmentSlot();
    }

    @SuppressWarnings("unchecked") private static TagKey<Item> tag(String field) { return (TagKey<Item>) SpecialNative.field("twilightforest.data.tags.ItemTagGenerator", field); }
    private static final class Result extends VerifiedTransformation {
        private final List<RecipeReference> references;
        Result(RecipeHolder<?> source, RecipeHolder<?> target, List<ItemStack> inputs, ItemStack output, int cycle, ServerPlayer player) {
            super(target.id(), BuiltInRegistries.RECIPE_TYPE.getKey(target.value().getType()), BuiltInRegistries.RECIPE_SERIALIZER.getKey(target.value().getSerializer()),
                    "twilightforest:equipment_recrafting@4.8.3345", inputs, inputs.stream().map(s -> s.isEmpty() ? 0 : 1).toList(),
                    List.of(output), Collections.nCopies(inputs.size(), ItemStack.EMPTY), new ExperienceReward(0, 1),
                    new CallbackPlan("CRAFTING_RESULT_SLOT", List.of("native_result_slot_callbacks"), List.of("UncraftingMenu#slotsChanged", "UncraftingResultSlot#onTake"), true),
                    SamplingEvidence.deterministic(), Map.of("originalRecipe", source.id().toString(), "variantCycle", Integer.toString(cycle),
                            "configurationDigest", TwilightUncraftingAdapter.restrictions().digest(), "xpCharge", "0", "stationRequired", "false"), player.registryAccess());
            references = List.of(new RecipeReference(source.id(), BuiltInRegistries.RECIPE_TYPE.getKey(source.value().getType())),
                    new RecipeReference(target.id(), BuiltInRegistries.RECIPE_TYPE.getKey(target.value().getType())));
        }
        @Override public List<RecipeReference> backingRecipes() { return references; }
    }
}
