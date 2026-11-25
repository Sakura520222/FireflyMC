package firefly520.fireflymc.ai;

import firefly520.fireflymc.client.ai.ChatScrollAnchor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatScrollAnchorTest {
    @Test void openingAndSendingFollowAsynchronousMessagesAfterTheFirstLayout() {
        var scroll = new ChatScrollAnchor();
        assertEquals(0, scroll.layout(0, 0));
        assertEquals(120, scroll.layout(0, 120));
        assertEquals(240, scroll.layout(120, 240));
        scroll.manual(25, 240); assertEquals(25, scroll.layout(25, 300));
        scroll.latest(); assertEquals(300, scroll.layout(25, 300));
        assertEquals(450, scroll.layout(300, 450));
    }
    @Test void readingEarlierHistoryRemainsAnchoredUntilTheExplicitBottomAction() {
        var scroll = new ChatScrollAnchor(); scroll.history();
        assertEquals(0, scroll.layout(0, 180));
        assertEquals(60, scroll.layout(60, 400));
        scroll.latest(); assertEquals(400, scroll.layout(60, 400));
    }
}
