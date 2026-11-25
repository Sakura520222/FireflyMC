package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuestionPendingProjectionTest {
    @TempDir Path directory;
    @Test void archivedAdvisoryCardsCannotHideTheCurrentBlockerOrDeleteTheirHistory() throws Exception {
        var actor = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var store = new QuestionStore(workspace);
            for (int i = 0; i < 8; i++) {
                var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "旧任务 " + i);
                workspace.conversation().append(List.of(Draft.checkpoint(task)));
                store.create(actor, task.id(), "结束后要不要保留建议 " + i, List.of(), false);
                workspace.conversation().append(List.of(Draft.checkpoint(task.update(AgentTask.State.COMPLETED, "done"))));
            }
            var active = AgentTask.create(actor.owner(), UUID.randomUUID(), "现在的任务");
            workspace.conversation().append(List.of(Draft.checkpoint(active)));
            var blocker = store.create(actor, active.id(), "现在需要您的选择", List.of(), true);
            assertEquals(8, store.pending(actor, 8).size());
            assertEquals(blocker.id(), store.pending(actor, 8).getFirst().id());
            assertEquals(9, store.list(actor, null).size());
            var other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> store.pending(other, 8));
        }
    }
}
