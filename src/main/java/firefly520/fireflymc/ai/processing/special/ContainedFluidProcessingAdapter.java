package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.RecipeReference;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;

/**
 * A closed material chain: real filled containers -> exact raw fluids -> selected Iron recipe ->
 * real output containers. No fluid is invented, discarded, or left in an untracked virtual tank.
 */
public final class ContainedFluidProcessingAdapter {
    public static final String ID = "irons_spellbooks:contained_cauldron";
    private ContainedFluidProcessingAdapter() {}
    private record Decanted(FluidStack fluid, List<ItemStack> returned) {}
    private record Bottled(int consumedMb, ItemStack output) {}

    /** Role0=reagent/bottle being processed; 1..4=raw-fluid containers; 5..9=extra empty output containers. */
    public static VerifiedTransformation verify(RecipeHolder<?> target, List<ItemStack> supplied, ServerLevel level) {
        SpecialNative.current(target, level);
        IronCauldronAdapter.Kind kind = IronCauldronAdapter.kind(target.value());
        if (kind == null || supplied == null || supplied.size() != 10 || supplied.getFirst().isEmpty()
                || supplied.getFirst().getCount() > 16) throw new IllegalArgumentException("CONTAINED_FLUID_INPUT_INVALID");
        var inputs = RecipeInputIsolation.copyAll(supplied, level.registryAccess());
        if (inputs.stream().mapToInt(ItemStack::getCount).sum() > 128) throw new IllegalArgumentException("CONTAINED_FLUID_MATERIAL_LIMIT");
        var refs = new LinkedHashMap<String, RecipeReference>(); ref(refs, target);
        var outputs = new ArrayList<ItemStack>(); var containers = new ArrayList<ItemStack>(); var fluids = new ArrayList<FluidStack>();
        int operations = inputs.getFirst().getCount();
        FluidStack required = IronCauldronAdapter.fluidIngredient(target, level).map(s -> s.restore(level.registryAccess())).orElse(FluidStack.EMPTY);
        long totalRequired = required.isEmpty() ? 0 : Math.multiplyExact((long) required.getAmount(), operations);
        if (totalRequired > 16_000) throw new IllegalArgumentException("CONTAINED_FLUID_BATCH_LIMIT");
        long rawMb = 0;
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack source = inputs.get(slot);
            if (source.isEmpty()) continue;
            if (required.isEmpty()) throw new IllegalArgumentException("CONTAINED_FLUID_UNNEEDED_SOURCE");
            for (int count = 0; count < source.getCount(); count++) {
                if (available(fluids, required) >= totalRequired) { outputs.add(source.copyWithCount(source.getCount() - count)); break; }
                Decanted decanted = decant(source.copyWithCount(1), level, refs);
                if (!FluidStack.isSameFluidSameComponents(decanted.fluid(), required)) throw new IllegalArgumentException("CONTAINED_FLUID_SOURCE_COMPONENT_MISMATCH");
                rawMb = Math.addExact(rawMb, decanted.fluid().getAmount());
                if (rawMb > 16_000 || containers.size() > 64) throw new IllegalArgumentException("CONTAINED_FLUID_BATCH_LIMIT");
                add(fluids, decanted.fluid(), level); containers.addAll(decanted.returned());
            }
        }
        if (available(fluids, required) < totalRequired) throw new IllegalArgumentException("CONTAINED_RAW_FLUID_MISSING");
        for (int slot = 5; slot < 10; slot++) if (!inputs.get(slot).isEmpty()) containers.add(RecipeInputIsolation.copy(inputs.get(slot), level.registryAccess()));
        var transformedFluids = new ArrayList<FluidStack>();
        for (int operation = 0; operation < operations; operation++) {
            FluidStack actual = required.isEmpty() ? FluidStack.EMPTY : remove(fluids, required, level);
            var transformed = IronCauldronAdapter.verify(target, inputs.getFirst().copyWithCount(1), actual, level);
            outputs.addAll(transformed.restoreItemOutputs(level));
            for (var output : transformed.producedFluids()) add(transformedFluids, output.restore(level.registryAccess()), level);
        }
        // Returned containers from fill recipes are real outputs and may immediately serve as recipient containers.
        if (kind == IronCauldronAdapter.Kind.FILL) { containers.addAll(outputs); outputs.clear(); }
        for (var fluid : transformedFluids) add(fluids, fluid, level);
        int iterations = 0;
        for (FluidStack fluid : fluids) {
            while (!fluid.isEmpty()) {
                if (++iterations > 64) throw new IllegalArgumentException("CONTAINED_FLUID_OUTPUT_LIMIT");
                boolean inserted = false;
                for (int index = 0; index < containers.size(); index++) {
                    ItemStack container = containers.get(index); if (container.isEmpty()) continue;
                    Bottled bottled = bottle(container.copyWithCount(1), fluid, level, refs);
                    if (bottled == null) continue;
                    if (bottled.consumedMb() <= 0 || bottled.consumedMb() > fluid.getAmount()) throw new IllegalStateException("CONTAINED_FLUID_CONSERVATION_FAILED");
                    fluid.shrink(bottled.consumedMb()); container.shrink(1); outputs.add(bottled.output()); inserted = true; break;
                }
                if (!inserted) throw new IllegalArgumentException("CONTAINED_FLUID_OUTPUT_CONTAINER_MISSING");
            }
        }
        containers.stream().filter(s -> !s.isEmpty()).forEach(outputs::add);
        if (outputs.isEmpty() || outputs.size() > 128) throw new IllegalArgumentException("CONTAINED_FLUID_OUTPUT_LIMIT");
        return new Result(target, inputs, outputs, List.copyOf(refs.values()), operations, rawMb, level);
    }

    private static Decanted decant(ItemStack source, ServerLevel level, Map<String, RecipeReference> refs) {
        RecipeHolder<?> fill = match("fill", new SingleRecipeInput(source), level);
        if (fill != null) {
            var result = IronCauldronAdapter.verify(fill, source, FluidStack.EMPTY, level); ref(refs, fill);
            if (result.producedFluids().size() != 1) throw new IllegalArgumentException("CONTAINED_FILL_SHAPE_INVALID");
            return new Decanted(result.producedFluids().getFirst().restore(level.registryAccess()), result.restoreItemOutputs(level));
        }
        if (source.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)) {
            var result = IronCauldronMechanisms.pourPotion(source, level);
            return new Decanted(result.producedFluids().getFirst().restore(level.registryAccess()), result.producedItems().stream().map(s -> ItemSnapshotCodec.restore(s, level.registryAccess())).toList());
        }
        if (source.getItem().getClass() == BucketItem.class && !source.is(Items.BUCKET)) {
            var wrapper = new FluidBucketWrapper(RecipeInputIsolation.copy(source, level.registryAccess()));
            FluidStack actual = wrapper.getFluidInTank(0);
            if (actual.isEmpty() || actual.getAmount() > 1000) throw new IllegalArgumentException("CONTAINED_BUCKET_NOT_FINITE");
            FluidStack before = FluidMaterial.isolate(actual, level.registryAccess());
            FluidStack drained = wrapper.drain(before.getAmount(), FluidAction.EXECUTE);
            if (!FluidStack.matches(before, drained) || !wrapper.getFluidInTank(0).isEmpty()) throw new IllegalStateException("CONTAINED_BUCKET_DRAIN_MISMATCH");
            return new Decanted(FluidMaterial.isolate(drained, level.registryAccess()), List.of(SpecialNative.stack(wrapper.getContainer(), level, false)));
        }
        throw new IllegalArgumentException("CONTAINED_FLUID_SOURCE_UNSUPPORTED");
    }

    private static Bottled bottle(ItemStack container, FluidStack fluid, ServerLevel level, Map<String, RecipeReference> refs) {
        RecipeInput input = (RecipeInput) SpecialNative.create("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.EmptyAlchemistCauldronRecipe$Input",
                new Class<?>[]{ItemStack.class, FluidStack.class}, container, FluidMaterial.isolate(fluid, level.registryAccess()));
        RecipeHolder<?> empty = match("empty", input, level);
        if (empty != null) {
            var result = IronCauldronAdapter.verify(empty, container, fluid, level); ref(refs, empty);
            if (result.producedItems().size() != 1 || result.consumedFluids().size() != 1) throw new IllegalArgumentException("CONTAINED_EMPTY_SHAPE_INVALID");
            return new Bottled(result.consumedFluids().getFirst().amount(), result.restoreItemOutputs(level).getFirst());
        }
        if (container.is(Items.GLASS_BOTTLE) && fluid.getAmount() >= 250) {
            try {
                var result = IronCauldronMechanisms.bottlePotion(container, fluid, level);
                return new Bottled(result.consumedFluids().getFirst().amount(), ItemSnapshotCodec.restore(result.producedItems().getFirst(), level.registryAccess()));
            } catch (IllegalArgumentException noPotion) {
                if (!"CAULDRON_FLUID_NOT_POTION".equals(noPotion.getMessage())) throw noPotion;
            }
        }
        if (container.is(Items.BUCKET) && fluid.getAmount() >= 1000) {
            var wrapper = new FluidBucketWrapper(RecipeInputIsolation.copy(container, level.registryAccess()));
            FluidStack portion = FluidMaterial.isolate(fluid.copyWithAmount(1000), level.registryAccess());
            int inserted = wrapper.fill(portion, FluidAction.EXECUTE);
            if (inserted == 0) return null;
            if (inserted != 1000 || !FluidStack.matches(portion, wrapper.getFluidInTank(0))) throw new IllegalStateException("CONTAINED_BUCKET_FILL_COMPONENT_MISMATCH");
            return new Bottled(inserted, SpecialNative.stack(wrapper.getContainer(), level, false));
        }
        return null;
    }

    private static RecipeHolder<?> match(String suffix, RecipeInput input, ServerLevel level) {
        var type = BuiltInRegistries.RECIPE_TYPE.get(SpecialNative.id("irons_spellbooks:alchemist_cauldron_" + suffix));
        if (type == null) throw new IllegalArgumentException("CONTAINED_RECIPE_TYPE_UNAVAILABLE");
        @SuppressWarnings({"unchecked", "rawtypes"}) List<RecipeHolder<?>> recipes = (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        if (recipes.size() > 512) throw new IllegalArgumentException("CONTAINED_RECIPE_SEARCH_LIMIT");
        for (RecipeHolder<?> holder : recipes) {
            @SuppressWarnings("unchecked") Recipe<RecipeInput> recipe = (Recipe<RecipeInput>) holder.value();
            if (recipe.matches(input, level)) {
                if (IronCauldronAdapter.kind(recipe) == null) throw new IllegalArgumentException("CONTAINED_RECIPE_CLASS_UNREVIEWED");
                return holder;
            }
        }
        return null;
    }
    private static long available(List<FluidStack> fluids, FluidStack needed) { return needed.isEmpty() ? 0 : fluids.stream().filter(f -> FluidStack.isSameFluidSameComponents(f, needed)).mapToLong(FluidStack::getAmount).sum(); }
    private static FluidStack remove(List<FluidStack> fluids, FluidStack needed, ServerLevel level) {
        int left = needed.getAmount();
        for (FluidStack fluid : fluids) if (FluidStack.isSameFluidSameComponents(fluid, needed)) { int take = Math.min(left, fluid.getAmount()); fluid.shrink(take); left -= take; }
        if (left != 0) throw new IllegalStateException("CONTAINED_RAW_FLUID_MISSING");
        return FluidMaterial.isolate(needed, level.registryAccess());
    }
    private static void add(List<FluidStack> fluids, FluidStack incoming, ServerLevel level) {
        if (incoming == null || incoming.isEmpty()) throw new IllegalArgumentException("CONTAINED_EMPTY_NATIVE_FLUID");
        fluids.removeIf(FluidStack::isEmpty);
        for (FluidStack fluid : fluids) if (!fluid.isEmpty() && FluidStack.isSameFluidSameComponents(fluid, incoming)) { fluid.setAmount(Math.addExact(fluid.getAmount(), incoming.getAmount())); return; }
        if (fluids.size() >= 16) throw new IllegalArgumentException("CONTAINED_FLUID_VARIANT_LIMIT");
        fluids.add(FluidMaterial.isolate(incoming, level.registryAccess()));
    }
    private static void ref(Map<String, RecipeReference> refs, RecipeHolder<?> holder) { refs.put(holder.id().toString(), new RecipeReference(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()))); }
    private static final class Result extends VerifiedTransformation {
        private final List<RecipeReference> refs;
        Result(RecipeHolder<?> target, List<ItemStack> input, List<ItemStack> output, List<RecipeReference> refs, int count, long raw, ServerLevel level) {
            super(null, null, null, ID + "@1.21.1-3.16.2", input, input.stream().map(ItemStack::getCount).toList(), output,
                    Collections.nCopies(input.size(), ItemStack.EMPTY), new ExperienceReward(0, 1), new CallbackPlan("NO_CALLBACK", List.of(),
                    List.of("Iron native fill/brew/empty recipe chain", "FluidBucketWrapper exact finite transfers"), false), SamplingEvidence.deterministic(),
                    Map.of("targetRecipe", target.id().toString(), "targetOperations", Integer.toString(count), "rawInputMb", Long.toString(raw),
                            "untrackedFluidMb", "0", "stationRequired", "false", "waitTicks", "0"), level.registryAccess());
            this.refs = refs;
        }
        @Override public List<RecipeReference> backingRecipes() { return refs; }
    }
}
