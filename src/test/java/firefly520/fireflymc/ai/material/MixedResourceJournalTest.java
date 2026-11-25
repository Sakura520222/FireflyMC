package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** State-machine evidence only; native recipe/world acceptance remains in the dedicated scenarios. */
class MixedResourceJournalTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 0);
    private MaterialStack item(String id) { return new MaterialStack("{\"id\":\"minecraft:" + id + "\",\"count\":1}", 1); }
    private FluidAmount water(int count) { return count == 0 ? FluidAmount.EMPTY : new FluidAmount("{\"amount\":1,\"id\":\"minecraft:water\"}", count); }
    private FluidInventoryService.Source source(int amount) {
        return new FluidInventoryService.Source(UUID.randomUUID(), 1, "native-water", AiJson.digest("source-authorization"),
                new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(4000, water(amount)))));
    }
    private final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final UUID id = UUID.randomUUID(), fluidId = UUID.randomUUID(), task = UUID.randomUUID();
        final String hash = AiJson.digest("fixed-verified-container-recipe");
        final MixedResourceJournal.Definition definition;
        MaterialLedger items = new MaterialLedger(directory);
        FluidLedger fluids = new FluidLedger(directory);
        MixedResourceJournal mixed = new MixedResourceJournal(workspace, items, fluids);
        Fixture() throws Exception { this(false); }
        Fixture(boolean rawOutput) throws Exception {
            definition = new MixedResourceJournal.Definition(id, task, hash,
                    List.of(item(rawOutput ? "water_bucket" : "bucket")),
                    rawOutput ? List.of() : List.of(new MixedResourceJournal.FluidInput(fluidId, water(1000))),
                    List.of(item(rawOutput ? "bucket" : "water_bucket")), rawOutput ? List.of(water(1000)) : List.of(),
                    "test of previously verified recipe custody, not a native recipe claim");
            mixed.create(actor, definition);
        }
        void hold(boolean saved) throws Exception { hold(saved, 1000); }
        void hold(boolean saved, int drainedMb) throws Exception {
            var input = definition.itemInputs().getFirst();
            var stock = new MaterialLedger.Stock("inventory", 1, "player:" + actor.owner(), "player:" + actor.owner() + ":0",
                    0, input, 1, 1, 1);
            items.reserve(actor, task, id, hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
            String itemWitness = UUID.randomUUID().toString(); items.beginExtraction(actor, id, 0, input, 1, itemWitness);
            items.extracted(actor, id, input, 0);
            if (saved) items.nativeSaved(actor, id, "extract", itemWitness, AiJson.digest("native-item-save-proof"));
            if (definition.fluidInputs().isEmpty()) return;
            var source = source(2000); fluids.reserve(actor, task, fluidId, hash, source, water(1000));
            String fluidWitness = UUID.randomUUID().toString(); fluids.begin(actor, fluidId, fluidWitness, source, water(1000), false);
            fluids.observed(actor, fluidId, fluidWitness, new FluidInventoryService.Outcome(true, true, water(drainedMb), source(2000 - drainedMb).snapshot(), "", null));
            if (saved) {
                fluids.nativeSaved(actor, fluidId, fluidWitness, AiJson.digest("native-fluid-save-proof"));
            }
        }
        void reload() throws Exception {
            items = new MaterialLedger(directory); fluids = new FluidLedger(directory);
            mixed = new MixedResourceJournal(workspace, items, fluids);
        }
        void commitAndDeliverItems() throws Exception {
            hold(true); mixed.prepareCommit(actor, id, items, fluids); mixed.commit(actor, id, items, fluids);
            items.noCallbacksRequired(actor, id, "isolated native capability has no crafting callback");
            String witness = UUID.randomUUID().toString(); items.beginDelivery(actor, id, 0, 1, witness, MaterialStack.EMPTY);
            items.delivered(actor, id, 1, true); items.nativeSaved(actor, id, "deliver", witness, AiJson.digest("saved-item-output"));
        }
        public void close() throws java.io.IOException { manager.close(); }
    }
    @Test void fixedMixedCommitConsumesActualCustodyOnceAndFencesStandaloneReturn() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true);
            assertTrue(f.mixed.owns(f.id)); assertTrue(f.mixed.owns(f.fluidId));
            assertThrows(IllegalStateException.class, () -> f.items.cancel(actor, f.id));
            assertThrows(IllegalStateException.class, () -> f.fluids.cancel(actor, f.fluidId));
            assertThrows(SecurityException.class, () -> f.mixed.get(other, f.id));
            f.mixed.prepareCommit(actor, f.id, f.items, f.fluids);
            f.mixed.commit(actor, f.id, f.items, f.fluids);
            assertEquals(List.of(item("water_bucket")), f.items.get(actor, f.id).products());
            assertTrue(f.items.get(actor, f.id).escrow().isEmpty());
            assertEquals(1000, f.fluids.get(actor, f.fluidId).consumedMb()); assertEquals(0, f.fluids.get(actor, f.fluidId).escrowMb());
            assertEquals(MaterialLedger.State.PRODUCED, f.items.get(actor, f.id).state(), "Commit does not pretend to deliver or run callbacks");
            assertFalse(f.mixed.resourcesSettled(actor), "Undelivered item output still blocks private erasure");
            long revision = f.items.get(actor, f.id).revision();
            assertThrows(IllegalStateException.class, () -> f.mixed.commit(actor, f.id, f.items, f.fluids));
            assertEquals(revision, f.items.get(actor, f.id).revision());
            assertEquals(MixedResourceJournal.State.COMMITTED, f.mixed.create(actor, f.definition).state());
            f.items.noCallbacksRequired(actor, f.id, "fixed capability transformation has no native crafting callback");
            String delivery = UUID.randomUUID().toString();
            f.items.beginDelivery(actor, f.id, 0, 1, delivery, MaterialStack.EMPTY);
            f.items.delivered(actor, f.id, 1, true); f.items.nativeSaved(actor, f.id, "deliver", delivery, AiJson.digest("saved-native-output"));
            assertTrue(f.mixed.resourcesSettled(actor)); f.reload();
            assertEquals(MixedResourceJournal.State.COMMITTED, f.mixed.get(actor, f.id).state(), "Fully saved terminal work does not become a spurious recovery task");
            assertTrue(f.mixed.resourcesSettled(actor));
        }
    }
    @Test void changedOutputsCannotReuseTheApprovedIdentity() throws Exception {
        try (var f = new Fixture()) {
            var changed = new MixedResourceJournal.Definition(f.id, f.task, f.hash, f.definition.itemInputs(), f.definition.fluidInputs(),
                    List.of(item("diamond")), List.of(), f.definition.recipeEvidence());
            assertThrows(SecurityException.class, () -> f.mixed.create(actor, changed));
            assertThrows(SecurityException.class, () -> f.mixed.prepareCommit(actor, f.id, f.items, f.fluids), "No owned input operation exists yet");
        }
    }
    @Test void failureAfterFluidDebitNeverRefundsItOrBlindlyReplaysExtraction() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.prepareCommit(actor, f.id, f.items, f.fluids);
            Path path = f.workspace.directory().resolve("materials/journal.jsonl"), backup = path.resolveSibling("journal.before-mixed-test");
            Files.move(path, backup); Files.createDirectory(path);
            try { assertThrows(Exception.class, () -> f.mixed.commit(actor, f.id, f.items, f.fluids)); }
            finally { Files.delete(path); Files.move(backup, path); }
            assertEquals(1000, f.fluids.get(actor, f.fluidId).consumedMb());
            assertTrue(f.items.get(actor, f.id).products().isEmpty()); assertFalse(f.items.journalAvailable());
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            f.reload();
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            assertFalse(review.materialAlreadyProduced()); assertEquals(List.of(f.fluidId), review.alreadyConsumedFluids());
            assertThrows(IllegalStateException.class, () -> f.items.reconcile(actor, f.id, 1, "not-a-paired-review"));
            assertThrows(SecurityException.class, () -> f.mixed.reconcile(other, f.id, review.revision(), review.reviewHash(), f.items, f.fluids));
            assertThrows(IllegalStateException.class, () -> f.mixed.reconcile(actor, f.id, review.revision() - 1, review.reviewHash(), f.items, f.fluids));
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(List.of(item("water_bucket")), f.items.get(actor, f.id).products());
            assertEquals(1, f.fluids.get(actor, f.fluidId).effects().size());
            assertEquals(1, f.fluids.get(actor, f.fluidId).recipeDebits().size());
            assertTrue(f.mixed.exportJournal(actor).contains("PARTIAL_MIXED_COMMIT_REQUIRES_REVIEW"));
        }
    }
    @Test void failureAfterItemProductionDoesNotDuplicateOutputOnProofBoundRecovery() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.prepareCommit(actor, f.id, f.items, f.fluids);
            Path path = f.workspace.directory().resolve("mixed-materials/journal.jsonl"), backup = path.resolveSibling("journal.before-mixed-test");
            Files.move(path, backup); Files.createDirectory(path);
            try { assertThrows(Exception.class, () -> f.mixed.commit(actor, f.id, f.items, f.fluids)); }
            finally { Files.delete(path); Files.move(backup, path); }
            assertEquals(1, f.items.get(actor, f.id).products().size()); f.reload();
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids); assertTrue(review.materialAlreadyProduced());
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(List.of(item("water_bucket")), f.items.get(actor, f.id).products());
            assertEquals(1000, f.fluids.get(actor, f.fluidId).consumedMb());
        }
    }
    @Test void absentNativeInputSaveProofPreventsProductsAndSurvivesRestartAsRecovery() throws Exception {
        try (var f = new Fixture()) {
            f.hold(false); f.mixed.waitForInputSave(actor, f.id);
            assertEquals("MIXED_INPUT_SAVE_REQUIRED", assertThrows(IllegalStateException.class,
                    () -> f.mixed.prepareCommit(actor, f.id, f.items, f.fluids)).getMessage());
            assertTrue(f.items.get(actor, f.id).products().isEmpty());
            assertEquals(0, f.fluids.get(actor, f.fluidId).consumedMb()); f.reload();
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, f.mixed.get(actor, f.id).state());
            assertFalse(f.mixed.resourcesSettled(actor));
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            assertThrows(IllegalStateException.class, () -> f.mixed.heldFluidOutputs(actor, f.id));
        }
    }
    @Test void savedInputsWithoutCommitIntentRecoverOnlyCustodyAndNeverAnOldRecipe() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.waitForInputSave(actor, f.id); f.reload();
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            var restored = f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(MixedResourceJournal.State.INPUTS_PENDING, restored.state());
            assertEquals("RECONCILED_INPUT_CUSTODY_ONLY", restored.reason());
            assertEquals(MaterialLedger.State.ESCROWED, f.items.get(actor, f.id).state());
            assertTrue(f.items.get(actor, f.id).products().isEmpty());
            assertEquals(0, f.fluids.get(actor, f.fluidId).consumedMb());
            assertEquals(1000, f.fluids.get(actor, f.fluidId).escrowMb());
            assertThrows(IllegalStateException.class, () -> f.mixed.heldFluidOutputs(actor, f.id));
        }
    }
    @Test void privacyEpochFencesLateCommitBeforeEitherLedgerCanBeDebited() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.prepareCommit(actor, f.id, f.items, f.fluids);
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> f.mixed.commit(actor, f.id, f.items, f.fluids));
            assertEquals(1000, f.fluids.get(actor, f.fluidId).escrowMb()); assertTrue(f.items.get(actor, f.id).products().isEmpty());
        }
    }
    @Test void itemToFluidRecipesDoNotRequireInventedFluidInputsAndKeepOutputInCustody() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var items = new MaterialLedger(directory); var fluids = new FluidLedger(directory);
            var mixed = new MixedResourceJournal(workspace, items, fluids); UUID id = UUID.randomUUID(), task = UUID.randomUUID();
            String hash = AiJson.digest("fixed-real-container-decant");
            var definition = new MixedResourceJournal.Definition(id, task, hash, List.of(item("water_bucket")), List.of(),
                    List.of(item("bucket")), List.of(water(1000)), "Native container conversion shape; pure ledger fixture");
            mixed.create(actor, definition);
            var stock = new MaterialLedger.Stock("inventory", 1, "player:" + actor.owner(), "slot:1", 1, item("water_bucket"), 1, 1, 1);
            items.reserve(actor, task, id, hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
            String witness = UUID.randomUUID().toString(); items.beginExtraction(actor, id, 0, stock.stack(), 1, witness);
            items.extracted(actor, id, stock.stack(), 0); items.nativeSaved(actor, id, "extract", witness, AiJson.digest("native-input-proof"));
            mixed.prepareCommit(actor, id, items, fluids); mixed.commit(actor, id, items, fluids);
            assertTrue(fluids.list(actor).isEmpty()); assertEquals(List.of(water(1000)), mixed.heldFluidOutputs(actor, id));
            assertEquals(List.of(item("bucket")), items.get(actor, id).products());
            assertFalse(mixed.resourcesSettled(actor), "A raw fluid product cannot be reported as delivered or erased");
        }
    }
    @Test void partialFluidProductDeliveryDebitsOnlyObservedAmountAndRetainsNativeSaveObligations() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); String first = UUID.randomUUID().toString();
            f.mixed.beginFluidDelivery(actor, f.id, 0, first, source(0), water(800));
            f.mixed.fluidDeliveryObserved(actor, f.id, first, new FluidInventoryService.Outcome(true, true, water(600), source(600).snapshot(), "", null));
            assertEquals(List.of(water(400)), f.mixed.heldFluidOutputs(actor, f.id));
            assertThrows(IllegalStateException.class, () -> f.mixed.beginFluidDelivery(actor, f.id, 0, UUID.randomUUID().toString(), source(600), water(500)));
            String second = UUID.randomUUID().toString(); f.mixed.beginFluidDelivery(actor, f.id, 0, second, source(600), water(400));
            f.mixed.fluidDeliveryObserved(actor, f.id, second, new FluidInventoryService.Outcome(true, true, water(400), source(1000).snapshot(), "", null));
            assertEquals(List.of(FluidAmount.EMPTY), f.mixed.heldFluidOutputs(actor, f.id)); assertFalse(f.mixed.resourcesSettled(actor));
            f.mixed.fluidDeliverySaved(actor, f.id, first, AiJson.digest("first-native-fill-save"));
            f.mixed.fluidDeliverySaved(actor, f.id, second, AiJson.digest("second-native-fill-save"));
            assertTrue(f.mixed.resourcesSettled(actor)); f.reload();
            assertTrue(f.mixed.resourcesSettled(actor)); assertEquals(1000, f.mixed.get(actor, f.id).deliveredMb(0));
        }
    }
    @Test void lateNativeFluidProductResultStaysBehindRecoveryUntilPositiveSaveProof() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); String witness = UUID.randomUUID().toString();
            f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(500));
            f.mixed.requireRecovery(actor, f.id, "TASK_CANCELLED_WITH_NATIVE_FILL_PENDING");
            f.mixed.fluidDeliveryObserved(actor, f.id, witness, new FluidInventoryService.Outcome(true, true, water(500), source(500).snapshot(), "", null));
            assertEquals(500, f.mixed.get(actor, f.id).deliveredMb(0));
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            f.mixed.fluidDeliverySaved(actor, f.id, witness, AiJson.digest("actual-native-fill-saved"));
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(List.of(water(500)), f.mixed.heldFluidOutputs(actor, f.id));
            assertEquals(1, f.mixed.get(actor, f.id).fluidDeliveries().size());
        }
    }
    @Test void incorrectNativeFillCannotAcquireRecoveryBySavingTheWrongQuantity() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); String witness = UUID.randomUUID().toString();
            f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(500));
            f.mixed.fluidDeliveryObserved(actor, f.id, witness, new FluidInventoryService.Outcome(true, true, water(500), source(600).snapshot(), "", null));
            f.mixed.fluidDeliverySaved(actor, f.id, witness, AiJson.digest("native-file-written"));
            assertEquals(0, f.mixed.get(actor, f.id).deliveredMb(0));
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            assertThrows(IllegalStateException.class, () -> f.mixed.heldFluidOutputs(actor, f.id));
        }
    }
    @Test void unenteredFillKeepsProductAndCannotBeReplayedWithTheSameWitness() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); String witness = UUID.randomUUID().toString();
            f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(1000));
            f.mixed.fluidDeliveryRejected(actor, f.id, witness, "SOURCE_AUTHORIZATION_CHANGED");
            assertEquals(List.of(water(1000)), f.mixed.heldFluidOutputs(actor, f.id));
            assertThrows(IllegalStateException.class, () -> f.mixed.fluidDeliveryRejected(actor, f.id, witness, "AGAIN"));
            assertThrows(IllegalStateException.class, () -> f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(1000)));
        }
    }
    @Test void reopenedPendingFillCannotBeReplayedFromInventoryCounts() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); f.mixed.beginFluidDelivery(actor, f.id, 0, UUID.randomUUID().toString(), source(0), water(1000)); f.reload();
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, f.mixed.get(actor, f.id).state());
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            assertEquals(0, f.mixed.get(actor, f.id).deliveredMb(0));
        }
    }
    @Test void cancellationBeforeReservationClosesInputsWithoutInventingRefunds() throws Exception {
        try (var f = new Fixture()) {
            var result = f.mixed.beginReturn(actor, f.id);
            assertEquals(MixedResourceJournal.State.RETURNED, result.phase());
            assertTrue(result.items().isEmpty()); assertTrue(result.inputFluids().isEmpty()); assertTrue(result.outputFluids().isEmpty());
            assertTrue(f.mixed.settled(actor, f.id)); assertTrue(f.mixed.resourcesSettled(actor));
            long revision = f.mixed.get(actor, f.id).revision(); f.mixed.beginReturn(actor, f.id);
            assertEquals(revision, f.mixed.get(actor, f.id).revision());
            assertThrows(IllegalStateException.class, () -> f.fluids.reserve(actor, f.task, f.fluidId, f.hash, source(2000), water(1000)));
            assertThrows(IllegalStateException.class, () -> f.hold(true));
            f.reload(); assertTrue(f.mixed.settled(actor, f.id));
            assertEquals(MixedResourceJournal.State.RETURNED, f.mixed.get(actor, f.id).state());
        }
    }
    @Test void softReservationsAreReleasedWithoutNativeEffectsAndRepeatReturnIsStable() throws Exception {
        try (var f = new Fixture()) {
            var stock = new MaterialLedger.Stock("inventory", 1, "player:" + actor.owner(), "slot:0", 0, item("bucket"), 1, 1, 1);
            f.items.reserve(actor, f.task, f.id, f.hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
            var source = source(2000); f.fluids.reserve(actor, f.task, f.fluidId, f.hash, source, water(1000));
            assertEquals(1000, f.fluids.available(source, water(1000)));
            var result = f.mixed.beginReturn(actor, f.id);
            assertEquals(MixedResourceJournal.State.RETURNED, result.phase());
            assertEquals(MaterialLedger.State.CANCELLED, f.items.get(actor, f.id).state());
            assertEquals(FluidLedger.State.CANCELLED, f.fluids.get(actor, f.fluidId).state());
            assertEquals(2000, f.fluids.available(source, water(1000)));
            assertTrue(f.items.get(actor, f.id).nativeSaves().isEmpty()); assertTrue(f.fluids.get(actor, f.fluidId).effects().isEmpty());
        }
    }
    @Test void partialFluidExtractionReturnsOnlyObservedCustodyAndRetainsRejectedTarget() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true, 600);
            var result = f.mixed.beginReturn(actor, f.id);
            assertEquals(MixedResourceJournal.State.RETURNING, result.phase());
            assertEquals(List.of(item("bucket")), result.items());
            assertEquals(List.of(new MixedResourceJournal.FluidInput(f.fluidId, water(600))), result.inputFluids());
            assertTrue(result.outputFluids().isEmpty());
            assertTrue(f.items.get(actor, f.id).claims().stream().allMatch(claim -> claim.remaining() == 0));
            assertThrows(IllegalStateException.class, () -> f.fluids.begin(actor, f.fluidId, UUID.randomUUID().toString(), source(0), water(600), true));
            assertThrows(IllegalStateException.class, () -> f.fluids.begin(actor, f.fluidId, UUID.randomUUID().toString(), source(1400), water(400), false));
            String refused = UUID.randomUUID().toString();
            f.mixed.beginFluidReturn(actor, f.id, f.fluidId, refused, source(0), water(600));
            f.fluids.rejected(actor, f.fluidId, refused, "SOURCE_AUTHORIZATION_CHANGED");
            assertEquals(600, f.mixed.continueReturn(actor, f.id).inputFluids().getFirst().amount().millibuckets());
            String filled = UUID.randomUUID().toString();
            f.mixed.beginFluidReturn(actor, f.id, f.fluidId, filled, source(0), water(600));
            f.fluids.observed(actor, f.fluidId, filled, new FluidInventoryService.Outcome(true, true, water(600), source(600).snapshot(), "", null));
            deliverReturnItem(f);
            assertEquals(MixedResourceJournal.State.RETURNING, f.mixed.continueReturn(actor, f.id).phase(), "Actual fill is not durable settlement without save proof");
            f.fluids.nativeSaved(actor, f.fluidId, filled, AiJson.digest("saved-native-return"));
            assertEquals(MixedResourceJournal.State.RETURNED, f.mixed.continueReturn(actor, f.id).phase());
            assertEquals(0, f.fluids.get(actor, f.fluidId).consumedMb()); assertEquals(600, f.fluids.get(actor, f.fluidId).filled());
            f.reload(); assertTrue(f.mixed.resourcesSettled(actor));
        }
    }
    @Test void halfWrittenCancellationRecoversRemainingPureWritesWithoutConsumingInputs() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true);
            Path path = f.workspace.directory().resolve("fluids/journal.jsonl"), backup = path.resolveSibling("journal.before-return-test");
            Files.move(path, backup); Files.createDirectory(path);
            try { assertThrows(Exception.class, () -> f.mixed.beginReturn(actor, f.id)); }
            finally { Files.delete(path); Files.move(backup, path); }
            assertEquals(List.of(item("bucket")), f.items.get(actor, f.id).products());
            assertEquals(0, f.fluids.get(actor, f.fluidId).consumedMb());
            f.reload();
            assertEquals(MixedResourceJournal.ReturnMode.INPUTS, f.mixed.get(actor, f.id).returnMode());
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            assertThrows(IllegalStateException.class, () -> f.mixed.reconcile(actor, f.id, review.revision() - 1, review.reviewHash(), f.items, f.fluids));
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            var returned = f.mixed.continueReturn(actor, f.id);
            assertEquals(List.of(item("bucket")), returned.items());
            assertEquals(1000, returned.inputFluids().getFirst().amount().millibuckets());
            assertEquals(1, f.fluids.get(actor, f.fluidId).effects().size());
            assertTrue(f.fluids.get(actor, f.fluidId).recipeDebits().isEmpty());
            assertTrue(f.mixed.exportJournal(actor).contains("PARTIAL_MIXED_RETURN_REQUIRES_REVIEW"));
        }
    }
    @Test void cancelledCommitIntentFinishesOnlyDebitedRecipeAndReturnsProducts() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.prepareCommit(actor, f.id, f.items, f.fluids);
            f.fluids.consumeForRecipe(actor, f.fluidId, f.id, f.mixed.get(actor, f.id).receipt(), 1000);
            var blocked = f.mixed.beginReturn(actor, f.id);
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, blocked.phase()); assertTrue(blocked.inputFluids().isEmpty());
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            var returned = f.mixed.continueReturn(actor, f.id);
            assertEquals(List.of(item("water_bucket")), returned.items()); assertTrue(returned.inputFluids().isEmpty());
            assertEquals(1000, f.fluids.get(actor, f.fluidId).consumedMb()); assertEquals(0, f.fluids.get(actor, f.fluidId).filled());
            assertThrows(IllegalStateException.class, () -> f.mixed.beginFluidReturn(actor, f.id, f.fluidId, UUID.randomUUID().toString(), source(0), water(1000)));
        }
    }
    @Test void postCommitCancellationReturnsRemainingRealFluidProductsOnly() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems();
            var returned = f.mixed.beginReturn(actor, f.id);
            assertTrue(returned.items().isEmpty()); assertTrue(returned.inputFluids().isEmpty());
            assertEquals(List.of(water(1000)), returned.outputFluids());
            String witness = UUID.randomUUID().toString();
            f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(1000));
            assertFalse(f.mixed.recoveryBlocksBackend("native-water"));
            f.mixed.fluidDeliveryObserved(actor, f.id, witness, new FluidInventoryService.Outcome(true, true, water(1000), source(1000).snapshot(), "", null));
            assertEquals(MixedResourceJournal.State.RETURNING, f.mixed.continueReturn(actor, f.id).phase());
            f.mixed.fluidDeliverySaved(actor, f.id, witness, AiJson.digest("saved-real-fluid-product"));
            assertEquals(MixedResourceJournal.State.RETURNED, f.mixed.continueReturn(actor, f.id).phase());
            f.reload(); assertTrue(f.mixed.settled(actor, f.id));
        }
    }
    @Test void pendingReturnCannotReplayAndLateResultNeedsPositiveProofAndExplicitRecovery() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); f.mixed.beginReturn(actor, f.id);
            String witness = UUID.randomUUID().toString(); f.mixed.beginFluidReturn(actor, f.id, f.fluidId, witness, source(0), water(700));
            f.mixed.requireRecovery(actor, f.id, "TASK_CANCELLED_WITH_RETURN_PENDING");
            assertTrue(f.mixed.continueReturn(actor, f.id).inputFluids().isEmpty());
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            f.fluids.observed(actor, f.fluidId, witness, new FluidInventoryService.Outcome(true, true, water(700), source(700).snapshot(), "", null));
            assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.id, f.items, f.fluids));
            f.fluids.nativeSaved(actor, f.fluidId, witness, AiJson.digest("late-return-save"));
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(300, f.mixed.continueReturn(actor, f.id).inputFluids().getFirst().amount().millibuckets());
            assertThrows(IllegalStateException.class, () -> f.fluids.observed(actor, f.fluidId, witness, new FluidInventoryService.Outcome(true, true, water(700), source(700).snapshot(), "", null)));
        }
    }
    @Test void restartBeforeAnyCommitAndCancellationUsesInputDirectionWithoutCreatingProducts() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true, 600); f.reload();
            assertEquals(MixedResourceJournal.State.INPUTS_PENDING, f.mixed.get(actor, f.id).recoveryPhase());
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, f.mixed.beginReturn(actor, f.id).phase());
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertEquals(List.of(item("bucket")), f.mixed.continueReturn(actor, f.id).items());
            assertEquals(600, f.mixed.continueReturn(actor, f.id).inputFluids().getFirst().amount().millibuckets());
        }
    }
    @Test void returnOwnerAndPrivacyEpochAreCheckedBeforeMutatingEitherLedger() throws Exception {
        try (var f = new Fixture()) {
            f.hold(true); long itemRevision = f.items.get(actor, f.id).revision(), fluidRevision = f.fluids.get(actor, f.fluidId).revision();
            assertThrows(SecurityException.class, () -> f.mixed.beginReturn(other, f.id));
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> f.mixed.beginReturn(actor, f.id));
            assertFalse(f.mixed.journalAvailable());
            assertEquals(itemRevision, f.items.get(actor, f.id).revision()); assertEquals(fluidRevision, f.fluids.get(actor, f.fluidId).revision());
        }
    }
    @Test void cancellationDuringPartialItemExtractionWaitsForActualSavedResultAndReleasesRemainder() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var items = new MaterialLedger(directory); var fluids = new FluidLedger(directory);
            var mixed = new MixedResourceJournal(workspace, items, fluids); UUID id = UUID.randomUUID(), task = UUID.randomUUID(), fluidId = UUID.randomUUID();
            String hash = AiJson.digest("partial-native-item-return");
            mixed.create(actor, new MixedResourceJournal.Definition(id, task, hash, List.of(item("bucket").withCount(2)),
                    List.of(new MixedResourceJournal.FluidInput(fluidId, water(2000))), List.of(item("water_bucket").withCount(2)), List.of(), "fixed recipe"));
            var stock = new MaterialLedger.Stock("inventory", 1, "player:" + actor.owner(), "slot:0", 0, item("bucket").withCount(2), 1, 2, 2);
            items.reserve(actor, task, id, hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
            String witness = UUID.randomUUID().toString(); items.beginExtraction(actor, id, 0, stock.stack(), 2, witness);
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, mixed.beginReturn(actor, id).phase());
            assertTrue(mixed.recoveryBlocksBackend("player:" + actor.owner()));
            assertThrows(IllegalStateException.class, () -> mixed.recoveryReview(actor, id, items, fluids));
            items.extractionEvidence(actor, id, witness, item("bucket"), 1);
            assertThrows(IllegalStateException.class, () -> mixed.recoveryReview(actor, id, items, fluids));
            items.nativeSaved(actor, id, "extract", witness, AiJson.digest("late-partial-item-save"));
            var review = mixed.recoveryReview(actor, id, items, fluids);
            mixed.reconcile(actor, id, review.revision(), review.reviewHash(), items, fluids);
            assertFalse(mixed.recoveryBlocksBackend("player:" + actor.owner()));
            var returned = mixed.continueReturn(actor, id);
            assertEquals(List.of(item("bucket")), returned.items()); assertTrue(returned.inputFluids().isEmpty());
            assertEquals(1, items.get(actor, id).claims().getFirst().requested());
            assertEquals(0, items.get(actor, id).claims().getFirst().remaining());
            assertThrows(IllegalStateException.class, () -> items.beginExtraction(actor, id, 0, item("bucket"), 1, UUID.randomUUID().toString()));
            assertThrows(IllegalStateException.class, () -> items.extractionEvidence(actor, id, witness, item("bucket"), 1));
        }
    }
    @Test void failedItemJournalCannotApproveOrReconcileSavedPartialInputsFromItsOldMemory() throws Exception {
        assertFailedLinkedJournalKeepsInputBarrier(true);
    }
    @Test void failedFluidJournalCannotApproveOrReconcileSavedPartialInputsFromItsOldMemory() throws Exception {
        assertFailedLinkedJournalKeepsInputBarrier(false);
    }
    private void assertFailedLinkedJournalKeepsInputBarrier(boolean failItems) throws Exception {
        try (var f = new Fixture()) {
            f.hold(true, 600);
            var before = f.mixed.get(actor, f.id);
            var itemBefore = f.items.get(actor, f.id); var fluidBefore = f.fluids.get(actor, f.fluidId);
            Path path = f.workspace.directory().resolve(failItems ? "materials/journal.jsonl" : "fluids/journal.jsonl");
            Path backup = path.resolveSibling("journal.before-linked-failure-test");
            Files.move(path, backup); Files.createDirectory(path);
            try {
                if (failItems) assertThrows(java.io.IOException.class, () -> f.items.markUncertain(actor, f.id, "TEST_NATIVE_WRITE_FAILURE"));
                else assertThrows(java.io.IOException.class, () -> f.fluids.cancelMixed(actor, f.fluidId, before.receipt()));
            } finally { Files.delete(path); Files.move(backup, path); }
            assertFalse(failItems ? f.items.journalAvailable() : f.fluids.journalAvailable());
            assertEquals(itemBefore, f.items.get(actor, f.id)); assertEquals(fluidBefore, f.fluids.get(actor, f.fluidId));
            assertEquals("MIXED_LINKED_JOURNAL_UNAVAILABLE", assertThrows(IllegalStateException.class, () ->
                    f.mixed.approveRemainingInputs(actor, f.id, before.revision(), AiJson.digest("new-remainder-approval"))).getMessage());
            assertEquals(before, f.mixed.get(actor, f.id), "An unavailable linked journal cannot acquire a new extraction approval");
            f.mixed.requireRecovery(actor, f.id, "FAILED_LINKED_JOURNAL_REQUIRES_REOPEN");
            var fenced = f.mixed.get(actor, f.id);
            assertEquals("MIXED_LINKED_JOURNAL_UNAVAILABLE", assertThrows(IllegalStateException.class, () ->
                    f.mixed.recoveryReview(actor, f.id, f.items, f.fluids)).getMessage());
            assertThrows(IllegalStateException.class, () -> f.mixed.reconcile(actor, f.id, fenced.revision(), AiJson.digest("stale-review"), f.items, f.fluids));
            assertEquals(fenced, f.mixed.get(actor, f.id));
            assertEquals(List.of(item("bucket")), f.items.get(actor, f.id).escrow());
            assertEquals(600, f.fluids.get(actor, f.fluidId).escrowMb());
            assertTrue(f.items.get(actor, f.id).products().isEmpty());
        }
    }
    @Test void cancelledOutputFillQuarantinesOwnerAndBackendUntilProofBoundRecovery() throws Exception {
        try (var f = new Fixture(true)) {
            f.commitAndDeliverItems(); f.mixed.beginReturn(actor, f.id);
            String witness = UUID.randomUUID().toString(); f.mixed.beginFluidDelivery(actor, f.id, 0, witness, source(0), water(600));
            assertFalse(f.mixed.recoveryBlocksBackend("native-water"));
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, f.mixed.beginReturn(actor, f.id).phase());
            assertTrue(f.mixed.recoveryBlocksBackend("native-water")); assertTrue(f.mixed.recoveryBlocksPlayer(actor.owner()));
            assertFalse(f.mixed.recoveryBlocksPlayer(other.owner())); assertFalse(f.mixed.recoveryBlocksBackend("unrelated-tank"));
            f.mixed.fluidDeliveryObserved(actor, f.id, witness, new FluidInventoryService.Outcome(true, true, water(600), source(600).snapshot(), "", null));
            f.mixed.fluidDeliverySaved(actor, f.id, witness, AiJson.digest("late-output-return-save"));
            f.reload(); assertTrue(f.mixed.recoveryBlocksBackend("native-water"));
            var review = f.mixed.recoveryReview(actor, f.id, f.items, f.fluids);
            f.mixed.reconcile(actor, f.id, review.revision(), review.reviewHash(), f.items, f.fluids);
            assertFalse(f.mixed.recoveryBlocksBackend("native-water")); assertFalse(f.mixed.recoveryBlocksPlayer(actor.owner()));
            assertEquals(List.of(water(400)), f.mixed.continueReturn(actor, f.id).outputFluids());
        }
    }
    @Test void primaryConversationRecoverySnapshotNeverAcquiresTheMixedMonitor() throws Exception {
        try (var f = new Fixture()) {
            synchronized (f.mixed) {
                var snapshot = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    synchronized (f.workspace.conversation()) { return f.mixed.snapshot(actor); }
                }).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(List.of(f.mixed.get(actor, f.id)), snapshot);
                assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            }
            assertThrows(SecurityException.class, () -> f.mixed.snapshot(other));
            f.mixed.beginReturn(actor, f.id);
            assertEquals(MixedResourceJournal.State.RETURNED, f.mixed.snapshot(actor).getFirst().state());
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> f.mixed.snapshot(actor));
        }
    }
    private void deliverReturnItem(Fixture f) throws Exception {
        String witness = UUID.randomUUID().toString(); f.items.beginDelivery(actor, f.id, 0, 1, witness, MaterialStack.EMPTY);
        f.items.delivered(actor, f.id, 1, true); f.items.nativeSaved(actor, f.id, "deliver", witness, AiJson.digest("saved-return-item"));
    }
}
