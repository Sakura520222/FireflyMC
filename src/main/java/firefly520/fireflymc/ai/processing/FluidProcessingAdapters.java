package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.special.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;

/** Fixed native item/fluid contracts. Calculation touches detached components, never real inventories. */
public final class FluidProcessingAdapters {
    public static final String BUCKET_FILL = "fireflymc:bucket_fill";
    public static final String BUCKET_EMPTY = "fireflymc:bucket_empty";
    public record Evaluation(String referenceKind, String referenceId, String adapterVersion,
                             List<MaterialStack> consumedItems, List<FluidAmount> consumedFluids,
                             List<MaterialStack> producedItems, List<FluidAmount> producedFluids) {
        public Evaluation {
            consumedItems = List.copyOf(consumedItems); consumedFluids = List.copyOf(consumedFluids);
            producedItems = List.copyOf(producedItems); producedFluids = List.copyOf(producedFluids);
            if (!Set.of("recipe", "mechanism").contains(referenceKind) || referenceId == null || referenceId.isBlank()
                    || adapterVersion == null || adapterVersion.isBlank() || consumedItems.isEmpty() || consumedItems.size() > 9
                    || consumedFluids.size() > 16 || producedItems.size() > 16 || producedFluids.size() > 16
                    || producedItems.isEmpty() && producedFluids.isEmpty()) throw new IllegalArgumentException("FLUID_EVALUATION_INVALID");
        }
        public String digest() { return AiJson.digest(AiJson.canonical(this)); }
        public String costPolicy() { return "MATERIALS_ONLY"; }
    }
    private FluidProcessingAdapters() {}
    public static Ingredient itemIngredient(RecipeHolder<?> recipe, String mechanism, ServerLevel level) {
        thread(level); mechanism = reference(recipe, mechanism);
        if (recipe != null) return IronCauldronAdapter.itemIngredient(recipe, level);
        return switch (mechanism) {
            case BUCKET_FILL -> Ingredient.of(Items.BUCKET);
            case BUCKET_EMPTY -> Ingredient.of(Items.WATER_BUCKET, Items.LAVA_BUCKET);
            default -> throw new IllegalArgumentException("FLUID_MECHANISM_UNSUPPORTED");
        };
    }
    public static Optional<FluidAmount> requiredFluid(RecipeHolder<?> recipe, String mechanism, String fluidId, ServerLevel level) {
        thread(level); mechanism = reference(recipe, mechanism); fluidId = Objects.requireNonNullElse(fluidId, "");
        if (recipe != null || mechanism.equals(BUCKET_EMPTY)) {
            if (!fluidId.isEmpty()) throw new IllegalArgumentException("FLUID_OVERRIDE_NOT_APPLICABLE");
            return recipe == null ? Optional.empty() : IronCauldronAdapter.fluidIngredient(recipe, level)
                    .map(fluid -> FluidSnapshotCodec.capture(fluid.restore(level.registryAccess()), level.registryAccess()));
        }
        if (!mechanism.equals(BUCKET_FILL)) throw new IllegalArgumentException("FLUID_MECHANISM_UNSUPPORTED");
        var fluid = switch (fluidId) {
            case "minecraft:water" -> Fluids.WATER;
            case "minecraft:lava" -> Fluids.LAVA;
            default -> throw new IllegalArgumentException("VANILLA_BUCKET_FLUID_REQUIRED");
        };
        return Optional.of(FluidSnapshotCodec.capture(new FluidStack(fluid, 1000), level.registryAccess()));
    }
    public static Evaluation evaluate(RecipeHolder<?> recipe, String mechanism, String fluidId, ItemStack realItem,
                                      FluidAmount suppliedFluid, ServerLevel level) {
        thread(level);
        if (realItem == null || realItem.isEmpty()) throw new IllegalArgumentException("FLUID_REAL_ITEM_REQUIRED");
        ItemStack input = RecipeInputIsolation.copy(realItem.copyWithCount(1), level.registryAccess());
        if (!itemIngredient(recipe, mechanism, level).test(input)) throw new IllegalArgumentException("FLUID_ITEM_MISMATCH");
        var required = requiredFluid(recipe, mechanism, fluidId, level);
        if (required.isPresent() ? !required.get().equals(suppliedFluid) : suppliedFluid != null && !suppliedFluid.empty())
            throw new IllegalArgumentException("FLUID_EXACT_INPUT_REQUIRED");
        if (recipe != null) {
            var actual = IronCauldronAdapter.verify(recipe, input, required.isEmpty() ? FluidStack.EMPTY
                    : FluidSnapshotCodec.restore(suppliedFluid, level.registryAccess()), level);
            return new Evaluation("recipe", actual.recipeId().toString(), actual.adapterVersion(), actual.consumedItems(),
                    actual.consumedFluids().stream().map(fluid -> FluidSnapshotCodec.capture(fluid.restore(level.registryAccess()), level.registryAccess())).toList(),
                    actual.producedItems(), actual.producedFluids().stream().map(fluid -> FluidSnapshotCodec.capture(fluid.restore(level.registryAccess()), level.registryAccess())).toList());
        }
        var capability = input.getCapability(Capabilities.FluidHandler.ITEM);
        if (capability == null || capability.getClass() != FluidBucketWrapper.class)
            throw new IllegalArgumentException("VANILLA_BUCKET_CAPABILITY_CHANGED");
        var before = ItemSnapshotCodec.capture(input, level.registryAccess());
        List<FluidAmount> produced;
        if (mechanism.equals(BUCKET_FILL)) {
            var fluid = FluidSnapshotCodec.restore(suppliedFluid, level.registryAccess());
            if (capability.fill(fluid, FluidAction.EXECUTE) != 1000 || !FluidStack.matches(capability.getFluidInTank(0), fluid))
                throw new IllegalStateException("NATIVE_BUCKET_FILL_NOT_EXACT");
            produced = List.of();
        } else {
            var fluid = capability.drain(1000, FluidAction.EXECUTE);
            if (fluid.getAmount() != 1000 || !capability.getFluidInTank(0).isEmpty() || !capability.getContainer().is(Items.BUCKET))
                throw new IllegalStateException("NATIVE_BUCKET_EMPTY_NOT_EXACT");
            produced = List.of(FluidSnapshotCodec.capture(fluid, level.registryAccess()));
        }
        MaterialStack output = ItemSnapshotCodec.capture(capability.getContainer(), level.registryAccess());
        if (output.count() != 1) throw new IllegalStateException("NATIVE_BUCKET_CONTAINER_COUNT_CHANGED");
        return new Evaluation("mechanism", mechanism, "minecraft@1.21.1/neoforge-fluid-bucket@1",
                List.of(before), required.stream().toList(), List.of(output), produced);
    }
    private static void thread(ServerLevel level) {
        if (level == null || !level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
    }
    private static String reference(RecipeHolder<?> recipe, String mechanism) {
        String value = Objects.requireNonNullElse(mechanism, "");
        if (recipe == null && value.isEmpty() || recipe != null && !value.isEmpty())
            throw new IllegalArgumentException("ONE_FLUID_PROCESSING_REFERENCE_REQUIRED");
        return value;
    }
}
