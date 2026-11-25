package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionStoreTest {
    @TempDir Path directory;
    @Test void durableQuestionsBindOwnerTaskVersionAndExactlyOneAnswerRequest() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        UUID taskId, questionId, answerRequest = UUID.randomUUID();
        String digest;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "build");
            taskId = task.id();
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var store = new QuestionStore(workspace);
            var question = store.create(actor, taskId, "颜色选择", List.of("蓝色", "白色"), true);
            questionId = question.id(); digest = question.digest();
            assertEquals(questionId, store.create(actor, taskId, "颜色选择", List.of("蓝色", "白色"), true).id());
            assertThrows(SecurityException.class, () -> store.answer(Actor.player(UUID.randomUUID(), true, true, 4),
                    questionId, digest, "白色", answerRequest));
            assertThrows(SecurityException.class, () -> store.answer(actor, questionId, "changed", "白色", answerRequest));
            assertThrows(IllegalArgumentException.class, () -> store.create(actor, taskId, "请提供 API key", List.of(), true));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var store = new QuestionStore(manager.getWorkspace(actor));
            assertTrue(store.blocking(taskId));
            var answer = store.answer(actor, questionId, digest, "白色", answerRequest);
            assertEquals(answer, store.answer(actor, questionId, digest, "白色", answerRequest));
            assertThrows(IllegalStateException.class, () -> store.answer(actor, questionId, digest, "蓝色", UUID.randomUUID()));
            assertFalse(store.blocking(taskId));
            assertEquals(1, store.list(actor, taskId).size());
            var other = AgentTask.create(actor.owner(), UUID.randomUUID(), "separate task");
            manager.getWorkspace(actor).conversation().append(List.of(Draft.checkpoint(other)));
            var otherQuestion = store.create(actor, other.id(), "颜色选择", List.of("蓝色", "白色"), true);
            assertTrue(store.blocking(other.id()));
            assertThrows(IllegalStateException.class, () -> store.answer(actor, otherQuestion.id(), otherQuestion.digest(), "白色", answerRequest));
            store.appendAnsweredInputs(other.id());
            assertFalse(manager.getWorkspace(actor).conversation().getSnapshot(0).events().stream()
                    .anyMatch(e -> e.kind().equals("question_answer_input")));
            store.appendAnsweredInputs(taskId);
            store.appendAnsweredInputs(taskId);
            assertEquals(1, manager.getWorkspace(actor).conversation().getSnapshot(0).events().stream()
                    .filter(e -> e.kind().equals("question_answer_input") && taskId.equals(e.taskId())).count());
            var input = manager.getWorkspace(actor).conversation().getSnapshot(0).events().stream()
                    .filter(e -> e.kind().equals("question_answer_input")).findFirst().orElseThrow();
            assertTrue(manager.getWorkspace(actor).conversation().readableEvent(input.seq()).orElseThrow()
                    .getAsJsonObject("message").get("text").getAsString().contains("白色"));
        }
    }
}
