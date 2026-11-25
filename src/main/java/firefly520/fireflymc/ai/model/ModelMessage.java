package firefly520.fireflymc.ai.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** Provider-neutral message; Responses output items retain opaque reasoning state. */
public record ModelMessage(String role, String text, List<ToolCall> calls,
                           String toolCallId, JsonArray responseItems, JsonObject providerState) {
    public ModelMessage(String role, String text, List<ToolCall> calls, String toolCallId, JsonArray responseItems) {
        this(role, text, calls, toolCallId, responseItems, null);
    }
    public ModelMessage {
        calls = calls == null ? List.of() : List.copyOf(calls);
        text = text == null ? "" : text;
        responseItems = responseItems == null ? new JsonArray() : responseItems.deepCopy();
        providerState = providerState == null ? new JsonObject() : providerState.deepCopy();
    }

    public static ModelMessage text(String role, String text) {
        return new ModelMessage(role, text, List.of(), null, null);
    }

    public static ModelMessage result(String id, String text) {
        return new ModelMessage("tool", text, List.of(), id, null);
    }

    public record ToolCall(String id, String name, String arguments) {}
}
