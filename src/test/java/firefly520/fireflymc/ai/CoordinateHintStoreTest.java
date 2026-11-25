package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateHintStoreTest {
    @TempDir Path directory;
    private final Actor alice = Actor.player(UUID.randomUUID(), true, true, 0), bob = Actor.player(UUID.randomUUID(), true, true, 0);
    private JsonObject point() {
        var data = new JsonObject(); data.addProperty("version", 1); data.addProperty("requestId", UUID.randomUUID().toString());
        data.addProperty("label", "我的选点"); data.addProperty("dimension", "minecraft:overworld");
        data.addProperty("x", -10); data.addProperty("y", 64); data.addProperty("z", 20);
        data.addProperty("source", "XAERO_WAYPOINT"); data.addProperty("sourceVersion", "26.4.2");
        data.addProperty("authority", "CLIENT_REPORTED"); data.addProperty("worldVerified", false); return data;
    }
    @Test void explicitSinglePointStaysPrivateAndUnverifiedAcrossReopenWithIdempotentRequests() throws Exception {
        var submitted = point(); UUID conversation;
        try (var manager = new WorkspaceManager(directory)) {
            var first = manager.getWorkspace(alice); conversation = first.primaryConversationId();
            var second = manager.getWorkspace(bob); var store = new CoordinateHintStore(first, value -> value);
            var accepted = store.accept(alice, submitted); long revision = first.conversation().revision();
            assertFalse(accepted.get("worldVerified").getAsBoolean()); assertFalse(accepted.get("duplicate").getAsBoolean());
            assertTrue(store.accept(alice, submitted).get("duplicate").getAsBoolean()); assertEquals(revision, first.conversation().revision());
            assertThrows(SecurityException.class, () -> store.accept(bob, submitted));
            assertEquals("", CoordinateHintStore.context(second)); assertTrue(CoordinateHintStore.context(first).contains("CLIENT_REPORTED"));
            assertTrue(first.conversation().getSnapshot(0).tasks().isEmpty(), "selecting a point never starts an agent task");
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice); assertEquals(conversation, workspace.primaryConversationId());
            assertTrue(new CoordinateHintStore(workspace, value -> value).accept(alice, submitted).get("duplicate").getAsBoolean());
            assertTrue(CoordinateHintStore.context(workspace).contains("-10 64 20"));
        }
    }
    @Test void aReusedPointIdentityCannotRewriteCoordinatesOrItsSourceVersion() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice); var store = new CoordinateHintStore(workspace, value -> value);
            var data = point(); store.accept(alice, data); long revision = workspace.conversation().revision();
            for (String field : List.of("x", "sourceVersion")) {
                var changed = data.deepCopy(); if (field.equals("x")) changed.addProperty(field, 123); else changed.addProperty(field, "changed");
                assertThrows(SecurityException.class, () -> store.accept(alice, changed));
                assertEquals(revision, workspace.conversation().revision());
            }
        }
    }
    @Test void maliciousAuthorityExtraMapsAndInexactCoordinatesCannotBecomeServerFacts() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice); var store = new CoordinateHintStore(workspace, value -> value);
            for (String field : List.of("authority", "worldVerified", "mapPixels", "owner", "route")) {
                var data = point();
                if (field.equals("authority")) data.addProperty(field, "SERVER_RUNTIME");
                else if (field.equals("worldVerified")) data.addProperty(field, true); else data.add(field, new JsonArray());
                assertThrows(IllegalArgumentException.class, () -> store.accept(alice, data), field);
            }
            for (String number : List.of("1.5", "30000001", "-30000001", "1e100", "\"10\"")) {
                var data = point(); data.add("x", JsonParser.parseString(number));
                assertThrows(IllegalArgumentException.class, () -> store.accept(alice, data), number);
            }
            assertEquals(0, workspace.conversation().revision());
        }
    }
    @Test void evenAServerTaskReferenceIsOnlyAClientHintAndMustReferToThisWorkspace() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice); var foreign = manager.getWorkspace(bob);
            var task = AgentTask.create(alice.owner(), UUID.randomUUID(), "地点引用"); workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var data = point(); data.addProperty("source", "SERVER_TASK_REFERENCE"); data.addProperty("taskId", task.id().toString());
            assertFalse(new CoordinateHintStore(workspace, value -> value).accept(alice, data).get("worldVerified").getAsBoolean());
            assertThrows(SecurityException.class, () -> new CoordinateHintStore(foreign, value -> value).accept(bob, data));
            assertEquals(0, foreign.conversation().revision());
        }
    }
    @Test void coordinateMetadataDoesNotSplitACommittedModelToolTransaction() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice);
            var call = new ModelMessage.ToolCall("pending-native-call", "inventory_scan", "{}");
            workspace.conversation().append(List.of(Draft.message(null, new ModelMessage("assistant", "", List.of(call), null, null), null)));
            new CoordinateHintStore(workspace, value -> value).accept(alice, point());
            workspace.conversation().append(List.of(Draft.message(null, new ModelMessage("tool", "actual result", List.of(), call.id(), null), call.id())));
            var messages = workspace.conversation().getSnapshot(0).events().stream().filter(event -> event.message() != null).map(event -> event.message().role()).toList();
            assertEquals(List.of("assistant", "tool"), messages);
            assertTrue(CoordinateHintStore.context(workspace).contains("不是操作批准"));
        }
    }
    @Test void sourceTextIsFilteredBeforePersistenceAndNoRawMapOrCredentialIsRetained() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(alice); var data = point(); data.addProperty("label", "api-secret");
            new CoordinateHintStore(workspace, value -> value.replace("api-secret", "[redacted]")).accept(alice, data);
            String persisted = workspace.conversation().getSnapshot(0).events().getFirst().data().toString();
            assertFalse(persisted.contains("api-secret")); assertTrue(persisted.contains("[redacted]"));
            assertFalse(persisted.contains("mapPixels")); assertFalse(persisted.contains("route"));
        }
    }
}
