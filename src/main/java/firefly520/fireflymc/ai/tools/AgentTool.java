package firefly520.fireflymc.ai.tools;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface AgentTool {
    enum Risk {
        READ_ONLY, MEMORY_WRITE, PLAYER_STATE, WORLD_CHANGE;
        public boolean requiresApproval() { return this == PLAYER_STATE || this == WORLD_CHANGE; }
    }
    enum ExecutionThread { IO, SERVER }
    record Descriptor(String name, String description, JsonObject inputSchema, Risk risk,
                      String requiredCapability, int permissionLevel, ExecutionThread executionThread,
                      long maxExecutionMillis, boolean needsOnlinePlayer, String unavailableReason) {
        public ModelGateway.ToolDefinition modelDefinition() {
            return new ModelGateway.ToolDefinition(name, description, inputSchema);
        }
    }
    record Context(Workspace workspace, Actor actor, TaskControl control, UUID taskId, int modelResultBytes) {
        public Context(Workspace workspace, Actor actor, TaskControl control, UUID taskId) { this(workspace, actor, control, taskId, 4096); }
        public Context(Workspace workspace, Actor actor, TaskControl control) { this(workspace, actor, control, null, 4096); }
    }
    record Result(boolean success, String errorCode, String summary, JsonObject observations,
                  String evidence, String worldChanges, boolean retriable) {
        public static Result ok(String summary) {
            return new Result(true, "", summary, new JsonObject(), "", "", false);
        }
        public static Result error(String code, String summary) {
            return new Result(false, code, summary, new JsonObject(), "", "", false);
        }
    }

    @FunctionalInterface
    interface Continuation {
        Result resume(Context freshContext) throws Exception;
    }

    /** Trusted server control flow, never a model result or a client supplied continuation.
     * The caller must durably checkpoint owned native work before throwing this signal.
     * Only identifiers/status belong in checkpoint; never retain Minecraft objects in continuation.
     */
    final class Deferred extends Exception {
        private final String reason;
        private final JsonObject checkpoint;
        private final CompletionStage<?> readiness;
        private final Continuation continuation;

        public Deferred(String reason, JsonObject checkpoint, CompletionStage<?> readiness, Continuation continuation) {
            super("EXTERNAL_WAIT", null, false, false);
            if (reason == null || !reason.matches("[A-Z0-9_]{1,96}"))
                throw new IllegalArgumentException("EXTERNAL_WAIT_REASON");
            this.reason = reason;
            this.checkpoint = Objects.requireNonNull(checkpoint).deepCopy();
            if (this.checkpoint.toString().length() > 4096)
                throw new IllegalArgumentException("EXTERNAL_WAIT_CHECKPOINT_SIZE");
            this.readiness = Objects.requireNonNull(readiness);
            this.continuation = Objects.requireNonNull(continuation);
        }
        public String reason() { return reason; }
        public JsonObject checkpoint() { return checkpoint.deepCopy(); }
        public CompletionStage<?> readiness() { return readiness; }
        public Continuation continuation() { return continuation; }
    }
    Descriptor descriptor();
    Result execute(Context context, JsonObject arguments) throws Exception;
}
