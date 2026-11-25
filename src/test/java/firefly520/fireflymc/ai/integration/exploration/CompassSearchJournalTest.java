package firefly520.fireflymc.ai.integration.exploration;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static firefly520.fireflymc.ai.integration.exploration.CompassSearchJournal.*;
import static org.junit.jupiter.api.Assertions.*;

/** Durable protocol tests only. Native worker, cost and world acceptance are separate dedicated scenarios. */
class CompassSearchJournalTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 0);
    private CompassSearchPlan plan(WorkspaceManager.Workspace workspace) {
        return new CompassSearchPlan(1, UUID.randomUUID(), owner.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), UUID.randomUUID(), 1, Long.MAX_VALUE, CompassSearchPlan.Kind.BIOME,
                "minecraft:plains", "MAIN_HAND", "naturescompass:naturescompass", AiJson.digest("real-held-item-fingerprint"), "minecraft:overworld", 0, 64, 0,
                AiJson.digest("native-config"), 2, false, new CompassSearchBudget(128, 20, 5000), "").signed();
    }
    private Observation before() { return new Observation(AiJson.digest("before"), "INACTIVE", 0, false, false); }
    private Observation after() { return new Observation(AiJson.digest("actual-after"), "FOUND", 1, false, false); }
    private ResultEvidence found() { return new ResultEvidence(true, false, null, null, null, "SERVER_GENERATOR_BIOME_SAMPLE", 6, 1, 1000000, 0); }
    @Test void savedNativeTerminalResultSurvivesReopenAndNeverChargesOrRunsAgain() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3);
            journal.finished(owner, plan.id(), State.FOUND, "", true, true, after(), 3, found());
            assertFalse(journal.settled(owner)); journal.nativeSaved(owner, plan.id(), true, AiJson.digest("positive-final-native-receipt"));
            assertTrue(journal.settled(owner)); var reopened = new CompassSearchJournal(workspace); var op = reopened.get(owner, plan.id());
            assertEquals(State.FOUND, op.state()); assertEquals(5, op.levelsBefore()); assertEquals(3, op.levelsAfterStart());
            assertEquals(1, op.after().damage()); assertThrows(IllegalStateException.class, () -> reopened.create(owner, plan, before(), 5));
            assertThrows(IllegalStateException.class, () -> reopened.finished(owner, plan.id(), State.FOUND, "", true, true, after(), 3, found()));
            assertTrue(CompassSearchService.view(op).get("result").getAsJsonObject().get("verified").getAsBoolean());
            assertFalse(CompassSearchService.view(op).getAsJsonObject("result").has("x"));
        }
    }
    @Test void lateResultAndSaveCannotClearTheExplicitRecoveryFenceWithoutRevisionBoundReconciliation() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3); journal.requireRecovery(owner, plan.id(), "CANCELLED_WITH_NATIVE_RESULT_PENDING");
            var actual = journal.finished(owner, plan.id(), State.FOUND, "LATE_ACCEPTED_RESULT", true, true, after(), 3, found());
            assertEquals(State.RECOVERY_REQUIRED, actual.state()); assertEquals(State.FOUND, actual.terminalState());
            assertThrows(IllegalStateException.class, () -> journal.acknowledgeSavedTerminal(owner, plan.id(), actual.revision()));
            journal.nativeSaved(owner, plan.id(), true, AiJson.digest("late-positive-final-receipt")); var saved = journal.get(owner, plan.id());
            assertEquals(State.RECOVERY_REQUIRED, saved.state());
            assertThrows(IllegalStateException.class, () -> journal.acknowledgeSavedTerminal(owner, plan.id(), actual.revision()));
            assertEquals(State.FOUND, journal.acknowledgeSavedTerminal(owner, plan.id(), saved.revision()).state());
        }
    }
    @Test void unknownNativeCompletionCannotBorrowAStartSaveOrFabricateHiddenCoordinates() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3);
            journal.nativeSaved(owner, plan.id(), false, AiJson.digest("start-file-only"));
            var reopened = new CompassSearchJournal(workspace); assertEquals(State.RECOVERY_REQUIRED, reopened.get(owner, plan.id()).state());
            assertThrows(IllegalStateException.class, () -> reopened.acknowledgeSavedTerminal(owner, plan.id(), reopened.get(owner, plan.id()).revision()));
            assertThrows(IllegalArgumentException.class, () -> reopened.nativeSaved(owner, plan.id(), true, AiJson.digest("unrelated-file")));
            assertThrows(IllegalArgumentException.class, () -> new ResultEvidence(true, false, 1, 2, 64, "hidden", 1, 1, 0, 0));
            assertThrows(SecurityException.class, () -> reopened.get(other, plan.id()));
        }
    }
    @Test void trustedPreDispatchRejectionHasNoNativeSaveObligationAndNoNativeProofIsInvented() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5);
            var rejected = journal.finished(owner, plan.id(), State.STOPPED, "REJECTED_BEFORE_NATIVE_START", false, true, before(), 5, null);
            assertTrue(rejected.saved()); assertTrue(journal.settled(owner)); assertEquals("", rejected.terminalSave());
            assertEquals(5, rejected.levelsAfterStart());
        }
    }
    @Test void planApprovalBindsWorkspaceConnectionEpochGenerationAndCompleteNativeBudget() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace);
            assertTrue(plan.intact());
            plan.requireIdentity(workspace, owner, plan.executionEpoch(), 1, 1);
            assertThrows(SecurityException.class, () -> plan.requireIdentity(workspace, other, plan.executionEpoch(), 1, 1));
            assertThrows(IllegalStateException.class, () -> plan.requireIdentity(workspace, owner, UUID.randomUUID(), 1, 1));
            assertThrows(IllegalStateException.class, () -> plan.requireIdentity(workspace, owner, plan.executionEpoch(), 2, 1));
            var modified = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); modified.getAsJsonObject("budget").addProperty("radiusBlocks", 1024);
            assertFalse(AiJson.GSON.fromJson(modified, CompassSearchPlan.class).intact());
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(SecurityException.class, () -> plan.requireIdentity(workspace, owner, plan.executionEpoch(), 1, 1));
        }
    }
    @Test void privacyAndSharedVerifierCanReadPublishedStateWhileAnotherThreadHoldsTheJournalMonitor() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5);
            var enteredPrimary = new CountDownLatch(1);
            synchronized (journal) {
                var reading = CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) {
                        enteredPrimary.countDown();
                        return List.of(journal.settled(owner), journal.settledForTask(owner, plan.taskId()), journal.snapshot(owner).size() == 1);
                    }
                });
                assertTrue(enteredPrimary.await(1, TimeUnit.SECONDS), "The reader really holds the primary monitor while the other thread holds the journal monitor");
                assertEquals(List.of(false, false, true), reading.get(2, TimeUnit.SECONDS));
            }
            var snapshot = journal.snapshot(owner); assertThrows(UnsupportedOperationException.class, snapshot::clear);
            journal.finished(owner, plan.id(), State.STOPPED, "REJECTED_BEFORE_NATIVE_START", false, true, before(), 5, null);
            assertEquals(State.START_INTENT, snapshot.getFirst().state()); assertTrue(journal.settled(owner));
            assertThrows(SecurityException.class, () -> journal.snapshot(other));
        }
    }
    @Test void confirmedErasureCanForgetOnlySettledPublishedObligationsAndDoesNotImportOrDeleteOldEpochFiles() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5);
            assertFalse(journal.canForgetAfterErasure(owner));
            journal.finished(owner, plan.id(), State.STOPPED, "REJECTED_BEFORE_NATIVE_START", false, true, before(), 5, null);
            assertFalse(journal.canForgetAfterErasure(owner), "A settled journal alone is not authorization to forget its cache");
            var oldFile = workspace.directory().resolve("exploration/epoch-0/journal.jsonl"); assertTrue(Files.isRegularFile(oldFile));
            workspace.conversation().erase(workspace.conversation().revision());
            assertTrue(journal.canForgetAfterErasure(owner));
            assertThrows(SecurityException.class, () -> journal.canForgetAfterErasure(other));
            var next = new CompassSearchJournal(workspace);
            assertTrue(next.snapshot(owner).isEmpty()); assertEquals(1, next.privacyEpoch()); assertTrue(Files.isRegularFile(oldFile));
        }
    }
    @Test void advancingThePrimaryEpochCannotDiscardAnUnsettledNativeSearch() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3);
            workspace.conversation().erase(workspace.conversation().revision());
            assertFalse(journal.canForgetAfterErasure(owner));
            assertTrue(Files.isRegularFile(workspace.directory().resolve("exploration/epoch-0/journal.jsonl")));
        }
    }
    @Test void originalCallSettlementRequiresItsOwnTaskOwnerHashAndOriginalTimelineArtifactIndex() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3);
            journal.finished(owner, plan.id(), State.FOUND, "", true, true, after(), 3, found());
            journal.nativeSaved(owner, plan.id(), true, AiJson.digest("positive-terminal-receipt"));
            var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
            var call = new ModelMessage.ToolCall("original", "compass_search_execute", args.toString());
            assertFalse(journal.settledCall(owner, plan.taskId(), call));
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("hash", plan.hash());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "compass_search_plan", null, manifest, null)));
            synchronized (journal) {
                var verified = CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) { return journal.settledCall(owner, plan.taskId(), call); }
                }).get(2, TimeUnit.SECONDS);
                assertTrue(verified, "The original-call verifier must not take the writer's monitor while holding primary");
            }
            assertFalse(journal.settledCall(other, plan.taskId(), call)); assertFalse(journal.settledCall(owner, UUID.randomUUID(), call));
            args.addProperty("planHash", AiJson.digest("changed-original-plan"));
            assertFalse(journal.settledCall(owner, plan.taskId(), new ModelMessage.ToolCall("changed", call.name(), args.toString())));
        }
    }
    @Test void reconciliationCallRequiresItsIndexedReviewAndTheActualPureLedgerTransition() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var plan = plan(workspace); var journal = new CompassSearchJournal(workspace);
            journal.create(owner, plan, before(), 5); journal.started(owner, plan.id(), before(), 3);
            journal.finished(owner, plan.id(), State.FOUND, "", true, true, after(), 3, found()); journal.nativeSaved(owner, plan.id(), true, AiJson.digest("actual-final-proof"));
            var current = journal.requireRecovery(owner, plan.id(), "RESTART_REVIEW"); var reviewId = UUID.randomUUID(); String hash = AiJson.digest("fixed-review");
            var args = new JsonObject(); args.addProperty("reviewId", reviewId.toString()); args.addProperty("reviewHash", hash);
            var call = new ModelMessage.ToolCall("reconcile", "compass_search_reconcile", args.toString());
            var manifest = new JsonObject(); manifest.addProperty("reviewId", reviewId.toString()); manifest.addProperty("hash", hash); manifest.addProperty("operationId", plan.id().toString());
            manifest.addProperty("originalPlanHash", plan.hash()); manifest.addProperty("operationRevision", current.revision());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "compass_reconcile_preview", null, manifest, null)));
            assertFalse(journal.settledCall(owner, plan.taskId(), call));
            journal.acknowledgeSavedTerminal(owner, plan.id(), current.revision()); assertTrue(journal.settledCall(owner, plan.taskId(), call));
            assertFalse(journal.settledCall(owner, UUID.randomUUID(), call));
            args.addProperty("reviewHash", AiJson.digest("another-review"));
            assertFalse(journal.settledCall(owner, plan.taskId(), new ModelMessage.ToolCall("forged", call.name(), args.toString())));
        }
    }
}
