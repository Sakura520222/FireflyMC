package firefly520.fireflymc.ai.task;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** Private task questions are journal facts, never approvals or a separate conversation. */
public final class QuestionStore {
    public record Question(UUID id, UUID taskId, int revision, String digest, String prompt,
                           List<String> choices, boolean blocking, long createdAt,
                           String answer, UUID answerRequestId, long answeredAt, String stepId,
                           QuestionCoordinator.BlockingScope blockingScope, List<String> requiredBeforeStepIds,
                           String contextDigest) {
        public Question {
            Objects.requireNonNull(id); Objects.requireNonNull(taskId);
            choices = List.copyOf(choices);
            // Earlier records produced by this same new runtime have only the task-wide boolean.
            // Preserve their answer digest and conservative scope without importing old AI history.
            if (blockingScope == null) blockingScope = blocking ? QuestionCoordinator.BlockingScope.TASK : QuestionCoordinator.BlockingScope.ADVISORY;
            if (stepId == null) stepId = QuestionCoordinator.taskStep(taskId);
            if (requiredBeforeStepIds == null) requiredBeforeStepIds = blockingScope == QuestionCoordinator.BlockingScope.TASK ? List.of(stepId) : List.of();
            requiredBeforeStepIds = List.copyOf(requiredBeforeStepIds);
            if (contextDigest == null) contextDigest = digest;
            QuestionCoordinator.validateContext(contextDigest);
            if (blocking != (blockingScope != QuestionCoordinator.BlockingScope.ADVISORY)) throw new IllegalArgumentException("QUESTION_SCOPE_INVALID");
            if (blockingScope == QuestionCoordinator.BlockingScope.STEPS) {
                requiredBeforeStepIds = QuestionCoordinator.validateSteps(requiredBeforeStepIds);
                if (!requiredBeforeStepIds.contains(stepId)) throw new IllegalArgumentException("QUESTION_STEP_INVALID");
            } else if (blockingScope == QuestionCoordinator.BlockingScope.TASK && (!QuestionCoordinator.taskStep(taskId).equals(stepId)
                    || !requiredBeforeStepIds.equals(List.of(stepId)))) throw new IllegalArgumentException("QUESTION_SCOPE_INVALID");
            else if (blockingScope == QuestionCoordinator.BlockingScope.ADVISORY && !requiredBeforeStepIds.isEmpty())
                throw new IllegalArgumentException("QUESTION_SCOPE_INVALID");
        }
        public boolean answered() { return answerRequestId != null; }
    }
    private final Workspace workspace;
    public QuestionStore(Workspace workspace) { this.workspace = workspace; }

    public static List<Question> from(ConversationStore.Snapshot snapshot) {
        Map<UUID, Question> questions = new LinkedHashMap<>();
        for (var event : snapshot.events()) {
            if (!Set.of("question", "question_answer").contains(event.kind())) continue;
            Question question = AiJson.GSON.fromJson(event.data(), Question.class);
            questions.put(question.id(), question);
        }
        return List.copyOf(questions.values());
    }
    public List<Question> list(Actor actor, UUID taskId) {
        actor.requireAccess(workspace.owner());
        return from(workspace.conversation().getSnapshot(0)).stream()
                .filter(q -> taskId == null || q.taskId().equals(taskId)).toList();
    }
    public boolean blocking(UUID taskId) {
        return from(workspace.conversation().getSnapshot(0)).stream()
                .anyMatch(q -> q.taskId().equals(taskId) && QuestionCoordinator.blocksTask(q));
    }
    /** Current blockers precede archived suggestions; the bounded UI must never hide a live decision behind old cards. */
    public List<Question> pending(Actor actor, int limit) {
        actor.requireAccess(workspace.owner());
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("QUESTION_PAGE_LIMIT");
        var snapshot = workspace.conversation().getSnapshot(0);
        var tasks = new HashMap<UUID, AgentTask>(); snapshot.tasks().forEach(task -> tasks.put(task.id(), task));
        java.util.function.ToIntFunction<Question> priority = question -> {
            var task = tasks.get(question.taskId());
            if (question.answered() || task == null || task.state().terminal()
                    && question.blockingScope() != QuestionCoordinator.BlockingScope.ADVISORY) return 3;
            return task.state().terminal() ? 2 : question.blocking() ? 0 : 1;
        };
        return from(snapshot).stream().filter(question -> priority.applyAsInt(question) < 3)
                .sorted(Comparator.comparingInt(priority).thenComparing(Comparator.comparingLong(Question::createdAt).reversed()))
                .limit(limit).toList();
    }

    public boolean hasUnansweredSteps(UUID taskId) {
        return from(workspace.conversation().getSnapshot(0)).stream().anyMatch(q -> q.taskId().equals(taskId)
                && q.blockingScope() == QuestionCoordinator.BlockingScope.STEPS && !q.answered());
    }

    public boolean blocksCall(UUID taskId, String tool, JsonObject arguments) {
        var steps = QuestionCoordinator.callSteps(tool, arguments);
        return from(workspace.conversation().getSnapshot(0)).stream().anyMatch(q -> q.taskId().equals(taskId)
                && QuestionCoordinator.blocksCall(q, steps));
    }

    /** IO-side safe checkpoint. Native dispatch must retain its own live plan, approval and version guards. */
    public void requireBeforeStep(Actor actor, UUID taskId, String stepId, String contextDigest) {
        actor.requireAccess(workspace.owner());
        QuestionCoordinator.requireBeforeStep(list(actor, taskId), taskId, stepId, contextDigest);
    }

    /** A model reply may only consume the answers that existed when its context was assembled. */
    public long answerRevision(UUID taskId) {
        return workspace.conversation().getSnapshot(0).events().stream()
                .filter(e -> taskId.equals(e.taskId()) && "question_answer".equals(e.kind()))
                .mapToLong(ConversationStore.Event::seq).max().orElse(0);
    }

    /**
     * Deliver each durable answer once as user input in the same primary conversation. A card can
     * be answered while a tool is still returning, so never insert a user message in the middle
     * of an accepted assistant/tool transaction. The original answer event remains the UI receipt.
     * A null task ID backfills earlier answers too, including logs created before input delivery.
     */
    public void appendAnsweredInputs(UUID taskId) throws IOException {
        synchronized (workspace.conversation()) {
            if (taskId != null && workspace.conversation().task(taskId) == null) return;
            if (workspace.conversation().tasks().stream()
                    .anyMatch(t -> t.nextCall() < t.pending().size())) return;
            var snapshot = workspace.conversation().getSnapshot(0);
            Set<String> delivered = new HashSet<>();
            for (var event : snapshot.events()) {
                if ("question_answer_input".equals(event.kind()))
                    delivered.add(event.data().get("id").getAsString() + ":" + event.data().get("answerRequestId").getAsString());
            }
            List<ConversationStore.Draft> inputs = new ArrayList<>();
            for (Question question : from(snapshot)) {
                if ((taskId != null && !taskId.equals(question.taskId())) || !question.answered()
                        || !delivered.add(question.id() + ":" + question.answerRequestId())) continue;
                JsonObject data = AiJson.GSON.toJsonTree(question).getAsJsonObject();
                inputs.add(new ConversationStore.Draft(question.taskId(), "question_answer_input", ModelMessage.text("user",
                        "本人已回答此任务问题。以下是玩家输入，不授予执行权限；继续时以该回答为准，不再等待该问题。\n" + data),
                        data, null));
            }
            workspace.conversation().append(inputs);
        }
    }
    public Question create(Actor actor, UUID taskId, String prompt, List<String> choices, boolean blocking) throws IOException {
        if (taskId == null) throw new IllegalArgumentException("INVALID_TASK_QUESTION");
        String step = QuestionCoordinator.taskStep(taskId);
        var scope = blocking ? QuestionCoordinator.BlockingScope.TASK : QuestionCoordinator.BlockingScope.ADVISORY;
        String context = AiJson.digest(taskId + "\n" + prompt + "\n" + AiJson.GSON.toJson(choices) + "\n" + scope);
        return create(actor, taskId, prompt, choices, scope, step, blocking ? List.of(step) : List.of(), context);
    }

    /** Not model-exposed: the calling service supplies real immutable plan/step dependencies. */
    public Question createForSteps(Actor actor, UUID taskId, String prompt, List<String> choices,
                                   String stepId, List<String> requiredBeforeStepIds, String contextDigest) throws IOException {
        QuestionCoordinator.validateContext(contextDigest);
        var steps = QuestionCoordinator.validateSteps(requiredBeforeStepIds);
        if (!steps.contains(stepId)) throw new IllegalArgumentException("QUESTION_STEP_INVALID");
        return create(actor, taskId, prompt, choices, QuestionCoordinator.BlockingScope.STEPS, stepId, steps, contextDigest);
    }

    private Question create(Actor actor, UUID taskId, String prompt, List<String> choices,
                            QuestionCoordinator.BlockingScope scope, String stepId, List<String> requiredBeforeStepIds,
                            String contextDigest) throws IOException {
        actor.requireAccess(workspace.owner());
        if (taskId == null || prompt == null || prompt.isBlank() || prompt.length() > 1600
                || choices == null || choices.size() > 6 || choices.stream().anyMatch(c -> c.isBlank() || c.length() > 160))
            throw new IllegalArgumentException("INVALID_TASK_QUESTION");
        // Credentials belong to the existing authentication/configuration flows, never model context.
        if (prompt.toLowerCase(Locale.ROOT).matches("(?s).*(密码|口令|密钥|api.?key|password|access.?token).*"))
            throw new IllegalArgumentException("CREDENTIAL_QUESTIONS_FORBIDDEN");
        synchronized (workspace.conversation()) {
            AgentTask task = workspace.conversation().task(taskId);
            if (task == null || task.state().terminal()) throw new SecurityException("TASK_NOT_ACTIVE");
            boolean blocking = scope != QuestionCoordinator.BlockingScope.ADVISORY;
            String digest = AiJson.digest(taskId + "\n" + prompt + "\n" + AiJson.GSON.toJson(choices) + "\n" + scope
                    + "\n" + stepId + "\n" + AiJson.GSON.toJson(requiredBeforeStepIds) + "\n" + contextDigest);
            var old = list(actor, taskId);
            var duplicate = old.stream().filter(q -> q.digest().equals(digest)).findFirst();
            if (duplicate.isPresent()) return duplicate.get();
            if (old.stream().filter(q -> !q.answered()).count() >= 8 || old.size() >= 64)
                throw new IllegalArgumentException("QUESTION_LIMIT");
            Question question = new Question(UUID.randomUUID(), taskId, 1, digest, prompt, List.copyOf(choices),
                    blocking, System.currentTimeMillis(), "", null, 0, stepId, scope, requiredBeforeStepIds, contextDigest);
            append("question", question);
            return question;
        }
    }
    public Question answer(Actor actor, UUID questionId, String digest, String text, UUID requestId) throws IOException {
        actor.requireAccess(workspace.owner());
        if (text == null || text.isBlank() || text.length() > 2048 || requestId == null)
            throw new IllegalArgumentException("INVALID_ANSWER");
        synchronized (workspace.conversation()) {
            Question question = list(actor, null).stream().filter(q -> q.id().equals(questionId)).findFirst()
                    .orElseThrow(() -> new SecurityException("QUESTION_NOT_FOUND"));
            if (!question.digest().equals(digest)) throw new SecurityException("QUESTION_CHANGED");
            if (question.answered()) {
                if (requestId.equals(question.answerRequestId()) && text.equals(question.answer())) return question;
                throw new IllegalStateException("QUESTION_ALREADY_ANSWERED");
            }
            if (list(actor, null).stream().anyMatch(q -> requestId.equals(q.answerRequestId())))
                throw new IllegalStateException("ANSWER_REQUEST_REUSED");
            AgentTask task = workspace.conversation().task(question.taskId());
            if (task == null || task.state().terminal() && question.blockingScope() != QuestionCoordinator.BlockingScope.ADVISORY)
                throw new IllegalStateException("QUESTION_NO_LONGER_ACTIVE");
            Question answered = new Question(question.id(), question.taskId(), question.revision(), question.digest(),
                    question.prompt(), question.choices(), question.blocking(), question.createdAt(), text, requestId, System.currentTimeMillis(),
                    question.stepId(), question.blockingScope(), question.requiredBeforeStepIds(), question.contextDigest());
            append("question_answer", answered);
            return answered;
        }
    }
    private void append(String kind, Question question) throws IOException {
        workspace.conversation().append(List.of(new ConversationStore.Draft(question.taskId(), kind, null,
                AiJson.GSON.toJsonTree(question).getAsJsonObject(), null)));
    }

    public static void register(ToolRegistry registry) {
        JsonObject schema = AiJson.object("""
                {"type":"object","properties":{"prompt":{"type":"string","maxLength":1600},
                "choices":{"type":"array","maxItems":6,"items":{"type":"string","maxLength":160}},
                "blocking":{"type":"boolean"}},"required":["prompt","blocking"],"additionalProperties":false}
                """, 2000, 8);
        registry.register(new DefinedTool(new AgentTool.Descriptor("task_ask", "在本人工作台提出问题提案。blocking=true 在缺少受信步骤图时暂停本任务；false 是ADVISORY建议，不改变已批准独立计划。真实步骤依赖由服务端工作流登记，回答不授予操作权限，不得询问凭据。",
                schema, AgentTool.Risk.READ_ONLY, "workspace.read", 0, AgentTool.ExecutionThread.IO, 5000, false, ""), (ctx, args) -> {
            List<String> choices = new ArrayList<>();
            if (args.has("choices")) args.getAsJsonArray("choices").forEach(v -> choices.add(v.getAsString()));
            var question = new QuestionStore(ctx.workspace()).create(ctx.actor(), ctx.taskId(),
                    args.get("prompt").getAsString(), choices, args.get("blocking").getAsBoolean());
            return AgentTool.Result.ok(AiJson.GSON.toJson(question));
        }));
        registry.register(new DefinedTool(new AgentTool.Descriptor("question_lookup", "按任务 ID 查看本人的问题与回答全文；答案仅是玩家输入，不是执行审批。",
                AiJson.object("{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"}},"
                        + "\"required\":[\"taskId\"],\"additionalProperties\":false}", 1000, 8),
                AgentTool.Risk.READ_ONLY, "workspace.read", 0, AgentTool.ExecutionThread.IO, 5000, false, ""),
                (ctx, args) -> AgentTool.Result.ok(AiJson.GSON.toJson(new QuestionStore(ctx.workspace())
                        .list(ctx.actor(), UUID.fromString(args.get("taskId").getAsString()))))));
    }
}
