package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.FluidProcessingAdapters;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiFluidGameTests {
    @GameTest(template = "story_empty", batch = "ai-fluid", timeoutTicks = 40)
    public static void actualBucketAdaptersUseExactRealInputsWithoutDevicesOrInventedContainers(GameTestHelper helper) {
        var level = helper.getLevel();
        for (String id : java.util.List.of("minecraft:water", "minecraft:lava")) {
            var bucket = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET, 2);
            var input = FluidProcessingAdapters.requiredFluid(null, FluidProcessingAdapters.BUCKET_FILL, id, level).orElseThrow();
            var filled = FluidProcessingAdapters.evaluate(null, FluidProcessingAdapters.BUCKET_FILL, id, bucket, input, level);
            helper.assertValueEqual(bucket.getCount(), 2, "Planning does not consume the native source stack");
            helper.assertValueEqual(filled.consumedItems().getFirst().count(), 1, "One actual container is required");
            helper.assertValueEqual(filled.consumedFluids(), java.util.List.of(input), "Exactly one native bucket volume is required");
            helper.assertTrue(filled.producedFluids().isEmpty() && filled.producedItems().size() == 1, "Filling does not invent a returned empty bucket");
            var actualFilled = ItemSnapshotCodec.restore(filled.producedItems().getFirst(), level.registryAccess());
            var emptied = FluidProcessingAdapters.evaluate(null, FluidProcessingAdapters.BUCKET_EMPTY, "", actualFilled, FluidAmount.EMPTY, level);
            helper.assertValueEqual(emptied.producedFluids(), java.util.List.of(input), "Native bucket fluid/components survive the reverse conversion");
            helper.assertTrue(emptied.consumedFluids().isEmpty(), "Decanting does not demand invented input fluid");
            helper.assertTrue(ItemSnapshotCodec.restore(emptied.producedItems().getFirst(), level.registryAccess()).is(net.minecraft.world.item.Items.BUCKET), "Only the actually consumed filled container returns an empty bucket");
            helper.assertValueEqual(filled.costPolicy(), "MATERIALS_ONLY", "No machine/fuel/energy/experience input is invented");
            helper.assertValueEqual(filled.referenceKind(), "mechanism", "Native capability operations are not fake recipe IDs");
        }
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-fluid", timeoutTicks = 40)
    public static void fluidAdapterRejectsMissingComponentsAmountsAndConflictingReferences(GameTestHelper helper) {
        var level = helper.getLevel(); var bucket = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET);
        var water = FluidProcessingAdapters.requiredFluid(null, FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", level).orElseThrow();
        boolean rejected = false;
        try { FluidProcessingAdapters.evaluate(null, FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", bucket, water.withAmount(999), level); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().equals("FLUID_EXACT_INPUT_REQUIRED"); }
        helper.assertTrue(rejected, "Missing raw fluid cannot be waived as processing fuel");
        rejected = false;
        try { FluidProcessingAdapters.evaluate(null, FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", net.minecraft.world.item.ItemStack.EMPTY, water, level); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().equals("FLUID_REAL_ITEM_REQUIRED"); }
        helper.assertTrue(rejected, "The recipe cannot fabricate a container");
        rejected = false;
        try { FluidProcessingAdapters.requiredFluid(null, FluidProcessingAdapters.BUCKET_EMPTY, "minecraft:water", level); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().equals("FLUID_OVERRIDE_NOT_APPLICABLE"); }
        helper.assertTrue(rejected, "Unused fluid override must not silently change the user's intent");
        var holder = level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace("crafting_table")).orElseThrow();
        rejected = false;
        try { FluidProcessingAdapters.itemIngredient(holder, FluidProcessingAdapters.BUCKET_FILL, level); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().equals("ONE_FLUID_PROCESSING_REFERENCE_REQUIRED"); }
        helper.assertTrue(rejected, "Only one fixed recipe or native mechanism reference is accepted");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-fluid", timeoutTicks = 40)
    public static void fluidCodecAndNativeDeltaPreserveComponentsAndMillibuckets(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var tank = new FluidTank(4000);
        var tag = new CompoundTag(); tag.putString("kind", "private-native-fluid");
        var water = new FluidStack(Fluids.WATER, 1500); water.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        helper.assertValueEqual(tank.fill(water, FluidAction.EXECUTE), 1500, "Native finite tank was actually filled");
        var before = FluidTankSnapshot.capture(tank, registries);
        var immutable = FluidSnapshotCodec.capture(water, registries);
        tank.drain(500, FluidAction.SIMULATE);
        helper.assertValueEqual(FluidTankSnapshot.capture(tank, registries), before, "Simulation did not debit fluid");
        var actual = FluidSnapshotCodec.capture(tank.drain(500, FluidAction.EXECUTE), registries);
        helper.assertTrue(before.verifies(FluidTankSnapshot.capture(tank, registries), actual, false), "Observed native mB delta is exact");
        helper.assertValueEqual(actual.millibuckets(), 500, "Real quantity is retained independently of the unit codec");
        helper.assertTrue(FluidStack.isSameFluidSameComponents(water, FluidSnapshotCodec.restore(immutable, registries)), "Component-bearing fluid roundtrips");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-fluid", timeoutTicks = 40)
    public static void transientComponentsAndNonDedicatedFluidServiceAreRefused(GameTestHelper helper) {
        var water = new FluidStack(Fluids.WATER, 1000);
        water.set(DataComponentType.<String>builder().networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8).build(), "unpersistable");
        boolean refused = false;
        try { FluidSnapshotCodec.capture(water, helper.getLevel().registryAccess()); }
        catch (IllegalArgumentException expected) { refused = true; }
        helper.assertTrue(refused, "A dropped component must not become an ordinary fluid ingredient");
        helper.assertTrue(!helper.getLevel().getServer().isDedicatedServer(), "GameTest server is not a dedicated player server");
        refused = false;
        try { new FluidInventoryService(helper.getLevel().getServer(), null, null); }
        catch (IllegalStateException expected) { refused = expected.getMessage().equals("AI_DEDICATED_SERVER_REQUIRED"); }
        helper.assertTrue(refused, "Environment gate runs before services, native observers or workspace access");
        helper.succeed();
    }
}
