package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionCoordinatorTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);

    @Test void onlyRegisteredStepsWaitAndTheExactPlanContextSurvivesReopen() throws Exception {
        UUID taskId, questionId, operation = UUID.randomUUID();
        String digest, context = AiJson.digest("fixed-blueprint-version-and-materials");
        String step = QuestionCoordinator.toolStep("blueprint_material_apply", operation);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "build"); taskId = task.id();
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var store = new QuestionStore(workspace);
            var question = store.createForSteps(owner, task.id(), "采用这一版替代？", List.of("采用", "保留"), step, List.of(step), context);
            questionId = question.id(); digest = question.digest();
            assertEquals(QuestionCoordinator.BlockingScope.STEPS, question.blockingScope());
            assertFalse(store.blocking(task.id()), "A real step dependency must not become a task-wide stop");
            var args = new JsonObject(); args.addProperty("proposalId", operation.toString());
            assertTrue(store.blocksCall(task.id(), "blueprint_material_apply", args));
            assertFalse(store.blocksCall(task.id(), "inventory_inspect", args));
            args.addProperty("proposalId", UUID.randomUUID().toString());
            assertFalse(store.blocksCall(task.id(), "blueprint_material_apply", args));
            assertDoesNotThrow(() -> store.requireBeforeStep(owner, task.id(), "independent-survey", context));
            assertThrows(IllegalStateException.class, () -> store.requireBeforeStep(owner, task.id(), step, context));
            assertThrows(SecurityException.class, () -> store.requireBeforeStep(Actor.player(UUID.randomUUID(), true, true, 4), task.id(), step, context));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var store = new QuestionStore(manager.getWorkspace(owner));
            var question = store.list(owner, taskId).getFirst();
            assertEquals(step, question.stepId()); assertEquals(List.of(step), question.requiredBeforeStepIds()); assertEquals(context, question.contextDigest());
            store.answer(owner, questionId, digest, "采用", UUID.randomUUID());
            assertDoesNotThrow(() -> store.requireBeforeStep(owner, taskId, step, context));
            var changed = assertThrows(IllegalStateException.class, () -> store.requireBeforeStep(owner, taskId, step, AiJson.digest("changed-version")));
            assertEquals("QUESTION_STEP_CONTEXT_CHANGED", changed.getMessage());
        }
    }

    @Test void advisoryCannotGateApprovedWorkAndCanBeAnsweredAfterTheTaskTerminates() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "approved build");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var store = new QuestionStore(workspace); var question = store.create(owner, task.id(), "完成后要材料明细吗？", List.of(), false);
            assertEquals(QuestionCoordinator.BlockingScope.ADVISORY, question.blockingScope());
            assertTrue(question.requiredBeforeStepIds().isEmpty());
            var args = new JsonObject(); args.addProperty("planId", UUID.randomUUID().toString());
            assertFalse(store.blocksCall(task.id(), "build_execute", args));
            workspace.conversation().append(List.of(Draft.checkpoint(task.update(AgentTask.State.COMPLETED, "actual-world-verified"))));
            var answer = store.answer(owner, question.id(), question.digest(), "要，请给明细", UUID.randomUUID());
            assertEquals("要，请给明细", answer.answer());
            assertEquals(AgentTask.State.COMPLETED, workspace.conversation().task(task.id()).state());
            store.appendAnsweredInputs(task.id());
            assertEquals(1, workspace.conversation().getSnapshot(0).events().stream().filter(e -> "question_answer_input".equals(e.kind())).count());
        }
    }

    @Test void anUnscopedBlockingQuestionKeepsTheSafeWholeTaskFallback() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "unknown dependency graph");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var store = new QuestionStore(workspace); var question = store.create(owner, task.id(), "是否继续需要改变目标的工作？", List.of(), true);
            assertEquals(QuestionCoordinator.BlockingScope.TASK, question.blockingScope()); assertTrue(store.blocking(task.id()));
            assertThrows(IllegalArgumentException.class, () -> store.createForSteps(owner, task.id(), "invalid", List.of(), "step-a", List.of("step-b"), AiJson.digest("plan")));
            workspace.conversation().append(List.of(Draft.checkpoint(task.update(AgentTask.State.CANCELLED, "cancelled"))));
            assertThrows(IllegalStateException.class, () -> store.answer(owner, question.id(), question.digest(), "继续", UUID.randomUUID()));
        }
    }

    @Test void compactionKeepsTheAuthoritativeStepDependencyOutsideItsLossySummary() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "build");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            String step = QuestionCoordinator.toolStep("blueprint_material_apply", UUID.randomUUID()), hash = AiJson.digest("source-blueprint-r7");
            new QuestionStore(workspace).createForSteps(owner, task.id(), "确定材质？", List.of("是", "否"), step, List.of(step), hash);
            for (int i = 0; i < 16; i++) workspace.conversation().append(List.of(
                    Draft.message(task.id(), ModelMessage.text("user", "survey-" + i + "x".repeat(800)), null),
                    Draft.message(task.id(), ModelMessage.text("assistant", "observed-" + i + "x".repeat(800)), null)));
            var compactor = new ContextCompactor(new ContextCompactor.Settings(16000, 1000, 1000, 1000, .75, 1), "test");
            var context = compactor.assemble(workspace, task.id(), "policy", null);
            assertNotNull(context.summary());
            String authoritative = context.messages().get(2).text();
            assertTrue(authoritative.contains("STEPS")); assertTrue(authoritative.contains(step)); assertTrue(authoritative.contains(hash));
            assertEquals(32, workspace.conversation().getSnapshot(0).events().stream().filter(e -> e.message() != null).count());
            assertEquals(1, new QuestionStore(workspace).list(owner, task.id()).size());
        }
    }
}
