package firefly520.fireflymc.ai.processing.advanced;

import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Native calculation plus explicit deferred success stages; callbacks require the caller's durable intent. */
public final class AdvancedTransformation extends VerifiedTransformation {
    @FunctionalInterface public interface SuccessCallback { void apply(ServerPlayer player); }
    private final List<RecipeReference> references;
    private final SuccessCallback callback;
    private final RecipeHolder<?> holder;
    private final HolderLookup.Provider registries;

    AdvancedTransformation(RecipeHolder<?> holder, String mechanism, List<ItemStack> inputs, List<Integer> consumed,
            List<ItemStack> outputs, List<ItemStack> remainders, SamplingEvidence sampling,
            Map<String, String> context, List<RecipeReference> references, List<String> callbackStages,
            SuccessCallback callback, HolderLookup.Provider registries) {
        super(holder == null ? null : holder.id(), holder == null ? null : BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()),
                holder == null ? null : BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()), mechanism,
                inputs, consumed, outputs, remainders, new ExperienceReward(0, 1),
                new CallbackPlan("ADVANCED_NATIVE", callbackStages, List.of(mechanism), true), sampling, context, registries);
        this.references = List.copyOf(references);
        this.callback = callback;
        this.holder = holder;
        this.registries = registries;
        if (callbackStages.isEmpty() != (callback == null)) throw new IllegalArgumentException("ADVANCED_CALLBACK_INVALID");
    }

    @Override public List<RecipeReference> backingRecipes() { return references; }

    AdvancedTransformation ownedBy(ServerPlayer player) {
        var context = new java.util.HashMap<>(contextRequirements());
        String previous = context.putIfAbsent("owner", player.getUUID().toString());
        if (previous != null && !previous.equals(player.getUUID().toString())) throw AdvancedReflection.failure("ADVANCED_OWNER_CHANGED");
        return new AdvancedTransformation(holder, mechanism(), inputSnapshots(), consumedBySlot(), outputs(), remaindersBySlot(),
                samplingEvidence(), context, references, callbackPlan().requiredStages(), callback, registries);
    }

    /** Never invoke during preview or ordinary recovery; journal intent must precede this call. */
    public void commitCallbacks(ServerPlayer player) {
        AdvancedReflection.thread(player.serverLevel());
        String owner = contextRequirements().get("owner");
        if (callback != null && !player.getUUID().toString().equals(owner)) throw AdvancedReflection.failure("ADVANCED_OWNER_CHANGED");
        if (callback != null) callback.apply(player);
    }

    static List<RecipeReference> references(RecipeHolder<?> holder) {
        return List.of(new RecipeReference(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType())));
    }
}
