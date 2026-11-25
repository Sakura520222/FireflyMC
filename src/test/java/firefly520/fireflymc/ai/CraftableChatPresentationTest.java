package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.client.ai.WorkspaceClientState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftableChatPresentationTest {
    @TempDir Path path;
    @Test void toolRoundChatterIsKeptInEvidenceButDoesNotOccupyChatPages() throws Exception {
        var store = new ConversationStore(path.resolve("chat.jsonl"));
        store.append(List.of(ConversationStore.Draft.message(null, new ModelMessage("assistant", "装入工具、继续翻页",
                List.of(new ModelMessage.ToolCall("1", "crafting_discover", "{}")), null, null), "1"),
                ConversationStore.Draft.message(null, ModelMessage.text("assistant", "橡木木板×4"), null)));
        assertEquals(2, store.getSnapshot(0).events().size());
        assertEquals(1, store.chatPage(0, Long.MAX_VALUE, 24).events().size());
    }
    @Test void liveIncrementalProjectionHidesIntermediateChatterAndKeepsTheCompleteCatalog() {
        var state = new WorkspaceClientState();
        state.events.put(1L, JsonParser.parseString("{\"role\":\"assistant\",\"text\":\"继续扫描\",\"intermediate\":true}").getAsJsonObject());
        state.events.put(2L, JsonParser.parseString("{\"role\":\"notice\",\"kind\":\"task_notice\",\"essential\":true,\"text\":\"完整清单\"}").getAsJsonObject());
        assertEquals(1, state.messages().size()); assertEquals("完整清单", state.messages().getFirst().get("text").getAsString());
    }
}
