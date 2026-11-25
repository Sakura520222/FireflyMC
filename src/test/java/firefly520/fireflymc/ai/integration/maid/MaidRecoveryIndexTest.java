package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact prepared artifact/call identities, not simulated native acceptance. */
class MaidRecoveryIndexTest {
    @TempDir Path root;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    MaidControlPlan.NativeState state() {
        return new MaidControlPlan.NativeState(UUID.randomUUID(), "minecraft:overworld", "touhou_little_maid:idle", "native.TaskIdle", 1,
                false, false, true, false, "DAY", 0, 0, 0, 8, false, false, false, false, 0, true);
    }
    MaidControlPlan plan(WorkspaceManager.Workspace workspace) {
        return new MaidControlPlan(1, UUID.randomUUID(), owner.owner(), UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), 100000, MaidControlPlan.Mode.FOLLOW, state(), "").signed();
    }
    ModelMessage.ToolCall call(MaidControlPlan plan) {
        var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
        return new ModelMessage.ToolCall("mode-" + plan.id(), "maid_control_execute", args.toString());
    }
    @Test void runtimeAckUnderPrimaryConversationLockDoesNotWaitForTheNativeJournalMonitor() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var index = new MaidRecoveryIndex(workspace, journal);
            var plan = plan(workspace); index.mode(plan); journal.intent(owner, plan);
            journal.observed(owner, plan.id(), new MaidControlNative.Outcome(false, false, true, plan.before(), "", null));
            synchronized (journal) {
                var answer = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) { return index.settledCall(workspace, owner, plan.taskId(), call(plan)); }
                }).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(java.util.Optional.of(true), answer);
            }
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, owner, UUID.randomUUID(), call(plan)));
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, Actor.player(UUID.randomUUID(), true, true, 4), plan.taskId(), call(plan)));
            var altered = new JsonObject(); altered.addProperty("planId", plan.id().toString()); altered.addProperty("planHash", AiJson.digest("forged"));
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, owner, plan.taskId(), new ModelMessage.ToolCall("bad", "maid_control_execute", altered.toString())));
            assertTrue(index.settledCall(workspace, owner, plan.taskId(), new ModelMessage.ToolCall("other", "other_module", "{}")).isEmpty());
        }
    }
    @Test void aStoredPreviewNeedsItsOwnDurableAcceptanceAndMatchingOriginalOperationBeforeAck() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(owner); var journal = new MaidControlJournal(workspace); var index = new MaidRecoveryIndex(workspace, journal);
            var plan = plan(workspace); index.mode(plan); journal.intent(owner, plan);
            var op = journal.get(owner, plan.id());
            var review = new MaidControlReconcile(1, UUID.randomUUID(), owner.owner(), plan.taskId(), workspace.metadata().serverInstanceId(),
                    workspace.primaryConversationId(), workspace.conversation().privacyEpoch(), UUID.randomUUID(), 100000,
                    plan.id(), op.revision(), AiJson.digest(AiJson.canonical(op)), plan.before(), "").signed();
            index.review(review); var args = new JsonObject(); args.addProperty("reviewId", review.id().toString()); args.addProperty("reviewHash", review.hash());
            var call = new ModelMessage.ToolCall("recovery", "maid_control_reconcile", args.toString());
            journal.beginReconciliation(owner, review); journal.reconciled(owner, review, AiJson.digest("state-test-current-proof"));
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, owner, plan.taskId(), call));
            index.accepted(UUID.randomUUID(), plan.id(), review.hash());
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, owner, plan.taskId(), call));
            index.accepted(plan.taskId(), plan.id(), review.hash());
            assertEquals(java.util.Optional.of(true), index.settledCall(workspace, owner, plan.taskId(), call));
            workspace.conversation().erase(workspace.conversation().revision());
            assertEquals(java.util.Optional.of(false), index.settledCall(workspace, owner, plan.taskId(), call));
        }
    }
}
