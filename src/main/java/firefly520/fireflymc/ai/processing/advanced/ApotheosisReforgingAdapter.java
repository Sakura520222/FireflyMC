package firefly520.fireflymc.ai.processing.advanced;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** Uses the actual rarity rules and player's tier/luck/biome/stages. Seed advancement is a separate journalled callback. */
public final class ApotheosisReforgingAdapter {
    public static final String SEED_KEY = "apoth_reforge_seed";
    private static final String ROOT = "dev.shadowsoffire.apotheosis.";
    private ApotheosisReforgingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(ROOT + "affix.reforging.ReforgingRecipe")
                && AdvancedReflection.version("apotheosis", "8.6.0")
                && AdvancedReflection.id("apotheosis:reforging").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, ServerPlayer player, int choice) {
        require(holder, player, choice);
        Object rarity = AdvancedReflection.call(holder.value(), "rarity");
        int multiplier = choice + 1;
        var requirements = new java.util.ArrayList<AdvancedInput>();
        requirements.add(AdvancedInput.one(0, "real_reforged_equipment", 1, stack -> {
                    Object category = AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "loot.LootCategory"), null,
                            "forItem", new Class<?>[]{ItemStack.class}, stack);
                    return !(boolean) AdvancedReflection.call(category, "isNone");
                }));
        requirements.add(AdvancedInput.one(1, "rarity_material", Math.max(1, Math.multiplyExact(multiplier, number(holder, "matCost"))), stack ->
                    rarity.equals(AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "loot.RarityRegistry"), null,
                            "getMaterialRarity", new Class<?>[]{Item.class}, stack.getItem()))));
        int sigils = Math.multiplyExact(multiplier, number(holder, "sigilCost"));
        if (sigils > 0) requirements.add(AdvancedInput.one(2, "consumed_rebirth_sigil", sigils,
                stack -> stack.is(BuiltInRegistries.ITEM.get(AdvancedReflection.id("apotheosis:sigil_of_rebirth")))));
        return List.copyOf(requirements);
    }

    /** initializationSeed is server-generated, retained in the plan, and ignored if the player already has a native seed. */
    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player,
                                                int choice, int initializationSeed) {
        require(holder, player, choice);
        if (supplied.size() != 3 || supplied.getFirst().getCount() != 1) throw AdvancedReflection.failure("REFORGING_INPUT_INVALID");
        var requirements = inputRequirements(holder, player, choice);
        for (var requirement : requirements) {
            var option = requirement.alternatives().getFirst();
            if (supplied.get(requirement.slot()).getCount() < option.count() || !option.matches().test(supplied.get(requirement.slot())))
                throw AdvancedReflection.failure("REFORGING_REAL_MATERIAL_MISSING");
        }
        var inputs = RecipeInputIsolation.copyAll(supplied, player.registryAccess());
        int previous = player.getPersistentData().getInt(SEED_KEY);
        int effective = previous == 0 ? initializationSeed : previous;
        if (effective == 0) throw AdvancedReflection.failure("REFORGING_SERVER_SEED_REQUIRED");
        int choiceSeed = effective ^ (BuiltInRegistries.ITEM.getKey(inputs.getFirst().getItem()).hashCode() + choice);
        RandomSource random = new XoroshiroRandomSource(choiceSeed);
        Object context = AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "tiers.GenContext"), null, "forPlayer",
                new Class<?>[]{RandomSource.class, Player.class}, random, player);
        Object rarityHolder = AdvancedReflection.call(holder.value(), "rarity");
        if (!(boolean) AdvancedReflection.call(rarityHolder, "isBound")) throw AdvancedReflection.failure("REFORGING_RARITY_UNBOUND");
        Object rarity = AdvancedReflection.call(rarityHolder, "get");
        ItemStack output = (ItemStack) AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "loot.LootController"), null, "createLootItem",
                new Class<?>[]{ItemStack.class, AdvancedReflection.type(ROOT + "loot.LootRarity"), AdvancedReflection.type(ROOT + "tiers.GenContext")},
                AdvancedReflection.copy(inputs.getFirst(), player.registryAccess()), rarity, context);
        output = AdvancedReflection.copy(output, player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("REFORGING_RESULT_EMPTY");
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("owner", player.getUUID().toString()); facts.put("choice", Integer.toString(choice));
        facts.put("previousSeed", Integer.toString(previous)); facts.put("effectiveSeed", Integer.toString(effective));
        facts.put("choiceSeed", Integer.toString(choiceSeed)); facts.put("levelCost", "0");
        facts.put("tier", AdvancedReflection.call(context, "tier").toString());
        facts.put("luck", AdvancedReflection.call(context, "luck").toString());
        facts.put("dimension", player.serverLevel().dimension().location().toString());
        Holder<?> biome = (Holder<?>) AdvancedReflection.call(context, "biome");
        facts.put("biome", biome.unwrapKey().orElseThrow().location().toString());
        facts.put("stages", String.join(",", new TreeSet<>((Set<String>) AdvancedReflection.call(context, "stages"))));
        int materialCost = number(holder, "matCost") * (choice + 1), sigilCost = number(holder, "sigilCost") * (choice + 1);
        var remainders = AdvancedReflection.emptySlots(3);
        if (materialCost == 0) remainders.set(1, AdvancedReflection.copy(inputs.get(1).copyWithCount(1), player.registryAccess()));
        return new AdvancedTransformation(holder, "apotheosis:reforging@8.6.0", inputs,
                List.of(1, Math.max(1, materialCost), sigilCost), List.of(output),
                remainders, new SamplingEvidence(true, "native-reforge-player-seed-xor-choice-v1", choiceSeed, 0, List.of()),
                facts, AdvancedTransformation.references(holder), List.of("advance_native_reforge_seed"), actor -> advanceSeed(actor, previous), player.registryAccess());
    }

    static void advanceSeed(ServerPlayer player, int expected) {
        if (player.getPersistentData().getInt(SEED_KEY) != expected) throw AdvancedReflection.failure("REFORGING_SEED_CHANGED");
        player.getPersistentData().putInt(SEED_KEY, player.getRandom().nextInt());
    }
    private static int number(RecipeHolder<?> holder, String method) {
        int number = ((Number) AdvancedReflection.call(holder.value(), method)).intValue();
        if (number < 0 || number > 170) throw AdvancedReflection.failure("REFORGING_MATERIAL_COUNT_INVALID");
        return number;
    }
    private static void require(RecipeHolder<?> holder, ServerPlayer player, int choice) {
        AdvancedReflection.current(holder, player.serverLevel());
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        if (choice < 0 || choice > 2) throw AdvancedReflection.failure("REFORGING_CHOICE_INVALID");
    }
}
