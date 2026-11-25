package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.config.AiConfiguration;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.network.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AiBoundariesTest {
    @TempDir Path directory;
    @Test void integratedServerCannotInitializeAnythingEvenWithOperatorOrPrivateIp() {
        AtomicInteger calls = new AtomicInteger();
        assertNull(AiEnvironmentGate.onDedicatedServer(false, () -> { calls.incrementAndGet(); return new Object(); }));
        assertEquals(0, calls.get());
        assertNotNull(AiEnvironmentGate.onDedicatedServer(true, () -> { calls.incrementAndGet(); return new Object(); }));
        assertEquals(1, calls.get());
        assertEquals(AiEnvironmentGate.Availability.NOT_SUPPORTED,
                AiEnvironmentGate.evaluate(false, true, true, true, true, true, true, false, true));
        assertEquals(AiEnvironmentGate.Availability.READY,
                AiEnvironmentGate.evaluate(true, true, true, true, false, true, true, true, true));
        assertEquals(AiEnvironmentGate.Availability.AUTH_REQUIRED,
                AiEnvironmentGate.evaluate(true, true, true, true, false, true, true, false, false));
        assertEquals(AiEnvironmentGate.Availability.DISABLED,
                AiEnvironmentGate.evaluate(true, true, false, true, true, true, true, true, true));
    }

    @Test void absentNewConfigurationDoesNotReadOldConfigOrCreateDirectories() throws Exception {
        Files.writeString(directory.resolve("fireflymc-server.toml"), "[ai]\napiKey=\"old-do-not-use\"\nengine=\"legacy\"");
        assertTrue(AiConfiguration.load(directory.resolve("fireflymc-ai4.json")).isEmpty());
        assertEquals(1, Files.list(directory).count());
        JsonObject old = JsonParser.parseString("{\"schemaVersion\":1,\"enabled\":true,\"ai\":{\"apiKey\":\"old\"}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> AiConfiguration.parse(old));
        var disabled = AiConfiguration.parse(JsonParser.parseString("{\"schemaVersion\":1,\"enabled\":false}").getAsJsonObject());
        assertFalse(disabled.enabled());
        assertEquals("", disabled.apiKey());
        assertEquals("", disabled.endpoint());
        assertFalse(disabled.toString().contains("old"));
        assertFalse(disabled.crossChatSharing());
        assertFalse(disabled.virtualConstruction());
        assertTrue(AiConfiguration.parse(JsonParser.parseString("{\"schemaVersion\":1,\"enabled\":false,\"construction\":{\"allowVirtualProxy\":true}}").getAsJsonObject()).virtualConstruction());
        assertThrows(IllegalArgumentException.class, () -> AiConfiguration.parse(JsonParser.parseString(
                "{\"schemaVersion\":1,\"enabled\":false,\"sharing\":{\"crossChatEnabled\":true}}").getAsJsonObject()));
    }

    @Test void profilesStartPrivateWithNoEventCaptureOrOldNameImportAndConsoleHasNoIdentity() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 4);
        assertThrows(IllegalArgumentException.class, () -> new Actor("console", true, true, 4));
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            Files.writeString(workspace.directory().resolve("companion.json"), "{\"name\":\"old-name\"}");
            var preferences = WorkspacePreferences.load(workspace);
            assertEquals("小萤", preferences.name());
            assertFalse(preferences.recordDeaths());
            assertFalse(preferences.recordAdvancements());
            new WorkspacePreferences(1, "自定义", "温和", true, false).save(workspace, actor);
            assertEquals("自定义", WorkspacePreferences.load(workspace).name());
            assertTrue(Files.exists(workspace.directory().resolve("companion.json")));
        }
    }

    @Test void clientCannotChooseOwnerOrPermissionAndPacketsAreVersionedAndBounded() {
        for (String body : List.of("{\"owner\":\"x\"}", "{\"playerUUID\":\"x\"}", "{\"permissionLevel\":4}",
                "{\"toolResult\":\"success\"}", "[".repeat(30) + "]".repeat(30), "x".repeat(9000))) {
            assertThrows(RuntimeException.class, () -> new AiRequestPayload(1, UUID.randomUUID(),
                    AiProtocol.NO_SESSION, AiProtocol.Action.SUBMIT, body));
        }
        assertThrows(IllegalArgumentException.class, () -> new AiRequestPayload(0, UUID.randomUUID(),
                AiProtocol.NO_SESSION, AiProtocol.Action.HELLO, "{}"));
        var request = new AiRequestPayload(1, UUID.randomUUID(), UUID.randomUUID(), AiProtocol.Action.SUBMIT,
                "{\"text\":\"私有任务\"}");
        var buffer = Unpooled.buffer();
        try {
            AiRequestPayload.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, AiRequestPayload.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
        assertThrows(RuntimeException.class, () -> new AiResponsePayload(1, UUID.randomUUID(), UUID.randomUUID(),
                "open", "{\"nested\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}"));
    }

    @Test void requestReplayIsBoundToContentAndRateLimitIsPerConnection() {
        AtomicLong clock = new AtomicLong(1000);
        var gate = new AiRequestGate(clock::get);
        UUID id = UUID.randomUUID();
        assertTrue(gate.accept(id, "first"));
        assertFalse(gate.accept(id, "first"));
        assertThrows(SecurityException.class, () -> gate.accept(id, "changed"));
        for (int i = 0; i < 13; i++) gate.accept(UUID.randomUUID(), "other");
        assertThrows(IllegalStateException.class, () -> gate.accept(UUID.randomUUID(), "excess"));
        clock.addAndGet(1000);
        assertTrue(gate.accept(UUID.randomUUID(), "next-window"));
    }

    @Test void operatorDoesNotReplaceApprovalAndAdministrativeToolsCannotRegister() {
        var policy = new ToolPolicyEngine();
        String owner = UUID.randomUUID().toString();
        var actor = new Actor(owner, true, true, 4);
        var descriptor = new AgentTool.Descriptor("build_execute", "approved construction", new JsonObject(),
                AgentTool.Risk.WORLD_CHANGE, "build.execute", 0, AgentTool.ExecutionThread.SERVER, 1000, true, "");
        var call = new ModelMessage.ToolCall("a", "build_execute", "{\"x\":1}");
        assertEquals(ToolPolicyEngine.Decision.APPROVAL, policy.check(actor, owner, descriptor, true, call, "").decision());
        String digest = ToolPolicyEngine.digest(owner, call);
        assertEquals(ToolPolicyEngine.Decision.ALLOW, policy.check(actor, owner, descriptor, true, call, digest).decision());
        assertEquals(ToolPolicyEngine.Decision.APPROVAL, policy.check(actor, owner, descriptor, true,
                new ModelMessage.ToolCall("a", "build_execute", "{\"x\":2}"), digest).decision());
        var registry = new ToolRegistry();
        for (String name : List.of("set_time", "set_weather", "give_item", "spawn_entities", "execute_command")) {
            assertThrows(IllegalArgumentException.class, () -> registry.register(new AgentTool() {
                public Descriptor descriptor() {
                    return new Descriptor(name, name, new JsonObject(), Risk.WORLD_CHANGE, "game.write", 0,
                            ExecutionThread.SERVER, 1000, true, "");
                }
                public Result execute(Context context, JsonObject args) { throw new AssertionError("Never execute"); }
            }));
        }
    }

    @Test void schemaRejectsWrongTypesExtraIdentityAndNonFiniteNumbers() {
        var schema = JsonParser.parseString("""
                {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":4}},"required":["count"]}
                """).getAsJsonObject();
        for (String args : List.of("{}", "{\"count\":\"2\"}", "{\"count\":2.5}", "{\"count\":5}",
                "{\"count\":2,\"playerUUID\":\"other\"}", "{\"count\":1e999}")) {
            assertThrows(RuntimeException.class, () -> ToolPolicyEngine.arguments(
                    new ModelMessage.ToolCall("a", "tool", args), schema));
        }
        assertEquals(2, ToolPolicyEngine.arguments(new ModelMessage.ToolCall("a", "tool", "{\"count\":2}"),
                schema).get("count").getAsInt());
    }
}
