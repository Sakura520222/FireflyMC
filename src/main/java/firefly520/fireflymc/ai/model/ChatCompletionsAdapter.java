package firefly520.fireflymc.ai.model;

import com.google.gson.*;
import java.util.*;

public final class ChatCompletionsAdapter implements ModelProtocol {
    @Override public String path() { return "/chat/completions"; }

    @Override
    public JsonObject encode(String model, ModelGateway.Request request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", request.stream());
        if (request.stream()) {
            JsonObject options = new JsonObject(); options.addProperty("include_usage", true);
            body.add("stream_options", options);
        }
        body.addProperty("max_completion_tokens", request.maxOutputTokens());
        JsonArray messages = new JsonArray();
        for (ModelMessage message : request.messages()) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.text());
            if (message.providerState().has("reasoning_content"))
                item.add("reasoning_content", message.providerState().get("reasoning_content").deepCopy());
            if (message.providerState().has("reasoning_details"))
                item.add("reasoning_details", message.providerState().get("reasoning_details").deepCopy());
            if (message.toolCallId() != null) item.addProperty("tool_call_id", message.toolCallId());
            if (!message.calls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (var call : message.calls()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", call.id());
                    entry.addProperty("type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.arguments());
                    entry.add("function", function);
                    calls.add(entry);
                }
                item.add("tool_calls", calls);
            }
            messages.add(item);
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (var definition : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", definition.name());
                function.addProperty("description", definition.description());
                function.add("parameters", definition.inputSchema().deepCopy());
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", request.parallelToolCalls());
            if (Set.of("auto", "none", "required").contains(request.toolChoice()))
                body.addProperty("tool_choice", request.toolChoice());
            else {
                JsonObject choice = new JsonObject(), function = new JsonObject();
                choice.addProperty("type", "function");
                function.addProperty("name", request.toolChoice());
                choice.add("function", function);
                body.add("tool_choice", choice);
            }
        }
        return body;
    }

    @Override
    public ModelGateway.Reply decode(JsonObject response) throws ModelGateway.Failure {
        try {
            JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            String finish = ModelProtocol.string(choice, "finish_reason", "");
            if (!Set.of("stop", "tool_calls").contains(finish))
                throw new ModelGateway.Failure("MODEL_INCOMPLETE", Map.of("reason",
                        finish.equals("length") ? "max_output_tokens" : finish.equals("content_filter") ? "content_filter" : "unspecified"));
            JsonObject message = choice.getAsJsonObject("message");
            if (!"assistant".equals(ModelProtocol.string(message, "role", "assistant")))
                throw new IllegalArgumentException("role");
            List<ModelMessage.ToolCall> calls = new ArrayList<>();
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                    JsonObject call = element.getAsJsonObject();
                    if (!"function".equals(ModelProtocol.string(call, "type", "")))
                        throw new IllegalArgumentException("tool type");
                    JsonObject function = call.getAsJsonObject("function");
                    calls.add(new ModelMessage.ToolCall(ModelProtocol.string(call, "id", ""),
                            ModelProtocol.string(function, "name", ""),
                            ModelProtocol.string(function, "arguments", "")));
                }
            }
            ModelProtocol.validateCalls(calls);
            String content = ModelProtocol.string(message, "content", "");
            if (content.isBlank() && calls.isEmpty()) {
                content = ModelProtocol.string(message, "refusal", "");
                if (content.isBlank()) throw new IllegalArgumentException("empty response");
            }
            JsonObject state = new JsonObject();
            for (String field : List.of("reasoning_content", "reasoning_details"))
                if (message.has(field) && !message.get(field).isJsonNull()) state.add(field, message.get(field).deepCopy());
            return new ModelGateway.Reply(new ModelMessage("assistant", content, calls, null, null, state),
                    ModelProtocol.usage(response, "prompt_tokens"), ModelProtocol.usage(response, "completion_tokens"));
        } catch (ModelGateway.Failure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelGateway.Failure("PROTOCOL_ERROR");
        }
    }
}
