package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VanillaProcessingKernel;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.nio.file.*;
import java.util.*;

/** Real ItemStack handlers, registered recipes and forced journals in the isolated GameTest gameDir. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiMaterialTransactionGameTests {
    private static MaterialLedger ledger() throws Exception {
        Path folder = Path.of("ai4-test-ledgers").toAbsolutePath();
        Files.createDirectories(folder);
        return new MaterialLedger(Files.createTempDirectory(folder, "case-"));
    }
    private static Actor actor() { return Actor.player(UUID.randomUUID(), true, true, 0); }
    private static MaterialLedger.Stock stock(MaterialStack stack) {
        return new MaterialLedger.Stock("test-source", 1, "physical-chest", "physical-slot", 0, stack, 1, stack.count(), stack.count());
    }
    @GameTest(template = "story_empty", batch = "ai-material-transactions", timeoutTicks = 80)
    public static void realPlanksBecomeOneCraftingTableWithFourInputsConsumed(GameTestHelper helper) throws Exception {
        var registry = helper.getLevel().registryAccess();
        var owner = actor(); var ledger = ledger();
        var inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 8));
        var before = ItemSnapshotCodec.capture(inventory.getStackInSlot(0), registry);
        var source = stock(before);
        var op = ledger.reserve(owner, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("crafting-table"),
                List.of(source), List.of(new MaterialLedger.Need(source.key(), 4)));
        ledger.beginExtraction(owner, op.operationId(), 0, before, 8, "physical-handler");
        var taken = NativeMaterialTransfer.extract(inventory, 0, 4, registry, () -> inventory.getStackInSlot(0).getCount());
        ledger.extracted(owner, op.operationId(), taken.actual(), taken.unitsAfter());
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int slot : List.of(0, 1, 3, 4)) grid.set(slot, new ItemStack(Items.OAK_PLANKS));
        var recipe = helper.getLevel().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("crafting_table")).orElseThrow();
        var verified = VanillaProcessingKernel.verifyCrafting(recipe, grid, helper.getLevel());
        var output = ItemSnapshotCodec.capture(verified.outputs().getFirst(), registry);
        ledger.produce(owner, op.operationId(), List.of(output), "actual selected recipe");
        // This test verifies inventory transfer only; callback semantics have separate coverage.
        ledger.beginCallback(owner, op.operationId(), "TEST_TRANSFER_ONLY");
        ledger.callbackCompleted(owner, op.operationId());
        ledger.beginDelivery(owner, op.operationId(), 0, 1, "slot-1", MaterialStack.EMPTY);
        var inserted = NativeMaterialTransfer.insert(inventory, 1, output, MaterialStack.EMPTY, registry);
        op = ledger.delivered(owner, op.operationId(), inserted.accepted(), inserted.verified());
        helper.assertValueEqual(inventory.getStackInSlot(0).getCount(), 4, "Exactly four real planks were consumed");
        helper.assertTrue(inventory.getStackInSlot(1).is(Items.CRAFTING_TABLE), "A real recipe output was inserted");
        helper.assertValueEqual(op.state(), MaterialLedger.State.COMPLETED, "Delivery is backed by native inventory evidence");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-material-transactions", timeoutTicks = 80)
    public static void partialNativeExtractionReturnsOnlyActualInputOnCancellation(GameTestHelper helper) throws Exception {
        var registry = helper.getLevel().registryAccess(); var ledger = ledger(); var owner = actor();
        var inventory = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return super.extractItem(slot, Math.min(2, amount), simulate);
            }
        };
        inventory.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 8));
        var source = stock(ItemSnapshotCodec.capture(inventory.getStackInSlot(0), registry));
        var op = ledger.reserve(owner, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("partial"),
                List.of(source), List.of(new MaterialLedger.Need(source.key(), 4)));
        ledger.beginExtraction(owner, op.operationId(), 0, source.stack(), 8, "native");
        var taken = NativeMaterialTransfer.extract(inventory, 0, 4, registry, () -> inventory.getStackInSlot(0).getCount());
        ledger.extracted(owner, op.operationId(), taken.actual(), taken.unitsAfter());
        op = ledger.cancel(owner, op.operationId());
        helper.assertValueEqual(op.remainingProducts().getFirst().count(), 2, "Only the two extracted planks are held for return");
        helper.assertValueEqual(inventory.getStackInSlot(0).getCount(), 6, "Unextracted items remain in the source");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-material-transactions", timeoutTicks = 80)
    public static void fullAndVoidDestinationsCannotFalselyAcknowledgeDelivery(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var output = ItemSnapshotCodec.capture(new ItemStack(Items.STONE, 3), registry);
        var full = new ItemStackHandler(1); full.setStackInSlot(0, new ItemStack(Items.DIRT, 64));
        var result = NativeMaterialTransfer.insert(full, 0, output, ItemSnapshotCodec.capture(full.getStackInSlot(0), registry), registry);
        helper.assertValueEqual(result.accepted(), 0, "Full inventory retains the full output");
        helper.assertTrue(result.verified(), "A verified rejection is not a delivery");
        var voiding = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return ItemStack.EMPTY; }
        };
        result = NativeMaterialTransfer.insert(voiding, 0, output, MaterialStack.EMPTY, registry);
        helper.assertTrue(!result.verified(), "A void handler returning EMPTY without storing items is not accepted");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-material-transactions", timeoutTicks = 80)
    public static void componentIdentitySurvivesEscrowAndRejectsMovedInput(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var named = new ItemStack(Items.DIAMOND_PICKAXE); named.setDamageValue(27);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("owner tool"));
        MaterialStack captured = ItemSnapshotCodec.capture(named, registry);
        named.setDamageValue(90);
        ItemStack restored = ItemSnapshotCodec.restore(captured, registry);
        helper.assertValueEqual(restored.getDamageValue(), 27, "Durable components do not alias the live stack");
        helper.assertValueEqual(restored.getHoverName().getString(), "owner tool", "Name is preserved");
        var target = new ItemStackHandler(1); target.setStackInSlot(0, new ItemStack(Items.DIRT));
        boolean rejected = false;
        try { NativeMaterialTransfer.insert(target, 0, captured, MaterialStack.EMPTY, registry); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected && target.getStackInSlot(0).is(Items.DIRT), "Moved destination is rejected before any insertion");
        helper.succeed();
    }
}
