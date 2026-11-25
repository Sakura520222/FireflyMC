package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.client.ai.WorkspaceClientState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InlineConversationTest {
    private WorkspaceClientState state() {
        var state = new WorkspaceClientState();
        var event = new JsonObject(); event.addProperty("seq", 7); event.addProperty("messageId", "message-7");
        event.addProperty("role", "assistant"); event.addProperty("text", "preview"); event.addProperty("truncated", true);
        state.events.put(7L, event); return state;
    }
    private JsonObject chunk(String full, int offset, int end) {
        var data = new JsonObject(); data.addProperty("seq", 7); data.addProperty("messageId", "message-7");
        data.addProperty("offset", offset); data.addProperty("nextOffset", end); data.addProperty("length", full.length());
        data.addProperty("text", full.substring(offset, end)); return data;
    }
    @Test void longMarkdownAutomaticallyAssemblesAllChunksWithoutTruncationOrTurningIntoJson() {
        var state = state(); String full = "正文\n".repeat(6000) + "末尾必须可见";
        assertEquals(0, state.nextMessageRead().orElseThrow().get("offset").getAsInt());
        for (int start = 0; start < full.length(); start += 6000) {
            assertEquals(start, state.nextMessageRead().orElseThrow().get("offset").getAsInt());
            assertTrue(state.applyMessagePart(chunk(full, start, Math.min(full.length(), start + 6000))));
        }
        assertEquals(full, state.events.get(7L).get("text").getAsString());
        assertFalse(state.events.get(7L).get("truncated").getAsBoolean());
        assertFalse(state.events.get(7L).has("blocks")); assertTrue(state.nextMessageRead().isEmpty());
        state.latestHistory(); assertEquals(full, state.events.get(7L).get("text").getAsString());
    }
    @Test void gapsAndLateChunksFromAnotherMessageCannotReplaceCurrentChat() {
        var state = state(); assertFalse(state.applyMessagePart(chunk("abcdefgh", 4, 8)));
        assertTrue(state.nextMessageRead().isEmpty()); state.latestHistory(); assertTrue(state.nextMessageRead().isPresent());
        var stale = chunk("old", 0, 3); stale.addProperty("messageId", "another-server-message");
        assertFalse(state.applyMessagePart(stale)); assertEquals("preview", state.events.get(7L).get("text").getAsString());
        state.clearPrivate(); assertFalse(state.applyMessagePart(chunk("old", 0, 3)));
    }
    @Test void componentMessagesAreRestoredAsComponentsAndDetailsAreUnchanged() {
        var state = state(); state.detail.addProperty("artifact", "keep-open");
        String body = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"InfoCard\",\"text\":\"完整说明\"}]}";
        assertTrue(state.applyMessagePart(chunk(body, 0, body.length())));
        assertEquals("InfoCard", state.events.get(7L).getAsJsonArray("blocks").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("keep-open", state.detail.get("artifact").getAsString());
    }
    @Test void reopeningKeepsLoadedHistoryAndRepeatedPreviewsCannotOverwriteTheFullReply() {
        var state = state(); var preview = state.events.get(7L).deepCopy();
        String full = "完整内容".repeat(2000) + "TAIL";
        assertTrue(state.applyMessagePart(chunk(full, 0, full.length())));
        state.latestHistory();
        var page = new JsonObject(); page.addProperty("serverInstanceId", "server"); page.addProperty("conversationId", "main");
        page.addProperty("revision", 100); page.addProperty("lastSeq", 100);
        var events = new JsonArray(); events.add(preview); page.add("events", events); page.add("tasks", new JsonArray());
        var preferences = new JsonObject(); preferences.addProperty("name", "小萤"); page.add("preferences", preferences);
        page.addProperty("nextBeforeSeq", 7); page.addProperty("hasEarlierHistory", true);
        state.applyPage(page);
        assertEquals(full, state.events.get(7L).get("text").getAsString()); assertTrue(state.hasEarlierHistory);
        assertEquals(7, state.nextHistoryBeforeSeq); assertTrue(state.nextMessageRead().isEmpty());
    }
    @Test void unicodeAcrossChunkBoundariesSurvivesActualUtf8RoundTrips() {
        String text = "a".repeat(5999) + "😀中文\n".repeat(2500) + "尾部";
        var state = state();
        for (int offset = 0; offset < text.length();) {
            int end = firefly520.fireflymc.ai.presentation.MessageTextChunks.end(text, offset, 6000);
            var page = chunk(text, offset, end);
            var received = JsonParser.parseString(new String(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(state.applyMessagePart(received)); offset = end;
        }
        assertEquals(text, state.events.get(7L).get("text").getAsString());
        assertThrows(IllegalArgumentException.class, () -> firefly520.fireflymc.ai.presentation.MessageTextChunks.end("😀", 1, 6000));
    }
}
