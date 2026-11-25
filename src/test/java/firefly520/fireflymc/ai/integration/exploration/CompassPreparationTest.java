package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static firefly520.fireflymc.ai.integration.exploration.CompassPreparationJournal.State;

/** Protocol tests: no synthetic chunk preparation, native generation, or saved-world claim. */
class CompassPreparationTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 0);
    final CompassPreparationBudget budget = new CompassPreparationBudget(2, 5000);

    private CompassPreparationPlan plan(WorkspaceManager.Workspace workspace) {
        var search = new CompassSearchPlan(1, UUID.randomUUID(), owner.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), UUID.randomUUID(), 1, Long.MAX_VALUE, CompassSearchPlan.Kind.STRUCTURE,
                "minecraft:village_plains", "MAIN_HAND", "explorerscompass:explorerscompass", AiJson.digest("held-native-compass"), "minecraft:overworld", 0, 64, 0,
                AiJson.digest("native-config"), 2, false, new CompassSearchBudget(128, 20, 5000), "").signed();
        return new CompassPreparationPlan(UUID.randomUUID(), search, budget, "").signed();
    }
    @Test void disabledByDefaultAndPreparationAllowanceIsBoundInItsOwnApprovalHash() throws Exception {
        assertFalse(CompassPreparationBudget.disabled().enabled());
        assertThrows(IllegalArgumentException.class, () -> new CompassPreparationBudget(9, 5000));
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace);
            assertTrue(plan.intact());
            var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.getAsJsonObject("budget").addProperty("maximumChunks", 8);
            assertFalse(AiJson.GSON.fromJson(changed, CompassPreparationPlan.class).intact());
            assertThrows(SecurityException.class, () -> plan.requireIdentity(workspace, other, plan.search().executionEpoch(), 1, 0));
        }
    }
    @Test void admissionDeadlineNeverRenewsAcrossCandidateRetries() {
        var clock = new java.util.concurrent.atomic.AtomicLong(10);
        var deadline = budget.start(clock::get); deadline.beforeDispatch();
        clock.addAndGet(4_999_999_999L); deadline.beforeDispatch(); clock.incrementAndGet();
        assertTrue(deadline.expired()); assertThrows(IllegalStateException.class, deadline::beforeDispatch);
    }
    @Test void oneInFlightIsSharedAcrossPlayersAndDispatchedCancellationDoesNotReleaseIt() {
        var gate = new CompassPreparationGate(); var id = UUID.randomUUID();
        var first = gate.acquire(owner, id, "minecraft:overworld", 1, budget).orElseThrow();
        assertTrue(gate.acquire(other, UUID.randomUUID(), "minecraft:overworld", 2, budget).isEmpty());
        gate.dispatched(owner, first); assertFalse(gate.cancel(owner, first)); assertTrue(first.cancelled());
        assertTrue(gate.pending()); assertTrue(gate.acquire(other, UUID.randomUUID(), "minecraft:overworld", 2, budget).isEmpty());
        assertThrows(SecurityException.class, () -> gate.nativeCompleted(other, first));
        gate.nativeCompleted(owner, first); assertFalse(gate.pending());
        assertThrows(IllegalStateException.class, () -> gate.acquire(owner, id, "minecraft:overworld", 1, budget));
        assertTrue(gate.acquire(other, UUID.randomUUID(), "minecraft:overworld", 2, budget).isPresent());
    }
    @Test void exactLeasesBudgetAndDimensionCannotBeExpandedOrReleasedByAnOldLease() {
        var gate = new CompassPreparationGate(); var id = UUID.randomUUID();
        var first = gate.acquire(owner, id, "minecraft:overworld", 1, budget).orElseThrow(); gate.dispatched(owner, first); gate.nativeCompleted(owner, first);
        assertThrows(IllegalStateException.class, () -> gate.acquire(owner, id, "minecraft:the_nether", 2, budget));
        assertThrows(IllegalStateException.class, () -> gate.acquire(owner, id, "minecraft:overworld", 2, new CompassPreparationBudget(8, 5000)));
        var second = gate.acquire(owner, id, "minecraft:overworld", 2, budget).orElseThrow();
        assertThrows(IllegalStateException.class, () -> gate.cancel(owner, first)); gate.dispatched(owner, second); gate.nativeCompleted(owner, second);
        assertThrows(IllegalStateException.class, () -> gate.acquire(owner, id, "minecraft:overworld", 3, budget));
    }
    @Test void undispatchedCancellationCanRetireButRestoredUnknownWorkIsOnlyANegativeFence() {
        var gate = new CompassPreparationGate(); var id = UUID.randomUUID();
        var lease = gate.acquire(owner, id, "minecraft:overworld", 1, budget).orElseThrow(); assertTrue(gate.cancel(owner, lease));
        gate.retire(owner, id); gate.restoreUnknown(owner.owner(), id);
        assertThrows(IllegalStateException.class, () -> gate.acquire(other, UUID.randomUUID(), "minecraft:overworld", 2, budget));
        assertThrows(IllegalStateException.class, () -> gate.reconciled(other, id));
        assertThrows(IllegalStateException.class, () -> gate.retire(owner, id));
        gate.reconciled(owner, id); assertFalse(gate.pending());
    }
    @Test void realDispatchAndCanonicalSaveAreDistinctDurableObligationsAndReopenNeverReplays() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            UUID id = plan.search().id(), attempt = UUID.randomUUID(); journal.create(owner, plan); journal.intend(owner, id, attempt, 4, -5);
            journal.dispatched(owner, id, attempt); journal.ready(owner, id, attempt); journal.finishAdmission(owner, id);
            assertFalse(journal.settled(owner));
            var reopened = new CompassPreparationJournal(workspace); var op = reopened.get(owner, id);
            assertEquals(State.RECOVERY_REQUIRED, op.candidates().getFirst().state()); assertTrue(op.candidates().getFirst().nativeReady());
            assertThrows(IllegalStateException.class, () -> reopened.dispatched(owner, id, attempt));
            var unsaved = op; assertThrows(IllegalStateException.class, () -> reopened.reconcile(owner, id, unsaved.revision(), AiJson.digest(AiJson.canonical(unsaved))));
            reopened.nativeSaved(owner, id, attempt, AiJson.digest("test-fixture-canonical-write-only"));
            op = reopened.get(owner, id); assertEquals(State.RECOVERY_REQUIRED, op.candidates().getFirst().state());
            var current = op; assertThrows(IllegalStateException.class, () -> reopened.reconcile(owner, id, current.revision() - 1, AiJson.digest(AiJson.canonical(current))));
            reopened.reconcile(owner, id, op.revision(), AiJson.digest(AiJson.canonical(op))); assertTrue(reopened.settled(owner));
            assertTrue(new CompassPreparationJournal(workspace).settled(owner));
        }
    }
    @Test void unknownFutureDoesNotBecomeKnownByMatchingChunksAndPendingIntentHasNoGenerationToReplay() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            var id = plan.search().id(); var first = UUID.randomUUID(); var second = UUID.randomUUID(); journal.create(owner, plan);
            journal.intend(owner, id, first, 1, 2); journal.dispatched(owner, id, first); journal.intend(owner, id, second, 2, 3);
            var reopened = new CompassPreparationJournal(workspace); var op = reopened.get(owner, id);
            assertEquals(State.RECOVERY_REQUIRED, op.candidates().get(0).state()); assertEquals(State.NOT_ENTERED, op.candidates().get(1).state());
            assertThrows(IllegalStateException.class, () -> reopened.nativeSaved(owner, id, first, AiJson.digest("matching-world-without-native-ready")));
            assertThrows(IllegalStateException.class, () -> reopened.intend(owner, id, UUID.randomUUID(), 3, 4));
            assertThrows(SecurityException.class, () -> reopened.get(other, id));
        }
    }
    @Test void cancellationAcceptsLateEvidenceButDoesNotAutomaticallyClearItsRecoveryFence() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            var id = plan.search().id(); var attempt = UUID.randomUUID(); journal.create(owner, plan); journal.intend(owner, id, attempt, -3, -4); journal.dispatched(owner, id, attempt);
            journal.stopAdmission(owner, id, "CANCELLED"); journal.ready(owner, id, attempt); var saved = journal.nativeSaved(owner, id, attempt, AiJson.digest("late-native-save"));
            assertEquals(State.RECOVERY_REQUIRED, saved.candidates().getFirst().state()); assertFalse(saved.settled());
            assertEquals(saved, journal.nativeSaved(owner, id, attempt, AiJson.digest("late-native-save")));
            assertThrows(IllegalStateException.class, () -> journal.nativeSaved(owner, id, attempt, AiJson.digest("different-save")));
        }
    }
    @Test void duplicatesAreRejectedAndErasureInvalidatesOnlyTheOldEpochView() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            var id = plan.search().id(); var attempt = UUID.randomUUID(); journal.create(owner, plan); journal.intend(owner, id, attempt, 1, 1);
            assertThrows(IllegalStateException.class, () -> journal.intend(owner, id, UUID.randomUUID(), 1, 1));
            assertThrows(IllegalArgumentException.class, () -> journal.intend(owner, id, UUID.randomUUID(), 100, 100));
            journal.notEntered(owner, id, attempt, "CANCELLED_BEFORE_NATIVE"); journal.finishAdmission(owner, id); assertTrue(journal.settled(owner));
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> journal.snapshot(owner)); assertTrue(new CompassPreparationJournal(workspace).snapshot(owner).isEmpty());
        }
    }
    @Test void trustedLateNonEntryCanCloseACancelledDispatchIntentButNeverARealReadyResult() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            var id = plan.search().id(); var attempt = UUID.randomUUID(); journal.create(owner, plan); journal.intend(owner, id, attempt, 1, 1); journal.dispatched(owner, id, attempt);
            journal.stopAdmission(owner, id, "CANCELLED_BEFORE_QUEUED_NATIVE_ADMISSION");
            journal.notEntered(owner, id, attempt, "TRUSTED_EXECUTOR_REJECTED_BEFORE_NATIVE_CALL");
            assertTrue(journal.settled(owner)); assertEquals("", journal.get(owner, id).candidates().getFirst().saveProof());
            var secondPlan = plan(workspace); var secondId = secondPlan.search().id(); var secondAttempt = UUID.randomUUID();
            journal.create(owner, secondPlan); journal.intend(owner, secondId, secondAttempt, 2, 2); journal.dispatched(owner, secondId, secondAttempt);
            journal.ready(owner, secondId, secondAttempt); journal.stopAdmission(owner, secondId, "CANCELLED_AFTER_READY");
            assertThrows(IllegalStateException.class, () -> journal.notEntered(owner, secondId, secondAttempt, "CANNOT_REWRITE_NATIVE_EFFECT"));
        }
    }
    @Test void rejectedNativeSaveKeepsTheObservedReadyFactAndNeverBecomesADiskReceipt() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassPreparationJournal(workspace);
            var id = plan.search().id(); var attempt = UUID.randomUUID(); journal.create(owner, plan); journal.intend(owner, id, attempt, 1, 1); journal.dispatched(owner, id, attempt);
            journal.ready(owner, id, attempt, "NATIVE_WRAPPED_FULL_SAVE_NOT_ACCEPTED"); journal.finishAdmission(owner, id);
            var observed = journal.get(owner, id).candidates().getFirst();
            assertTrue(observed.nativeReady()); assertEquals("NATIVE_WRAPPED_FULL_SAVE_NOT_ACCEPTED", observed.reason()); assertEquals("", observed.saveProof());
            var reopened = new CompassPreparationJournal(workspace); var restored = reopened.get(owner, id);
            assertEquals(State.RECOVERY_REQUIRED, restored.candidates().getFirst().state()); assertTrue(restored.candidates().getFirst().nativeReady());
            assertThrows(IllegalStateException.class, () -> reopened.reconcile(owner, id, restored.revision(), AiJson.digest(AiJson.canonical(restored))));
        }
    }
}
