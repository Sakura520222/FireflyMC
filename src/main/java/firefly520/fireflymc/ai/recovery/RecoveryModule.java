package firefly520.fireflymc.ai.recovery;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Trusted server wiring for concrete native journals; clients cannot register or choose providers. */
public interface RecoveryModule {
    record Preview(String toolName, JsonObject arguments, String text) {
        public Preview { Objects.requireNonNull(toolName); arguments = arguments.deepCopy(); Objects.requireNonNull(text); }
        @Override public JsonObject arguments() { return arguments.deepCopy(); }
    }
    List<JsonObject> rows(Workspace workspace, Actor actor, UUID taskId) throws Exception;
    Preview preview(AgentTool.Context context, UUID operationId) throws Exception;
    /** Full current, private evidence, with a top-level original taskId. No version validation or native calls. */
    JsonObject evidence(Workspace workspace, Actor actor, UUID operationId) throws Exception;
    void validatePreview(Workspace workspace, Actor actor, UUID taskId, UUID operationId, JsonObject arguments) throws Exception;
    boolean settled(Workspace workspace, Actor actor, UUID operationId) throws Exception;
    String settledStatus();
    /** Empty for unrelated tools; recognized tools must verify the original arguments and this task's journal. */
    Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID taskId, ModelMessage.ToolCall call) throws Exception;
}
