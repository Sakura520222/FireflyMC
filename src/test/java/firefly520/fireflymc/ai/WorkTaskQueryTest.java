package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkTaskQueryTest {
    @TempDir Path directory;
    @Test void defaultProgressFindsRealOlderGameplayWorkBehindManyNewChatRequests() throws Exception {
        var actor = Actor.player(UUID.randomUUID(), true, true, 0);
        var other = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var work = AgentTask.create(actor.owner(), UUID.randomUUID(), "建造小屋");
            var accepted = new JsonObject(); accepted.addProperty("name", "blueprint_generate"); accepted.addProperty("risk", "READ_ONLY");
            workspace.conversation().append(List.of(Draft.checkpoint(work.update(AgentTask.State.WAITING_APPROVAL, "固定版本待确认")),
                    new Draft(work.id(), "tool_start", null, accepted, "native-blueprint-plan")));
            for (int i = 0; i < 20; i++) {
                var chat = AgentTask.create(actor.owner(), UUID.randomUUID(), i % 2 == 0 ? "继续" : "查询材料");
                workspace.conversation().append(List.of(Draft.checkpoint(chat.update(AgentTask.State.COMPLETED, ""))));
            }
            var foreign = AgentTask.create(other.owner(), UUID.randomUUID(), "他人的房屋");
            manager.getWorkspace(other).conversation().append(List.of(Draft.checkpoint(foreign.update(AgentTask.State.WAITING_APPROVAL, ""))));
            var registry = new ToolRegistry(); WorkspaceTools.register(registry);
            var ctx = new AgentTool.Context(workspace, actor, new TaskControl(5000));
            var data = JsonParser.parseString(registry.get("task_list").execute(ctx, new JsonObject()).summary()).getAsJsonObject();
            assertEquals("AI_GAMEPLAY_WORK", data.get("domain").getAsString());
            assertEquals(1, data.get("total").getAsInt());
            assertEquals(work.id().toString(), data.getAsJsonArray("tasks").get(0).getAsJsonObject().get("taskId").getAsString());
            var all = new JsonObject(); all.addProperty("scope", "all");
            var history = JsonParser.parseString(registry.get("task_list").execute(ctx, all).summary()).getAsJsonObject();
            assertEquals(21, history.get("total").getAsInt());
            assertFalse(history.toString().contains(foreign.id().toString()));
        }
    }
}
