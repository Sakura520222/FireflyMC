package firefly520.fireflymc.ai.processing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** Exact FD 1.3.2 cutting semantics with an internal, unenchanted virtual tool and native multi-output rolls. */
public final class FdCuttingProcessingAdapter {
    private static final String CLASS = "vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe";
    private static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("farmersdelight", "cutting");
    private static final List<String> TOOLS = List.of("farmersdelight:iron_knife", "minecraft:iron_axe", "minecraft:iron_hoe",
            "minecraft:iron_pickaxe", "minecraft:shears", "minecraft:iron_shovel");
    private FdCuttingProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS)
                && TYPE.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && ModList.get().getModContainerById("farmersdelight")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.3.2")).orElse(false);
    }
    public static boolean matches(RecipeHolder<?> holder, ItemStack source, ServerLevel level) {
        require(holder, level);
        if (source.isEmpty()) return false;
        return holder.value().getIngredients().size() == 1 && holder.value().getIngredients().getFirst().test(source);
    }
    public static VerifiedTransformation preview(RecipeHolder<?> holder, ItemStack source, ServerLevel level) {
        return calculate(holder, source, level, null);
    }
    public static VerifiedTransformation sample(RecipeHolder<?> holder, ItemStack source, ServerLevel level, long seed) {
        return calculate(holder, source, level, seed);
    }
    public static List<VerifiedTransformation.ProbabilityOutput> outputHints(RecipeHolder<?> holder, ServerLevel level) {
        require(holder, level);
        try { return distribution(holder, level); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_CUTTING_API_MISMATCH"); }
    }
    private static VerifiedTransformation calculate(RecipeHolder<?> holder, ItemStack supplied, ServerLevel level, Long seed) {
        require(holder, level);
        ItemStack source = RecipeInputIsolation.copy(supplied, level.registryAccess());
        if (source.isEmpty()) throw new IllegalArgumentException("FD_CUTTING_MATERIAL_MISSING");
        ItemStack tool = tool(holder);
        try {
            Class<?> inputClass = Class.forName("vectorwing.farmersdelight.common.crafting.CuttingBoardRecipeInput", false,
                    FdCuttingProcessingAdapter.class.getClassLoader());
            RecipeInput input = (RecipeInput) inputClass.getConstructor(ItemStack.class, ItemStack.class)
                    .newInstance(RecipeInputIsolation.copy(source, level.registryAccess()), tool.copy());
            @SuppressWarnings("unchecked") Recipe<RecipeInput> recipe = (Recipe<RecipeInput>) holder.value();
            if (!recipe.matches(input, level)) throw new IllegalArgumentException("FD_CUTTING_INPUT_MISMATCH");
            var distribution = distribution(holder, level);
            List<ItemStack> outputs;
            if (seed == null) outputs = distribution.stream().map(VerifiedTransformation.ProbabilityOutput::template).toList();
            else {
                var wrapper = new ItemStackHandler(1);
                wrapper.setStackInSlot(0, RecipeInputIsolation.copy(source, level.registryAccess()));
                Method roll = holder.value().getClass().getMethod("rollResults", RandomSource.class, int.class, RecipeWrapper.class);
                if (roll.getReturnType() != List.class || Modifier.isStatic(roll.getModifiers())) throw new NoSuchMethodException();
                List<?> actual = (List<?>) roll.invoke(holder.value(), RandomSource.create(seed), 0, new RecipeWrapper(wrapper));
                if (actual.size() > 64) throw new IllegalStateException("FD_CUTTING_OUTPUT_LIMIT");
                var checked = new ArrayList<ItemStack>();
                for (Object value : actual) {
                    if (!(value instanceof ItemStack stack)) throw new IllegalStateException("FD_CUTTING_OUTPUT_INVALID");
                    if (!stack.isEmpty()) checked.add(RecipeInputIsolation.copy(stack, level.registryAccess()));
                }
                if (wrapper.getStackInSlot(0).getCount() != source.getCount()
                        || !ItemStack.isSameItemSameComponents(wrapper.getStackInSlot(0), source))
                    throw new IllegalStateException("FD_CUTTING_INPUT_MUTATED");
                validateRoll(checked, distribution);
                outputs = List.copyOf(checked);
            }
            var delta = CommonModMaterialRules.cutting(source, outputs, level.registryAccess());
            int draws = distribution.stream().mapToInt(VerifiedTransformation.ProbabilityOutput::trials).sum();
            return new VerifiedTransformation(holder.id(), TYPE, BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()),
                    "farmersdelight:cutting@1.3.2", delta.inputs(), delta.consumed(), delta.outputs(), delta.remainders(),
                    new VerifiedTransformation.ExperienceReward(0, 1), new VerifiedTransformation.CallbackPlan("FD_CUTTING_USE",
                    List.of("virtual_tool_use_stat", "fd_cutting_advancement"), List.of("CuttingBoardBlockEntity#processStoredItem", "CuttingBoardRecipe#rollResults"), true),
                    new VerifiedTransformation.SamplingEvidence(seed != null, seed == null ? "distribution_only" : "fd_native_randomsource",
                            seed == null ? 0 : seed, seed == null ? 0 : draws, distribution),
                    Map.of("virtualTool", BuiltInRegistries.ITEM.getKey(tool.getItem()).toString(), "fortune", "0",
                            "stationRequired", "false", "auxiliaryDurability", "waived"), level.registryAccess());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_CUTTING_API_MISMATCH"); }
    }
    private static List<VerifiedTransformation.ProbabilityOutput> distribution(RecipeHolder<?> holder, ServerLevel level)
            throws ReflectiveOperationException {
        Method method = holder.value().getClass().getMethod("getRollableResults");
        if (!List.class.isAssignableFrom(method.getReturnType()) || Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException();
        List<?> source = (List<?>) method.invoke(holder.value());
        if (source.isEmpty() || source.size() > 64) throw new IllegalArgumentException("FD_CUTTING_DISTRIBUTION_LIMIT");
        Class<?> chanceClass = Class.forName("vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult", false,
                FdCuttingProcessingAdapter.class.getClassLoader());
        Method stack = chanceClass.getMethod("stack"), chance = chanceClass.getMethod("chance");
        if (stack.getReturnType() != ItemStack.class || chance.getReturnType() != float.class) throw new NoSuchMethodException();
        var result = new ArrayList<VerifiedTransformation.ProbabilityOutput>(); int draws = 0;
        for (Object value : source) {
            if (value.getClass() != chanceClass) throw new IllegalArgumentException("FD_CUTTING_DISTRIBUTION_INVALID");
            var entry = new VerifiedTransformation.ProbabilityOutput(result.size(), (ItemStack) stack.invoke(value),
                    (float) chance.invoke(value), level.registryAccess());
            draws = Math.addExact(draws, entry.trials());
            if (draws > 4096) throw new IllegalArgumentException("FD_CUTTING_DRAW_LIMIT");
            result.add(entry);
        }
        return List.copyOf(result);
    }
    private static void validateRoll(List<ItemStack> outputs, List<VerifiedTransformation.ProbabilityOutput> distribution) {
        long[] remaining = distribution.stream().mapToLong(VerifiedTransformation.ProbabilityOutput::trials).toArray();
        var templates = distribution.stream().map(VerifiedTransformation.ProbabilityOutput::template).toList();
        for (var output : outputs) {
            int left = output.getCount();
            for (int i = 0; i < distribution.size() && left > 0; i++) {
                if (!ItemStack.isSameItemSameComponents(output, templates.get(i))) continue;
                int consumed = (int) Math.min(left, remaining[i]); left -= consumed; remaining[i] -= consumed;
            }
            if (left != 0) throw new IllegalStateException("FD_CUTTING_UNDECLARED_OUTPUT");
        }
    }
    private static ItemStack tool(RecipeHolder<?> holder) {
        try {
            Method getter = holder.value().getClass().getMethod("getTool");
            if (getter.getReturnType() != Ingredient.class || Modifier.isStatic(getter.getModifiers())) throw new NoSuchMethodException();
            Ingredient predicate = (Ingredient) getter.invoke(holder.value());
            for (String id : TOOLS) {
                ResourceLocation key = ResourceLocation.parse(id);
                if (!BuiltInRegistries.ITEM.containsKey(key)) continue;
                ItemStack candidate = new ItemStack(BuiltInRegistries.ITEM.get(key));
                if (!candidate.isEmpty() && predicate.test(candidate)) return candidate;
            }
            // A recipe may explicitly require another ordinary tool. Use its unenchanted native
            // candidates in a stable order; the model never chooses Fortune or receives the tool.
            var candidates = predicate.getItems();
            if (candidates.length > 4096) throw new IllegalStateException("FD_VIRTUAL_TOOL_CANDIDATE_LIMIT");
            for (ItemStack candidate : Arrays.stream(candidates).filter(s -> !s.isEmpty() && !s.isEnchanted())
                    .sorted(Comparator.comparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())).toList())
                if (predicate.test(candidate)) return candidate.copyWithCount(1);
            throw new IllegalStateException("UNSUPPORTED_VIRTUAL_TOOL_RULE");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_CUTTING_API_MISMATCH"); }
    }
    public static void callbacks(RecipeHolder<?> holder, ServerPlayer player) {
        require(holder, player.serverLevel());
        ItemStack virtual = tool(holder);
        player.awardStat(Stats.ITEM_USED.get(virtual.getItem()));
        try {
            Class<?> registry = Class.forName("vectorwing.farmersdelight.common.registry.ModAdvancements", false,
                    FdCuttingProcessingAdapter.class.getClassLoader());
            Field field = registry.getField("USE_CUTTING_BOARD");
            if (field.getType() != Supplier.class || !Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException();
            Object trigger = ((Supplier<?>) field.get(null)).get();
            Class<?> type = Class.forName("vectorwing.farmersdelight.common.advancement.CuttingBoardTrigger", false,
                    FdCuttingProcessingAdapter.class.getClassLoader());
            if (!type.isInstance(trigger)) throw new IllegalStateException("FD_CUTTING_ADVANCEMENT_MISMATCH");
            type.getMethod("trigger", ServerPlayer.class).invoke(trigger, player);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_CUTTING_CALLBACK_UNCERTAIN"); }
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value())) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!TYPE.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalStateException("FD_CUTTING_SERIALIZER_MISMATCH");
    }
}
