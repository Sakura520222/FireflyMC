package firefly520.fireflymc.ai.task;

import com.google.gson.JsonObject;
import java.util.*;

/** Server-owned question dependencies. An answer is input, never an execution grant. */
public final class QuestionCoordinator {
    public enum BlockingScope { TASK, STEPS, ADVISORY }

    private QuestionCoordinator() {}

    public static String taskStep(UUID task) { return "task:" + Objects.requireNonNull(task); }

    public static String toolStep(String tool, UUID operation) {
        if (tool == null || !tool.matches("[a-z][a-z0-9_]{0,79}")) throw new IllegalArgumentException("QUESTION_STEP_INVALID");
        return tool + ":" + Objects.requireNonNull(operation);
    }

    public static List<String> validateSteps(Collection<String> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 32) throw new IllegalArgumentException("QUESTION_STEP_LIMIT");
        var result = new LinkedHashSet<String>();
        for (String step : steps) {
            if (step == null || step.isBlank() || step.length() > 160 || !step.matches("[A-Za-z0-9_.:/-]+") || !result.add(step))
                throw new IllegalArgumentException("QUESTION_STEP_INVALID");
        }
        return List.copyOf(result);
    }

    public static void validateContext(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("QUESTION_CONTEXT_INVALID");
    }

    /** Only trusted service code creates these keys; model fields cannot declare independence. */
    public static Set<String> callSteps(String tool, JsonObject arguments) {
        var result = new LinkedHashSet<String>();
        for (String field : List.of("proposalId", "planId", "operationId", "reviewId")) {
            try {
                if (arguments.has(field) && arguments.get(field).isJsonPrimitive())
                    result.add(toolStep(tool, UUID.fromString(arguments.get(field).getAsString())));
            } catch (RuntimeException ignored) { /* Invalid IDs are rejected by the actual tool schema/service. */ }
        }
        return Set.copyOf(result);
    }

    public static boolean blocksTask(QuestionStore.Question question) {
        return !question.answered() && question.blockingScope() == BlockingScope.TASK;
    }

    public static boolean blocksCall(QuestionStore.Question question, Set<String> actualSteps) {
        return !question.answered() && (question.blockingScope() == BlockingScope.TASK
                || question.blockingScope() == BlockingScope.STEPS
                && question.requiredBeforeStepIds().stream().anyMatch(actualSteps::contains));
    }

    /** Exact plan context remains mandatory after answering; an answer for an older revision cannot carry over. */
    public static void requireBeforeStep(Collection<QuestionStore.Question> questions, UUID task, String step, String contextDigest) {
        validateContext(contextDigest);
        for (var question : questions) {
            if (!task.equals(question.taskId())) continue;
            if (blocksTask(question)) throw new IllegalStateException("TASK_REQUIRES_ANSWER");
            if (question.blockingScope() != BlockingScope.STEPS || !question.requiredBeforeStepIds().contains(step)) continue;
            if (!question.contextDigest().equals(contextDigest)) throw new IllegalStateException("QUESTION_STEP_CONTEXT_CHANGED");
            if (!question.answered()) throw new IllegalStateException("QUESTION_STEP_REQUIRES_ANSWER");
        }
    }
}
