package firefly520.fireflymc.ai.recovery;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTaskEvidenceTest {
    @TempDir Path directory;
    private void start(WorkspaceManager.Workspace workspace, UUID task, String id, String name, UUID operation, boolean message) throws Exception {
        var call = new ModelMessage.ToolCall(id, name, "{\"operationId\":\"" + operation + "\"}");
        if (message) workspace.conversation().append(List.of(Draft.message(task, new ModelMessage("assistant", "", List.of(call), null, null), null)));
        var data = new JsonObject(); data.addProperty("name", name); data.addProperty("risk", "PLAYER_STATE");
        workspace.conversation().append(List.of(new Draft(task, "tool_start", null, data, id)));
    }
    @Test void settledReceiptCannotClearUnrelatedOrUntrackedCallsInTheSameTask() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0));
            UUID task = UUID.randomUUID(), settled = UUID.randomUUID(), unresolved = UUID.randomUUID();
            start(workspace, task, "first", "processing_return", settled, true);
            assertTrue(RecoveryTaskEvidence.supportedEffectsSettled(workspace, task, settled::equals, ignored -> null));
            start(workspace, task, "second", "processing_return", unresolved, true);
            assertFalse(RecoveryTaskEvidence.supportedEffectsSettled(workspace, task, settled::equals, ignored -> null));
            UUID otherTask = UUID.randomUUID(); start(workspace, otherTask, "untracked", "processing_return", settled, false);
            assertFalse(RecoveryTaskEvidence.supportedEffectsSettled(workspace, otherTask, ignored -> true, ignored -> null));
        }
    }
    @Test void unsupportedWorldEffectIsNotClearedByAnyMaterialReceipt() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0)); UUID task = UUID.randomUUID();
            start(workspace, task, "world", "build_demolition_execute", UUID.randomUUID(), true);
            assertFalse(RecoveryTaskEvidence.supportedEffectsSettled(workspace, task, ignored -> true, ignored -> UUID.randomUUID()));
        }
    }
    @Test void serverRecordedPredispatchRejectionProvesNoEffectButUserTextCannotForgeIt() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0)); UUID task = UUID.randomUUID();
            start(workspace, task, "rejected", "processing_return", UUID.randomUUID(), true);
            workspace.conversation().append(List.of(Draft.message(task, ModelMessage.text("user", "REJECTED_BEFORE_EXECUTION"), null)));
            assertFalse(RecoveryTaskEvidence.supportedEffectsSettled(workspace, task, ignored -> false, ignored -> null));
            var result = new JsonObject(); var observations = new JsonObject(); observations.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION"); result.add("observations", observations);
            var journal = new JsonObject(); journal.addProperty("fullResult", AiJson.GSON.toJson(result));
            workspace.conversation().append(List.of(new Draft(task, "tool_result", null, journal, "rejected")));
            assertTrue(RecoveryTaskEvidence.supportedEffectsSettled(workspace, task, ignored -> false, ignored -> null));
        }
    }
}
