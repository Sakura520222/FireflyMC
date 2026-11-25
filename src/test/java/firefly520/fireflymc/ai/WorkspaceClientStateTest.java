package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.client.ai.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceClientStateTest {
    @TempDir Path directory;
    @Test void privacyErasureImmediatelyDropsAllPrivateTabsWhileKeepingTheAuthenticatedConnection() {
        var state = new WorkspaceClientState(); state.session = UUID.randomUUID();
        UUID session = state.session; state.availability = "READY"; state.serverId = "server"; state.conversationId = "main";
        state.supportedActions = Set.of("OPEN"); state.name = "私人人格"; state.draft = "私人草稿";
        state.buildRegionView.addProperty("private", "old-region"); state.materialView.addProperty("private", "old-stock");
        state.recipeView.addProperty("private", "old-recipe"); state.integrationView.addProperty("private", "old-integration");
        state.erasePrivateViews(81);
        assertEquals(session, state.session); assertTrue(state.ready()); assertEquals("server", state.serverId);
        assertEquals("main", state.conversationId); assertEquals(Set.of("OPEN"), state.supportedActions);
        assertTrue(state.buildRegionView.isEmpty()); assertTrue(state.materialView.isEmpty());
        assertTrue(state.recipeView.isEmpty()); assertTrue(state.integrationView.isEmpty());
        assertEquals("小萤", state.name); assertEquals("", state.draft); assertEquals(81, state.lastSeq);
    }
    private JsonObject page(String server, String conversation, int seq, String text) {
        JsonObject page = new JsonObject();
        page.addProperty("serverInstanceId", server);
        page.addProperty("conversationId", conversation);
        page.addProperty("revision", seq);
        page.addProperty("lastSeq", seq);
        page.add("events", JsonParser.parseString("[{\"seq\":" + seq + ",\"role\":\"assistant\",\"text\":\"" + text + "\"}]"));
        page.add("tasks", new JsonArray());
        page.add("preferences", JsonParser.parseString("{\"name\":\"小萤\"}"));
        return page;
    }
    @Test void replayIsIdempotentAndAnotherServerCannotKeepPrivateEvents() {
        var state = new WorkspaceClientState();
        state.applyPage(page("server-a", "conversation", 3, "A-private"));
        state.applyPage(page("server-a", "conversation", 3, "A-private"));
        assertEquals(1, state.events.size());
        state.buildRegionView = JsonParser.parseString("{\"privacyEpoch\":0,\"revision\":7,\"privateMarker\":\"A-region\"}").getAsJsonObject();
        state.integrationView.addProperty("modId", "server-a-only");
        state.applyPage(page("server-b", "conversation", 1, "B-private"));
        assertEquals(1, state.events.size());
        assertEquals("B-private", state.events.firstEntry().getValue().get("text").getAsString());
        assertEquals(1, state.lastSeq);
        assertTrue(state.buildRegionView.isEmpty()); assertTrue(state.integrationView.isEmpty());
        state.clearPrivate();
        assertTrue(state.events.isEmpty());
        assertFalse(state.ready());
    }
    @Test void regionReconnectProjectionCannotRollBackNewerSelectionOrRestoreAnErasedEpoch() {
        var state = new WorkspaceClientState();
        var current = JsonParser.parseString("{\"privacyEpoch\":0,\"revision\":7}").getAsJsonObject();
        assertTrue(state.acceptBuildRegion(current));
        assertFalse(state.acceptBuildRegion(JsonParser.parseString("{\"privacyEpoch\":0,\"revision\":6}").getAsJsonObject()));
        var page = page("server", "conversation", 20, "same conversation");
        page.add("buildRegion", JsonParser.parseString("{\"privacyEpoch\":10,\"revision\":0,\"empty\":true}"));
        state.applyPage(page); assertEquals(10, state.buildRegionView.get("privacyEpoch").getAsLong());
        assertFalse(state.acceptBuildRegion(current)); assertTrue(state.buildRegionView.get("empty").getAsBoolean());
        var proposal = JsonParser.parseString("{\"privacyEpoch\":10,\"revision\":0,\"projectionRevision\":25,\"proposal\":{\"hash\":\"new\"}}").getAsJsonObject();
        assertTrue(state.acceptBuildRegion(proposal));
        var oldProposal = proposal.deepCopy(); oldProposal.addProperty("projectionRevision", 24);
        oldProposal.getAsJsonObject("proposal").addProperty("hash", "old");
        assertFalse(state.acceptBuildRegion(oldProposal));
        state.clearPrivate(); assertTrue(state.buildRegionView.isEmpty());
    }
    @Test void draftsHudAndUnacknowledgedSubmissionsPersistPerServerAndPlayer() throws Exception {
        UUID player = UUID.randomUUID(), id = UUID.randomUUID();
        Path a = AiClientPreferences.path(directory, "server-a", player);
        Path b = AiClientPreferences.path(directory, "server-b", player);
        Path other = AiClientPreferences.path(directory, "server-a", UUID.randomUUID());
        var data = AiClientPreferences.Data.defaults().move(.2, .7)
                .withDraft("unfinished draft", List.of(new AiClientPreferences.Outbox(id, "pending send")));
        AiClientPreferences.save(a, data);
        assertEquals(data, AiClientPreferences.load(a));
        assertEquals("", AiClientPreferences.load(b).draft());
        assertEquals("", AiClientPreferences.load(other).draft());
        assertEquals(id, AiClientPreferences.load(a).outbox().getFirst().requestId());
    }
    @Test void lateHistoryPageCannotRevertTaskStatusOrNewPreferences() {
        var state = new WorkspaceClientState();
        var current = page("server", "conversation", 20, "current");
        current.add("tasks", JsonParser.parseString("[{\"taskId\":\"a\",\"state\":\"COMPLETED\"}]"));
        current.add("preferences", JsonParser.parseString("{\"name\":\"新名称\"}"));
        state.applyPage(current);
        var older = page("server", "conversation", 10, "old");
        older.add("tasks", JsonParser.parseString("[{\"taskId\":\"a\",\"state\":\"EXECUTING\"}]"));
        state.applyPage(older);
        assertEquals(2, state.events.size());
        assertEquals("COMPLETED", state.tasks.getFirst().get("state").getAsString());
        assertEquals("新名称", state.name);
        assertEquals(20, state.lastSeq);
    }
    @Test void hiddenToolTrafficRemainsAvailableInCompleteSyncedHistory() {
        var state = new WorkspaceClientState();
        var data = page("server", "conversation", 3, "final answer");
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":2,\"role\":\"tool\",\"text\":\"private full result\"}"));
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":4,\"role\":\"notice\",\"kind\":\"task_notice\",\"text\":\"tool accounting\"}"));
        data.addProperty("revision", 4); data.addProperty("lastSeq", 4);
        state.applyPage(data);
        assertEquals(1, state.messages().size());
        assertEquals(3, state.events.size());
        assertEquals("private full result", state.events.get(2L).get("text").getAsString());
    }
    @Test void emptyToolRoundAssistantCardsAreHiddenWithoutDeletingHistoryOrRealComponents() {
        var state = new WorkspaceClientState(); var data = page("server", "conversation", 1, "final answer");
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":2,\"role\":\"assistant\",\"text\":\"\",\"blocks\":[{\"type\":\"MarkdownBlock\",\"text\":\"\",\"data\":{}}]}"));
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":3,\"role\":\"assistant\",\"text\":\" \\n\"}"));
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":4,\"role\":\"user\",\"kind\":\"question_answer_input\",\"text\":\"authoritative internal answer\"}"));
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":5,\"role\":\"assistant\",\"text\":\"\",\"blocks\":[{\"type\":\"ItemStackBlock\",\"text\":\"\",\"data\":{\"itemId\":\"minecraft:stone\",\"count\":2}}]}"));
        data.addProperty("revision", 5); data.addProperty("lastSeq", 5); state.applyPage(data);
        assertEquals(2, state.messages().size());
        assertEquals(5, state.events.size()); assertEquals(5, state.lastSeq);
        assertTrue(state.events.containsKey(2L)); assertTrue(state.events.containsKey(4L));
    }
    @Test void essentialQueueFailureRemainsVisibleWhileToolAccountingIsHidden() {
        var state = new WorkspaceClientState(); var data = page("server", "conversation", 1, "user request");
        data.getAsJsonArray("events").add(JsonParser.parseString("{\"seq\":2,\"role\":\"notice\",\"kind\":\"task_notice\",\"essential\":true,\"text\":\"Queue full; request not executed\"}"));
        data.addProperty("revision", 2); data.addProperty("lastSeq", 2); state.applyPage(data);
        assertEquals(2, state.messages().size());
        assertTrue(state.messages().getLast().get("text").getAsString().contains("not executed"));
    }
    @Test void olderPagesRemainVisibleAtTheCacheLimitWhileLiveTasksKeepUpdating() {
        var state = new WorkspaceClientState();
        var latest = page("server", "conversation", 5000, "latest");
        JsonArray entries = new JsonArray();
        for (int seq = 2953; seq <= 5000; seq++) entries.add(JsonParser.parseString("{\"seq\":" + seq + ",\"role\":\"user\",\"text\":\"message\"}"));
        latest.add("events", entries); state.applyPage(latest);
        state.earlierHistory();
        state.applyPage(page("server", "conversation", 100, "old source"), true);
        assertTrue(state.events.containsKey(100L)); assertEquals(2048, state.events.size());
        var live = page("server", "conversation", 5001, "new live reply");
        live.add("tasks", JsonParser.parseString("[{\"taskId\":\"live\",\"state\":\"COMPLETED\"}]"));
        state.applyPage(live);
        assertTrue(state.events.containsKey(100L)); assertFalse(state.events.containsKey(5001L));
        assertEquals("COMPLETED", state.tasks.getFirst().get("state").getAsString());
        long priorEpoch = state.viewEpoch; state.latestHistory();
        assertTrue(state.viewEpoch > priorEpoch); state.applyPage(live);
        assertTrue(state.events.containsKey(5001L)); assertFalse(state.events.containsKey(100L));
    }
}
