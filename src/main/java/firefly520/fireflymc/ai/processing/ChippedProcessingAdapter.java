package firefly520.fireflymc.ai.processing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

/** Chipped 4.0.2 performs one-for-one selection from the actual recipe's alternatives. */
public final class ChippedProcessingAdapter {
    public static final String CLASS = "earth.terrarium.chipped.common.recipes.ChippedRecipe";
    private static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("chipped", "workbench");
    private static final Map<Class<?>, Method> RESULTS = new java.util.concurrent.ConcurrentHashMap<>();
    private ChippedProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS)
                && TYPE.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && ModList.get().getModContainerById("chipped")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("4.0.2")).orElse(false);
    }
    public static boolean matches(RecipeHolder<?> holder, ItemStack input, String target, ServerLevel level) {
        require(holder, level);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("CHIPPED_TARGET_REQUIRED");
        ResourceLocation id = ResourceLocation.parse(target);
        if (!BuiltInRegistries.ITEM.containsKey(id)) return false;
        ItemStack requested = new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (requested.isEmpty() || input.isEmpty()) return false;
        @SuppressWarnings("unchecked") var recipe = (Recipe<RecipeInput>) holder.value();
        ItemStack copy = RecipeInputIsolation.copy(input.copyWithCount(1), level.registryAccess());
        if (!recipe.matches(new SingleRecipeInput(copy), level)) return false;
        return selected(holder, copy, requested, level) != null;
    }
    private static ItemStack selected(RecipeHolder<?> holder, ItemStack input, ItemStack requested, ServerLevel level) {
        try {
            Method method = resultsMethod(holder.value().getClass());
            try (Stream<?> values = (Stream<?>) method.invoke(holder.value(), input)) {
                var iterator = values.iterator(); int inspected = 0;
                while (iterator.hasNext()) {
                    if (++inspected > CommonModMaterialRules.MAX_CANDIDATES) throw new IllegalArgumentException("CHIPPED_CANDIDATE_LIMIT");
                    Object value = iterator.next();
                    if (!(value instanceof ItemStack stack)) throw new IllegalArgumentException("CHIPPED_RESULT_INVALID");
                    if (ItemStack.isSameItemSameComponents(stack, requested)) return RecipeInputIsolation.copy(stack, level.registryAccess());
                }
                return null;
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("CHIPPED_API_MISMATCH"); }
    }
    private static Method resultsMethod(Class<?> type) throws ReflectiveOperationException {
        Method existing = RESULTS.get(type);
        if (existing != null) return existing;
        Method method = type.getMethod("getResults", ItemStack.class);
        if (method.getReturnType() != Stream.class || Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException("CHIPPED_RESULTS_SIGNATURE");
        RESULTS.put(type, method); return method;
    }
    public static List<ItemStack> candidates(RecipeHolder<?> holder, ItemStack source, ServerLevel level) {
        require(holder, level);
        try {
            Method method = resultsMethod(holder.value().getClass());
            ItemStack input = RecipeInputIsolation.copy(source, level.registryAccess());
            try (Stream<?> result = (Stream<?>) method.invoke(holder.value(), input)) {
                var values = result.limit(CommonModMaterialRules.MAX_CANDIDATES + 1L).toList();
                if (values.size() > CommonModMaterialRules.MAX_CANDIDATES) throw new IllegalArgumentException("CHIPPED_CANDIDATE_LIMIT");
                var outputs = new ArrayList<ItemStack>();
                for (Object value : values) {
                    if (!(value instanceof ItemStack stack)) throw new IllegalArgumentException("CHIPPED_RESULT_INVALID");
                    if (!stack.isEmpty()) outputs.add(RecipeInputIsolation.copy(stack, level.registryAccess()));
                }
                return List.copyOf(outputs);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("CHIPPED_API_MISMATCH"); }
    }
    public static VerifiedTransformation verify(RecipeHolder<?> holder, ItemStack source, String target, ServerLevel level) {
        require(holder, level);
        if (!matches(holder, source, target, level)) throw new IllegalArgumentException("CHIPPED_INPUT_OR_TARGET_MISMATCH");
        ItemStack requested = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(target)));
        var chosen = selected(holder, RecipeInputIsolation.copy(source, level.registryAccess()), requested, level);
        if (chosen == null) throw new IllegalArgumentException("CHIPPED_TARGET_NOT_IN_RECIPE");
        var delta = CommonModMaterialRules.chipped(source, 1, List.of(chosen), requested, level.registryAccess());
        return new VerifiedTransformation(holder.id(), TYPE, BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()),
                "chipped:workbench@4.0.2", delta.inputs(), delta.consumed(), delta.outputs(), delta.remainders(),
                new VerifiedTransformation.ExperienceReward(0, 1), new VerifiedTransformation.CallbackPlan(
                "CHIPPED_DECORATIVE", List.of(), List.of("WorkbenchMenu#craft:exact chosen variant replaces actual input count"), false),
                VerifiedTransformation.SamplingEvidence.deterministic(), Map.of("targetItem", target, "stationRequired", "false"), level.registryAccess());
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value())) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!TYPE.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalStateException("CHIPPED_SERIALIZER_MISMATCH");
    }
}
