package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YoloModeTest {
    @TempDir Path directory;
    @Test void yoloIsPersistedPerPlayerAndPartialTogglePreservesTheRestOfTheProfile() throws Exception {
        var a = Actor.player(UUID.randomUUID(), true, true, 0);
        var b = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory)) {
            var first = manager.getWorkspace(a); var second = manager.getWorkspace(b);
            Files.writeString(first.directory().resolve("preferences.json"),
                    "{\"version\":1,\"name\":\"萤\",\"personality\":\"简洁\",\"recordDeaths\":true,\"recordAdvancements\":false}");
            var original = WorkspacePreferences.load(first); assertFalse(original.yoloMode());
            var enabled = original.update(JsonParser.parseString("{\"yoloMode\":true}").getAsJsonObject());
            enabled.save(first, a);
            assertEquals(enabled, WorkspacePreferences.load(first));
            assertEquals("萤", enabled.name()); assertEquals("简洁", enabled.personality()); assertTrue(enabled.recordDeaths());
            assertFalse(WorkspacePreferences.load(second).yoloMode());
            assertThrows(SecurityException.class, () -> enabled.save(first, b));
            assertFalse(enabled.update(JsonParser.parseString("{\"yoloMode\":false}").getAsJsonObject()).yoloMode());
            assertThrows(IllegalArgumentException.class, () -> enabled.update(JsonParser.parseString("{\"yoloMode\":\"true\"}").getAsJsonObject()));
        }
    }
    @Test void everyRiskRunsWithoutApprovalInYoloAndReturnsToManualWhenDisabled() {
        String owner = UUID.randomUUID().toString(); var actor = new Actor(owner, true, true, 0);
        var policy = new ToolPolicyEngine(); var call = new ModelMessage.ToolCall("a", "operation", "{}");
        for (var risk : AgentTool.Risk.values()) {
            var descriptor = new AgentTool.Descriptor("operation", "test", new JsonObject(), risk, "test", 0,
                    AgentTool.ExecutionThread.SERVER, 1000, true, "");
            assertEquals(ToolPolicyEngine.Decision.ALLOW, policy.check(actor, owner, descriptor, true, call, "", true).decision());
            assertEquals(risk.requiresApproval() ? ToolPolicyEngine.Decision.APPROVAL : ToolPolicyEngine.Decision.ALLOW,
                    policy.check(actor, owner, descriptor, true, call, "", false).decision());
            assertEquals(ToolPolicyEngine.Decision.DENY,
                    policy.check(actor, owner, descriptor, false, call, "", true).decision());
            assertEquals(ToolPolicyEngine.Decision.DENY,
                    policy.check(new Actor(UUID.randomUUID().toString(), true, true, 0), owner, descriptor, true, call, "", true).decision());
            assertEquals(ToolPolicyEngine.Decision.PAUSE,
                    policy.check(new Actor(owner, true, false, 0), owner, descriptor, true, call, "", true).decision());
        }
    }
}
