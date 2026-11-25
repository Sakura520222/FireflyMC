package firefly520.fireflymc.ai.processing.advanced;

import java.util.List;
import java.util.Map;
import java.util.Set;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.Tags;

/** Apothic 1.6.0 infusion uses ItemStack/attribute overloads; the RecipeInput overloads are intentionally unusable. */
public final class ApothicInfusionAdapter {
    private static final String ROOT = "dev.shadowsoffire.apothic_enchanting.table.infusion.";
    private static final Set<String> CLASSES = Set.of(ROOT + "InfusionRecipe", ROOT + "KeepNBTInfusionRecipe",
            "dev.shadowsoffire.apotheosis.recipe.CharmInfusionRecipe");
    private ApothicInfusionAdapter() {}

    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && CLASSES.contains(recipe.getClass().getName())
                && AdvancedReflection.id("apothic_enchanting:infusion").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && AdvancedReflection.version("apothic_enchanting", "1.6.0")
                && (!recipe.getClass().getName().contains(".apotheosis.") || AdvancedReflection.version("apotheosis", "8.6.0"));
    }

    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, ServerPlayer player) {
        require(holder, player);
        Ingredient target = (Ingredient) AdvancedReflection.call(holder.value(), "getInput");
        return List.of(AdvancedInput.one(0, "infusion_target", 1, target::test),
                AdvancedInput.one(1, "consumable_enchanting_catalyst", 3, stack -> stack.is(Tags.Items.ENCHANTING_FUELS)));
    }

    @SuppressWarnings("unchecked")
    public static InfusionStatsPolicy.Stats virtualStats(RecipeHolder<?> holder, ServerPlayer player) {
        require(holder, player);
        Object minimum = AdvancedReflection.call(holder.value(), "getRequirements");
        Object maximum = AdvancedReflection.call(holder.value(), "getMaxRequirements");
        Holder<Attribute> cap = (Holder<Attribute>) AdvancedReflection.field("dev.shadowsoffire.apothic_enchanting.Ench$Attributes", "MAX_ETERNA");
        return InfusionStatsPolicy.choose(bounds(minimum, maximum, "eterna"), bounds(minimum, maximum, "quanta"),
                bounds(minimum, maximum, "arcana"), (float) player.getAttributeValue(cap));
    }

    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player) {
        require(holder, player);
        if (supplied.size() != 2 || supplied.get(0).getCount() != 1 || supplied.get(1).getCount() < 3
                || !supplied.get(1).is(Tags.Items.ENCHANTING_FUELS)) throw AdvancedReflection.failure("INFUSION_REAL_MATERIAL_MISSING");
        var inputs = RecipeInputIsolation.copyAll(supplied, player.registryAccess());
        var parameters = virtualStats(holder, player);
        var signature = new Class<?>[]{ItemStack.class, float.class, float.class, float.class};
        if (!(boolean) AdvancedReflection.call(holder.value(), "matches", signature, inputs.getFirst(), parameters.eterna(), parameters.quanta(), parameters.arcana()))
            throw AdvancedReflection.failure("INFUSION_INPUT_OR_ATTRIBUTE_MISMATCH");
        ItemStack isolated = AdvancedReflection.copy(inputs.getFirst(), player.registryAccess());
        ItemStack output = AdvancedReflection.copy((ItemStack) AdvancedReflection.call(holder.value(), "assemble", signature,
                isolated, parameters.eterna(), parameters.quanta(), parameters.arcana()), player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("INFUSION_OUTPUT_INVALID");
        int seed = player.getEnchantmentSeed();
        int power = ((Number) AdvancedReflection.invoke(AdvancedReflection.type("dev.shadowsoffire.apothic_enchanting.table.ApothEnchantmentHelper"),
                null, "getEnchantmentCost", new Class<?>[]{net.minecraft.util.RandomSource.class, int.class, float.class, ItemStack.class},
                net.minecraft.util.RandomSource.create(seed), 2, Math.max(1.5f, parameters.eterna()), inputs.getFirst())).intValue();
        if (power < 3) power++;
        final int triggerPower = Math.min(power, Math.max(3, (int) Math.ceil(parameters.eterna())));
        return new AdvancedTransformation(holder, "apothic_enchanting:infusion@1.6.0", inputs, List.of(1, 3),
                List.of(output), AdvancedReflection.emptySlots(2), SamplingEvidence.deterministic(),
                Map.of("owner", player.getUUID().toString(), "virtualStatsPolicy", InfusionStatsPolicy.VERSION,
                        "eterna", Float.toString(parameters.eterna()), "quanta", Float.toString(parameters.quanta()),
                        "arcana", Float.toString(parameters.arcana()), "enchantmentSeed", Integer.toString(seed),
                        "catalystCount", "3", "experienceCost", "0"), AdvancedTransformation.references(holder),
                List.of("native_zero_cost_enchantment_performed", "native_enchantment_triggers"),
                actor -> callbacks(actor, inputs.getFirst(), output, seed, triggerPower, parameters), player.registryAccess());
    }

    static void callbacks(ServerPlayer player, ItemStack input, ItemStack output, int seed, int power,
                                  InfusionStatsPolicy.Stats stats) {
        if (player.getEnchantmentSeed() != seed) throw AdvancedReflection.failure("ENCHANTMENT_SEED_CHANGED");
        player.onEnchantmentPerformed(AdvancedReflection.copy(input, player.registryAccess()), 0);
        player.awardStat(Stats.ENCHANT_ITEM);
        Object trigger = AdvancedReflection.field("dev.shadowsoffire.apothic_enchanting.Ench$Triggers", "ENCHANTED");
        AdvancedReflection.call(trigger, "trigger", new Class<?>[]{ServerPlayer.class, ItemStack.class, int.class, float.class, float.class, float.class, boolean.class},
                player, AdvancedReflection.copy(output, player.registryAccess()), power, stats.eterna(), stats.quanta(), stats.arcana(), false);
        CriteriaTriggers.ENCHANTED_ITEM.trigger(player, AdvancedReflection.copy(output, player.registryAccess()), power);
    }

    private static InfusionStatsPolicy.Bounds bounds(Object min, Object max, String name) {
        return new InfusionStatsPolicy.Bounds(((Number) AdvancedReflection.call(min, name)).floatValue(),
                ((Number) AdvancedReflection.call(max, name)).floatValue());
    }
    private static void require(RecipeHolder<?> holder, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
}
