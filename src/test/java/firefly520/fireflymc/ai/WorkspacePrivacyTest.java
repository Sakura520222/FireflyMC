package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.events.PersonalEventStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.sharing.ShareService;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePrivacyTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 0);
    private final Actor b = Actor.player(UUID.randomUUID(), true, true, 4);
    @Test void explicitPrivacyErasureWaitsForNativeSettlementAndPreservesUnrecognizedModuleFiles() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var settled = new java.util.concurrent.atomic.AtomicBoolean(false);
            for (String name : List.of("travel", "maid-control", "exploration")) {
                var folder = workspace.directory().resolve(name); Files.createDirectories(folder);
                Files.writeString(folder.resolve("journal.jsonl"), "native history fixture"); Files.writeString(folder.resolve("my-unrecognized-note.txt"), "keep user file");
            }
            var privacy = new WorkspacePrivacyService(() -> 1000, value -> settled.get());
            assertThrows(IllegalStateException.class, () -> privacy.preview(a, workspace));
            for (String name : List.of("travel", "maid-control", "exploration")) assertTrue(Files.exists(workspace.directory().resolve(name).resolve("journal.jsonl")));
            settled.set(true); var preview = privacy.preview(a, workspace);
            assertThrows(SecurityException.class, () -> privacy.confirm(b, workspace, preview.token()));
            privacy.confirm(a, workspace, preview.token());
            for (String name : List.of("travel", "maid-control", "exploration")) {
                assertFalse(Files.exists(workspace.directory().resolve(name).resolve("journal.jsonl")));
                assertEquals("keep user file", Files.readString(workspace.directory().resolve(name).resolve("my-unrecognized-note.txt")));
            }
        }
    }

    @Test void sharePreviewContainsOnlySelectedSanitizedAnswerAndDispatchesAtMostOnce() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var store = workspace.conversation();
            store.append(List.of(
                    ConversationStore.Draft.message(null, ModelMessage.text("user", "adjacent-private-input"), null),
                    ConversationStore.Draft.message(null, ModelMessage.text("assistant", "selected answer\napiKey=SECRET\nend"), null),
                    ConversationStore.Draft.message(null, ModelMessage.result("tool", "internal-parameters"), "tool")));
            var message = store.getSnapshot(0).events().get(1);
            var clock = new AtomicLong(1000);
            var service = new ShareService(workspace, new SecretFilter(List.of("SECRET")), clock::get);
            var preview = service.preview(a, message.messageId(), ShareService.Target.SERVER_CHAT, "PlayerA", true);
            assertTrue(preview.publicText().contains("selected answer"));
            assertFalse(preview.publicText().contains("SECRET"));
            assertFalse(preview.publicText().contains("adjacent-private-input"));
            assertFalse(preview.publicText().contains("internal-parameters"));
            assertThrows(SecurityException.class, () -> service.confirm(b, preview.id(), UUID.randomUUID(),
                    preview.contentDigest(), preview.target(), true));
            assertThrows(SecurityException.class, () -> service.confirm(a, preview.id(), UUID.randomUUID(),
                    preview.contentDigest(), ShareService.Target.CROSS_CHAT, true));
            UUID request = UUID.randomUUID();
            assertTrue(service.confirm(a, preview.id(), request, preview.contentDigest(), preview.target(), true).dispatch());
            assertFalse(service.confirm(a, preview.id(), request, preview.contentDigest(), preview.target(), true).dispatch());
            var reopened = new ShareService(workspace, new SecretFilter(List.of("SECRET")), clock::get);
            reopened.recoverPending();
            var recovered = reopened.confirm(a, preview.id(), UUID.randomUUID(), preview.contentDigest(), preview.target(), true);
            assertFalse(recovered.dispatch());
            assertEquals(ShareService.State.DELIVERY_UNCERTAIN, recovered.preview().state());
            assertThrows(SecurityException.class, () -> service.preview(a, store.getSnapshot(0).events().get(2).messageId(),
                    ShareService.Target.SERVER_CHAT, "PlayerA", true));
        }
    }

    @Test void expiredPreviewAndUnavailableTargetCannotPublish() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            workspace.conversation().append(List.of(ConversationStore.Draft.message(null,
                    ModelMessage.text("assistant", "one public answer"), null)));
            var id = workspace.conversation().getSnapshot(0).events().getFirst().messageId();
            AtomicLong clock = new AtomicLong(1000);
            var service = new ShareService(workspace, new SecretFilter(List.of()), clock::get);
            assertThrows(IllegalStateException.class, () -> service.preview(a, id, ShareService.Target.CROSS_CHAT, "A", false));
            var preview = service.preview(a, id, ShareService.Target.SERVER_CHAT, "A", true);
            clock.addAndGet(120001);
            assertThrows(SecurityException.class, () -> service.confirm(a, preview.id(), UUID.randomUUID(),
                    preview.contentDigest(), preview.target(), true));
            assertEquals(1, workspace.conversation().revision(), "Preview/cancel must not publish or append a fake result");
        }
    }

    @Test void crossChatRequiresServerConfiguredAudienceAndChangingChannelInvalidatesPriorPreview() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            workspace.conversation().append(List.of(ConversationStore.Draft.message(null,
                    ModelMessage.text("assistant", "selected public answer"), null)));
            var id = workspace.conversation().getSnapshot(0).events().getFirst().messageId();
            var secrets = new SecretFilter(List.of());
            var unconfigured = new ShareService(workspace, secrets, () -> 1000L);
            assertThrows(IllegalStateException.class, () -> unconfigured.preview(a, id, ShareService.Target.CROSS_CHAT, "A", true));
            var service = new ShareService(workspace, secrets, () -> 1000L, Map.of(ShareService.Target.CROSS_CHAT,
                    new ShareService.Channel("cross-chat", "隔离测试频道 A 的成员", "endpoint-a")));
            var preview = service.preview(a, id, ShareService.Target.CROSS_CHAT, "A", true);
            assertEquals("隔离测试频道 A 的成员", preview.audience());
            for (var changed : List.of(new ShareService.Channel("cross-chat", "隔离测试频道 B 的成员", "endpoint-a"),
                    new ShareService.Channel("cross-chat", "隔离测试频道 A 的成员", "endpoint-b"))) {
                var changedService = new ShareService(workspace, secrets, () -> 1000L, Map.of(ShareService.Target.CROSS_CHAT, changed));
                assertThrows(SecurityException.class, () -> changedService.confirm(a, preview.id(), UUID.randomUUID(),
                        preview.contentDigest(), ShareService.Target.CROSS_CHAT, true));
            }
            assertTrue(service.confirm(a, preview.id(), UUID.randomUUID(), preview.contentDigest(), preview.target(), true).dispatch());
        }
    }

    @Test void approvalIsSingleUseAndBindsCurrentExactCallAndOwner() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var call = new ModelMessage.ToolCall("call", "processing_execute", "{\"planId\":\"fixed-plan\"}");
            var task = AgentTask.create(a.owner(), UUID.randomUUID(), "craft")
                    .progress(1, 0, 5, 0, List.of(call), 0, "", true, true)
                    .update(AgentTask.State.WAITING_APPROVAL, "");
            var approvals = new ApprovalStore(workspace, () -> 1000L);
            var preview = approvals.preview(a, task);
            assertThrows(SecurityException.class, () -> approvals.consume(b, preview.token(), UUID.randomUUID(), task));
            var changed = task.progress(1, 0, 5, 0,
                    List.of(new ModelMessage.ToolCall("call", "processing_execute", "{\"planId\":\"other\"}")),
                    0, "", true, true);
            assertThrows(SecurityException.class, () -> approvals.consume(a, preview.token(), UUID.randomUUID(), changed));
            UUID request = UUID.randomUUID();
            assertTrue(approvals.consume(a, preview.token(), request, task));
            assertFalse(approvals.consume(a, preview.token(), request, task));
            assertThrows(SecurityException.class, () -> approvals.consume(a, preview.token(), UUID.randomUUID(), task));
        }
    }

    @Test void personalEventsAreOffByDefaultAndOnlyRecordSubsequentSelectedTypes() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var events = new PersonalEventStore(workspace);
            JsonObject data = new JsonObject();
            data.addProperty("dimension", "minecraft:overworld");
            assertFalse(events.record(a, PersonalEventStore.Type.DEATH, "before-opt-in", data));
            assertFalse(Files.exists(workspace.directory().resolve("personal-events.jsonl")));
            new WorkspacePreferences(1, "小萤", "", true, false).save(workspace, a);
            assertTrue(events.record(a, PersonalEventStore.Type.DEATH, "after-opt-in", data));
            assertFalse(events.record(a, PersonalEventStore.Type.ADVANCEMENT, "unselected", data));
            assertEquals(1, events.page(a, Long.MAX_VALUE, 20).size());
            assertThrows(SecurityException.class, () -> events.page(b, Long.MAX_VALUE, 20));
            WorkspacePreferences.defaults().save(workspace, a);
            assertFalse(events.record(a, PersonalEventStore.Type.DEATH, "after-opt-out", data));
            assertEquals(1, events.page(a, Long.MAX_VALUE, 20).size());
            events.clear(a);
            assertTrue(events.page(a, Long.MAX_VALUE, 20).isEmpty());
            assertEquals(0, workspace.conversation().revision(), "Observations must never trigger a conversation/model/task");
            assertTrue(workspace.conversation().tasks().isEmpty());
        }
    }

    @Test void explicitErasureKeepsConversationIdentityMonotonicSequenceAndReplayProtection() throws Exception {
        UUID conversation;
        UUID oldRequest = UUID.randomUUID();
        long erasedAt;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            conversation = workspace.primaryConversationId();
            var task = AgentTask.create(a.owner(), oldRequest, "private-old-task").update(AgentTask.State.COMPLETED, "");
            workspace.conversation().append(List.of(ConversationStore.Draft.message(task.id(),
                    ModelMessage.text("user", "private-old-text"), null), ConversationStore.Draft.checkpoint(task)));
            UUID artifact = workspace.saveArtifact(Map.of("private", "old-artifact"));
            Files.writeString(workspace.directory().resolve("unrelated-old-file.txt"), "preserve");
            var privacy = new WorkspacePrivacyService(() -> 1000, ignored -> true);
            var preview = privacy.preview(a, workspace);
            assertThrows(SecurityException.class, () -> privacy.confirm(b, workspace, preview.token()));
            erasedAt = privacy.confirm(a, workspace, preview.token());
            assertTrue(erasedAt > preview.revision());
            assertTrue(workspace.conversation().search("private-old", 10).isEmpty());
            assertFalse(Files.exists(workspace.artifact(artifact)));
            assertTrue(Files.exists(workspace.directory().resolve("unrelated-old-file.txt")));
            assertEquals(conversation, workspace.primaryConversationId());
            assertTrue(workspace.conversation().requestWasErased(oldRequest));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            assertEquals(conversation, workspace.primaryConversationId());
            assertTrue(workspace.conversation().requestWasErased(oldRequest));
            workspace.conversation().append(List.of(ConversationStore.Draft.message(null, ModelMessage.text("user", "new"), null)));
            assertEquals(erasedAt + 1, workspace.conversation().revision());
            assertEquals("new", workspace.conversation().getSnapshot(erasedAt).events().getFirst().message().text());
        }
    }

    @Test void erasureRequiresUnchangedPreviewAndNoUnfinishedWork() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var privacy = new WorkspacePrivacyService(() -> 1000, ignored -> true);
            var preview = privacy.preview(a, workspace);
            var pending = AgentTask.create(a.owner(), UUID.randomUUID(), "must-not-lose");
            workspace.conversation().append(List.of(ConversationStore.Draft.checkpoint(pending)));
            assertThrows(SecurityException.class, () -> privacy.confirm(a, workspace, preview.token()));
            assertThrows(IllegalStateException.class, () -> privacy.preview(a, workspace));
            assertNotNull(workspace.conversation().task(pending.id()));
        }
    }
}
