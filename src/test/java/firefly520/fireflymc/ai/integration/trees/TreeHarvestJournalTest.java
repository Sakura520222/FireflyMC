package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol tests only. Synthetic observations are not DT gameplay or native-save acceptance. */
class TreeHarvestJournalTest {
    @TempDir Path root;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack axe = new MaterialStack("{\"id\":\"minecraft:iron_axe\",\"count\":1}", 1);
    TreeHarvestPlan plan(WorkspaceManager.Workspace workspace) {
        var pos = new BlueprintDocument.Position(1, 65, 1);
        return new TreeHarvestPlan(1, UUID.randomUUID(), actor.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), UUID.randomUUID(), 1, workspace.conversation().regionSequence(), Long.MAX_VALUE,
                "minecraft:overworld", pos, "NORTH", "dynamictrees:oak", "dynamictrees:oak", "com.dtteam.dynamictrees.block.branch.BasicBranchBlock", pos,
                1, axe, AiJson.digest("synthetic-config"), List.of(new TreeHarvestNative.Cell(pos, new BlueprintDocument.State("dynamictrees:oak_branch", Map.of("radius", "8")))), "").signed();
    }
    TreeHarvestNative.Evidence observation(TreeHarvestNative.State state, NativeSaveBarrier.Receipt save) {
        return new TreeHarvestNative.Evidence(state, "SYNTHETIC", 10, 1, axe, .005F, List.of(), List.of(UUID.randomUUID()),
                List.of(new TreeHarvestNative.Drop(UUID.randomUUID(), new MaterialStack("{\"id\":\"minecraft:oak_log\",\"count\":1}", 4), 1.5, 65, 1.5)), List.of(), List.of(), List.of(), save, CompletableFuture.completedFuture(null));
    }
    @Test void queuedNativeHandoffIsAnUnsettledOnceOnlyIntentAndCannotReplayAcrossRestart() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID attempt = repo.intent(actor, plan.id());
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan.id()));
            var queued = repo.observed(actor, plan.id(), attempt, observation(TreeHarvestNative.State.QUEUED, new NativeSaveBarrier().changed(List.of(new Object()))));
            assertEquals(TreeHarvestNative.State.QUEUED, queued.state()); assertFalse(queued.settled());
            var restored = new TreeHarvestJournal(workspace);
            assertEquals(TreeHarvestNative.State.RECOVERY_REQUIRED, restored.get(actor, plan.id()).state());
            assertThrows(IllegalStateException.class, () -> restored.intent(actor, plan.id()));
            assertEquals(plan, restored.get(actor, plan.id()).plan());
        }
    }
    @Test void acceptedRootCallbackHasItsOwnDurableIntentAndCannotSettleWithoutItsActualResult() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan);
            var rootTick = new TreeHarvestNative.RootTickRecord(UUID.randomUUID(), "dynamictrees:rooty_dirt", plan.root(),
                    new BlueprintDocument.State("dynamictrees:rooty_dirt", Map.of()), 11, "NORMAL", 3);
            UUID accepted = repo.intent(actor, plan.id()); var save = new NativeSaveBarrier().changed(List.of(new Object()));
            var falling = new TreeHarvestNative.Evidence(TreeHarvestNative.State.FALLING, "SYNTHETIC_ROOT_HANDOFF", 10, 1, axe, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(rootTick), List.of(), save, CompletableFuture.completedFuture(null));
            repo.observed(actor, plan.id(), accepted, falling); repo.saved(actor, plan.id(), accepted, AiJson.digest("synthetic-fell-save"));
            UUID intent = repo.intent(actor, plan.id(), rootTick);
            assertTrue(repo.export(actor).contains("nativeRootTickIntent"));
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan.id(), rootTick));
            var incomplete = new TreeHarvestNative.Evidence(TreeHarvestNative.State.COMPLETED, "SYNTHETIC_MISSING_ROOT_RESULT", 11, 1, axe, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(rootTick), List.of(), save, CompletableFuture.completedFuture(null));
            repo.observed(actor, plan.id(), intent, incomplete); repo.saved(actor, plan.id(), intent, AiJson.digest("synthetic-root-save"));
            assertFalse(repo.get(actor, plan.id()).settled());
            assertFalse(repo.get(actor, plan.id()).savedKnownTerminal());
            var restored = new TreeHarvestJournal(workspace);
            assertEquals(TreeHarvestNative.State.RECOVERY_REQUIRED, restored.get(actor, plan.id()).state());
            assertFalse(restored.get(actor, plan.id()).savedKnownTerminal());
        }
    }
    @Test void realDropEvidenceWithoutCanonicalSaveCoverageCannotDeclareCompletion() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID attempt = repo.intent(actor, plan.id());
            var op = repo.observed(actor, plan.id(), attempt, observation(TreeHarvestNative.State.COMPLETED, null));
            assertEquals(TreeHarvestNative.State.RECOVERY_REQUIRED, op.state()); assertFalse(op.settled());
            assertEquals(4, op.observation().drops().getFirst().stack().count());
            assertThrows(IllegalArgumentException.class, () -> repo.saved(actor, plan.id(), attempt, AiJson.digest("not-registered")));
        }
    }
    @Test void lateKnownResultAndPositiveSaveDoNotEraseARecoveryBarrier() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID attempt = repo.intent(actor, plan.id()); repo.uncertain(actor, plan.id(), "NATIVE_RESULT_NOT_ACKNOWLEDGED");
            repo.observed(actor, plan.id(), attempt, observation(TreeHarvestNative.State.COMPLETED, new NativeSaveBarrier().changed(List.of(new Object()))));
            repo.saved(actor, plan.id(), attempt, AiJson.digest("synthetic-canonical-proof"));
            assertEquals(TreeHarvestNative.State.RECOVERY_REQUIRED, repo.get(actor, plan.id()).state()); assertFalse(repo.settled(actor));
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan.id()));
        }
    }
    @Test void everyAcceptedStageRequiresItsOwnProofAndSavedCompletionRemainsStableAcrossRestart() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID first = repo.intent(actor, plan.id());
            repo.observed(actor, plan.id(), first, observation(TreeHarvestNative.State.QUEUED, new NativeSaveBarrier().changed(List.of(new Object()))));
            UUID second = repo.intent(actor, plan.id()); repo.observed(actor, plan.id(), second, observation(TreeHarvestNative.State.COMPLETED, new NativeSaveBarrier().changed(List.of(new Object()))));
            repo.saved(actor, plan.id(), second, AiJson.digest("second-stage")); assertFalse(repo.get(actor, plan.id()).settled());
            repo.saved(actor, plan.id(), first, AiJson.digest("first-stage")); assertTrue(repo.get(actor, plan.id()).settled());
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan.id()));
            assertThrows(IllegalStateException.class, () -> repo.saved(actor, plan.id(), first, AiJson.digest("different-proof")));
            assertTrue(new TreeHarvestJournal(workspace).get(actor, plan.id()).settled());
        }
    }
    @Test void scopeAndOwnerStayBoundAndSettlementReadsNeverAcquireJournalMonitor() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan);
            assertThrows(SecurityException.class, () -> repo.snapshot(Actor.player(UUID.randomUUID(), true, true, 4)));
            assertThrows(IllegalStateException.class, () -> plan.requireIdentity(workspace, actor, UUID.randomUUID(), 1, 0));
            synchronized (repo) {
                assertFalse(CompletableFuture.supplyAsync(() -> { synchronized (workspace.conversation()) { return repo.taskSettled(actor, plan.taskId()); } }).get(2, TimeUnit.SECONDS));
            }
            assertEquals(plan.footprint(), new TreeHarvestJournal(workspace).get(actor, plan.id()).plan().footprint());
        }
    }
    @Test void newVersionApprovalCanSettleOnlyASavedKnownTerminalWithoutReplayingTheOriginalAction() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID attempt = repo.intent(actor, plan.id());
            repo.uncertain(actor, plan.id(), "WAITER_STOPPED_WITH_NATIVE_RESULT_PENDING");
            var late = repo.observed(actor, plan.id(), attempt, observation(TreeHarvestNative.State.COMPLETED, new NativeSaveBarrier().changed(List.of(new Object()))));
            assertFalse(late.savedKnownTerminal());
            assertThrows(IllegalStateException.class, () -> repo.acknowledgeSavedTerminal(actor, plan.id(), late.revision(), AiJson.digest("too-early")));
            var known = repo.saved(actor, plan.id(), attempt, AiJson.digest("synthetic-complete-canonical-proof"));
            assertTrue(known.savedKnownTerminal()); assertFalse(known.settled());
            UUID epoch = UUID.randomUUID();
            var review = new TreeHarvestReconciliation(1, UUID.randomUUID(), plan.id(), actor.owner(), plan.taskId(), workspace.metadata().serverInstanceId(),
                    workspace.primaryConversationId(), workspace.conversation().privacyEpoch(), epoch, known.revision(), AiJson.digest(AiJson.canonical(known)), Long.MAX_VALUE, List.of(), "").signed();
            review.requireIdentity(workspace, actor, epoch); review.requireCurrent(known);
            var settled = repo.acknowledgeSavedTerminal(actor, plan.id(), known.revision(), review.hash());
            assertTrue(settled.settled()); assertEquals(known.observation(), settled.observation()); assertEquals(known.saves(), settled.saves());
            assertEquals("TREE_RECONCILED_SAVED_RESULT:" + review.hash(), settled.reason());
            assertThrows(IllegalStateException.class, () -> review.requireCurrent(settled));
            assertThrows(IllegalStateException.class, () -> repo.acknowledgeSavedTerminal(actor, plan.id(), known.revision(), review.hash()));
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan.id()));
        }
    }
    @Test void unknownNativeObservationNeverAcquiresPositiveRecoveryEvenWhenItsPartialSavesArePresent() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var repo = new TreeHarvestJournal(workspace); var plan = plan(workspace);
            repo.create(actor, plan); UUID attempt = repo.intent(actor, plan.id());
            repo.observed(actor, plan.id(), attempt, observation(TreeHarvestNative.State.RECOVERY_REQUIRED, new NativeSaveBarrier().changed(List.of(new Object()))));
            var unknown = repo.saved(actor, plan.id(), attempt, AiJson.digest("only-partial-native-effects"));
            assertFalse(unknown.savedKnownTerminal()); assertFalse(unknown.settled());
            assertThrows(IllegalStateException.class, () -> repo.acknowledgeSavedTerminal(actor, plan.id(), unknown.revision(), AiJson.digest("cannot-approve-unknown")));
        }
    }
}
