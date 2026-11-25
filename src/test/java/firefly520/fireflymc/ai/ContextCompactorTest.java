package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);

    @Test void repeatedCompactionsRetainEarlyArtifactsTaskReferencesAndCompleteToolGroups() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            UUID artifact = workspace.saveArtifact(Map.of("material", "early-special-stone", "count", 57));
            var compactor = new ContextCompactor(new ContextCompactor.Settings(14000, 1000, 1000, 1000, .75, 1), "test");
            UUID early = null;
            for (int i = 0; i < 22; i++) {
                var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "goal-" + i);
                if (i == 0) early = task.id();
                var call = new ModelMessage.ToolCall("call-" + i, "lookup", "{}");
                workspace.conversation().append(List.of(
                        Draft.message(task.id(), ModelMessage.text("user", "goal-" + i + "x".repeat(2400)), null),
                        Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), call.id()),
                        Draft.message(task.id(), ModelMessage.result(call.id(), "early-special-stone artifact:" + artifact), call.id()),
                        Draft.message(task.id(), ModelMessage.text("assistant", "verified " + i), null),
                        Draft.checkpoint(task.update(AgentTask.State.COMPLETED, ""))));
                var context = compactor.assemble(workspace, task.id(), "server policy", null);
                Set<String> pending = new HashSet<>();
                for (var message : context.messages()) {
                    message.calls().forEach(c -> pending.add(c.id()));
                    if (message.toolCallId() != null) assertTrue(pending.remove(message.toolCallId()));
                }
                assertTrue(pending.isEmpty());
                assertTrue(context.estimatedTokens() <= 11000);
            }
            assertTrue(ContextCompactor.readLatest(workspace).summaryRevision() >= 5);
            assertEquals(110, workspace.conversation().revision());
            assertNotNull(workspace.conversation().task(early));
            assertFalse(workspace.conversation().search("early-special-stone", 10).isEmpty());
            assertTrue(java.nio.file.Files.readString(workspace.artifact(artifact)).contains("\"count\":57"));
            assertFalse(workspace.conversation().search(early.toString(), 10).isEmpty());
        }
    }

    @Test void activeTaskSurvivesMoreThanFifteenCompactionsWithoutSplittingAnyToolTransaction() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "keep-original-goal-and-uncertainty");
            workspace.conversation().append(List.of(Draft.message(task.id(), ModelMessage.text("user", task.prompt()), null),
                    Draft.checkpoint(task.update(AgentTask.State.EXECUTING, "unverified-build"))));
            var compactor = new ContextCompactor(new ContextCompactor.Settings(15000, 1000, 1000, 1000, .75, 1), "test");
            for (int i = 0; i < 36; i++) {
                String id = "step-" + i;
                workspace.conversation().append(List.of(
                        Draft.message(task.id(), new ModelMessage("assistant", "", List.of(
                                new ModelMessage.ToolCall(id, "lookup", "{}")), null, null), id),
                        Draft.message(task.id(), ModelMessage.result(id, "evidence-" + i + "x".repeat(3600)), id)));
                var context = compactor.assemble(workspace, task.id(), "policy", 3000, null);
                String wire = firefly520.fireflymc.ai.storage.AiJson.GSON.toJson(context.messages());
                assertTrue(wire.contains(task.prompt()));
                assertTrue(wire.contains("unverified-build"));
                assertTrue(wire.contains("evidence-" + i));
                assertTrue(context.estimatedTokens() + 3000 <= compactor.inputBudget());
                Set<String> pending = new HashSet<>();
                for (var message : context.messages()) {
                    message.calls().forEach(call -> assertTrue(pending.add(call.id())));
                    if (message.toolCallId() != null) assertTrue(pending.remove(message.toolCallId()));
                }
                assertTrue(pending.isEmpty());
            }
            assertTrue(ContextCompactor.readLatest(workspace).summaryRevision() >= 15);
            assertEquals(74, workspace.conversation().revision());
            assertFalse(workspace.conversation().search("evidence-0", 10).isEmpty());
            assertEquals(AgentTask.State.EXECUTING, workspace.conversation().task(task.id()).state());
        }
    }

    @Test void compactionRejectsStaleSnapshotAndPinsUnfinishedGoalAndApproval() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var old = AgentTask.create(actor.owner(), UUID.randomUUID(), "old");
            var current = AgentTask.create(actor.owner(), UUID.randomUUID(), "pending-goal");
            workspace.conversation().append(List.of(
                    Draft.message(old.id(), ModelMessage.text("user", "a".repeat(12000)), null),
                    Draft.checkpoint(old.update(AgentTask.State.COMPLETED, "")),
                    Draft.message(current.id(), ModelMessage.text("user", "pending-goal"), null),
                    Draft.checkpoint(current.update(AgentTask.State.WAITING_APPROVAL, "permission-required"))));
            var compactor = new ContextCompactor(new ContextCompactor.Settings(8000, 1000, 1000, 1000, .75, 1), "test");
            assertThrows(ConcurrentModificationException.class, () -> compactor.assemble(workspace, current.id(), "policy",
                    (source, limit) -> {
                        workspace.conversation().append(List.of(Draft.message(current.id(),
                                ModelMessage.text("user", "newer-message"), null)));
                        return "{}";
                    }));
            assertNull(ContextCompactor.readLatest(workspace));
            var context = compactor.assemble(workspace, current.id(), "policy", null);
            String encoded = firefly520.fireflymc.ai.storage.AiJson.GSON.toJson(context.messages());
            assertTrue(encoded.contains("pending-goal"));
            assertTrue(encoded.contains("WAITING_APPROVAL"));
            assertTrue(encoded.contains("newer-message"));
            assertEquals(5, workspace.conversation().revision());
        }
    }

    @Test void incompleteToolTransactionCannotBeSplitEvenWhenItExceedsContextBudget() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "unfinished");
            workspace.conversation().append(List.of(Draft.message(task.id(),
                    new ModelMessage("assistant", "x".repeat(8000),
                            List.of(new ModelMessage.ToolCall("a", "pending", "{}")), null, null), "a"),
                    Draft.checkpoint(task)));
            var compactor = new ContextCompactor(new ContextCompactor.Settings(5000, 500, 500, 500, .75, 1), "test");
            assertThrows(IllegalStateException.class, () -> compactor.assemble(workspace, task.id(), "policy", null));
            assertEquals(2, workspace.conversation().revision());
        }
    }
}
