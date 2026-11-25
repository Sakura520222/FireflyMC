package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolDiscoveryTest {
    @TempDir Path directory;
    @Test void largeCatalogExposesOnlyBoundedDiscoveredDefinitionsAndNeverReenablesDisabledTools() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        ToolRegistry registry = new ToolRegistry();
        for (int i = 0; i < 60; i++) registry.register(new DefinedTool(new AgentTool.Descriptor(
                "action_" + i, "capability " + i + "x".repeat(180), JsonParser.parseString(
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}").getAsJsonObject(),
                AgentTool.Risk.READ_ONLY, "knowledge", 0, AgentTool.ExecutionThread.IO, 1000, false, ""), (ctx, args) -> AgentTool.Result.ok("verified")));
        registry.registerDiscovery(name -> !name.equals("action_58"));
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "find a specific capability");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var before = registry.exposed(workspace, task.id(), actor, name -> !name.equals("action_58"), 2000);
            assertEquals(List.of("tools_discover"), before.stream().map(t -> t.name()).toList());
            JsonObject query = new JsonObject(); query.addProperty("query", "action_59");
            registry.get("tools_discover").execute(new AgentTool.Context(workspace, actor, new TaskControl(5000), task.id()), query);
            var after = registry.exposed(workspace, task.id(), actor, name -> !name.equals("action_58"), 2000);
            assertTrue(after.stream().anyMatch(t -> t.name().equals("action_59")));
            assertTrue(ContextCompactor.estimate(after) <= 2000);
            query.addProperty("query", "action_58");
            var disabled = registry.get("tools_discover").execute(new AgentTool.Context(workspace, actor, new TaskControl(5000), task.id()), query);
            assertEquals(0, disabled.observations().get("total").getAsInt());
            assertTrue(registry.exposed(workspace, UUID.randomUUID(), actor, name -> true, 2000).stream()
                    .noneMatch(t -> t.name().equals("action_59")));
        }
    }

    @Test void readersRemainAvailableAndTwoDiscoveryCallsDoNotReplaceEachOthersCapabilities() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        var registry = new ToolRegistry(); WorkspaceTools.register(registry);
        for (int i = 0; i < 60; i++) registry.register(new DefinedTool(new AgentTool.Descriptor(
                "query_" + i, "current server evidence " + "x".repeat(180), JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject(),
                AgentTool.Risk.READ_ONLY, "knowledge", 0, AgentTool.ExecutionThread.IO, 1000, false, ""), (ctx, args) -> AgentTool.Result.ok("data")));
        registry.registerDiscovery(name -> !name.equals("query_59"));
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "query and read");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var before = registry.exposed(workspace, task.id(), actor, name -> true, 9000);
            assertTrue(before.stream().anyMatch(tool -> tool.name().equals("artifact_read")));
            assertTrue(before.stream().anyMatch(tool -> tool.name().equals("conversation_read")));
            for (String name : List.of("query_56", "query_57")) {
                var args = new JsonObject(); var names = new JsonArray(); names.add(name); args.add("names", names);
                var discovery = registry.get("tools_discover").execute(new AgentTool.Context(workspace, actor, new TaskControl(5000), task.id(), 1024), args);
                assertTrue(ContextCompactor.estimate(discovery) <= 1024);
                assertFalse(discovery.observations().get("requiresUserReply").getAsBoolean());
            }
            var names = registry.exposed(workspace, task.id(), actor, name -> !name.equals("query_59"), 9000)
                    .stream().map(tool -> tool.name()).toList();
            assertTrue(names.containsAll(List.of("query_56", "query_57", "artifact_read", "conversation_read")));
            assertFalse(names.contains("query_59"));
            var otherTask = registry.exposed(workspace, UUID.randomUUID(), actor, name -> true, 9000).stream().map(tool -> tool.name()).toList();
            assertTrue(otherTask.contains("artifact_read")); assertFalse(otherTask.contains("query_56"));
        }
    }
}
