package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.processing.NativeProcessingCallback;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeCallbackEvidenceTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final String witness = "fixed-chain:sampledExperience=[0, 1]";
    private UUID produced(MaterialLedger ledger) throws Exception {
        var item = new MaterialStack("{\"id\":\"minecraft:stone\"}", 1);
        var stock = new MaterialLedger.Stock("player", 1, "player:" + actor.owner(), "slot", 0, item, 1, 1, 1);
        var op = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("fixed"), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
        UUID id = op.operationId(); ledger.beginExtraction(actor, id, 0, item, 1, "extract"); ledger.extracted(actor, id, item, 0);
        ledger.nativeSaved(actor, id, "extract", "extract", AiJson.digest("input saved"));
        ledger.produceSampled(actor, id, List.of(), "sampled-once-no-item-result"); return id;
    }
    @Test void everyAcceptedPartAndItsOwnSaveAreRequiredForASettledBatch() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = produced(ledger); ledger.beginCallback(actor, id, witness, 2);
        ledger.callbackPartEvidence(actor, id, witness, 0, true, true, "", Set.of("player:" + actor.owner()), "first callback observed");
        assertThrows(IllegalStateException.class, () -> ledger.callbackEvidence(actor, id, witness));
        assertThrows(IllegalStateException.class, () -> ledger.callbackCompleted(actor, id));
        ledger.callbackPartEvidence(actor, id, witness, 1, true, true, "", Set.of("player:" + actor.owner()), "orb UUID count 1->2");
        var completed = ledger.callbackEvidence(actor, id, witness); assertEquals(MaterialLedger.State.COMPLETED, completed.state());
        assertFalse(completed.saved()); assertTrue(completed.reason().contains("orb UUID count 1->2"));
        ledger.nativeSaved(actor, id, "callback-part", MaterialLedger.callbackPartWitness(witness, 0), AiJson.digest("player stats advancements"));
        assertFalse(ledger.get(actor, id).saved());
        ledger.nativeSaved(actor, id, "callback-part", MaterialLedger.callbackPartWitness(witness, 1), AiJson.digest("pre-existing orb region saved"));
        assertTrue(ledger.get(actor, id).saved()); assertEquals(MaterialLedger.State.COMPLETED, new MaterialLedger(directory).get(actor, id).state());
        assertThrows(IllegalStateException.class, () -> ledger.callbackEvidence(actor, id, witness));
    }
    @Test void lateAcceptedCallbackAndItsSavePreserveRecoveryAfterCancellation() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = produced(ledger); ledger.beginCallback(actor, id, witness, 1);
        ledger.markUncertain(actor, id, "CALLER_CANCELLED_AFTER_ACCEPTANCE");
        var late = ledger.callbackPartEvidence(actor, id, witness, 0, true, true, "", Set.of("player:" + actor.owner()), "actual accepted callback");
        assertTrue(late.uncertain()); assertTrue(late.reason().contains("CALLER_CANCELLED_AFTER_ACCEPTANCE"));
        ledger.callbackEvidence(actor, id, witness);
        ledger.nativeSaved(actor, id, "callback-part", MaterialLedger.callbackPartWitness(witness, 0), AiJson.digest("late physical write"));
        assertTrue(ledger.get(actor, id).uncertain()); assertNull(ledger.get(actor, id).intent());
        assertThrows(IllegalStateException.class, () -> ledger.produceSampled(actor, id, List.of(), "reroll"));
    }
    @Test void unknownNativeEffectCannotCloseEvenWhenItsKnownStoresSaved() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = produced(ledger); ledger.beginCallback(actor, id, witness, 1);
        var observed = ledger.callbackPartEvidence(actor, id, witness, 0, false, true, "UNTRACKED_MOD_EFFECT", Set.of("player:" + actor.owner()));
        assertTrue(observed.uncertain());
        ledger.nativeSaved(actor, id, "callback-part-unknown", MaterialLedger.callbackPartWitness(witness, 0), AiJson.digest("only known stores saved"));
        assertThrows(IllegalStateException.class, () -> ledger.callbackEvidence(actor, id, witness));
        assertThrows(IllegalStateException.class, () -> ledger.recoveryReview(actor, id));
        assertTrue(new MaterialLedger(directory).get(actor, id).uncertain());
    }
    @Test void duplicateOrCrossOwnerPartEvidenceCannotReplayOrCloseTheBatch() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = produced(ledger); ledger.beginCallback(actor, id, witness, 1);
        ledger.callbackPartEvidence(actor, id, witness, 0, true, true, "", Set.of());
        assertThrows(IllegalStateException.class, () -> ledger.callbackPartEvidence(actor, id, witness, 0, true, true, "", Set.of()));
        assertThrows(IllegalStateException.class, () -> ledger.callbackPartEvidence(actor, id, "other", 0, true, true, "", Set.of()));
        assertThrows(SecurityException.class, () -> ledger.callbackEvidence(Actor.player(UUID.randomUUID(), true, true, 4), id, witness));
    }
    @Test void sharedWriterRefusesACompletedCallbackWithoutAnyNativeSaveCoverage() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = produced(ledger); ledger.beginCallback(actor, id, witness, 1);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var ctx = new AgentTool.Context(workspace, actor, new TaskControl(30000), ledger.get(actor, id).taskId());
            var service = new VirtualProcessingService(null, null, ledger, () -> 1);
            try {
                var actual = service.recordNativeCallbackPart(ctx, id, witness, 0,
                        new NativeProcessingCallback.Outcome(true, true, "", null, "actual callback returned but no store witness"));
                assertTrue(actual.uncertain()); assertNotNull(actual.intent()); assertFalse(actual.saved());
                assertTrue(actual.reason().contains("NATIVE_CALLBACK_SAVE_UNCOVERED"));
                assertTrue(actual.reason().contains("actual callback returned"));
                assertThrows(IllegalStateException.class, () -> ledger.callbackEvidence(actor, id, witness));
                var foreign = new AgentTool.Context(workspace, Actor.player(UUID.randomUUID(), true, true, 4), new TaskControl(30000), ctx.taskId());
                assertThrows(SecurityException.class, () -> service.recordNativeCallbackPart(foreign, id, witness, 0,
                        new NativeProcessingCallback.Outcome(false, false, "NOT_ENTERED", null, "")));
            } finally { service.close(); }
        }
    }
}
