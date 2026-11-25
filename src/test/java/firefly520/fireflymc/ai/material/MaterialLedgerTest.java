package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialLedgerTest {
    @TempDir Path directory;
    final Actor a = Actor.player(UUID.randomUUID(), true, true, 0);
    final Actor b = Actor.player(UUID.randomUUID(), true, true, 4);
    final String plan = AiJson.digest("fixed recipe generation and allocations");
    MaterialStack stone(int count) { return new MaterialStack("{\"id\":\"minecraft:stone\"}", count); }
    MaterialLedger.Stock source(String source, String pool, MaterialStack stack, long weight, long units) {
        return new MaterialLedger.Stock(source, 1, "same-backend", pool, 0, stack, weight, units, stack.count());
    }
    MaterialLedger.Operation reserve(MaterialLedger ledger, Actor actor, MaterialLedger.Stock stock, int count) throws Exception {
        return ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), plan, List.of(stock),
                List.of(new MaterialLedger.Need(stock.key(), count)));
    }

    @Test void aliasesDoNotDoubleCountAndTwoPlayersCannotReserveTheSameSixteenItems() throws Exception {
        var ledger = new MaterialLedger(directory);
        var chest = source("chest", "physical-slot", stone(16), 1, 16);
        var alias = source("other-side", "physical-slot", stone(16), 1, 16);
        assertEquals(16L, ledger.available(List.of(chest, alias)).get(chest.key()));
        reserve(ledger, a, chest, 16);
        assertEquals(0L, ledger.available(List.of(alias)).get(alias.key()));
        assertThrows(IllegalStateException.class, () -> reserve(ledger, b, alias, 1));
        assertTrue(ledger.list(b).isEmpty());
    }

    @Test void compressedDrawerVariantsCompeteForOneWeightedPoolAcrossRestart() throws Exception {
        var ledger = new MaterialLedger(directory);
        var ingots = source("ingot", "compressed", new MaterialStack("{\"id\":\"minecraft:iron_ingot\"}", 9), 9, 81);
        var block = source("block", "compressed", new MaterialStack("{\"id\":\"minecraft:iron_block\"}", 1), 81, 81);
        reserve(ledger, a, ingots, 1);
        assertEquals(0L, ledger.available(List.of(block)).get(block.key()));
        assertThrows(IllegalStateException.class, () -> reserve(new MaterialLedger(directory), b, block, 1));
    }

    @Test void operationIdCannotBeReboundOrExecutedAgain() throws Exception {
        var ledger = new MaterialLedger(directory);
        var stock = source("chest", "slot", stone(8), 1, 8);
        var op = reserve(ledger, a, stock, 2);
        assertEquals(op, ledger.reserve(a, op.taskId(), op.operationId(), plan, List.of(stock),
                List.of(new MaterialLedger.Need(stock.key(), 2))));
        assertThrows(SecurityException.class, () -> ledger.get(b, op.operationId()));
        assertThrows(SecurityException.class, () -> ledger.reserve(b, op.taskId(), op.operationId(), plan,
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2))));
        assertThrows(SecurityException.class, () -> ledger.reserve(a, op.taskId(), op.operationId(), AiJson.digest("new"),
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2))));
    }

    @Test void partialExtractionRetainsOnlyActualEscrowAndCancellationReleasesUnextractedReservation() throws Exception {
        var ledger = new MaterialLedger(directory);
        var stock = source("chest", "slot", stone(16), 1, 16);
        var op = reserve(ledger, a, stock, 8);
        ledger.beginExtraction(a, op.operationId(), 0, stone(16), 16, "live-identity");
        op = ledger.extracted(a, op.operationId(), stone(3), 13);
        assertEquals(MaterialLedger.State.RESERVED, op.state());
        assertEquals(3, op.escrow().getFirst().count());
        assertEquals(8L, ledger.available(List.of(source("chest", "slot", stone(13), 1, 13))).get(stock.key()));
        op = ledger.cancel(a, op.operationId());
        assertEquals(3, op.remainingProducts().getFirst().count());
        assertEquals(MaterialLedger.State.CALLBACK_DONE, op.state());
        assertEquals(13L, ledger.available(List.of(source("chest", "slot", stone(13), 1, 13))).get(stock.key()));
    }

    @Test void unexpectedHandlerConsumptionQuarantinesEveryAliasAndCannotBeCancelledAway() throws Exception {
        var ledger = new MaterialLedger(directory);
        var stock = source("chest", "slot", stone(16), 1, 16);
        var op = reserve(ledger, a, stock, 4);
        ledger.beginExtraction(a, op.operationId(), 0, stone(16), 16, "live");
        op = ledger.extracted(a, op.operationId(), stone(3), 12); // native source lost four, returned three
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state());
        assertEquals(op, ledger.cancel(a, op.operationId()));
        assertEquals(0L, ledger.available(List.of(stock)).get(stock.key()));
        var alias = source("other-authorized-chest", "slot", stone(12), 1, 12);
        var refused = assertThrows(IllegalStateException.class, () -> reserve(ledger, b, alias, 1));
        assertEquals("MATERIAL_POOL_REQUIRES_RECONCILIATION", refused.getMessage());
        UUID id = op.operationId();
        assertThrows(IllegalStateException.class, () -> ledger.produce(a, id,
                List.of(stone(99)), "cannot manufacture a recovery"));
    }

    @Test void aCrashAfterAcknowledgedExtractionStillRequiresWorldSaveReconciliation() throws Exception {
        var ledger = new MaterialLedger(directory);
        var op = reserve(ledger, a, source("chest", "slot", stone(2), 1, 2), 2);
        ledger.beginExtraction(a, op.operationId(), 0, stone(2), 2, "before");
        ledger.extracted(a, op.operationId(), stone(2), 0);
        var restarted = new MaterialLedger(directory);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restarted.get(a, op.operationId()).state());
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED,
                new MaterialLedger(directory).get(a, op.operationId()).state());
        assertFalse(restarted.settled(a.owner()));
    }

    @Test void outputsAreSampledOnceAndFullInventoryRetainsUndeliveredProducts() throws Exception {
        var ledger = new MaterialLedger(directory);
        var op = reserve(ledger, a, source("chest", "slot", stone(2), 1, 2), 2);
        UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(2), 2, "before");
        ledger.extracted(a, id, stone(2), 0);
        var result = new MaterialStack("{\"id\":\"minecraft:stone_slab\"}", 4);
        ledger.produce(a, id, List.of(result), "verified fixed recipe; sampled result");
        assertThrows(IllegalStateException.class, () -> ledger.produce(a, id, List.of(result.withCount(64)), "reroll"));
        ledger.beginCallback(a, id, "STONECUTTING");
        ledger.callbackCompleted(a, id);
        ledger.beginDelivery(a, id, 0, 4, "player-slot", MaterialStack.EMPTY);
        op = ledger.delivered(a, id, 0, true);
        assertEquals(4, op.remainingProducts().getFirst().count());
        ledger.beginDelivery(a, id, 0, 2, "player-slot-second", MaterialStack.EMPTY);
        op = ledger.delivered(a, id, 2, true);
        assertEquals(2, op.remainingProducts().getFirst().count());
        ledger.beginDelivery(a, id, 0, 2, "player-slot-third", result.withCount(2));
        op = ledger.delivered(a, id, 2, true);
        assertEquals(MaterialLedger.State.COMPLETED, op.state());
        assertFalse(ledger.settled(a.owner()));
        for (var save : op.nativeSaves()) ledger.nativeSaved(a, id, save.kind(), save.witness(), AiJson.digest("test native write: " + save.witness()));
        assertTrue(ledger.settled(a.owner()));
        assertEquals(MaterialLedger.State.COMPLETED, new MaterialLedger(directory).get(a, id).state());
    }

    @Test void completedWithoutNativeSaveProofQuarantinesSharedSourceOnRestart() throws Exception {
        var ledger = new MaterialLedger(directory);
        var stock = source("chest", "slot", stone(1), 1, 1);
        var op = reserve(ledger, a, stock, 1); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stock.stack(), 1, "physical-extract");
        ledger.extracted(a, id, stone(1), 0);
        ledger.produce(a, id, List.of(stone(1)), "trusted deterministic adapter");
        ledger.noCallbacksRequired(a, id, "no native callback for this adapter");
        ledger.beginDelivery(a, id, 0, 1, "physical-deliver", MaterialStack.EMPTY, Set.of("shared-output"));
        ledger.delivered(a, id, 1, true);
        ledger.nativeSaved(a, id, "deliver", "physical-deliver", AiJson.digest("only output saved"));
        var restored = new MaterialLedger(directory);
        assertEquals("NATIVE_SAVE_NOT_CONFIRMED", restored.get(a, id).reason());
        assertEquals(0L, restored.available(List.of(stock)).get(stock.key()));
        var outputAlias = source("different-player-warehouse", "shared-output", stone(12), 1, 12);
        assertThrows(IllegalStateException.class, () -> reserve(restored, b, outputAlias, 1));
        assertThrows(IllegalStateException.class, () -> reserve(restored, b, stock, 1));
        // A later successful save alone cannot establish whether a previously ambiguous native call ran.
        restored.nativeSaved(a, id, "extract", "physical-extract", AiJson.digest("late evidence"));
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.get(a, id).state());
        assertThrows(SecurityException.class, () -> restored.nativeSaved(b, id, "extract", "physical-extract", AiJson.digest("spoof")));
    }

    @Test void cancelledEscrowRetainsOriginalSourceIdentityForCrashQuarantine() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "original-pool", stone(2), 1, 2);
        var op = reserve(ledger, a, stock, 2); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(2), 2, "extract"); ledger.extracted(a, id, stone(1), 1);
        ledger.cancel(a, id);
        ledger.beginDelivery(a, id, 0, 1, "return", MaterialStack.EMPTY); ledger.delivered(a, id, 1, true);
        var restored = new MaterialLedger(directory);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.get(a, id).state());
        assertEquals(0L, restored.available(List.of(stock)).get(stock.key()));
        assertThrows(IllegalStateException.class, () -> restored.forgetSettled(a));
    }

    @Test void provenPredispatchRejectionNeedsNoNativeSave() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        var op = reserve(ledger, a, stock, 1); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "never-entered");
        ledger.rejectedBeforeExecution(a, id, "CANCELLED_BEFORE_NATIVE_EXTRACTION"); ledger.cancel(a, id);
        assertTrue(ledger.settled(a.owner()));
        assertEquals(MaterialLedger.State.CANCELLED, new MaterialLedger(directory).get(a, id).state());
    }

    @Test void explicitRecoveryUsesCompleteNativeReceiptsAndCannotReplayOrCrossPlayers() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        var op = reserve(ledger, a, stock, 1); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "actual-extract"); ledger.extracted(a, id, stone(1), 0);
        ledger.nativeSaved(a, id, "extract", "actual-extract", AiJson.digest("actual native write and force receipt"));
        var restored = new MaterialLedger(directory); var review = restored.recoveryReview(a, id);
        assertEquals(MaterialLedger.State.ESCROWED, review.restorePhase()); assertEquals(1, review.escrowUnits());
        assertThrows(SecurityException.class, () -> restored.recoveryReview(b, id));
        assertThrows(IllegalStateException.class, () -> restored.reconcile(a, id, review.revision(), AiJson.digest("wrong review")));
        var settled = restored.reconcile(a, id, review.revision(), review.reviewHash());
        assertEquals(MaterialLedger.State.ESCROWED, settled.state()); assertEquals(List.of(stone(1)), settled.escrow());
        assertThrows(IllegalStateException.class, () -> restored.reconcile(a, id, review.revision(), review.reviewHash()));
        assertThrows(IllegalStateException.class, () -> restored.beginExtraction(a, id, 0, stone(1), 1, "cannot repeat"));
    }

    @Test void unknownNativeIntentCannotBeRecoveredEvenWithAPostSaveReceipt() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        var op = reserve(ledger, a, stock, 1); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "maybe-extracted");
        ledger.nativeSaved(a, id, "extract", "maybe-extracted", AiJson.digest("a save does not reveal native return"));
        var restored = new MaterialLedger(directory);
        assertThrows(IllegalStateException.class, () -> restored.recoveryReview(a, id));
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.get(a, id).state());
    }
    @Test void rejectedBeforeDispatchDoesNotLeaveAPhantomWorldSaveOrConsumeEscrow() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        UUID id = reserve(ledger, a, stock, 1).operationId(), step = UUID.randomUUID();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "taken"); ledger.extracted(a, id, stone(1), 0);
        ledger.nativeSaved(a, id, "extract", "taken", AiJson.digest("actual saved input"));
        ledger.beginWorldUse(a, id, step, 0);
        assertThrows(IllegalStateException.class, () -> ledger.rejectionEvidence(a, id, "wrong-step", "world", "no dispatch"));
        var op = ledger.rejectionEvidence(a, id, step.toString(), "world", "cancelled before main-thread claim");
        assertEquals(MaterialLedger.State.ESCROWED, op.state()); assertTrue(op.saved());
        assertEquals(List.of(stone(1)), op.escrow()); assertTrue(op.worldDebits().isEmpty());
        assertEquals(1, op.nativeSaves().size());
        var reloaded = new MaterialLedger(directory); var review = reloaded.recoveryReview(a, id);
        assertEquals(MaterialLedger.State.ESCROWED, reloaded.reconcile(a, id, review.revision(), review.reviewHash()).state());
    }
    @Test void lateNonDispatchProofRemovesOnlyThatObligationWithoutClearingRecovery() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        UUID id = reserve(ledger, a, stock, 1).operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "pending"); ledger.markUncertain(a, id, "DISPATCH_RESULT_PENDING");
        var rejected = ledger.rejectionEvidence(a, id, "pending", "extract", "not claimed");
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, rejected.state()); assertTrue(rejected.nativeSaves().isEmpty());
        assertNull(rejected.intent()); assertTrue(rejected.escrow().isEmpty());
        var reloaded = new MaterialLedger(directory); var review = reloaded.recoveryReview(a, id);
        assertEquals(MaterialLedger.State.RESERVED, review.restorePhase());
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, reloaded.get(a, id).state());
        assertEquals(MaterialLedger.State.RESERVED, reloaded.reconcile(a, id, review.revision(), review.reviewHash()).state());
    }
    @Test void rebootPreservesAnUnknownEffectReasonAndCannotInventARecoveryReview() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        UUID id = reserve(ledger, a, stock, 1).operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "taken"); ledger.extracted(a, id, stone(1), 0);
        ledger.nativeSaved(a, id, "extract", "taken", AiJson.digest("known input persisted"));
        ledger.markUncertain(a, id, "UNADAPTED_NATIVE_EFFECT");
        for (int restart = 0; restart < 3; restart++) {
            ledger = new MaterialLedger(directory);
            assertEquals("UNADAPTED_NATIVE_EFFECT", ledger.get(a, id).reason());
            var current = ledger; assertThrows(IllegalStateException.class, () -> current.recoveryReview(a, id));
        }
    }
    @Test void lateWorldNonDispatchStillRequiresAllEarlierInputSaveProofs() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(1), 1, 1);
        UUID id = reserve(ledger, a, stock, 1).operationId(), step = UUID.randomUUID();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "taken"); ledger.extracted(a, id, stone(1), 0);
        ledger.beginWorldUse(a, id, step, 0); ledger.markUncertain(a, id, "DISPATCH_RESULT_PENDING");
        ledger.rejectionEvidence(a, id, step.toString(), "world", "not claimed");
        assertThrows(IllegalStateException.class, () -> ledger.recoveryReview(a, id));
        ledger.nativeSaved(a, id, "extract", "taken", AiJson.digest("actual native input write"));
        var reloaded = new MaterialLedger(directory); var review = reloaded.recoveryReview(a, id);
        assertEquals(MaterialLedger.State.ESCROWED, review.restorePhase());
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, reloaded.get(a, id).state());
        assertEquals(List.of(stone(1)), reloaded.reconcile(a, id, review.revision(), review.reviewHash()).escrow());
    }

    @Test void missingSaveAndUnknownConservationHaveNoRecoveryPreview() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "slot", stone(2), 1, 2);
        var op = reserve(ledger, a, stock, 2); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(2), 2, "actual"); ledger.extracted(a, id, stone(1), 0);
        ledger.nativeSaved(a, id, "extract", "actual", AiJson.digest("native store saved despite conservation mismatch"));
        assertThrows(IllegalStateException.class, () -> new MaterialLedger(directory).recoveryReview(a, id));
    }

    @Test void rollbackCannotEscapeQuarantineWithNewBlockIdentityOrAnotherSlot() throws Exception {
        long position = net.minecraft.core.BlockPos.asLong(-12, 64, 37);
        String beforeBackend = "block:minecraft:overworld:" + position + ":" + UUID.randomUUID();
        var original = new MaterialLedger.Stock("approved-chest", 1, beforeBackend, beforeBackend + ":slot:0", 0, stone(3), 1, 3, 3);
        var ledger = new MaterialLedger(directory); var op = reserve(ledger, a, original, 1);
        ledger.beginExtraction(a, op.operationId(), 0, stone(3), 3, "maybe-taken");
        ledger = new MaterialLedger(directory);
        String reidentifiedBackend = "block/minecraft:overworld/-12,64,37:" + UUID.randomUUID();
        var anotherSlot = new MaterialLedger.Stock("other-owner-new-grant", 1, reidentifiedBackend, reidentifiedBackend + "/slot/7", 7, stone(3), 1, 3, 3);
        assertEquals(MaterialSourceScope.key(beforeBackend), MaterialSourceScope.key(reidentifiedBackend));
        assertEquals(0L, ledger.available(List.of(anotherSlot)).get(anotherSlot.key()));
        var restored = ledger;
        assertThrows(IllegalStateException.class, () -> reserve(restored, b, anotherSlot, 1));
        var elsewhere = new MaterialLedger.Stock("different-dimension", 1, reidentifiedBackend.replace("overworld", "the_nether"),
                "different-pool", 7, stone(3), 1, 3, 3);
        assertEquals(3L, restored.available(List.of(elsewhere)).get(elsewhere.key()));
    }

    @Test void callbackIntentAndDeliveryIntentNeverReplayAfterRestart() throws Exception {
        var ledger = new MaterialLedger(directory);
        var op = reserve(ledger, a, source("chest", "slot", stone(1), 1, 1), 1);
        UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(1), 1, "before");
        ledger.extracted(a, id, stone(1), 0);
        ledger.produce(a, id, List.of(stone(1)), "same item transformation");
        ledger.beginCallback(a, id, "EXPERIENCE_AND_ADVANCEMENT");
        var restarted = new MaterialLedger(directory);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restarted.get(a, id).state());
        assertThrows(IllegalStateException.class, () -> restarted.callbackCompleted(a, id));
    }
    @Test void zeroProbabilityOutputStillConsumesRealInputAndTheSameOperationCannotReroll() throws Exception {
        var ledger = new MaterialLedger(directory);
        var op = reserve(ledger, a, source("chest", "slot", stone(1), 1, 1), 1);
        UUID id = op.operationId();
        assertThrows(IllegalStateException.class, () -> ledger.produceSampled(a, id, List.of(), "trusted-native-roll:seed=42"));
        assertFalse(ledger.exportJournal(a).contains("seed=42"));
        assertTrue(ledger.get(a, id).remainingProducts().isEmpty());
        ledger.beginExtraction(a, id, 0, stone(1), 1, "physical-before");
        ledger.extracted(a, id, stone(1), 0);
        op = ledger.produceSampled(a, id, List.of(), "trusted-native-roll:seed=42");
        assertEquals(MaterialLedger.State.PRODUCED, op.state()); assertTrue(op.escrow().isEmpty());
        op = ledger.cancel(a, id);
        assertEquals(MaterialLedger.State.CANCELLED, op.state());
        assertTrue(op.products().isEmpty()); assertTrue(op.escrow().isEmpty());
        assertEquals("trusted-native-roll:seed=42", op.samplingEvidence());
        assertThrows(IllegalStateException.class, () -> ledger.produceSampled(a, id, List.of(stone(8)), "seed=99"));
        assertThrows(IllegalStateException.class, () -> ledger.produce(a, id, List.of(stone(1)), "changed roll"));
        assertThrows(IllegalStateException.class, () -> ledger.noCallbacksRequired(a, id, "no native reward or callback"));
        assertEquals(1, op.claims().getFirst().extracted());
        assertTrue(op.products().isEmpty()); assertTrue(op.escrow().isEmpty());
        assertEquals("trusted-native-roll:seed=42", new MaterialLedger(directory).get(a, id).samplingEvidence());
    }
    @Test void exportedRandomResultAlwaysHasCommittedInputsAndCancellationCannotRefundThem() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 2); UUID id = op.operationId();
        String evidence = "trusted-choice:seed=100";
        assertFalse(ledger.exportJournal(a).contains(evidence));
        var product = new MaterialStack("{\"id\":\"minecraft:gravel\"}", 1);
        op = ledger.produceSampled(a, id, List.of(product), evidence);
        var events = com.google.gson.JsonParser.parseString(ledger.exportJournal(a)).getAsJsonArray();
        MaterialJournalCodec.replay(events, data -> {
            if (!data.get("samplingEvidence").getAsString().equals(evidence)) return;
            assertEquals("PRODUCED", data.get("state").getAsString()); assertTrue(data.getAsJsonArray("escrow").isEmpty());
            var claim = data.getAsJsonArray("claims").get(0).getAsJsonObject();
            assertEquals(claim.get("requested"), claim.get("extracted"));
        });
        var cancelled = ledger.cancel(a, id);
        assertEquals(MaterialLedger.State.CALLBACK_DONE, cancelled.state());
        assertEquals(op.claims(), cancelled.claims()); assertEquals(evidence, cancelled.samplingEvidence());
        assertEquals(List.of(product), cancelled.remainingProducts());
        assertTrue(cancelled.escrow().isEmpty());
        var restored = new MaterialLedger(directory);
        assertEquals(evidence, restored.get(a, id).samplingEvidence());
        assertThrows(IllegalStateException.class, () -> restored.produceSampled(a, id, List.of(stone(99)), "another-seed"));
    }
    @Test void longWorldJournalKeepsEveryHistoricalStepWithoutQuadraticSnapshots() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 128); UUID id = op.operationId();
        long started = System.nanoTime(), repeatedSnapshotBytes = 0;
        for (int i = 0; i < 128; i++) {
            UUID step = UUID.randomUUID();
            op = ledger.beginWorldUse(a, id, step, 0);
            repeatedSnapshotBytes += AiJson.GSON.toJson(op).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            op = ledger.worldUsed(a, id, step, MaterialStack.EMPTY, true, AiJson.digest("unit native receipt " + i));
            repeatedSnapshotBytes += AiJson.GSON.toJson(op).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        ledger.worldCompleted(a, id);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        long bytes = java.nio.file.Files.size(directory.resolve("workspace").resolve(a.owner()).resolve("materials/journal.jsonl"));
        var events = com.google.gson.JsonParser.parseString(ledger.exportJournal(a)).getAsJsonArray();
        int[] replayed = {0}; MaterialJournalCodec.replay(events, state -> {
            assertEquals(++replayed[0], state.get("revision").getAsLong());
        });
        assertEquals(260, replayed[0]); assertTrue(bytes < repeatedSnapshotBytes / 4);
        var restored = new MaterialLedger(directory).get(a, id);
        assertEquals(128, restored.worldDebits().size()); assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.state());
        assertTrue(restored.escrow().isEmpty()); // No missing save proof can invent 128 refundable inputs.
        System.out.println("MATERIAL_JOURNAL_MEASUREMENT " + AiJson.GSON.toJson(Map.of("steps", 128,
                "elapsedMillis", elapsed, "actualJournalBytes", bytes, "repeatedSnapshotPayloadBytes", repeatedSnapshotBytes,
                "historicalStates", replayed[0], "scope", "pure ledger IO and replay; not Minecraft placement or MSPT")));
    }
    @Test void firstWriteAfterLoadingOlderAi4StateCreatesAnExactFullBaseline() throws Exception {
        var sourceDirectory = directory.resolve("source"); var original = new MaterialLedger(sourceDirectory);
        var op = reserve(original, a, source("chest", "slot", stone(1), 1, 1), 1);
        var old = AiJson.GSON.toJsonTree(op).getAsJsonObject();
        old.remove("nativeSaves"); old.remove("samplingEvidence");
        old.getAsJsonArray("claims").get(0).getAsJsonObject().getAsJsonObject("stock").remove("maxTransferCount");
        var file = directory.resolve("workspace").resolve(a.owner()).resolve("materials/journal.jsonl");
        var journal = new firefly520.fireflymc.ai.conversation.ConversationStore(file);
        journal.append(List.of(new firefly520.fireflymc.ai.conversation.ConversationStore.Draft(op.taskId(), MaterialJournalCodec.FULL, null, old, op.operationId().toString())));
        var loaded = new MaterialLedger(directory); var cancelled = loaded.cancel(a, op.operationId());
        var events = com.google.gson.JsonParser.parseString(loaded.exportJournal(a)).getAsJsonArray();
        assertEquals(MaterialJournalCodec.FULL, events.get(1).getAsJsonObject().get("kind").getAsString());
        com.google.gson.JsonObject[] last = new com.google.gson.JsonObject[1]; MaterialJournalCodec.replay(events, value -> last[0] = value);
        assertEquals(AiJson.GSON.toJsonTree(cancelled), last[0]);
    }
    @Test void worldPlacementConsumesOnlyOneOwnedUnitAndCancellationReturnsOnlyTheRemainder() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 3); UUID step = UUID.randomUUID();
        ledger.beginWorldUse(a, op.operationId(), step, 0);
        op = ledger.worldUsed(a, op.operationId(), step, MaterialStack.EMPTY, true, AiJson.digest("native-world-receipt"));
        assertEquals(2, op.escrow().getFirst().count()); assertEquals(1, op.worldDebits().getFirst().count());
        UUID id = op.operationId();
        assertThrows(IllegalStateException.class, () -> ledger.beginWorldUse(a, id, step, 0));
        assertThrows(IllegalStateException.class, () -> ledger.produce(a, id, List.of(stone(3)), "cannot refund world-consumed input"));
        op = ledger.cancel(a, id); assertEquals(List.of(stone(2)), op.remainingProducts());
    }
    @Test void interruptedWorldIntentCannotBeResumedOrReturnedAsUnusedInput() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 1); UUID id = op.operationId(), step = UUID.randomUUID();
        ledger.beginWorldUse(a, id, step, 0);
        var restarted = new MaterialLedger(directory);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restarted.get(a, id).state());
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restarted.cancel(a, id).state());
        assertThrows(IllegalStateException.class, () -> restarted.worldUsed(a, id, step, MaterialStack.EMPTY, true, AiJson.digest("unproven")));
    }
    @Test void cancelledNativePlacementKeepsItsOneUnitAndWrongStepCannotAcknowledgeIt() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 1); UUID id = op.operationId(), step = UUID.randomUUID();
        ledger.beginWorldUse(a, id, step, 0);
        assertThrows(SecurityException.class, () -> ledger.worldUsed(a, id, UUID.randomUUID(), stone(1), true, AiJson.digest("proof")));
        op = ledger.worldUsed(a, id, step, stone(1), true, AiJson.digest("known rejection"));
        assertEquals(0, op.worldDebits().getFirst().count()); assertEquals(List.of(stone(1)), op.escrow());
        assertThrows(IllegalStateException.class, () -> ledger.worldCompleted(a, id));
        assertEquals(List.of(stone(1)), ledger.cancel(a, id).remainingProducts());
    }
    @Test void allMaterialCommittedToWorldCompletesWithoutInventingAnInventoryProduct() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 1); UUID id = op.operationId(), step = UUID.randomUUID();
        ledger.beginWorldUse(a, id, step, 0);
        ledger.worldUsed(a, id, step, MaterialStack.EMPTY, true, AiJson.digest("world receipt"));
        op = ledger.worldCompleted(a, id);
        assertEquals(MaterialLedger.State.COMPLETED, op.state()); assertTrue(op.remainingProducts().isEmpty()); assertTrue(op.escrow().isEmpty());
        assertEquals(1, new MaterialLedger(directory).get(a, id).worldDebits().getFirst().count());
    }
    private MaterialLedger.Operation escrow(MaterialLedger ledger, int count) throws Exception {
        var op = reserve(ledger, a, source("chest", "world-material", stone(count), 1, count), count);
        ledger.beginExtraction(a, op.operationId(), 0, stone(count), count, "real-source-witness");
        return ledger.extracted(a, op.operationId(), stone(count), 0);
    }
    @Test void lateExtractionEvidencePreservesActualItemsAfterTheWaiterHasStoppedWithoutReplaying() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = source("chest", "late", stone(2), 1, 2);
        var op = reserve(ledger, a, stock, 1); UUID id = op.operationId();
        ledger.beginExtraction(a, id, 0, stone(2), 2, "accepted-native-call");
        ledger.markUncertain(a, id, "WAITER_TIMEOUT");
        op = ledger.extractionEvidence(a, id, "accepted-native-call", stone(1), 1);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state()); assertEquals(List.of(stone(1)), op.escrow());
        assertEquals(1, op.claims().getFirst().extracted()); assertNull(op.intent());
        assertThrows(IllegalStateException.class, () -> ledger.extractionEvidence(a, id, "accepted-native-call", stone(1), 1));
        assertEquals(List.of(stone(1)), ledger.get(a, id).escrow());
    }
    @Test void lateDeliveryAcknowledgementCannotRecreateAnAlreadyInsertedProduct() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 1); UUID id = op.operationId();
        ledger.produce(a, id, List.of(stone(1)), "verified native recipe");
        ledger.noCallbacksRequired(a, id, "test transfer only");
        ledger.beginDelivery(a, id, 0, 1, "accepted-insert", MaterialStack.EMPTY);
        ledger.cancel(a, id);
        op = ledger.deliveryEvidence(a, id, "accepted-insert", 1, true);
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state()); assertTrue(op.remainingProducts().isEmpty());
        assertEquals(List.of(1), op.delivered());
        assertThrows(IllegalStateException.class, () -> ledger.deliveryEvidence(a, id, "accepted-insert", 1, true));
        assertTrue(ledger.cancel(a, id).remainingProducts().isEmpty());
    }
    @Test void largeFiniteStockIsReservedOnceAndExtractedInNativeTransferSizedChunks() throws Exception {
        var ledger = new MaterialLedger(directory);
        var stock = new MaterialLedger.Stock("chest", 1, "large-finite", "large-pool", 0, stone(200), 1, 200, 200, 64);
        var op = reserve(ledger, a, stock, 150); UUID id = op.operationId();
        assertThrows(IllegalStateException.class, () -> ledger.beginExtraction(a, id, 0, stone(200), 200, "oversized", 150));
        int before = 200;
        for (int count : List.of(64, 64, 22)) {
            ledger.beginExtraction(a, id, 0, stone(before), before, "chunk-" + before, count);
            before -= count; op = ledger.extracted(a, id, stone(count), before);
        }
        assertEquals(MaterialLedger.State.ESCROWED, op.state()); assertEquals(150, op.escrow().stream().mapToInt(MaterialStack::count).sum());
        assertEquals(150, op.claims().getFirst().extracted()); assertEquals(50, before);
    }
    @Test void lateAcceptedWorldResultPreservesDebitButCannotRemoveTheRecoveryBarrier() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 2); UUID id = op.operationId(), step = UUID.randomUUID();
        ledger.beginWorldUse(a, id, step, 0); ledger.markUncertain(a, id, "CANCELLED_WHILE_NATIVE_RUNNING");
        op = ledger.worldEvidence(a, id, step, MaterialStack.EMPTY, true, AiJson.digest("actual native receipt"));
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state()); assertEquals(1, op.worldDebits().getFirst().count());
        assertEquals(List.of(stone(1)), op.escrow()); assertNull(op.intent());
        assertThrows(IllegalStateException.class, () -> ledger.worldEvidence(a, id, step, MaterialStack.EMPTY, true, AiJson.digest("duplicate")));
        assertEquals(1, ledger.get(a, id).worldDebits().size());
    }
    @Test void externalEntityCommitConsumesOnlyExactOwnedInputsAndDeliversOnlyActualRemainders() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 3); UUID id = op.operationId(), effect = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> ledger.beginExternalUse(a, id, effect, "entity_conversion", List.of(stone(4))));
        ledger.beginExternalUse(a, id, effect, "entity_conversion", List.of(stone(2)));
        var container = new MaterialStack("{\"id\":\"minecraft:bucket\"}", 1);
        op = ledger.externalEvidence(a, id, effect, "entity_conversion", true, true, List.of(container), AiJson.digest("verified native entity UUIDs and container return"));
        assertEquals(List.of(stone(1), container), op.escrow()); assertEquals(1, op.externalDebits().size());
        assertThrows(IllegalStateException.class, () -> ledger.produce(a, id, List.of(stone(3)), "must not synthesize old inputs"));
        assertThrows(IllegalStateException.class, () -> ledger.beginExternalUse(a, id, effect, "entity_conversion", List.of(stone(1))));
        op = ledger.externalProductsReady(a, id); assertEquals(List.of(stone(1), container), op.products()); assertTrue(op.escrow().isEmpty());
        ledger.noCallbacksRequired(a, id, "test-native-effect-has-no-extra-callbacks");
        assertEquals(1, ledger.get(a, id).externalDebits().size());
    }
    @Test void rejectedExternalConversionKeepsInputsAndAnUncertainOneCannotBeReturnedOrRetried() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 3); UUID id = op.operationId(), effect = UUID.randomUUID();
        ledger.beginExternalUse(a, id, effect, "fluid_conversion", List.of(stone(2)));
        op = ledger.externalEvidence(a, id, effect, "fluid_conversion", true, false, List.of(), AiJson.digest("native refused before accepting fluid/items"));
        assertEquals(List.of(stone(3)), op.escrow()); assertFalse(op.externalDebits().getFirst().accepted());
        assertThrows(IllegalStateException.class, () -> ledger.externalProductsReady(a, id));
        UUID second = UUID.randomUUID(); ledger.beginExternalUse(a, id, second, "fluid_conversion", List.of(stone(1)));
        op = ledger.externalEvidence(a, id, second, "fluid_conversion", false, false, List.of(), AiJson.digest("callback outcome cannot be proven"));
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state());
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, ledger.cancel(a, id).state());
        assertThrows(IllegalStateException.class, () -> ledger.externalEvidence(a, id, second, "fluid_conversion", true, true, List.of(), AiJson.digest("cannot overwrite unproven receipt")));
        assertEquals(2, new MaterialLedger(directory).get(a, id).externalDebits().size());
    }
    @Test void lateExternalEvidenceAfterCancellationDebitsActualConsumptionWithoutUnblockingRecovery() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 2); UUID id = op.operationId(), effect = UUID.randomUUID();
        ledger.beginExternalUse(a, id, effect, "entity_conversion", List.of(stone(1))); ledger.cancel(a, id);
        op = ledger.externalEvidence(a, id, effect, "entity_conversion", true, true, List.of(), AiJson.digest("accepted native effect after waiter stopped"));
        assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, op.state()); assertEquals(List.of(stone(1)), op.escrow());
        assertTrue(op.externalDebits().getFirst().known()); assertTrue(op.externalDebits().getFirst().accepted());
        assertNull(op.intent()); assertThrows(IllegalStateException.class, () -> ledger.externalProductsReady(a, id));
    }
    @Test void provenUndispatchedCallbackCanClearOnlyItsExactIntentWithoutUndoingTheRecipe() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger, 1); UUID id = op.operationId();
        ledger.produce(a, id, List.of(stone(2)), "actual verified recipe"); ledger.beginCallback(a, id, "callback-proof");
        assertThrows(IllegalStateException.class, () -> ledger.rejectionEvidence(a, id, "other-proof", "callback", "rejected"));
        op = ledger.rejectionEvidence(a, id, "callback-proof", "callback", "NOT_CLAIMED");
        assertEquals(MaterialLedger.State.PRODUCED, op.state()); assertEquals(List.of(stone(2)), op.products()); assertTrue(op.escrow().isEmpty());
        assertThrows(IllegalStateException.class, () -> ledger.rejectionEvidence(a, id, "callback-proof", "callback", "duplicate"));
    }
}
