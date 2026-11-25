package firefly520.fireflymc.ai.model;

import com.google.gson.*;
import java.util.*;

public interface ModelProtocol {
    String path();
    JsonObject encode(String model, ModelGateway.Request request);
    ModelGateway.Reply decode(JsonObject response) throws ModelGateway.Failure;

    static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    static void validateCalls(List<ModelMessage.ToolCall> calls) {
        if (calls.size() > 64) throw new IllegalArgumentException("too many calls");
        Set<String> ids = new HashSet<>();
        for (var call : calls) {
            if (call.id() == null || call.id().isBlank() || call.id().length() > 256
                    || !ids.add(call.id()) || call.name() == null
                    || !call.name().matches("[a-zA-Z0-9_.-]{1,64}")
                    || call.arguments() == null || call.arguments().length() > 65536)
                throw new IllegalArgumentException("invalid tool call");
        }
    }

    static long usage(JsonObject response, String field) {
        if (!response.has("usage") || response.get("usage").isJsonNull()) return 0;
        long value = response.getAsJsonObject("usage").has(field)
                ? response.getAsJsonObject("usage").get(field).getAsLong() : 0;
        if (value < 0 || value > 1_000_000_000) throw new IllegalArgumentException("usage");
        return value;
    }
}
