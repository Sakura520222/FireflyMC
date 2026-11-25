package firefly520.fireflymc.ai.task;

import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import java.util.List;
import java.util.UUID;

/** Immutable durable checkpoint. Every task belongs to the workspace's primary conversation. */
public record AgentTask(UUID id, UUID requestId, String owner, String prompt, State state,
                        long createdAt, int steps, int toolCalls, long tokens, long elapsedMillis,
                        List<ToolCall> pending, int nextCall, String approvalDigest,
                        String reason, boolean started, boolean privateResult) {
    public enum State {
        QUEUED, PLANNING, EXECUTING, WAITING_APPROVAL, WAITING_ANSWER_BLOCKING, WAITING_EXTERNAL, PAUSED, RECOVERY_REQUIRED,
        COMPLETED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public AgentTask {
        pending = pending == null ? List.of() : List.copyOf(pending);
        approvalDigest = approvalDigest == null ? "" : approvalDigest;
        reason = reason == null ? "" : reason;
    }

    public static AgentTask create(String owner, UUID requestId, String prompt) {
        return new AgentTask(UUID.randomUUID(), requestId, owner, prompt, State.QUEUED,
                System.currentTimeMillis(), 0, 0, 0, 0, List.of(), 0, "", "", false, false);
    }

    public AgentTask update(State state, String reason) {
        return new AgentTask(id, requestId, owner, prompt, state, createdAt, steps, toolCalls,
                tokens, elapsedMillis, pending, nextCall, approvalDigest, reason, started, privateResult);
    }

    public AgentTask progress(int steps, int calls, long tokens, long elapsed, List<ToolCall> pending,
                              int next, String approval, boolean started, boolean privateResult) {
        return new AgentTask(id, requestId, owner, prompt, state, createdAt, steps, calls,
                tokens, elapsed, pending, next, approval, reason, started, privateResult);
    }
}
