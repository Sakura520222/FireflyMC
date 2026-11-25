package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;

/** Random effect types are equally likely; repeated potions do not weight their effect twice. */
public final class BumbleCandleAdapter {
    public static final String CLASS = "com.telepathicgrunt.the_bumblezone.items.recipes.PotionCandleRecipe";
    public record Outcome(String effectId, MaterialStack stack, double probability) {}
    public record Preview(List<MaterialStack> inputs, List<MaterialStack> remainders, List<Outcome> outcomes) {
        public Preview { inputs = List.copyOf(inputs); remainders = List.copyOf(remainders); outcomes = List.copyOf(outcomes); }
    }
    private BumbleCandleAdapter() {}

    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS)
                && recipe.getType() == RecipeType.CRAFTING
                && SpecialNative.id("the_bumblezone:potion_candle_recipe").equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))
                && SpecialNative.version("the_bumblezone", "7.15.2+1.21.1-neoforge");
    }

    /** Calls no random assemble function. Native public stat/component helpers produce every possible outcome. */
    public static Preview preview(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        require(holder, level);
        List<ItemStack> inputs = SpecialNative.units(supplied, 9, level);
        CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, RecipeInputIsolation.copyAll(inputs, level.registryAccess()));
        CraftingRecipe recipe = (CraftingRecipe) holder.value();
        if (!recipe.matches(positioned.input(), level)) throw new IllegalArgumentException("POTION_CANDLE_INPUT_MISMATCH");
        var effects = new TreeMap<String, MobEffect>();
        int totalDuration = 0, totalAmplifiers = 0, effectCount = 0, splash = 0, lingering = 0;
        @SuppressWarnings("unchecked") TagKey<MobEffect> disallowed = (TagKey<MobEffect>) SpecialNative.field("com.telepathicgrunt.the_bumblezone.modinit.BzTags", "DISALLOWED_POTION_CANDLE_EFFECTS");
        for (ItemStack input : inputs) {
            if (!input.is(Items.POTION) && !input.is(Items.SPLASH_POTION) && !input.is(Items.LINGERING_POTION)) continue;
            var contents = input.get(DataComponents.POTION_CONTENTS);
            if (contents == null) throw new IllegalArgumentException("POTION_CANDLE_MISSING_CONTENTS");
            for (var effect : contents.getAllEffects()) {
                if (++effectCount > 64 || effect.getDuration() < 0 || effect.getAmplifier() < 0) throw new IllegalArgumentException("POTION_CANDLE_EFFECT_LIMIT");
                MobEffect nativeEffect = effect.getEffect().value();
                totalDuration = Math.addExact(totalDuration, nativeEffect.isInstantenous() ? 200 : effect.getDuration());
                totalAmplifiers = Math.addExact(totalAmplifiers, Math.addExact(effect.getAmplifier(), 1));
                if (!effect.getEffect().is(disallowed)) effects.put(BuiltInRegistries.MOB_EFFECT.getKey(nativeEffect).toString(), nativeEffect);
            }
            if (input.is(Items.SPLASH_POTION)) splash++;
            if (input.is(Items.LINGERING_POTION)) lingering++;
        }
        ItemStack base = SpecialNative.stack(recipe.getResultItem(level.registryAccess()), level, false);
        int count = base.getCount();
        int cap = (int) SpecialNative.get(recipe, "getMaxLevelCap", int.class);
        var outcomes = new ArrayList<Outcome>();
        if (effectCount == 0) outcomes.add(new Outcome("none", ItemSnapshotCodec.capture(base, level.registryAccess()), 1));
        else {
            if (effects.isEmpty()) throw new IllegalArgumentException("POTION_CANDLE_ALL_EFFECTS_DISALLOWED");
            for (var effect : effects.entrySet()) {
                var duration = new AtomicInteger(totalDuration);
                var amplifier = new AtomicInteger(totalAmplifiers);
                var totals = new AtomicInteger(effectCount);
                SpecialNative.callStatic(CLASS, "balanceMainStats", void.class,
                        new Class<?>[]{MobEffect.class, AtomicInteger.class, AtomicInteger.class, AtomicInteger.class}, effect.getValue(), duration, amplifier, totals);
                amplifier.set(Math.min(amplifier.get(), cap));
                ItemStack output = (ItemStack) SpecialNative.callStatic(CLASS, "createTaggedPotionCandle", ItemStack.class,
                        new Class<?>[]{MobEffect.class, AtomicInteger.class, AtomicInteger.class, int.class, int.class, int.class},
                        effect.getValue(), duration, amplifier, splash, lingering, count);
                outcomes.add(new Outcome(effect.getKey(), ItemSnapshotCodec.capture(SpecialNative.stack(output, level, false), level.registryAccess()), 1.0 / effects.size()));
            }
        }
        var remainder = new ArrayList<>(Collections.nCopies(9, MaterialStack.EMPTY));
        var compact = recipe.getRemainingItems(positioned.input());
        if (compact.size() != positioned.input().size()) throw new IllegalStateException("POTION_CANDLE_REMAINDER_SIZE");
        for (int y = 0; y < positioned.input().height(); y++) for (int x = 0; x < positioned.input().width(); x++) {
            int slot = x + positioned.left() + 3 * (y + positioned.top());
            ItemStack remaining = SpecialNative.stack(compact.get(x + y * positioned.input().width()), level, true);
            if (inputs.get(slot).isEmpty() && !remaining.isEmpty()) throw new IllegalStateException("POTION_CANDLE_REMAINDER_WITHOUT_INPUT");
            remainder.set(slot, ItemSnapshotCodec.capture(remaining, level.registryAccess()));
        }
        return new Preview(inputs.stream().map(s -> ItemSnapshotCodec.capture(s, level.registryAccess())).toList(), remainder, outcomes);
    }

    /** Executor publishes this private sample atomically with consumption of already-held real inputs. */
    public static VerifiedTransformation sample(RecipeHolder<?> holder, List<ItemStack> supplied, long seed, ServerLevel level) {
        Preview preview = preview(holder, supplied, level);
        Outcome selected = preview.outcomes().get(new Random(seed).nextInt(preview.outcomes().size()));
        return new Result(holder, preview, selected, seed, level);
    }

    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        SpecialNative.current(holder, level);
        if (!supports(holder.value())) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
    }

    private static final class Result extends VerifiedTransformation {
        Result(RecipeHolder<?> holder, Preview preview, Outcome selected, long seed, ServerLevel level) {
            super(holder.id(), SpecialNative.id("minecraft:crafting"), SpecialNative.id("the_bumblezone:potion_candle_recipe"),
                    "the_bumblezone:potion_candle@7.15.2+1.21.1-neoforge",
                    preview.inputs().stream().map(s -> ItemSnapshotCodec.restore(s, level.registryAccess())).toList(),
                    preview.inputs().stream().map(s -> s.empty() ? 0 : 1).toList(),
                    List.of(ItemSnapshotCodec.restore(selected.stack(), level.registryAccess())),
                    preview.remainders().stream().map(s -> ItemSnapshotCodec.restore(s, level.registryAccess())).toList(),
                    new ExperienceReward(0, 1), new CallbackPlan("CRAFTING_RESULT_SLOT", List.of("native_result_slot_callbacks"),
                    List.of("PotionCandleRecipe#assemble", "PotionCandleRecipe#balanceMainStats", "PotionCandleRecipe#createTaggedPotionCandle"), true),
                    new SamplingEvidence(true, "java.util.Random.nextInt:unique-allowed-effect:canonical-id-order:one-whole-batch", seed, 1,
                            List.of()), Map.of("distribution", "UNIFORM_CATEGORICAL_WHOLE_BATCH", "possibleEffects", Integer.toString(preview.outcomes().size()),
                            "selectedEffect", selected.effectId(), "stationRequired", "false"), level.registryAccess());
        }
    }
}
