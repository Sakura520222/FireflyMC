package firefly520.fireflymc.ai.model;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Incremental UTF-8 SSE decoder. Split code points, tool arguments and interleaved call indexes are preserved. */
public final class StreamingResponseParser {
    private static final class Call {
        final StringBuilder id = new StringBuilder(), name = new StringBuilder(), arguments = new StringBuilder();
    }
    private final boolean responses;
    private final Consumer<ModelEvent> listener;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder(), text = new StringBuilder(), reasoning = new StringBuilder();
    private final SortedMap<Integer, Call> calls = new TreeMap<>();
    private JsonObject usage = new JsonObject(), finalResponse;
    private String eventType = "", finish = "";
    private boolean terminal;
    private long received;

    public StreamingResponseParser(boolean responses, Consumer<ModelEvent> listener) {
        this.responses = responses; this.listener = listener;
    }
    public void accept(byte[] bytes) throws ModelGateway.Failure {
        for (byte value : bytes) accept(value);
    }
    public void accept(byte value) throws ModelGateway.Failure {
        if (++received > 8 * 1024 * 1024) throw new ModelGateway.Failure("MODEL_RESPONSE_TOO_LARGE");
        if (terminal) return;
        if (value == '\n') {
            String decoded = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (decoded.endsWith("\r")) decoded = decoded.substring(0, decoded.length() - 1);
            line(decoded);
        } else {
            if (line.size() > 2 * 1024 * 1024) throw new ModelGateway.Failure("MODEL_EVENT_TOO_LARGE");
            line.write(value);
        }
    }
    private void line(String line) throws ModelGateway.Failure {
        if (line.isEmpty()) {
            dispatch();
            eventType = "";
        } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).stripLeading());
            if (data.length() > 2 * 1024 * 1024) throw new ModelGateway.Failure("MODEL_EVENT_TOO_LARGE");
        } else if (line.startsWith("event:")) eventType = line.substring(6).trim();
    }
    private void dispatch() throws ModelGateway.Failure {
        if (data.isEmpty()) return;
        String raw = data.toString();
        data.setLength(0);
        if (raw.trim().equals("[DONE]")) { terminal = true; return; }
        try {
            JsonObject item = AiJson.object(raw, 2 * 1024 * 1024, 48);
            if (responses) {
                String type = ModelProtocol.string(item, "type", eventType);
                switch (type) {
                    case "response.output_text.delta" -> {
                        String delta = ModelProtocol.string(item, "delta", "");
                        text.append(delta);
                        listener.accept(new ModelEvent.TextDelta(delta));
                    }
                    case "response.completed", "response.incomplete", "response.failed" -> {
                        finalResponse = item.getAsJsonObject("response").deepCopy();
                        terminal = true;
                    }
                    case "error" -> throw new ModelGateway.Failure("MODEL_STREAM_ERROR");
                    default -> { /* Item/argument fragments are committed only through the completed response. */ }
                }
            } else {
                if (item.has("error")) throw new ModelGateway.Failure("MODEL_STREAM_ERROR");
                if (item.has("usage") && item.get("usage").isJsonObject()) usage = item.getAsJsonObject("usage").deepCopy();
                if (!item.has("choices")) return;
                for (var choiceElement : item.getAsJsonArray("choices")) {
                    JsonObject choice = choiceElement.getAsJsonObject();
                    if (choice.has("index") && choice.get("index").getAsInt() != 0) continue;
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull())
                        finish = choice.get("finish_reason").getAsString();
                    if (!choice.has("delta")) continue;
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta.has("role") && !"assistant".equals(delta.get("role").getAsString()))
                        throw new ModelGateway.Failure("PROTOCOL_ERROR");
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String fragment = delta.get("content").getAsString();
                        text.append(fragment);
                        listener.accept(new ModelEvent.TextDelta(fragment));
                    }
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull())
                        reasoning.append(delta.get("reasoning_content").getAsString());
                    if (delta.has("tool_calls")) for (var callElement : delta.getAsJsonArray("tool_calls")) {
                        JsonObject fragment = callElement.getAsJsonObject();
                        int index = fragment.get("index").getAsInt();
                        if (index < 0 || index >= 64) throw new ModelGateway.Failure("PROTOCOL_ERROR");
                        Call call = calls.computeIfAbsent(index, ignored -> new Call());
                        appendIdentity(call.id, ModelProtocol.string(fragment, "id", ""));
                        if (fragment.has("function")) {
                            JsonObject function = fragment.getAsJsonObject("function");
                            appendIdentity(call.name, ModelProtocol.string(function, "name", ""));
                            call.arguments.append(ModelProtocol.string(function, "arguments", ""));
                        }
                    }
                }
            }
            if (text.length() + reasoning.length() > 2 * 1024 * 1024) throw new ModelGateway.Failure("MODEL_RESPONSE_TOO_LARGE");
        } catch (ModelGateway.Failure e) { throw e; }
        catch (RuntimeException e) { throw new ModelGateway.Failure("PROTOCOL_ERROR"); }
    }
    private static void appendIdentity(StringBuilder target, String fragment) {
        if (!fragment.isEmpty() && !target.toString().equals(fragment)) target.append(fragment);
    }
    public boolean terminal() { return terminal; }
    public JsonObject finish() throws ModelGateway.Failure {
        if (line.size() > 0) {
            line(line.toString(StandardCharsets.UTF_8));
            line.reset();
        }
        dispatch();
        if (responses) {
            if (finalResponse == null) throw new ModelGateway.Failure("MODEL_INCOMPLETE");
            return finalResponse;
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text.toString());
        if (!reasoning.isEmpty()) message.addProperty("reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) {
            JsonArray array = new JsonArray();
            for (Call call : calls.values()) {
                JsonObject item = new JsonObject(), function = new JsonObject();
                item.addProperty("id", call.id.toString()); item.addProperty("type", "function");
                function.addProperty("name", call.name.toString()); function.addProperty("arguments", call.arguments.toString());
                item.add("function", function); array.add(item);
            }
            message.add("tool_calls", array);
        }
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", finish);
        choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices); response.add("usage", usage);
        return response;
    }
}
