package firefly520.fireflymc.ai.model;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.task.TaskControl;
import java.util.List;
import java.util.Map;

public interface ModelGateway {
    record ToolDefinition(String name, String description, JsonObject inputSchema) {}
    record Request(List<ModelMessage> messages, List<ToolDefinition> tools, int maxOutputTokens,
                   boolean parallelToolCalls, String toolChoice, boolean stream) {
        public Request(List<ModelMessage> messages, List<ToolDefinition> tools, int maxOutputTokens, boolean parallelToolCalls) {
            this(messages, tools, maxOutputTokens, parallelToolCalls, "auto", false);
        }
        public Request(List<ModelMessage> messages, List<ToolDefinition> tools, int maxOutputTokens, boolean parallelToolCalls, String toolChoice) {
            this(messages, tools, maxOutputTokens, parallelToolCalls, toolChoice, false);
        }
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
            if (toolChoice == null || !java.util.Set.of("auto", "none", "required").contains(toolChoice)
                    && tools.stream().noneMatch(t -> t.name().equals(toolChoice)))
                throw new IllegalArgumentException("TOOL_CHOICE_NOT_OFFERED");
        }
    }
    record Reply(ModelMessage message, long inputTokens, long outputTokens, int attempts) {
        public Reply(ModelMessage message, long inputTokens, long outputTokens) {
            this(message, inputTokens, outputTokens, 1);
        }
    }

    /** Safe error code only: provider bodies, URLs and credentials never enter a task/log. */
    final class Failure extends Exception {
        private final Map<String, String> diagnostics;
        public Failure(String code) { this(code, Map.of()); }
        public Failure(String code, Map<String, String> diagnostics) {
            super(code);
            this.diagnostics = Map.copyOf(diagnostics);
        }
        public Map<String, String> diagnostics() { return diagnostics; }
    }

    Reply complete(Request request, TaskControl control) throws Exception;
    default Reply complete(Request request, TaskControl control, java.util.function.Consumer<ModelEvent> listener) throws Exception {
        Reply reply = complete(request, control);
        listener.accept(new ModelEvent.Completed(reply));
        return reply;
    }
    default int maximumAttempts() { return 1; }
}
