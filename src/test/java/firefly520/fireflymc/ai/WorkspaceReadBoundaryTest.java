package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceReadBoundaryTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    final ToolRegistry tools = new ToolRegistry();
    WorkspaceReadBoundaryTest() { WorkspaceTools.register(tools); }

    @Test void internalPlansAndEntityReceiptsCannotBeRetrievedThroughGenericReaders() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner);
            for (String kind : List.of("construction_plan", "advanced_processing_plan", "special_processing_plan",
                    "special_entity_plan", "material_substitution", "special_entity_prepared", "approval_granted")) {
                JsonObject nativeData = AiJson.object("{\"itemJson\":\"private-book-and-container\",\"token\":\"private-approval\"}", 1000, 8);
                UUID id = workspace.saveArtifact(nativeData);
                nativeData.addProperty("artifactId", id.toString());
                nativeData.addProperty("fullResult", "a similarly named field is not a readable tool result");
                workspace.conversation().append(List.of(new Draft(null, kind, null, nativeData, null)));
                assertEquals("NOT_FOUND", read(workspace, owner, "artifact_read", Map.of("artifactId", id)).errorCode());
                assertEquals("INTERNAL_EVENT_NOT_READABLE", read(workspace, owner, "conversation_read",
                        Map.of("seq", workspace.conversation().revision())).errorCode());
                assertTrue(Files.readString(workspace.artifact(id)).contains("private-book-and-container"));
            }
            assertTrue(workspace.conversation().searchComplete("private-book-and-container", Long.MAX_VALUE, 10).isEmpty());
            assertTrue(AiJson.GSON.toJson(workspace.conversation().getSnapshot(0)).contains("private-book-and-container"));
        }
    }

    @Test void fullCommittedToolResultSurvivesPagingRestartAndChangedArtifactFile() throws Exception {
        String original = "原文🙂".repeat(1600) + "tail-proof";
        UUID id;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner);
            id = workspace.saveArtifact(Map.of("result", original));
            JsonObject event = new JsonObject(); event.addProperty("artifactId", id.toString()); event.addProperty("fullResult", original);
            workspace.conversation().append(List.of(new Draft(null, "tool_result", null, event, "call-1")));
            AiJson.write(workspace.artifact(id), Map.of("itemJson", "private-container-replacement"));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); StringBuilder recovered = new StringBuilder();
            int offset = 0;
            do {
                var page = read(workspace, owner, "artifact_read", Map.of("artifactId", id, "offset", offset));
                assertTrue(page.success()); recovered.append(page.observations().get("text").getAsString());
                int next = page.observations().get("nextOffset").getAsInt(); assertTrue(next > offset); offset = next;
            } while (offset < original.length());
            assertEquals(original, recovered.toString());
            Actor other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertEquals("NOT_FOUND", read(manager.getWorkspace(other), other, "artifact_read", Map.of("artifactId", id)).errorCode());
            assertThrows(SecurityException.class, () -> read(workspace, other, "artifact_read", Map.of("artifactId", id)));
        }
    }

    @Test void historyProjectionKeepsMessagesCallsAndResultsButNotOpaqueProviderState() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner);
            JsonArray opaque = new JsonArray(); opaque.add("opaque-reasoning");
            JsonObject provider = new JsonObject(); provider.addProperty("opaqueReplay", "provider-private-state");
            var message = new ModelMessage("assistant", "可见正文", List.of(new ModelMessage.ToolCall("c", "inventory_list", "{}")), null, opaque, provider);
            workspace.conversation().append(List.of(Draft.message(null, message, "c")));
            var result = read(workspace, owner, "conversation_read", Map.of("seq", 1));
            String projected = result.observations().get("text").getAsString();
            assertTrue(projected.contains("可见正文")); assertTrue(projected.contains("inventory_list"));
            assertFalse(projected.contains("opaque-reasoning")); assertFalse(projected.contains("provider-private-state"));
            assertEquals(opaque, workspace.conversation().getSnapshot(0).events().getFirst().message().responseItems());
            assertEquals(provider, workspace.conversation().getSnapshot(0).events().getFirst().message().providerState());
            assertEquals("NOT_FOUND", read(workspace, owner, "conversation_read", Map.of("seq", Long.MAX_VALUE)).errorCode());
        }
    }

    @Test void explicitPrivacyErasureRevokesPreviouslyReadableArtifactIdsAcrossRestart() throws Exception {
        UUID id;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); id = workspace.saveArtifact(Map.of("text", "old"));
            JsonObject event = new JsonObject(); event.addProperty("artifactId", id.toString()); event.addProperty("fullResult", "old");
            workspace.conversation().append(List.of(new Draft(null, "tool_result", null, event, "old")));
            assertTrue(workspace.conversation().readableArtifact(id).isPresent());
            workspace.conversation().erase(workspace.conversation().revision());
            assertTrue(workspace.conversation().readableArtifact(id).isEmpty());
        }
        try (var manager = new WorkspaceManager(directory)) {
            assertTrue(manager.getWorkspace(owner).conversation().readableArtifact(id).isEmpty());
        }
    }

    private AgentTool.Result read(WorkspaceManager.Workspace workspace, Actor actor, String name, Map<String, ?> args) throws Exception {
        return tools.get(name).execute(new AgentTool.Context(workspace, actor, new TaskControl(30000)),
                AiJson.GSON.toJsonTree(args).getAsJsonObject());
    }
}
