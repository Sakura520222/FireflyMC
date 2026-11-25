package firefly520.fireflymc.ai.model;

import com.google.gson.*;
import java.util.*;

/** Stateless replay including reasoning items; does not mix previous_response_id with full history. */
public final class ResponsesAdapter implements ModelProtocol {
    @Override public String path() { return "/responses"; }

    @Override
    public JsonObject encode(String model, ModelGateway.Request request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("store", false);
        body.addProperty("stream", request.stream());
        body.addProperty("max_output_tokens", request.maxOutputTokens());
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content");
        body.add("include", include);
        JsonArray input = new JsonArray();
        for (ModelMessage message : request.messages()) {
            if (!message.responseItems().isEmpty()) {
                for (JsonElement item : message.responseItems()) input.add(item.deepCopy());
            } else if ("tool".equals(message.role())) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "function_call_output");
                item.addProperty("call_id", message.toolCallId());
                item.addProperty("output", message.text());
                input.add(item);
            } else {
                if (!message.text().isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("role", message.role());
                    item.addProperty("content", message.text());
                    input.add(item);
                }
                for (var call : message.calls()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "function_call");
                    item.addProperty("call_id", call.id());
                    item.addProperty("name", call.name());
                    item.addProperty("arguments", call.arguments());
                    input.add(item);
                }
            }
        }
        body.add("input", input);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (var definition : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.addProperty("name", definition.name());
                tool.addProperty("description", definition.description());
                tool.add("parameters", definition.inputSchema().deepCopy());
                tool.addProperty("strict", false);
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", request.parallelToolCalls());
            if (Set.of("auto", "none", "required").contains(request.toolChoice()))
                body.addProperty("tool_choice", request.toolChoice());
            else {
                JsonObject choice = new JsonObject();
                choice.addProperty("type", "function");
                choice.addProperty("name", request.toolChoice());
                body.add("tool_choice", choice);
            }
        }
        return body;
    }

    @Override
    public ModelGateway.Reply decode(JsonObject response) throws ModelGateway.Failure {
        try {
            String status = ModelProtocol.string(response, "status", "");
            if (!"completed".equals(status)) {
                String reason = response.has("incomplete_details") && response.get("incomplete_details").isJsonObject()
                        ? ModelProtocol.string(response.getAsJsonObject("incomplete_details"), "reason", "") : "";
                throw new ModelGateway.Failure("MODEL_INCOMPLETE", Map.of(
                        "status", Set.of("incomplete", "in_progress", "failed", "queued", "cancelled").contains(status)
                                ? status : "missing_or_unknown",
                        "reason", Set.of("max_output_tokens", "content_filter").contains(reason) ? reason : "unspecified"));
            }
            JsonArray output = response.getAsJsonArray("output");
            List<ModelMessage.ToolCall> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonElement element : output) {
                JsonObject item = element.getAsJsonObject();
                switch (ModelProtocol.string(item, "type", "")) {
                    case "function_call" -> calls.add(new ModelMessage.ToolCall(
                            ModelProtocol.string(item, "call_id", ""),
                            ModelProtocol.string(item, "name", ""),
                            ModelProtocol.string(item, "arguments", "")));
                    case "message" -> {
                        for (JsonElement block : item.getAsJsonArray("content")) {
                            JsonObject content = block.getAsJsonObject();
                            String type = ModelProtocol.string(content, "type", "");
                            if ("output_text".equals(type)) text.append(ModelProtocol.string(content, "text", ""));
                            else if ("refusal".equals(type)) text.append(ModelProtocol.string(content, "refusal", ""));
                            else throw new IllegalArgumentException("unsupported output content");
                        }
                    }
                    case "reasoning" -> { /* Opaque provider state is replayed verbatim. */ }
                    default -> throw new IllegalArgumentException("unsupported output item");
                }
            }
            ModelProtocol.validateCalls(calls);
            if (text.isEmpty() && calls.isEmpty()) throw new IllegalArgumentException("empty response");
            return new ModelGateway.Reply(new ModelMessage("assistant", text.toString(), calls, null, output),
                    ModelProtocol.usage(response, "input_tokens"), ModelProtocol.usage(response, "output_tokens"));
        } catch (ModelGateway.Failure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelGateway.Failure("PROTOCOL_ERROR");
        }
    }
}
