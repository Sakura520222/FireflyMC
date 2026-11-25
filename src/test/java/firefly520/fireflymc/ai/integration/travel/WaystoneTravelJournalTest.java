package firefly520.fireflymc.ai.integration.travel;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** State/approval protocol only. Real Waystones charges, movement and saves use the isolated scenario. */
class WaystoneTravelJournalTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private JsonObject state(int levels, String dimension) {
        var value = new JsonObject(); value.addProperty("levels", levels); value.addProperty("dimension", dimension); return value;
    }
    private final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final UUID epoch = UUID.randomUUID();
        final WaystoneTravelPlan plan = new WaystoneTravelPlan(1, UUID.randomUUID(), workspace.metadata().serverInstanceId(),
                workspace.primaryConversationId(), actor.owner(), UUID.randomUUID(), 0, epoch,
                new WaystoneTravelPlan.Stone(UUID.randomUUID(), "minecraft:overworld", 8, 64, 8, "waystones:waystone", "source", "native:source"),
                new WaystoneTravelPlan.Stone(UUID.randomUUID(), "minecraft:the_nether", 8, 64, 8, "waystones:waystone", "target", "native:target"),
                List.of(0L), List.of(), "{\"type\":\"native-level-fee\",\"levels\":2}", List.of("native fee: two levels"), true, "").signed();
        WaystoneTravelJournal journal = new WaystoneTravelJournal(workspace);
        Fixture() throws Exception {}
        void begin() throws Exception { journal.begin(actor, plan, state(10, "minecraft:overworld")); }
        @Override public void close() throws Exception { manager.close(); }
    }
    @Test void permanentOwnerAndApprovalVersionBindNativeSourceTargetCostsAndEpoch() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.plan.intact()); f.plan.requireIdentity(f.workspace, actor, f.epoch);
            assertThrows(SecurityException.class, () -> f.plan.requireIdentity(f.workspace, Actor.player(UUID.randomUUID(), true, true, 0), f.epoch));
            assertThrows(SecurityException.class, () -> f.plan.requireIdentity(f.workspace, actor, UUID.randomUUID()));
            var changed = AiJson.GSON.toJsonTree(f.plan).getAsJsonObject(); changed.addProperty("requirements", "{\"levels\":3}");
            assertFalse(AiJson.GSON.fromJson(changed, WaystoneTravelPlan.class).intact());
            changed = AiJson.GSON.toJsonTree(f.plan).getAsJsonObject(); changed.getAsJsonObject("target").addProperty("x", 9);
            assertFalse(AiJson.GSON.fromJson(changed, WaystoneTravelPlan.class).intact());
            assertEquals(f.plan, AiJson.GSON.fromJson(AiJson.GSON.toJson(f.plan), WaystoneTravelPlan.class));
        }
    }
    @Test void nativeSuccessWithoutPlayerFileProofCannotSettleOrBeReplayed() throws Exception {
        try (var f = new Fixture()) {
            f.begin(); var outcome = new JsonObject(); outcome.addProperty("nativeSuccess", true);
            f.journal.observed(actor, f.plan.id(), state(8, "minecraft:the_nether"), outcome, true, true, true, "");
            assertEquals(WaystoneTravelJournal.State.RETURNED, f.journal.get(actor, f.plan.id()).state());
            assertFalse(f.journal.settledForTask(actor, f.plan.taskId()));
            assertThrows(IllegalStateException.class, () -> f.journal.begin(actor, f.plan, state(10, "minecraft:overworld")));
            f.journal = new WaystoneTravelJournal(f.workspace);
            assertEquals(WaystoneTravelJournal.State.RECOVERY_REQUIRED, f.journal.get(actor, f.plan.id()).state());
            assertThrows(IllegalStateException.class, () -> f.journal.recoveryReview(actor, f.plan.id()));
            f.journal.nativeSaved(actor, f.plan.id(), AiJson.digest("canonical-player-file-proof"));
            var review = f.journal.recoveryReview(actor, f.plan.id());
            f.journal.reconcile(actor, f.plan.id(), review.revision(), review.reviewHash());
            assertTrue(f.journal.settledForTask(actor, f.plan.taskId()));
            assertEquals(WaystoneTravelJournal.State.COMPLETED, f.journal.get(actor, f.plan.id()).state());
            assertEquals(8, f.journal.get(actor, f.plan.id()).after().get("levels").getAsInt());
        }
    }
    @Test void chargedNativeFailureRequiresSavedResultAndNewApprovalWithoutRefundOrRetry() throws Exception {
        try (var f = new Fixture()) {
            f.begin(); var result = new JsonObject(); result.addProperty("nativeError", "CancelledByEvent");
            f.journal.observed(actor, f.plan.id(), state(8, "minecraft:overworld"), result, true, false, true, "NATIVE_CHARGED_FAILURE");
            assertThrows(IllegalStateException.class, () -> f.journal.recoveryReview(actor, f.plan.id()));
            f.journal.nativeSaved(actor, f.plan.id(), AiJson.digest("saved-native-charged-failure"));
            var preview = f.journal.recoveryReview(actor, f.plan.id());
            assertTrue(preview.feesEntered()); assertFalse(preview.nativeSuccess());
            assertThrows(IllegalStateException.class, () -> f.journal.reconcile(actor, f.plan.id(), preview.revision() + 1, preview.reviewHash()));
            assertThrows(SecurityException.class, () -> f.journal.reconcile(Actor.player(UUID.randomUUID(), true, true, 0), f.plan.id(), preview.revision(), preview.reviewHash()));
            var accepted = f.journal.reconcile(actor, f.plan.id(), preview.revision(), preview.reviewHash());
            assertEquals(WaystoneTravelJournal.State.FAILED, accepted.state());
            assertTrue(accepted.saved()); assertTrue(accepted.settled());
            assertEquals(8, accepted.after().get("levels").getAsInt());
            assertThrows(IllegalStateException.class, () -> f.journal.begin(actor, f.plan, accepted.after()));
            assertThrows(IllegalStateException.class, () -> f.journal.reconcile(actor, f.plan.id(), preview.revision(), preview.reviewHash()));
        }
    }
    @Test void aMatchingPositionAndSaveNeverConvertAnUnknownEffectIntoKnownTravel() throws Exception {
        try (var f = new Fixture()) {
            f.begin(); f.journal.observed(actor, f.plan.id(), state(10, "minecraft:overworld"), new JsonObject(), true, false, false, "NATIVE_UNKNOWN");
            f.journal.nativeSaved(actor, f.plan.id(), AiJson.digest("a-file-was-written-but-effect-remains-unknown"));
            assertThrows(IllegalStateException.class, () -> f.journal.recoveryReview(actor, f.plan.id()));
            assertFalse(f.journal.settledForTask(actor, f.plan.taskId()));
            f.journal = new WaystoneTravelJournal(f.workspace);
            assertThrows(IllegalStateException.class, () -> f.journal.recoveryReview(actor, f.plan.id()));
        }
    }
    @Test void aLateKnownNativeReturnKeepsTheInterruptedBarrierUntilExplicitPositiveReview() throws Exception {
        try (var f = new Fixture()) {
            f.begin(); f.journal.requireRecovery(actor, f.plan.id(), "CANCELLED_WHILE_PREPARING");
            f.journal.observed(actor, f.plan.id(), state(8, "minecraft:the_nether"), new JsonObject(), true, true, true, "LATE_NATIVE_RETURN");
            assertEquals(WaystoneTravelJournal.State.RECOVERY_REQUIRED, f.journal.get(actor, f.plan.id()).state());
            f.journal.nativeSaved(actor, f.plan.id(), AiJson.digest("late-positive-native-player-save"));
            assertEquals(WaystoneTravelJournal.State.RECOVERY_REQUIRED, f.journal.get(actor, f.plan.id()).state());
            var review = f.journal.recoveryReview(actor, f.plan.id());
            f.journal.reconcile(actor, f.plan.id(), review.revision(), review.reviewHash());
            assertTrue(f.journal.settledForTask(actor, f.plan.taskId()));
        }
    }
    @Test void rejectedBeforeNativeInvocationIsAnOwnedTerminalRefusalAndSnapshotsAreDefensive() throws Exception {
        try (var f = new Fixture()) {
            f.begin(); var op = f.journal.rejectedBeforeDispatch(actor, f.plan.id(), "CANCELLED_BEFORE_NATIVE_INVOCATION");
            assertEquals(WaystoneTravelJournal.State.REFUSED, op.state()); assertTrue(op.saved()); assertFalse(op.feesEntered());
            op.before().addProperty("levels", 20000);
            assertEquals(10, f.journal.get(actor, f.plan.id()).before().get("levels").getAsInt());
            assertThrows(UnsupportedOperationException.class, () -> f.journal.snapshot(actor).clear());
            assertThrows(SecurityException.class, () -> f.journal.snapshot(Actor.player(UUID.randomUUID(), true, true, 0)));
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> f.journal.get(actor, f.plan.id()));
        }
    }
    @Test void preparationApprovalIsExactSmallMetadataScopeAndNeverBecomesATravelOrPlayerSaveClaim() throws Exception {
        try (var f = new Fixture()) {
            var destination = f.plan.target();
            var preparation = new WaystoneTargetPreparation(1, UUID.randomUUID(), f.plan.serverInstanceId(), f.plan.conversationId(), actor.owner(),
                    f.plan.taskId(), f.plan.privacyEpoch(), f.epoch, f.plan.source(),
                    new WaystoneTargetPreparation.Target(destination.id(), destination.dimension(), destination.x(), destination.y(), destination.z(), destination.type(), destination.name()), List.of(0L), "EXISTING_FULL_ONLY", "").signed();
            assertTrue(preparation.intact()); preparation.requireIdentity(f.workspace, actor, f.epoch);
            var changed = AiJson.GSON.toJsonTree(preparation).getAsJsonObject(); changed.getAsJsonArray("chunks").add(1L);
            assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(changed, WaystoneTargetPreparation.class));
            f.journal.beginPreparation(actor, preparation);
            assertThrows(IllegalStateException.class, () -> f.journal.beginPreparation(actor, preparation));
            f.journal.preparationLoadingIntent(actor, preparation.id());
            var observed = new JsonObject(); observed.addProperty("targetUuidVerified", true); observed.addProperty("readyForFreshTravelPlan", true);
            var op = f.journal.preparationObserved(actor, preparation.id(), observed, true, "");
            assertEquals(WaystoneTravelJournal.State.PREPARED, op.state()); assertTrue(op.preparation());
            assertFalse(op.feesEntered()); assertFalse(op.nativeSuccess()); assertEquals("", op.nativeSave());
            assertEquals("EPHEMERAL_BOUNDED_NATIVE_PREPARATION_ONLY", op.nativeResult().get("persistenceClaim").getAsString());
            assertTrue(op.settled());
            assertThrows(IllegalStateException.class, () -> f.journal.nativeSaved(actor, preparation.id(), AiJson.digest("not-a-player-mutation")));
            f.journal = new WaystoneTravelJournal(f.workspace);
            assertEquals(WaystoneTravelJournal.State.PREPARED, f.journal.get(actor, preparation.id()).state(), "historical readiness remains history, not current-world proof");
        }
    }
}
