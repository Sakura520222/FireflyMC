package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManualContextCompactorTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private final UUID task = UUID.randomUUID();
    private ContextCompactor compactor(int window) {
        return new ContextCompactor(new ContextCompactor.Settings(window, 1000, 1000, 1000, .75, 1), "test-model");
    }
    private static void text(WorkspaceManager.Workspace workspace, UUID task, String role, String text) throws Exception {
        workspace.conversation().append(List.of(Draft.message(task, ModelMessage.text(role, text), null)));
    }
    private void history(WorkspaceManager.Workspace workspace, int count, int characters) throws Exception {
        for (int i = 0; i < count; i++) text(workspace, task, i % 2 == 0 ? "user" : "assistant", "closed-" + i + "x".repeat(characters));
    }

    @Test void explicitRequestCompactsBelowAutomaticThresholdWithoutModelRequestsOrHistoryWrites() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(64000);
            assertEquals("NO_CLOSED_HISTORY", compactor.compactManually(workspace).status());
            history(workspace, 10, 30);
            var auto = compactor.assemble(workspace, task, "server policy", 100, null);
            assertNull(auto.summary());
            var usage = compactor.inspect(workspace).lastAssembly();
            var before = workspace.conversation().getSnapshot(0);
            var result = compactor.compactManually(workspace);
            assertEquals("COMPACTED", result.status());
            assertEquals("extractive-fallback", result.view().summary().model());
            assertEquals(before, workspace.conversation().getSnapshot(0));
            assertEquals(usage, result.view().lastAssembly());
            long revision = result.view().summary().summaryRevision();
            assertEquals("NO_CLOSED_HISTORY", compactor.compactManually(workspace).status());
            assertEquals(revision, ContextCompactor.readLatest(workspace).summaryRevision());
            assertEquals(before, workspace.conversation().getSnapshot(0));
        }
    }

    @Test void pendingMultiToolTransactionWaitsUntilEveryFullResultIsPresent() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(64000);
            history(workspace, 6, 30);
            var calls = List.of(new ModelMessage.ToolCall("a", "lookup", "{}"), new ModelMessage.ToolCall("b", "lookup", "{}"));
            workspace.conversation().append(List.of(Draft.message(task, new ModelMessage("assistant", "", calls, null, null), "pair")));
            long start = workspace.conversation().revision();
            assertEquals("WAITING_TOOL_TRANSACTION", compactor.compactManually(workspace).status());
            assertEquals(2, compactor.inspect(workspace).openToolCalls()); assertNull(ContextCompactor.readLatest(workspace));
            assertEquals(start, workspace.conversation().revision());
            workspace.conversation().append(List.of(Draft.message(task, ModelMessage.result("a", "FULL_RESULT_A"), "pair")));
            assertEquals("WAITING_TOOL_TRANSACTION", compactor.compactManually(workspace).status());
            assertEquals(1, compactor.inspect(workspace).openToolCalls());
            workspace.conversation().append(List.of(Draft.message(task, ModelMessage.result("b", "FULL_RESULT_B"), "pair")));
            long completed = workspace.conversation().revision();
            text(workspace, task, "assistant", "both tools complete"); text(workspace, task, "user", "newest turn");
            var result = compactor.compactManually(workspace);
            assertEquals("COMPACTED", result.status()); assertEquals(0, result.view().openToolCalls());
            assertTrue(result.view().summary().sourceThroughSeq() >= completed);
            String retained = AiJson.GSON.toJson(workspace.conversation().getSnapshot(0).events());
            assertTrue(retained.contains("FULL_RESULT_A")); assertTrue(retained.contains("FULL_RESULT_B"));
            var pending = new HashSet<String>();
            for (var message : compactor.assemble(workspace, task, "policy", null).messages()) {
                message.calls().forEach(call -> pending.add(call.id()));
                if (message.toolCallId() != null) assertTrue(pending.remove(message.toolCallId()));
            }
            assertTrue(pending.isEmpty());
        }
    }

    @Test void manualNullTaskCannotCompressAnotherTasksUnconsumedFullAnswer() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(64000); UUID other = UUID.randomUUID();
            history(workspace, 6, 50);
            String answer = "UNCONSUMED_TASK_ANSWER:" + "完整回答".repeat(300);
            workspace.conversation().append(List.of(new Draft(task, "question_answer_input", ModelMessage.text("user", answer), new JsonObject(), null)));
            long answerSeq = workspace.conversation().revision();
            text(workspace, other, "user", "unrelated request"); text(workspace, other, "assistant", "unrelated response");
            text(workspace, other, "user", "latest unrelated input");
            var result = compactor.compactManually(workspace);
            assertEquals("COMPACTED", result.status()); assertTrue(result.view().summary().sourceThroughSeq() < answerSeq);
            String projected = AiJson.GSON.toJson(compactor.assemble(workspace, other, "policy", null).messages());
            assertTrue(projected.contains(answer));
            text(workspace, task, "assistant", "accepted answer response"); text(workspace, other, "user", "last turn");
            var consumed = compactor.compactManually(workspace);
            assertEquals("COMPACTED", consumed.status()); assertTrue(consumed.view().summary().sourceThroughSeq() >= answerSeq);
            assertTrue(AiJson.GSON.toJson(workspace.conversation().getSnapshot(0).events()).contains(answer));
        }
    }

    @Test void usageIsHistoricalPrimitiveAccountingAndPrivacyErasureDropsOldUsage() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(64000);
            history(workspace, 4, 10);
            var context = compactor.assemble(workspace, task, "PRIVATE_POLICY_CANARY", 321, null);
            var view = compactor.inspect(workspace); var usage = view.lastAssembly();
            assertEquals(61000, view.inputBudget()); assertEquals(64000, view.contextWindow());
            assertEquals(context.estimatedTokens(), usage.messageTokens()); assertEquals(321, usage.externalTokens());
            assertEquals(context.estimatedTokens() + 321, usage.estimatedInputTokens());
            assertTrue(usage.scope().contains("HISTORICAL")); assertTrue(view.historyEstimateScope().contains("EXCLUDES_SYSTEM_POLICY"));
            assertFalse(AiJson.GSON.toJson(usage).contains("PRIVATE_POLICY_CANARY"));
            compactor.forget(workspace); assertNull(compactor.inspect(workspace).lastAssembly());
            compactor.assemble(workspace, task, "new private policy", 0, null);
            var privacy = new WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            var preview = privacy.preview(actor, workspace); privacy.confirm(actor, workspace, preview.token());
            assertNull(compactor.inspect(workspace).lastAssembly());
        }
    }

    @Test void anAutomaticSummarizerCannotOverwriteAConcurrentManualSummaryOrHoldTheStoreWhileSummarizing() throws Exception {
        try (var manager = new WorkspaceManager(directory);
             var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("manual-compaction-test").factory())) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(9000);
            history(workspace, 10, 900); var committed = new AtomicReference<ContextCompactor.Summary>();
            assertThrows(ConcurrentModificationException.class, () -> compactor.assemble(workspace, task, "policy", (source, limit) -> {
                var result = worker.submit(() -> compactor.compactManually(workspace)).get(5, TimeUnit.SECONDS);
                assertEquals("COMPACTED", result.status()); committed.set(result.view().summary());
                return "{}";
            }));
            assertNotNull(committed.get()); assertEquals(committed.get(), ContextCompactor.readLatest(workspace));
            assertEquals(1, committed.get().summaryRevision()); assertNull(compactor.inspect(workspace).lastAssembly());
        }
    }

    @Test void aNewNonMessageQueueEventInvalidatesAnOlderSummarySnapshot() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var compactor = compactor(9000);
            history(workspace, 10, 900);
            assertThrows(ConcurrentModificationException.class, () -> compactor.assemble(workspace, task, "policy", (source, limit) -> {
                var metadata = new JsonObject(); metadata.addProperty("requestId", UUID.randomUUID().toString());
                metadata.addProperty("status", "QUEUED");
                workspace.conversation().append(List.of(new Draft(null, "context_compaction_request", null, metadata, null)));
                return "{}";
            }));
            assertNull(ContextCompactor.readLatest(workspace));
            assertEquals("COMPACTED", compactor.compactManually(workspace).status());
            assertEquals(11, workspace.conversation().revision());
        }
    }
}
