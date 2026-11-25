package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.memory.MemoryStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticMemoryTest {
    @TempDir Path directory;
    final Actor a = Actor.player(UUID.randomUUID(), true, true, 0);
    final Actor b = Actor.player(UUID.randomUUID(), true, true, 0);
    @Test void automaticToolPersistsOwnSourceLinkedNoteWithoutApprovalAndDeduplicates() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            workspace.conversation().append(List.of(Draft.message(null, ModelMessage.text("user", "我喜欢用橡木建房"), null)));
            var tools = new ToolRegistry(); WorkspaceTools.register(tools);
            var tool = tools.get("memory_remember");
            var args = JsonParser.parseString("{\"text\":\"玩家喜欢橡木建筑\",\"sourceSeq\":1}").getAsJsonObject();
            var call = new ModelMessage.ToolCall("memory", "memory_remember", args.toString());
            assertEquals(ToolPolicyEngine.Decision.ALLOW, new ToolPolicyEngine()
                    .check(a, a.owner(), tool.descriptor(), true, call, "").decision());
            var context = new AgentTool.Context(workspace, a, new TaskControl(30000));
            assertTrue(tool.execute(context, args).success());
            assertTrue(tool.execute(context, args).success());
            var memory = new MemoryStore(workspace);
            assertEquals(1, memory.view(a).size());
            assertEquals("AI_RECORDED", memory.view(a).getFirst().confidence());
            assertTrue(memory.view(a).getFirst().source().contains(":seq:1"));
            assertTrue(new MemoryStore(manager.getWorkspace(b)).view(b).isEmpty());
            assertThrows(SecurityException.class, () -> tool.execute(new AgentTool.Context(workspace, b, new TaskControl(30000)), args));
        }
        try (var manager = new WorkspaceManager(directory)) {
            assertEquals(1, new MemoryStore(manager.getWorkspace(a)).view(a).size());
        }
    }
    @Test void fabricatedSourcesAndAssistantSpeculationCannotBecomeConfirmedEvidence() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            workspace.conversation().append(List.of(Draft.message(null, ModelMessage.text("assistant", "maybe"), null)));
            var memory = new MemoryStore(workspace);
            assertThrows(IllegalArgumentException.class, () -> memory.rememberAutomatically(a, "guess", 1, null, 0));
            assertThrows(IllegalArgumentException.class, () -> memory.rememberAutomatically(a, "guess", 999, null, 0));
            assertTrue(memory.view(a).isEmpty());
        }
    }
    @Test void staleAutomaticUpdateCannotOverwriteUserCorrectionAndForgetKeepsHistory() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            workspace.conversation().append(List.of(Draft.message(null, ModelMessage.text("user", "橡木或云杉"), null)));
            var memory = new MemoryStore(workspace);
            var note = memory.rememberAutomatically(a, "橡木", 1, null, 0);
            var updated = memory.rememberAutomatically(a, "云杉", 1, note.id(), note.updatedAt());
            assertEquals(note.id(), updated.id());
            assertThrows(IllegalStateException.class, () -> memory.rememberAutomatically(a, "石头", 1, note.id(), note.updatedAt()));
            var corrected = memory.edit(a, note.id(), "只用橡木", updated.updatedAt());
            assertThrows(IllegalStateException.class, () -> memory.rememberAutomatically(a, "石头", 1, note.id(), corrected.updatedAt()));
            memory.forget(a, note.id());
            assertTrue(memory.view(a).isEmpty());
            assertFalse(workspace.conversation().search("橡木", 10).isEmpty());
        }
    }
}
