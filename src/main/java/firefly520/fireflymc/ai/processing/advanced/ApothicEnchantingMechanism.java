package firefly520.fireflymc.ai.processing.advanced;

import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

/** Non-RecipeManager mechanism. Fixed vanilla-30 profile, real player's cap, native seed/choice/enchantment algorithm. */
public final class ApothicEnchantingMechanism {
    public static final String ID = "apothic_enchanting:virtual_enchanting";
    public static final String POLICY = "vanilla30-player-cap-v1";
    private static final String ROOT = "dev.shadowsoffire.apothic_enchanting.";
    private ApothicEnchantingMechanism() {}
    public static boolean available() { return AdvancedReflection.version("apothic_enchanting", "1.6.0"); }

    public static List<AdvancedInput> inputRequirements(ServerPlayer player, int choice) {
        require(player, choice);
        return List.of(AdvancedInput.one(0, "real_enchantment_target", 1, ApothicEnchantingMechanism.enchantable()),
                AdvancedInput.one(1, "consumed_enchantment_catalyst", choice + 1, stack -> stack.is(Tags.Items.ENCHANTING_FUELS)));
    }
    private static java.util.function.Predicate<ItemStack> enchantable() {
        return stack -> !stack.isEmpty() && stack.getItem().isEnchantable(stack)
                && (boolean) AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "table.ApothEnchantmentMenu"), null,
                        "isEnchantableEnough", new Class<?>[]{ItemStack.class}, stack);
    }

    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(List<ItemStack> supplied, ServerPlayer player, int choice) {
        require(player, choice);
        if (supplied.size() != 2 || supplied.getFirst().getCount() != 1 || !enchantable().test(supplied.getFirst())
                || supplied.get(1).getCount() < choice + 1 || !supplied.get(1).is(Tags.Items.ENCHANTING_FUELS))
            throw AdvancedReflection.failure("ENCHANTING_REAL_INPUT_MISSING");
        var inputs = RecipeInputIsolation.copyAll(supplied, player.registryAccess());
        Holder<Attribute> cap = (Holder<Attribute>) AdvancedReflection.field(ROOT + "Ench$Attributes", "MAX_ETERNA");
        float eterna = Math.min(30, (float) player.getAttributeValue(cap));
        if (!Float.isFinite(eterna) || eterna < 0) throw AdvancedReflection.failure("ENCHANTING_PLAYER_CAP_INVALID");
        Object stats = AdvancedReflection.construct(ROOT + "table.EnchantmentTableStats",
                new Class<?>[]{float.class, float.class, float.class, int.class, java.util.Set.class, boolean.class, boolean.class},
                eterna, 15f, 0f, 1, java.util.Set.of(), false, false);
        int seed = player.getEnchantmentSeed();
        var helper = AdvancedReflection.type(ROOT + "table.ApothEnchantmentHelper");
        var costsRandom = RandomSource.create(seed);
        int power = 0;
        for (int index = 0; index <= choice; index++) {
            power = ((Number) AdvancedReflection.invoke(helper, null, "getEnchantmentCost",
                    new Class<?>[]{RandomSource.class, int.class, float.class, ItemStack.class}, costsRandom, index, Math.max(1.5f, eterna), inputs.getFirst())).intValue();
            if (power < index + 1) power++;
        }
        Object infusion = AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "table.infusion.InfusionRecipe"), null,
                "findMatch", new Class<?>[]{Level.class, ItemStack.class, float.class, float.class, float.class},
                player.serverLevel(), inputs.getFirst(), eterna, 15f, 0f);
        if (choice == 2 && infusion != null) throw AdvancedReflection.failure("ENCHANTING_USE_EXPLICIT_INFUSION_RECIPE");
        var random = RandomSource.create((long) (seed + choice));
        ItemStack target = AdvancedReflection.copy(inputs.getFirst(), player.registryAccess());
        List<EnchantmentInstance> choices = (List<EnchantmentInstance>) AdvancedReflection.invoke(helper, null, "selectEnchantment",
                new Class<?>[]{RandomSource.class, ItemStack.class, int.class, AdvancedReflection.type(ROOT + "table.EnchantmentTableStats"), HolderLookup.RegistryLookup.class},
                random, target, power, stats, player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT));
        if (choices.isEmpty() || choices.size() > 64) throw AdvancedReflection.failure("ENCHANTING_NO_VALID_RESULT");
        ItemStack output = AdvancedReflection.copy((ItemStack) AdvancedReflection.call(target.getItem(), "applyEnchantments",
                new Class<?>[]{ItemStack.class, List.class}, target, choices), player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("ENCHANTING_RESULT_EMPTY");
        final int triggerPower = power;
        return new AdvancedTransformation(null, ID, inputs, List.of(1, choice + 1), List.of(output), AdvancedReflection.emptySlots(2),
                new SamplingEvidence(true, "native-apoth-enchant-seed-choice-v1", (long) (seed + choice), 0, List.of()),
                Map.of("owner", player.getUUID().toString(), "mechanismId", ID, "virtualStatsPolicy", POLICY, "eterna", Float.toString(eterna),
                        "quanta", "15.0", "arcana", "0.0", "choice", Integer.toString(choice), "enchantmentSeed", Integer.toString(seed),
                        "experienceCost", "0", "power", Integer.toString(power)), List.of(),
                List.of("native_zero_cost_enchantment_performed", "native_enchantment_triggers"),
                actor -> ApothicInfusionAdapter.callbacks(actor, inputs.getFirst(), output, seed, triggerPower,
                        new InfusionStatsPolicy.Stats(eterna, 15f, 0f)), player.registryAccess());
    }
    private static void require(ServerPlayer player, int choice) {
        AdvancedReflection.thread(player.serverLevel());
        if (!available()) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_MECHANISM");
        if (choice < 0 || choice > 2) throw AdvancedReflection.failure("ENCHANTING_CHOICE_INVALID");
    }
}
