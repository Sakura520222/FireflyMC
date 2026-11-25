package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationRetrievalTest {
    @TempDir Path directory;
    @Test void taskLinkFindsOwnVisibleReplyAcrossRestartAndErasureWithoutSelectingToolTraffic() throws Exception {
        var actor = Actor.player(UUID.randomUUID(), true, true, 0);
        var task = firefly520.fireflymc.ai.task.AgentTask.create(actor.owner(), UUID.randomUUID(), "建造");
        long anchor;
        try (var manager = new WorkspaceManager(directory)) {
            var conversation = manager.getWorkspace(actor).conversation();
            conversation.append(List.of(Draft.checkpoint(task), Draft.message(task.id(), ModelMessage.text("user", "建造房屋"), null),
                    Draft.message(task.id(), ModelMessage.text("assistant", "已完成结构，缺少真实燃料"), null),
                    Draft.message(task.id(), ModelMessage.result("tool-id", "内部工具结果"), "tool-id"),
                    Draft.message(task.id(), new ModelMessage("assistant", "", List.of(), null, null), null)));
            anchor = conversation.latestTaskMessage(task.id()).orElseThrow();
            assertEquals("已完成结构，缺少真实燃料", conversation.readableEvent(anchor).orElseThrow().getAsJsonObject("message").get("text").getAsString());
            var other = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 4));
            assertThrows(IllegalArgumentException.class, () -> other.conversation().latestTaskMessage(task.id()));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var conversation = manager.getWorkspace(actor).conversation();
            assertEquals(anchor, conversation.latestTaskMessage(task.id()).orElseThrow());
            assertEquals(anchor, conversation.eventPage(0, anchor + 1, 24, true).events().getLast().seq());
            conversation.append(List.of(Draft.checkpoint(task.update(firefly520.fireflymc.ai.task.AgentTask.State.COMPLETED, "done"))));
            conversation.erase(conversation.revision());
            assertThrows(IllegalArgumentException.class, () -> conversation.latestTaskMessage(task.id()));
        }
    }
    @Test void completeToolResultsRemainSearchableBeyondModelExcerptAndAcrossRestart() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        UUID task = UUID.randomUUID();
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            JsonObject journal = new JsonObject();
            journal.addProperty("fullResult", "x".repeat(6000) + "needle-not-in-excerpt");
            journal.addProperty("artifactId", workspace.saveArtifact(Map.of("value", "original")).toString());
            workspace.conversation().append(List.of(
                    Draft.message(task, new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall("a", "lookup", "{}")), null, null), "a"),
                    Draft.message(task, ModelMessage.result("a", "bounded model excerpt"), "a"),
                    new Draft(task, "tool_result", null, journal, "a")));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var hits = workspace.conversation().searchComplete("needle-not-in-excerpt", Long.MAX_VALUE, 10);
            assertEquals(1, hits.size());
            assertEquals("tool_result", hits.getFirst().kind());
            assertTrue(hits.getFirst().text().length() > 6000);
            assertFalse(hits.getFirst().artifactId().isEmpty());
            assertEquals(2, workspace.conversation().searchComplete("", Long.MAX_VALUE, 10).size());
            var other = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 4));
            assertTrue(other.conversation().searchComplete("needle-not-in-excerpt", Long.MAX_VALUE, 10).isEmpty());
        }
    }
    @Test void descendingSearchCursorRecoversEveryMatchingMessageWithoutDuplicates() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0));
            for (int i = 0; i < 37; i++) workspace.conversation().append(
                    List.of(Draft.message(null, ModelMessage.text("user", "find " + i), null)));
            long cursor = Long.MAX_VALUE;
            Set<Long> sequences = new HashSet<>();
            while (true) {
                var hits = workspace.conversation().searchComplete("find", cursor, 7);
                if (hits.isEmpty()) break;
                for (var hit : hits) assertTrue(sequences.add(hit.seq()));
                cursor = hits.getLast().seq();
            }
            assertEquals(37, sequences.size());
        }
    }
    @Test void historyWindowsReturnOnlyExclusiveCursorRangesAndRetainAllOriginalEvents() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var conversation = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0)).conversation();
            var drafts = new ArrayList<Draft>();
            for (int i = 1; i <= 200; i++) drafts.add(Draft.message(null, ModelMessage.text("user", "line " + i), null));
            conversation.append(drafts);
            var latest = conversation.eventPage(0, Long.MAX_VALUE, 24, true);
            assertEquals(200, latest.revision()); assertEquals(24, latest.events().size());
            assertEquals(177, latest.events().getFirst().seq()); assertEquals(200, latest.events().getLast().seq());
            var earlier = conversation.eventPage(0, 177, 24, true);
            assertEquals(153, earlier.events().getFirst().seq()); assertEquals(176, earlier.events().getLast().seq());
            var incremental = conversation.eventPage(160, Long.MAX_VALUE, 24, false);
            assertEquals(161, incremental.events().getFirst().seq()); assertEquals(184, incremental.events().getLast().seq());
            assertEquals(List.of(3L, 4L), conversation.eventPage(2, 5, 24, false).events().stream().map(e -> e.seq()).toList());
            assertTrue(conversation.eventPage(Long.MAX_VALUE, Long.MAX_VALUE, 24, false).events().isEmpty());
            assertTrue(conversation.eventPage(190, 10, 24, true).events().isEmpty());
            assertEquals(200, conversation.getSnapshot(0).events().size());
            assertEquals("line 1", conversation.readableEvent(1).orElseThrow().getAsJsonObject("message").get("text").getAsString());
        }
    }
    @Test void historyPageAfterPrivacyErasureCannotResurrectOldSequenceContents() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var conversation = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0)).conversation();
            conversation.append(List.of(Draft.message(null, ModelMessage.text("user", "erased content"), null)));
            conversation.erase(1);
            conversation.append(List.of(Draft.message(null, ModelMessage.text("user", "new content"), null)));
            assertTrue(conversation.eventPage(0, 2, 24, true).events().isEmpty());
            assertEquals(List.of(2L, 3L), conversation.eventPage(0, Long.MAX_VALUE, 24, true).events().stream().map(e -> e.seq()).toList());
            assertEquals("new content", conversation.eventPage(2, Long.MAX_VALUE, 24, false).events().getFirst().message().text());
            assertThrows(IllegalArgumentException.class, () -> conversation.eventPage(0, Long.MAX_VALUE, 1000000, false));
        }
    }
}
