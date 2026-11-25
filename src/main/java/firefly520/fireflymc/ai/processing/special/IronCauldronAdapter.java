package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.fluids.FluidStack;

/** Iron's Spellbooks 1.21.1-3.16.2's actual fill/empty/brew transactions, with real mB quantities. */
public final class IronCauldronAdapter {
    public enum Kind { FILL, EMPTY, BREW }
    private static final String PREFIX = "io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.";
    private IronCauldronAdapter() {}

    public static Kind kind(Recipe<?> recipe) {
        if (recipe == null || !SpecialNative.version("irons_spellbooks", "1.21.1-3.16.2")) return null;
        Kind kind = switch (recipe.getClass().getName()) {
            case PREFIX + "FillAlchemistCauldronRecipe" -> Kind.FILL;
            case PREFIX + "EmptyAlchemistCauldronRecipe" -> Kind.EMPTY;
            case PREFIX + "BrewAlchemistCauldronRecipe" -> Kind.BREW;
            default -> null;
        };
        if (kind == null) return null;
        var id = SpecialNative.id("irons_spellbooks:alchemist_cauldron_" + kind.name().toLowerCase(java.util.Locale.ROOT));
        return id.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && id.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())) ? kind : null;
    }

    public static Ingredient itemIngredient(RecipeHolder<?> holder, ServerLevel level) {
        Kind kind = require(holder, level);
        return (Ingredient) SpecialNative.get(holder.value(), kind == Kind.BREW ? "reagent" : "input", Ingredient.class);
    }

    public static Optional<FluidMaterial> fluidIngredient(RecipeHolder<?> holder, ServerLevel level) {
        Kind kind = require(holder, level);
        if (kind == Kind.FILL) return Optional.empty();
        return Optional.of(FluidMaterial.capture((FluidStack) SpecialNative.get(holder.value(), kind == Kind.BREW ? "fluidIn" : "fluid", FluidStack.class), level.registryAccess()));
    }

    public static VerifiedFluidTransformation verify(RecipeHolder<?> holder, ItemStack suppliedItem, FluidStack suppliedFluid, ServerLevel level) {
        Kind kind = require(holder, level);
        if (suppliedItem == null || suppliedItem.isEmpty()) throw new IllegalArgumentException("CAULDRON_REAL_ITEM_REQUIRED");
        ItemStack item = RecipeInputIsolation.copy(suppliedItem.copyWithCount(1), level.registryAccess());
        if (!itemIngredient(holder, level).test(item)) throw new IllegalArgumentException("CAULDRON_ITEM_MISMATCH");
        var required = fluidIngredient(holder, level);
        FluidStack fluid = FluidStack.EMPTY;
        if (required.isPresent()) {
            fluid = FluidMaterial.isolate(suppliedFluid, level.registryAccess());
            FluidStack exact = required.orElseThrow().restore(level.registryAccess());
            // Native brew.matches only checks identity; the device performs this amount check separately.
            if (!FluidStack.isSameFluidSameComponents(fluid, exact) || fluid.getAmount() < exact.getAmount())
                throw new IllegalArgumentException("CAULDRON_RAW_FLUID_MISSING");
            fluid.setAmount(exact.getAmount());
        } else if (suppliedFluid != null && !suppliedFluid.isEmpty()) throw new IllegalArgumentException("CAULDRON_UNEXPECTED_FLUID_INPUT");
        RecipeInput input = switch (kind) {
            case FILL -> new SingleRecipeInput(item);
            case EMPTY -> (RecipeInput) SpecialNative.create(PREFIX + "EmptyAlchemistCauldronRecipe$Input", new Class<?>[]{ItemStack.class, FluidStack.class}, item, fluid);
            case BREW -> (RecipeInput) SpecialNative.create(PREFIX + "BrewAlchemistCauldronRecipe$Input", new Class<?>[]{FluidStack.class, ItemStack.class}, fluid, item);
        };
        @SuppressWarnings("unchecked") var recipe = (Recipe<RecipeInput>) holder.value();
        if (!recipe.matches(input, level)) throw new IllegalArgumentException("CAULDRON_NATIVE_MATCH_REJECTED");
        List<FluidMaterial> fluids;
        List<MaterialStack> items;
        if (kind == Kind.BREW) {
            // The device tests this branch before any static brew recipe, including datapack recipes.
            // Its WATER tag check deliberately ignores fluid components and starts at 250 mB.
            if (item.is(BuiltInRegistries.ITEM.get(SpecialNative.id("irons_spellbooks:scroll")))
                    && fluid.is(net.neoforged.neoforge.common.Tags.Fluids.WATER) && fluid.getAmount() >= 250)
                throw new IllegalArgumentException("CAULDRON_SCROLL_RECYCLING_TAKES_PRECEDENCE");
            List<?> nativeFluids = (List<?>) SpecialNative.get(recipe, "results", List.class);
            if (nativeFluids.size() > 16 || nativeFluids.stream().anyMatch(f -> !(f instanceof FluidStack)))
                throw new IllegalArgumentException("CAULDRON_OUTPUT_LIMIT");
            fluids = nativeFluids.stream().map(f -> FluidMaterial.capture((FluidStack) f, level.registryAccess())).toList();
            Optional<?> byproduct = (Optional<?>) SpecialNative.get(recipe, "byproduct", Optional.class);
            if (byproduct.isPresent() && !(byproduct.get() instanceof ItemStack)) throw new IllegalArgumentException("CAULDRON_BYPRODUCT_INVALID");
            ItemStack nativeByproduct = byproduct.isEmpty() ? ItemStack.EMPTY : SpecialNative.stack((ItemStack) byproduct.get(), level, true);
            // One isolated reagent leaves an empty native input slot. tryMeltInput puts split(1)
            // there, then drops another split(1), not the rest of the configured byproduct stack.
            // Both actual outputs stay in private custody here; no world drop is required.
            items = nativeByproduct.isEmpty() ? List.of() : captureItems(nativeByproduct.copyWithCount(Math.min(2, nativeByproduct.getCount())), level);
        } else {
            fluids = kind == Kind.FILL ? List.of(FluidMaterial.capture((FluidStack) SpecialNative.get(recipe, "result", FluidStack.class), level.registryAccess())) : List.of();
            items = captureItems(recipe.assemble(input, level.registryAccess()), level);
        }
        validateNativeBatch(required.stream().toList(), level);
        validateNativeBatch(fluids, level);
        return new VerifiedFluidTransformation(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()),
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()), "irons_spellbooks@1.21.1-3.16.2",
                List.of(ItemSnapshotCodec.capture(suppliedItem.copyWithCount(1), level.registryAccess())), required.stream().toList(), items, fluids,
                kind == Kind.FILL && !(boolean) SpecialNative.get(recipe, "mustFitAll", boolean.class),
                List.of("AlchemistCauldronTile#tryMeltInput", "AlchemistCauldronTile#tryExecuteRecipeInteractions", "All item/fluid outputs require reserved capacity; no overflow loss"));
    }

    private static void validateNativeBatch(List<FluidMaterial> fluids, ServerLevel level) {
        @SuppressWarnings("unchecked") var forbidden = (net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>)
                SpecialNative.field("io.redspace.ironsspellbooks.util.ModTags", "CAULDRON_FLUID_DISALLOW");
        long total = 0;
        var variants = new ArrayList<FluidStack>();
        for (var material : fluids) {
            FluidStack fluid = material.restore(level.registryAccess());
            if (fluid.is(forbidden)) throw new IllegalArgumentException("CAULDRON_FLUID_DISALLOWED");
            total = Math.addExact(total, fluid.getAmount());
            if (variants.stream().noneMatch(other -> FluidStack.isSameFluidSameComponents(other, fluid))) variants.add(fluid);
        }
        // Four variant slots share one 1000-mB capacity; they are not four independent buckets.
        if (total > 1000 || variants.size() > 4) throw new IllegalArgumentException("CAULDRON_NATIVE_BATCH_LIMIT");
    }

    private static List<MaterialStack> captureItems(ItemStack output, ServerLevel level) {
        ItemStack safe = SpecialNative.stack(output, level, true);
        return safe.isEmpty() ? List.of() : List.of(ItemSnapshotCodec.capture(safe, level.registryAccess()));
    }

    private static Kind require(RecipeHolder<?> holder, ServerLevel level) {
        SpecialNative.current(holder, level);
        Kind kind = kind(holder.value());
        if (kind == null) throw new IllegalArgumentException("UNSUPPORTED_FLUID_PROCESSING_ADAPTER");
        return kind;
    }
}
