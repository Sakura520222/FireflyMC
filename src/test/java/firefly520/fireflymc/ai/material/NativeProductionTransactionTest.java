package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol receipts here are explicit fixtures; the native dedicated scenario supplies actual IO proofs. */
class NativeProductionTransactionTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private MaterialStack input() { return new MaterialStack("{\"id\":\"minecraft:shulker_box\"}", 1); }
    private MaterialStack output() { return new MaterialStack("{\"id\":\"minecraft:chest\"}", 1); }
    private MaterialLedger.Operation escrow(MaterialLedger ledger) throws Exception {
        var stock = new MaterialLedger.Stock("source", 1, "player:" + actor.owner(), "slot:0", 0, input(), 1, 1, 1);
        var op = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("native-assembly-plan"), List.of(stock),
                List.of(new MaterialLedger.Need(stock.key(), 1)));
        ledger.beginExtraction(actor, op.operationId(), 0, input(), 1, "actual-extract");
        ledger.extracted(actor, op.operationId(), input(), 0);
        ledger.nativeSaved(actor, op.operationId(), "extract", "actual-extract", AiJson.digest("fixture-input-write"));
        return ledger.get(actor, op.operationId());
    }
    @Test void knownNoDispatchPreservesTheOriginalContainerAndRetiresNoMaterials() throws Exception {
        var ledger = new MaterialLedger(directory); var op = escrow(ledger); UUID id = op.operationId();
        op = ledger.beginNativeProduction(actor, id, "assemble");
        assertEquals(List.of(input()), op.escrow()); assertTrue(op.products().isEmpty());
        assertThrows(IllegalStateException.class, () -> ledger.callbackEvidence(actor, id, "assemble"));
        op = ledger.callbackRejectedBeforeDispatch(actor, id, "assemble", 0, "CONTROL_STOPPED");
        assertEquals(MaterialLedger.State.ESCROWED, op.state()); assertTrue(op.samplingEvidence().isEmpty());
        op = ledger.cancel(actor, id);
        assertEquals(List.of(input()), op.products()); assertTrue(op.saved());
    }
    @Test void anUnprovenCancellationNeverReturnsOrFabricatesTheInput() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = escrow(ledger).operationId();
        ledger.beginNativeProduction(actor, id, "assemble"); var op = ledger.cancel(actor, id);
        assertTrue(op.uncertain()); assertNotNull(op.intent()); assertTrue(op.products().isEmpty());
        assertEquals(List.of(input()), op.escrow());
        var restored = new MaterialLedger(directory).get(actor, id);
        assertTrue(restored.uncertain()); assertNotNull(restored.intent()); assertTrue(restored.products().isEmpty());
    }
    @Test void actualProductionConsumesInputsOnceButBackingDataMustSaveBeforeDelivery() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = escrow(ledger).operationId();
        ledger.beginNativeProduction(actor, id, "assemble");
        ledger.callbackPartEvidence(actor, id, "assemble", 0, true, true, "", Set.of("player:" + actor.owner()));
        var op = ledger.nativeProductionEvidence(actor, id, "assemble", List.of(output()));
        assertEquals(MaterialLedger.State.CALLBACK_DONE, op.state()); assertTrue(op.escrow().isEmpty());
        assertEquals(List.of(output()), op.products()); assertFalse(op.saved());
        assertThrows(IllegalStateException.class, () -> ledger.beginDelivery(actor, id, 0, 1, "deliver", MaterialStack.EMPTY, Set.of("slot:1")));
        ledger.nativeSaved(actor, id, "callback-part", MaterialLedger.callbackPartWitness("assemble", 0), AiJson.digest("fixture-shared-store-write"));
        assertTrue(ledger.get(actor, id).saved());
        ledger.beginDelivery(actor, id, 0, 1, "deliver", MaterialStack.EMPTY, Set.of("slot:1"));
        op = ledger.delivered(actor, id, 1, true);
        assertEquals(MaterialLedger.State.COMPLETED, op.state()); assertFalse(op.saved());
        assertThrows(IllegalStateException.class, () -> ledger.nativeProductionEvidence(actor, id, "assemble", List.of(output())));
    }
    @Test void lateKnownOutputIsRecordedWithoutClearingAnEarlierRecoveryBarrier() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = escrow(ledger).operationId();
        ledger.beginNativeProduction(actor, id, "assemble"); ledger.markUncertain(actor, id, "WAITER_STOPPED_AFTER_DISPATCH");
        ledger.callbackPartEvidence(actor, id, "assemble", 0, true, true, "", Set.of("slot:0"));
        var op = ledger.nativeProductionEvidence(actor, id, "assemble", List.of(output()));
        assertTrue(op.uncertain()); assertNull(op.intent()); assertTrue(op.escrow().isEmpty()); assertEquals(List.of(output()), op.products());
    }
    @Test void pureProducedMaterialsCanBeReturnedWithoutReversingTheRecipeOrCallingCallbacks() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = escrow(ledger).operationId();
        ledger.produceSampled(actor, id, List.of(output()), "actual-pure-outcome");
        var op = ledger.cancel(actor, id);
        assertEquals(MaterialLedger.State.CALLBACK_DONE, op.state()); assertEquals(List.of(output()), op.products());
        assertTrue(op.escrow().isEmpty()); assertTrue(op.reason().contains("WITHOUT_NEW_CALLBACKS"));
    }
    @Test void stoppingALaterKnownUnenteredPartKeepsTheEarlierRealReceiptAndDoesNotReplayIt() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = escrow(ledger).operationId();
        ledger.produceSampled(actor, id, List.of(output()), "actual-pure-outcome"); ledger.beginCallback(actor, id, "batch", 3);
        ledger.callbackPartEvidence(actor, id, "batch", 0, true, true, "", Set.of("slot:0"));
        assertThrows(IllegalStateException.class, () -> ledger.callbackRejectedBeforeDispatch(actor, id, "batch", 2, "NOT_DISPATCHED"));
        var op = ledger.callbackRejectedBeforeDispatch(actor, id, "batch", 1, "NOT_DISPATCHED");
        assertEquals(MaterialLedger.State.CALLBACK_DONE, op.state()); assertNull(op.intent());
        assertEquals(1, op.nativeSaves().stream().filter(save -> save.kind().equals("callback-part")).count());
        assertFalse(op.saved());
    }
}
