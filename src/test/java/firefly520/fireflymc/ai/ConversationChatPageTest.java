package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationChatPageTest {
    @TempDir Path directory;
    @Test void latestAndEarlierPagesContainChatEvenWhenThousandsOfInternalEventsFollowIt() throws Exception {
        var store = new ConversationStore(directory.resolve("conversation.jsonl"));
        var drafts = new ArrayList<Draft>();
        for (int i = 0; i < 40; i++) {
            drafts.add(Draft.message(null, ModelMessage.text("assistant", "reply " + i), null));
            for (int j = 0; j < 50; j++) drafts.add(new Draft(null, "tool_start", null, new JsonObject(), null));
        }
        store.append(drafts);
        var latest = store.chatPage(0, Long.MAX_VALUE, 24);
        assertEquals(24, latest.events().size()); assertEquals("reply 16", latest.events().getFirst().message().text());
        assertEquals("reply 39", latest.events().getLast().message().text());
        var earlier = store.chatPage(0, latest.events().getFirst().seq(), 24);
        assertEquals(16, earlier.events().size()); assertEquals("reply 0", earlier.events().getFirst().message().text());
        assertEquals("reply 15", earlier.events().getLast().message().text());
        assertTrue(store.chatPage(0, earlier.events().getFirst().seq(), 24).events().isEmpty());
        assertEquals(store.revision(), latest.revision());
        var reopened = new ConversationStore(directory.resolve("conversation.jsonl"));
        assertEquals(latest, reopened.chatPage(0, Long.MAX_VALUE, 24));
        assertTrue(store.eventPage(0, Long.MAX_VALUE, 24, true).events().stream().allMatch(event -> event.message() == null));
        store.erase(store.revision()); assertTrue(store.chatPage(0, Long.MAX_VALUE, 24).events().isEmpty());
    }
}
