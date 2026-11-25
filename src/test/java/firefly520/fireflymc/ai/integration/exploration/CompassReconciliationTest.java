package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static firefly520.fireflymc.ai.integration.exploration.CompassSearchJournal.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recovery authorization protocol; these fixed evidence hashes are not claims of native execution. */
class CompassReconciliationTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final CompassSearchJournal journal = new CompassSearchJournal(workspace);
        final UUID epoch = UUID.randomUUID();
        final CompassSearchPlan plan = new CompassSearchPlan(1, UUID.randomUUID(), actor.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), 0,
                UUID.randomUUID(), UUID.randomUUID(), 1, Long.MAX_VALUE, CompassSearchPlan.Kind.BIOME, "minecraft:plains", "MAIN_HAND", "naturescompass:naturescompass",
                AiJson.digest("before-item"), "minecraft:overworld", 0, 64, 0, AiJson.digest("native-config"), 2, false, new CompassSearchBudget(128, 20, 5000), "").signed();
        final Observation before = new Observation(AiJson.digest("before-item"), "UNINITIALIZED", 0, false, false);
        final Observation after = new Observation(AiJson.digest("after-item"), "FOUND", 1, false, false);
        Fixture() throws Exception { journal.create(actor, plan, before, 5); journal.started(actor, plan.id(), before, 3); journal.requireRecovery(actor, plan.id(), "NATIVE_RESULT_PENDING"); }
        void terminal(boolean saved) throws Exception {
            journal.finished(actor, plan.id(), State.FOUND, "LATE_KNOWN_NATIVE_RESULT", true, true, after, 3,
                    new ResultEvidence(true, false, null, null, null, "SERVER_GENERATOR_BIOME_SAMPLE", 6, 1, 2000000, 0));
            if (saved) journal.nativeSaved(actor, plan.id(), true, AiJson.digest("positive-known-terminal-save"));
        }
        CompassReconciliation review() {
            return new CompassReconciliation(1, UUID.randomUUID(), plan.id(), actor.owner(), plan.taskId(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                    workspace.conversation().privacyEpoch(), epoch, journal.get(actor, plan.id()), "").signed();
        }
        @Override public void close() throws Exception { manager.close(); }
    }
    @Test void unknownNativeEffectOrASavePendingTerminalCannotAcquireAReconciliationApproval() throws Exception {
        try (var f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, f::review);
            f.journal.nativeSaved(actor, f.plan.id(), false, AiJson.digest("start-save-only"));
            assertThrows(IllegalArgumentException.class, f::review);
            f.terminal(false); assertThrows(IllegalArgumentException.class, f::review);
            f.journal.nativeSaved(actor, f.plan.id(), true, AiJson.digest("positive-known-terminal-save"));
            assertTrue(f.review().intact());
        }
    }
    @Test void knownFeeAndDamageEvidenceAreFixedAndReconciliationNeverRefundsOrReplaysThem() throws Exception {
        try (var f = new Fixture()) {
            f.terminal(true); var review = f.review();
            assertEquals(5, review.evidence().levelsBefore()); assertEquals(3, review.evidence().levelsAfterStart()); assertEquals(1, review.evidence().after().damage());
            review.requireIdentity(f.workspace, actor, f.epoch); review.requireCurrent(f.journal.get(actor, f.plan.id()));
            var roundTrip = AiJson.GSON.fromJson(AiJson.GSON.toJson(review), CompassReconciliation.class); assertEquals(review, roundTrip); assertTrue(roundTrip.intact());
            var restored = f.journal.acknowledgeSavedTerminal(actor, f.plan.id(), review.operationRevision());
            assertEquals(State.FOUND, restored.state()); assertEquals(3, restored.levelsAfterStart()); assertEquals(1, restored.after().damage());
            assertThrows(IllegalStateException.class, () -> review.requireCurrent(restored));
            assertThrows(IllegalStateException.class, () -> f.journal.acknowledgeSavedTerminal(actor, f.plan.id(), review.operationRevision()));
        }
    }
    @Test void anotherOwnerOldEpochPrivacyOrChangedReceiptInvalidatesTheWholeReview() throws Exception {
        try (var f = new Fixture()) {
            f.terminal(true); var review = f.review();
            assertThrows(SecurityException.class, () -> review.requireIdentity(f.workspace, Actor.player(UUID.randomUUID(), true, true, 0), f.epoch));
            assertThrows(SecurityException.class, () -> review.requireIdentity(f.workspace, actor, UUID.randomUUID()));
            f.journal.nativeSaved(actor, f.plan.id(), false, AiJson.digest("late-start-receipt"));
            assertThrows(IllegalStateException.class, () -> review.requireCurrent(f.journal.get(actor, f.plan.id())));
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(SecurityException.class, () -> review.requireIdentity(f.workspace, actor, f.epoch));
        }
    }
}
