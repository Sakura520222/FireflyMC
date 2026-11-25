package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.fluids.FluidStack;

/** Potion-component and recycling fallbacks from the installed cauldron's native interaction chain. */
public final class IronCauldronMechanisms {
    private static final String POTION = "io.redspace.ironsspellbooks.fluids.PotionFluid";
    private static final String TILE = "io.redspace.ironsspellbooks.block.alchemist_cauldron.AlchemistCauldronTile";
    private static final String VERSION = "irons_spellbooks@1.21.1-3.16.2";
    public record ScrollPreview(MaterialStack scroll, FluidMaterial requiredWater, FluidMaterial possibleInk, double chance) {}
    private IronCauldronMechanisms() {}

    public static VerifiedFluidMechanism pourPotion(ItemStack supplied, ServerLevel level) {
        require(level);
        ItemStack input = realUnit(supplied, level);
        if (!input.has(DataComponents.POTION_CONTENTS)) throw new IllegalArgumentException("CAULDRON_POTION_CONTENTS_REQUIRED");
        rejectRecipePrecedence("fill", new SingleRecipeInput(input), level);
        var contents = input.get(DataComponents.POTION_CONTENTS);
        FluidStack output = contents.is(Potions.WATER) ? new FluidStack(Fluids.WATER, 250) : potionFluid(input);
        var fluid = FluidMaterial.capture(output, level.registryAccess());
        return result("potion_to_fluid", input, List.of(), List.of(), List.of(new ItemStack(Items.GLASS_BOTTLE)), List.of(fluid), level);
    }

    public static VerifiedFluidMechanism bottlePotion(ItemStack suppliedBottle, FluidStack suppliedFluid, ServerLevel level) {
        require(level);
        ItemStack bottle = realUnit(suppliedBottle, level);
        if (!bottle.is(Items.GLASS_BOTTLE)) throw new IllegalArgumentException("CAULDRON_REAL_BOTTLE_REQUIRED");
        FluidStack fluid = FluidMaterial.isolate(suppliedFluid, level.registryAccess());
        if (fluid.getAmount() < 250) throw new IllegalArgumentException("CAULDRON_RAW_FLUID_MISSING");
        RecipeInput input = (RecipeInput) SpecialNative.create("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.EmptyAlchemistCauldronRecipe$Input",
                new Class<?>[]{ItemStack.class, FluidStack.class}, bottle, fluid);
        rejectRecipePrecedence("empty", input, level);
        ItemStack output = potionItem(fluid);
        if (output.isEmpty()) throw new IllegalArgumentException("CAULDRON_FLUID_NOT_POTION");
        FluidMaterial exact = FluidMaterial.capture(fluid.copyWithAmount(250), level.registryAccess());
        return result("fluid_to_potion", bottle, List.of(exact), List.of(exact), List.of(output), List.of(), level);
    }

    public static VerifiedFluidMechanism brew(ItemStack suppliedReagent, List<FluidStack> suppliedFluids, ServerLevel level) {
        require(level);
        if (!(boolean) config("ALLOW_CAULDRON_BREWING")) throw new IllegalArgumentException("CAULDRON_BREWING_DISABLED");
        ItemStack reagent = realUnit(suppliedReagent, level);
        if (!level.potionBrewing().isIngredient(reagent)) throw new IllegalArgumentException("CAULDRON_BREWING_REAGENT_INVALID");
        if (suppliedFluids.isEmpty() || suppliedFluids.size() > 4) throw new IllegalArgumentException("CAULDRON_NATIVE_BATCH_LIMIT");
        var inputs = new ArrayList<FluidMaterial>(); var outputs = new ArrayList<FluidMaterial>();
        for (FluidStack supplied : suppliedFluids) {
            FluidStack fluid = FluidMaterial.isolate(supplied, level.registryAccess());
            // Native has four 1000-mB tanks; device exemption cannot turn one reagent into an unbounded batch.
            if (fluid.getAmount() < 250 || fluid.getAmount() > 1000) throw new IllegalArgumentException("CAULDRON_NATIVE_BATCH_LIMIT");
            RecipeInput nativeInput = (RecipeInput) SpecialNative.create("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe$Input",
                    new Class<?>[]{FluidStack.class, ItemStack.class}, fluid, reagent);
            rejectRecipePrecedence("brew", nativeInput, level);
            ItemStack potion = potionItem(fluid);
            if (potion.isEmpty() || !level.potionBrewing().hasPotionMix(potion, reagent) && !level.potionBrewing().hasContainerMix(potion, reagent))
                throw new IllegalArgumentException("CAULDRON_POTION_MIX_MISMATCH");
            ItemStack mixed = level.potionBrewing().mix(reagent, potion);
            FluidStack transformed = potionFluid(mixed);
            if (transformed.isEmpty()) throw new IllegalArgumentException("CAULDRON_POTION_RESULT_INVALID");
            transformed.setAmount(fluid.getAmount());
            inputs.add(FluidMaterial.capture(fluid, level.registryAccess())); outputs.add(FluidMaterial.capture(transformed, level.registryAccess()));
        }
        return result("cauldron_brewing", reagent, inputs, inputs, List.of(), outputs, level);
    }

    public static ScrollPreview previewScroll(ItemStack suppliedScroll, FluidStack suppliedWater, ServerLevel level) {
        require(level);
        ItemStack scroll = realUnit(suppliedScroll, level);
        if (!BuiltInRegistries.ITEM.getKey(scroll.getItem()).toString().equals("irons_spellbooks:scroll"))
            throw new IllegalArgumentException("CAULDRON_REAL_SCROLL_REQUIRED");
        FluidStack water = FluidMaterial.isolate(suppliedWater, level.registryAccess());
        // Native condition accepts WATER tag, but the actual drain requests plain vanilla WATER.
        // Do not treat an unconsumed tagged substitute as 250 mB of paid water.
        if (!FluidStack.isSameFluidSameComponents(water, new FluidStack(Fluids.WATER, 250)) || water.getAmount() < 250)
            throw new IllegalArgumentException("CAULDRON_REAL_WATER_REQUIRED");
        double chance = (double) config("SCROLL_RECYCLE_CHANCE");
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("CAULDRON_SCROLL_CHANCE_INVALID");
        try {
            Class<?> inkClass = Class.forName("io.redspace.ironsspellbooks.item.InkItem", false, IronCauldronMechanisms.class.getClassLoader());
            Object ink = SpecialNative.callStatic(TILE, "getInkFromScroll", inkClass, new Class<?>[]{ItemStack.class}, scroll);
            @SuppressWarnings("unchecked") Holder<Fluid> type = (Holder<Fluid>) SpecialNative.get(ink, "fluid", Holder.class);
            return new ScrollPreview(ItemSnapshotCodec.capture(scroll, level.registryAccess()),
                    FluidMaterial.capture(new FluidStack(Fluids.WATER, 250), level.registryAccess()),
                    FluidMaterial.capture(new FluidStack(type, 250), level.registryAccess()), chance);
        } catch (ClassNotFoundException failure) { throw new IllegalStateException("CAULDRON_SCROLL_API_MISMATCH", failure); }
    }

    public static VerifiedFluidMechanism sampleScroll(ItemStack scroll, FluidStack water, long seed, ServerLevel level) {
        ScrollPreview preview = previewScroll(scroll, water, level);
        boolean success = RandomSource.create(seed).nextFloat() < preview.chance();
        return new VerifiedFluidMechanism(SpecialNative.id("irons_spellbooks:scroll_recycling"), VERSION,
                List.of(preview.scroll()), List.of(preview.requiredWater()), success ? List.of(preview.requiredWater()) : List.of(),
                List.of(), success ? List.of(preview.possibleInk()) : List.of(), true, seed, preview.chance(),
                Map.of("sampling", "RandomSource.nextFloat<SCROLL_RECYCLE_CHANCE", "failure", "scroll consumed; water unchanged",
                        "stationRequired", "false", "waitTicks", "0"));
    }

    private static VerifiedFluidMechanism result(String id, ItemStack input, List<FluidMaterial> required, List<FluidMaterial> consumed,
                                                List<ItemStack> items, List<FluidMaterial> fluids, ServerLevel level) {
        return new VerifiedFluidMechanism(SpecialNative.id("irons_spellbooks:" + id), VERSION,
                List.of(ItemSnapshotCodec.capture(input, level.registryAccess())), required, consumed,
                items.stream().map(s -> ItemSnapshotCodec.capture(SpecialNative.stack(s, level, false), level.registryAccess())).toList(),
                fluids, false, 0, 1, Map.of("stationRequired", "false", "waitTicks", "0", "evidence", "AlchemistCauldronTile native fallback"));
    }
    private static Object config(String field) { return ((ModConfigSpec.ConfigValue<?>) SpecialNative.field("io.redspace.ironsspellbooks.config.ServerConfigs", field)).get(); }
    private static ItemStack realUnit(ItemStack supplied, ServerLevel level) {
        if (supplied == null || supplied.isEmpty()) throw new IllegalArgumentException("CAULDRON_REAL_ITEM_REQUIRED");
        return RecipeInputIsolation.copy(supplied.copyWithCount(1), level.registryAccess());
    }
    private static FluidStack potionFluid(ItemStack stack) { return (FluidStack) SpecialNative.callStatic(POTION, "from", FluidStack.class, new Class<?>[]{ItemStack.class}, stack); }
    private static ItemStack potionItem(FluidStack stack) { return (ItemStack) SpecialNative.callStatic(POTION, "from", ItemStack.class, new Class<?>[]{FluidStack.class}, stack); }
    private static void require(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!SpecialNative.version("irons_spellbooks", "1.21.1-3.16.2")) throw new IllegalArgumentException("UNSUPPORTED_FLUID_PROCESSING_ADAPTER");
    }
    private static void rejectRecipePrecedence(String suffix, RecipeInput input, ServerLevel level) {
        var type = BuiltInRegistries.RECIPE_TYPE.get(SpecialNative.id("irons_spellbooks:alchemist_cauldron_" + suffix));
        if (type == null) throw new IllegalStateException("CAULDRON_RECIPE_TYPE_MISSING");
        @SuppressWarnings({"rawtypes", "unchecked"}) List<RecipeHolder<?>> holders = (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        if (holders.size() > 1024) throw new IllegalArgumentException("CAULDRON_RECIPE_SEARCH_LIMIT");
        for (RecipeHolder<?> holder : holders) {
            @SuppressWarnings("unchecked") Recipe<RecipeInput> recipe = (Recipe<RecipeInput>) holder.value();
            if (recipe.matches(input, level)) throw new IllegalArgumentException("CAULDRON_EXPLICIT_RECIPE_TAKES_PRECEDENCE");
        }
    }
}
