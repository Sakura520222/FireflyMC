package firefly520.fireflymc.ai;

import firefly520.fireflymc.client.ai.WorkspaceComposerLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceComposerLayoutTest {
    @Test void composerIsCompactAndReservesNonOverlappingSpaceForChatActionsAndQuestionHint() {
        for (int width : new int[]{260, 540, 1000}) for (int height : new int[]{240, 360, 600}) {
            var normal = WorkspaceComposerLayout.of(20, width, height, 24, false);
            var reply = WorkspaceComposerLayout.of(20, width, height, 24, true);
            assertEquals(34, normal.inputHeight()); assertEquals(16, normal.buttonHeight());
            assertTrue(normal.actionWidth() < 68); assertTrue(normal.inputX() + normal.inputWidth() < normal.actionX());
            assertEquals(normal.actionX() + normal.actionWidth(), 20 + width);
            assertTrue(normal.contentBottom() < normal.inputY());
            assertEquals(normal.inputY() + normal.inputHeight(), normal.copyY() + normal.buttonHeight());
            assertTrue(normal.copyY() + normal.buttonHeight() <= height - 14);
            assertTrue(reply.contentBottom() < reply.hintY()); assertTrue(reply.hintY() + 9 < reply.inputY());
        }
    }
}
