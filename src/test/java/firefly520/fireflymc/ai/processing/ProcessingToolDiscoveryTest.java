package firefly520.fireflymc.ai.processing;
import com.google.gson.JsonParser;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ProcessingToolDiscoveryTest {
    @TempDir Path directory;
    @Test void chineseCraftingAndEnglishCraftingQueriesDiscoverTheActualExecutor() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var actor = Actor.player(UUID.randomUUID(), true, true, 0);
            var registry = new ToolRegistry(); new VirtualProcessingService(null, null, null, () -> 0).register(registry);
            registry.registerDiscovery(name -> true);
            var context = new AgentTool.Context(manager.getWorkspace(actor), actor, new TaskControl(30000));
            for (String query : new String[]{"合成", "制作", "crafting"}) {
                var response = registry.get("tools_discover").execute(context,
                        JsonParser.parseString("{\"query\":\"" + query + "\"}").getAsJsonObject());
                var names = response.observations().getAsJsonArray("matches").asList().stream()
                        .map(value -> value.getAsJsonObject().get("name").getAsString()).toList();
                assertTrue(names.contains("processing_plan")); assertTrue(names.contains("processing_execute"));
                if (!query.equals("制作")) assertTrue(names.contains("crafting_plan"));
            }
            assertTrue(registry.get("processing_execute").descriptor().risk().requiresApproval());
            assertFalse(registry.get("crafting_plan").descriptor().risk().requiresApproval());
        }
    }
}
