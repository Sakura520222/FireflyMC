package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FluidLedgerTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 0), b = Actor.player(UUID.randomUUID(), true, true, 0);
    private final UUID grant = UUID.randomUUID();
    private FluidAmount water(int amount) { return amount == 0 ? FluidAmount.EMPTY : new FluidAmount("{\"amount\":1,\"id\":\"minecraft:water\"}", amount); }
    private FluidInventoryService.Source source(int amount) {
        return new FluidInventoryService.Source(grant, 1, "shared-native-backend", AiJson.digest("authorization"),
                new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(10000, water(amount)))));
    }
    private FluidLedger.Operation reserve(FluidLedger ledger, int count) throws Exception {
        return ledger.reserve(a, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("fixed-plan"), source(1000), water(count));
    }
    private FluidLedger.Operation drain(FluidLedger ledger, FluidLedger.Operation op, String witness, int intended, int actual) throws Exception {
        int before = 1000 - op.extracted();
        ledger.begin(a, op.id(), witness, source(before), water(intended), false);
        return ledger.observed(a, op.id(), witness, new FluidInventoryService.Outcome(true, true, water(actual), source(before - actual).snapshot(), "", null));
    }
    @Test void sharedBackendsReserveOnceAcrossPlayersButOperationHistoryIsPrivate() throws Exception {
        var ledger = new FluidLedger(directory); var first = reserve(ledger, 800);
        assertEquals(200, ledger.available(source(1000), water(1)));
        assertThrows(IllegalStateException.class, () -> ledger.reserve(b, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("b"), source(1000), water(300)));
        assertThrows(SecurityException.class, () -> ledger.get(b, first.id())); assertTrue(ledger.list(b).isEmpty());
        assertEquals(first, ledger.reserve(a, first.task(), first.id(), first.planHash(), source(1000), water(800)));
        assertThrows(SecurityException.class, () -> ledger.reserve(a, first.task(), first.id(), first.planHash(), source(1000), water(700)));
        ledger.cancel(a, first.id()); assertEquals(1000, ledger.available(source(1000), water(1)));
    }
    @Test void partialNativeDrainRetainsActualCustodyAndCancellationReleasesOnlyUnextractedReservation() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 800); String witness = UUID.randomUUID().toString();
        op = drain(ledger, op, witness, 800, 300);
        assertEquals(FluidLedger.State.RESERVED, op.state()); assertEquals(300, op.escrowMb());
        assertEquals(200, ledger.available(source(700), water(1)));
        op = ledger.cancel(a, op.id()); assertEquals(FluidLedger.State.ESCROWED, op.state()); assertEquals(300, op.escrowMb());
        assertEquals(700, ledger.available(source(700), water(1)));
        String fill = UUID.randomUUID().toString(); ledger.begin(a, op.id(), fill, source(700), water(300), true);
        op = ledger.observed(a, op.id(), fill, new FluidInventoryService.Outcome(true, true, water(100), source(800).snapshot(), "", null));
        assertEquals(200, op.escrowMb()); assertEquals(FluidLedger.State.ESCROWED, op.state()); assertFalse(ledger.settled(a));
    }
    @Test void noNativeDispatchPreservesCountsAndReusingAnEffectCannotDebitTwice() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String witness = UUID.randomUUID().toString();
        ledger.begin(a, op.id(), witness, source(1000), water(500), false);
        ledger.rejected(a, op.id(), witness, "SOURCE_REVOKED");
        assertEquals(0, ledger.get(a, op.id()).extracted());
        UUID id = op.id(); assertThrows(IllegalStateException.class, () -> ledger.rejected(a, id, witness, "AGAIN"));
        assertThrows(IllegalArgumentException.class, () -> ledger.begin(a, id, witness, source(1000), water(500), false));
    }
    @Test void uncertainCountsQuarantineSourceAndNeverAcquireRecoveryFromAFileReceiptAlone() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String witness = UUID.randomUUID().toString();
        ledger.begin(a, op.id(), witness, source(1000), water(500), false);
        ledger.observed(a, op.id(), witness, new FluidInventoryService.Outcome(true, true, water(500), source(600).snapshot(), "", null));
        ledger.nativeSaved(a, op.id(), witness, AiJson.digest("actual-native-write"));
        assertTrue(ledger.recoveryBlocksBackend(source(1000).backend())); assertTrue(ledger.recoveryBlocksPlayer(a.owner()));
        UUID id = op.id(); assertThrows(IllegalStateException.class, () -> ledger.recoveryReview(a, id));
        assertEquals(0, ledger.get(a, id).escrowMb()); assertEquals(500, ledger.get(a, id).effects().getFirst().actual().millibuckets());
    }
    @Test void restartRequiresPositiveBoundProofThenRestoresOnlyLedgerWithoutRedraining() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String witness = UUID.randomUUID().toString();
        op = drain(ledger, op, witness, 500, 500); UUID id = op.id();
        ledger.nativeSaved(a, id, witness, AiJson.digest("both-native-files-saved"));
        var reloaded = new FluidLedger(directory);
        assertEquals(FluidLedger.State.RECOVERY_REQUIRED, reloaded.get(a, id).state());
        var review = reloaded.recoveryReview(a, id);
        assertThrows(SecurityException.class, () -> reloaded.reconcile(b, id, review.revision(), review.reviewHash()));
        assertThrows(IllegalStateException.class, () -> reloaded.reconcile(a, id, review.revision() - 1, review.reviewHash()));
        op = reloaded.reconcile(a, id, review.revision(), review.reviewHash());
        assertEquals(FluidLedger.State.ESCROWED, op.state()); assertEquals(500, op.escrowMb());
        assertEquals(1, op.effects().size()); assertFalse(reloaded.recoveryBlocksBackend(source(1000).backend()));
        assertTrue(reloaded.exportJournal(a).contains("RESTART_UNCERTAIN"));
    }
    @Test void cancelledWaiterStillRecordsOneLateActualResultButGenericResumeCannotReplayIt() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String witness = UUID.randomUUID().toString(); UUID id = op.id();
        ledger.begin(a, id, witness, source(1000), water(500), false); ledger.cancel(a, id);
        ledger.observed(a, id, witness, new FluidInventoryService.Outcome(true, true, water(500), source(500).snapshot(), "", null));
        assertEquals(500, ledger.get(a, id).escrowMb()); assertEquals(FluidLedger.State.RECOVERY_REQUIRED, ledger.get(a, id).state());
        assertThrows(IllegalStateException.class, () -> ledger.begin(a, id, UUID.randomUUID().toString(), source(500), water(500), true));
        assertThrows(IllegalStateException.class, () -> ledger.recoveryReview(a, id));
        ledger.nativeSaved(a, id, witness, AiJson.digest("late-native-save")); var review = ledger.recoveryReview(a, id);
        assertEquals(FluidLedger.State.ESCROWED, ledger.reconcile(a, id, review.revision(), review.reviewHash()).state());
    }
    @Test void finishedNativeTransfersStillNeedSaveProofBeforePrivacySettlementAndRestartAcceptance() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String drain = UUID.randomUUID().toString();
        op = drain(ledger, op, drain, 500, 500); UUID id = op.id(); String fill = UUID.randomUUID().toString();
        ledger.begin(a, id, fill, source(500), water(500), true);
        ledger.observed(a, id, fill, new FluidInventoryService.Outcome(true, true, water(500), source(1000).snapshot(), "", null));
        assertEquals(FluidLedger.State.COMPLETED, ledger.get(a, id).state()); assertFalse(ledger.settled(a));
        assertThrows(IllegalStateException.class, () -> ledger.forgetSettled(a));
        var reloaded = new FluidLedger(directory); assertEquals(FluidLedger.State.RECOVERY_REQUIRED, reloaded.get(a, id).state());
        assertThrows(IllegalStateException.class, () -> reloaded.recoveryReview(a, id));
    }
    @Test void recipeConsumesOnlyRealCustodyAndIdempotentReceiptCannotProduceARefund() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); String witness = UUID.randomUUID().toString();
        op = drain(ledger, op, witness, 500, 500); UUID id = op.id(), parent = UUID.randomUUID(); String receipt = AiJson.digest("fixed-mixed-recipe");
        assertThrows(SecurityException.class, () -> ledger.consumeForRecipe(b, id, parent, receipt, 500));
        op = ledger.consumeForRecipe(a, id, parent, receipt, 500);
        assertEquals(500, op.consumedMb()); assertEquals(0, op.filled()); assertEquals(0, op.escrowMb());
        assertEquals(op, ledger.consumeForRecipe(a, id, parent, receipt, 500));
        assertThrows(SecurityException.class, () -> ledger.consumeForRecipe(a, id, parent, AiJson.digest("changed-output"), 500));
        assertThrows(IllegalStateException.class, () -> ledger.consumeForRecipe(a, id, UUID.randomUUID(), receipt, 1));
        assertEquals(0, ledger.cancel(a, id).escrowMb()); assertFalse(op.saved(), "A pure recipe debit is not a native source-save acknowledgement");
        ledger.nativeSaved(a, id, witness, AiJson.digest("actual-source-saved"));
        var reopened = new FluidLedger(directory); assertEquals(500, reopened.get(a, id).consumedMb());
        assertEquals(0, reopened.get(a, id).escrowMb()); assertEquals(FluidLedger.State.COMPLETED, reopened.get(a, id).state());
    }
    @Test void partialRecipeDebitLeavesOnlyUnusedOriginalFluidInCustody() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 800);
        op = drain(ledger, op, UUID.randomUUID().toString(), 800, 800); UUID id = op.id();
        op = ledger.consumeForRecipe(a, id, UUID.randomUUID(), AiJson.digest("recipe-uses-500"), 500);
        assertEquals(300, op.escrowMb()); assertEquals(500, op.consumedMb());
        ledger.cancel(a, id); String fill = UUID.randomUUID().toString(); ledger.begin(a, id, fill, source(200), water(300), true);
        op = ledger.observed(a, id, fill, new FluidInventoryService.Outcome(true, true, water(300), source(500).snapshot(), "", null));
        assertEquals(300, op.filled()); assertEquals(500, op.consumedMb()); assertEquals(0, op.escrowMb());
        assertEquals(FluidLedger.State.CANCELLED, op.state());
    }
    @Test void pendingCancellationOrNativeUncertaintyCannotBePromotedIntoRecipeConsumption() throws Exception {
        var ledger = new FluidLedger(directory); var op = reserve(ledger, 500); UUID id = op.id(); String witness = UUID.randomUUID().toString();
        ledger.begin(a, id, witness, source(1000), water(500), false);
        assertThrows(IllegalStateException.class, () -> ledger.consumeForRecipe(a, id, UUID.randomUUID(), AiJson.digest("pending"), 500));
        ledger.cancel(a, id);
        ledger.observed(a, id, witness, new FluidInventoryService.Outcome(true, true, water(500), source(500).snapshot(), "", null));
        assertThrows(IllegalStateException.class, () -> ledger.consumeForRecipe(a, id, UUID.randomUUID(), AiJson.digest("late"), 500));
    }
}
