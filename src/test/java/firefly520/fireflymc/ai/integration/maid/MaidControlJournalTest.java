package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Journal/approval behavior only; these synthetic receipts are not native game acceptance. */
class MaidControlJournalTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    final UUID maid = UUID.randomUUID();
    MaidControlPlan.NativeState state(boolean home, boolean seated) {
        return new MaidControlPlan.NativeState(maid, "minecraft:overworld", "touhou_little_maid:idle",
                "com.github.tartaricacid.touhoulittlemaid.entity.task.TaskIdle", 3, home, seated, true, false, "DAY",
                0, 64, 0, 16, false, false, false, false, 0, !seated);
    }
    MaidControlPlan plan(WorkspaceManager.Workspace workspace) {
        return plan(workspace, state(true, false));
    }
    MaidControlPlan plan(WorkspaceManager.Workspace workspace, MaidControlPlan.NativeState before) {
        return new MaidControlPlan(1, UUID.randomUUID(), owner.owner(), UUID.randomUUID(), workspace.metadata().serverInstanceId(),
                workspace.primaryConversationId(), workspace.conversation().privacyEpoch(), UUID.randomUUID(), 100000,
                MaidControlPlan.Mode.FOLLOW, before, "").signed();
    }
    MaidControlNative.Outcome changed(MaidControlPlan.NativeState after) {
        return new MaidControlNative.Outcome(true, true, true, after, "", new NativeSaveBarrier().changed(List.of(new Object())));
    }
    MaidControlReconcile review(WorkspaceManager.Workspace workspace, MaidControlJournal.Operation op) {
        return new MaidControlReconcile(1, UUID.randomUUID(), owner.owner(), op.taskId(), workspace.metadata().serverInstanceId(),
                workspace.primaryConversationId(), workspace.conversation().privacyEpoch(), UUID.randomUUID(), 100000,
                op.id(), op.revision(), AiJson.digest(AiJson.canonical(op)), state(true, true), "").signed();
    }
    @Test void onlyPositiveObservedAndSavedModeEvidenceSettlesTheOperationAndCannotReplay() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); assertTrue(journal.requiresRecovery(owner, plan.taskId()));
            assertFalse(journal.settled(owner)); assertThrows(IllegalStateException.class, () -> journal.intent(owner, plan));
            assertThrows(IllegalStateException.class, () -> journal.saved(owner, plan.id(), AiJson.digest("no-native-return")));
            var observed = journal.observed(owner, plan.id(), changed(state(false, false)));
            assertEquals(MaidControlJournal.State.OBSERVED, observed.state()); assertFalse(observed.settled());
            String proof = AiJson.digest("synthetic-canonical-write"); var saved = journal.saved(owner, plan.id(), proof);
            assertTrue(saved.settled()); assertFalse(journal.requiresRecovery(owner, plan.taskId())); assertTrue(journal.settled(owner));
            assertEquals(saved, journal.saved(owner, plan.id(), proof));
            assertThrows(IllegalStateException.class, () -> journal.saved(owner, plan.id(), AiJson.digest("different-write")));
            assertThrows(IllegalStateException.class, () -> journal.observed(owner, plan.id(), changed(state(false, false))));
            assertEquals(1, journal.list(owner).size());
            assertTrue(workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("maid_control_evidence")
                    && event.taskId().equals(plan.taskId()) && event.data().get("state").getAsString().equals("SAVED")));
        }
    }
    @Test void missingCoverageOrWrongObservedModeKeepsTheNativeOutcomeUncertainEvenAfterAWrite() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace);
            for (var effect : List.of(new MaidControlNative.Outcome(true, true, true, state(false, false), "", null), changed(state(true, false)))) {
                var plan = plan(workspace); journal.intent(owner, plan); var observed = journal.observed(owner, plan.id(), effect);
                assertEquals(MaidControlJournal.State.RECOVERY_REQUIRED, observed.state()); assertFalse(observed.verified());
                var saved = journal.saved(owner, plan.id(), AiJson.digest("write-without-proven-effect:" + plan.id()));
                assertEquals(MaidControlJournal.State.RECOVERY_REQUIRED, saved.state()); assertFalse(saved.settled());
            }
        }
    }
    @Test void priorModeObservationWithoutMutationDoesNotManufactureANativeSaveReceipt() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace, state(false, false));
            journal.intent(owner, plan);
            var op = journal.observed(owner, plan.id(), new MaidControlNative.Outcome(false, false, true, state(false, false), "", null));
            assertEquals(MaidControlJournal.State.NO_CHANGE, op.state()); assertTrue(op.settled()); assertEquals("", op.nativeProof());
            assertThrows(IllegalStateException.class, () -> journal.saved(owner, plan.id(), AiJson.digest("invented-save")));
        }
    }
    @Test void rejectionBeforeNativeInvocationSettlesWithoutChangingModeOrInventingAnAfterSnapshot() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); var op = journal.rejected(owner, plan.id(), "CANCELLED_BEFORE_NATIVE_INVOCATION");
            assertTrue(op.settled()); assertFalse(op.entered()); assertNull(op.after()); assertEquals("", op.nativeProof());
            assertThrows(IllegalStateException.class, () -> journal.observed(owner, plan.id(), changed(state(false, false))));
        }
    }
    @Test void otherPlayerCannotReadOrWriteAnyModeReceiptAndPrivacyChangesFenceTheOldJournal() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); var other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> journal.get(other, plan.id()));
            assertThrows(SecurityException.class, () -> journal.observed(other, plan.id(), changed(state(false, false))));
            assertThrows(SecurityException.class, () -> journal.saved(other, plan.id(), AiJson.digest("foreign-write")));
            assertThrows(SecurityException.class, () -> journal.list(other));
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> journal.get(owner, plan.id()));
        }
    }
    @Test void restartOfAnIntentOrUnsavedOutcomeRequiresReviewWithoutReissuingNativeWork() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace);
            var intent = plan(workspace); var observed = plan(workspace);
            journal.intent(owner, intent); journal.intent(owner, observed); journal.observed(owner, observed.id(), changed(state(false, false)));
            var reopened = new MaidControlJournal(workspace);
            assertEquals(MaidControlJournal.State.RECOVERY_REQUIRED, reopened.get(owner, intent.id()).state());
            assertEquals(MaidControlJournal.State.RECOVERY_REQUIRED, reopened.get(owner, observed.id()).state());
            assertThrows(IllegalStateException.class, () -> reopened.intent(owner, intent));
            assertThrows(IllegalStateException.class, () -> reopened.saved(owner, intent.id(), AiJson.digest("intent-is-not-an-effect")));
            assertTrue(reopened.requiresRecovery(owner, observed.taskId())); assertFalse(reopened.settled(owner));
            assertEquals(2, reopened.list(owner).size());
        }
    }
    @Test void publishedSettlementGateNeverWaitsForTheJournalMonitorWhileHoldingTheConversation() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan);
            synchronized (journal) {
                var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) { return journal.requiresRecovery(owner, plan.taskId()); }
                });
                assertTrue(future.get(2, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }
    @Test void followModeRequiresTheNativeBrainToBeAbleToMoveAndNeverMeansArrival() {
        assertTrue(state(false, false).modeObserved(MaidControlPlan.Mode.FOLLOW));
        assertFalse(state(true, false).modeObserved(MaidControlPlan.Mode.FOLLOW));
        assertFalse(state(false, true).modeObserved(MaidControlPlan.Mode.FOLLOW));
        assertTrue(state(false, true).modeObserved(MaidControlPlan.Mode.STAY));
    }
    @Test void currentStateReconciliationIsDurableBeforeRetirementAndLateOriginalEvidenceCannotClaimSuccess() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); journal.observed(owner, plan.id(), changed(state(false, false)));
            var before = journal.uncertain(owner, plan.id(), "CANCELLED_WHILE_SAVE_PENDING"); var review = review(workspace, before);
            journal.beginReconciliation(owner, review);
            String proof = AiJson.digest("synthetic-fresh-current-state-canonical-proof"); journal.currentProof(owner, review, proof);
            var retirementCalls = new java.util.concurrent.atomic.AtomicInteger();
            var reconciled = journal.reconcileAndRetire(owner, review, proof, committed -> {
                retirementCalls.incrementAndGet(); assertEquals(MaidControlJournal.State.RECONCILED_CURRENT_STATE, committed.state());
                assertTrue(journal.settled(owner));
                assertTrue(workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("maid_control_evidence")
                        && event.data().get("state").getAsString().equals("RECONCILED_CURRENT_STATE")));
            });
            assertEquals(1, retirementCalls.get()); assertTrue(reconciled.settled()); assertFalse(reconciled.verified());
            assertTrue(reconciled.after().home()); assertTrue(reconciled.after().seated());
            assertEquals(proof, reconciled.nativeProof());
            assertEquals(reconciled, journal.saved(owner, plan.id(), AiJson.digest("late-old-receipt-or-approved-handoff")));
            assertEquals(reconciled, journal.observed(owner, plan.id(), changed(state(false, false))));
            assertEquals(MaidControlJournal.State.RECONCILED_CURRENT_STATE, journal.get(owner, plan.id()).state());
            assertEquals(proof, journal.get(owner, plan.id()).nativeProof());
            assertTrue(workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("maid_control_late_native_proof")
                    && !event.data().get("originalModeSuccessClaimed").getAsBoolean()));
            assertThrows(IllegalStateException.class, () -> journal.reconcileAndRetire(owner, review, proof, ignored -> retirementCalls.incrementAndGet()));
            assertEquals(1, retirementCalls.get());
        }
    }
    @Test void journalOrConversationWriteFailureCannotRetireTheOldNativeModeWitness() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); var review = review(workspace, journal.get(owner, plan.id()));
            journal.beginReconciliation(owner, review);
            var file = workspace.directory().resolve("maid-control/journal.jsonl");
            var backup = file.resolveSibling("journal-test-preserved.jsonl");
            assertTrue(file.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()));
            Files.move(file, backup); Files.createDirectory(file);
            var retired = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                assertThrows(java.io.IOException.class, () -> journal.reconcileAndRetire(owner, review, AiJson.digest("positive-current-proof"), ignored -> retired.set(true)));
            } finally { Files.delete(file); Files.move(backup, file); }
            assertFalse(retired.get()); assertFalse(journal.settled(owner)); assertTrue(journal.requiresRecovery(owner, plan.taskId()));
        }
    }
    @Test void aFailedMainConversationMirrorCannotRetireEvenWhenThePrivateJournalWriteSucceeded() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var plan = plan(workspace);
            journal.intent(owner, plan); var review = review(workspace, journal.get(owner, plan.id())); journal.beginReconciliation(owner, review);
            var file = workspace.directory().resolve("conversation").resolve(workspace.primaryConversationId() + ".jsonl");
            var backup = file.resolveSibling("conversation-test-preserved.jsonl");
            assertTrue(file.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()));
            Files.move(file, backup); Files.createDirectory(file);
            var retired = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                assertThrows(java.io.IOException.class, () -> journal.reconcileAndRetire(owner, review, AiJson.digest("positive-current-proof"), ignored -> retired.set(true)));
            } finally { Files.delete(file); Files.move(backup, file); }
            assertFalse(retired.get()); assertFalse(journal.settled(owner)); assertTrue(journal.requiresRecovery(owner, plan.taskId()));
        }
    }
}
