package firefly520.fireflymc.ai.agent;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Completion is gated by committed result consumption, not by a tool's success or an empty call list. */
final class ResultDeliveryGuard {
    record Need(String artifactId, int offset, int total) { }
    record Pending(List<Need> reads, List<String> selectedTools, int attempts) {
        boolean required() { return !reads.isEmpty() || !selectedTools.isEmpty(); }
        String instruction() {
            if (!required()) return "";
            return "服务端交付检查：这次请求还未完成。不要要求玩家回复‘继续’或等待新消息加载工具。"
                    + (reads.isEmpty() ? "已在当前 tools 中装入所发现能力，请现在调用所需工具；发现能力本身不是任务成果："
                    + AiJson.GSON.toJson(selectedTools) : "完整结果仍有未读正文。artifact_read 已在当前 tools 中，按下列 artifactId/offset 继续读取后直接回答："
                    + AiJson.GSON.toJson(reads.stream().limit(2).toList()))
                    + "。不得把查询成功、内部状态COMPLETED或产物地址说成已经把具体结果交给玩家。";
        }
    }
    private record Range(int from, int through) { }
    private ResultDeliveryGuard() { }

    static Pending pending(ConversationStore.Snapshot snapshot, UUID task) {
        Map<String, Integer> required = new LinkedHashMap<>();
        Map<String, List<Range>> ranges = new HashMap<>();
        List<String> selected = new ArrayList<>(); int attempts = 0;
        for (var event : snapshot.events()) {
            if (!task.equals(event.taskId()) || event.data() == null) continue;
            JsonObject data = event.data();
            if (event.kind().equals("tool_catalog") && data.has("intent") && data.get("intent").getAsString().equals("use")) {
                selected = new ArrayList<>();
                for (var name : data.getAsJsonArray("names")) selected.add(name.getAsString());
            } else if (event.kind().equals("tool_result")) {
                String name = data.has("name") ? data.get("name").getAsString() : "";
                if (!name.equals("tools_discover")) selected.clear();
                if (data.has("deliveryReadRequired") && data.get("deliveryReadRequired").getAsBoolean())
                    required.put(data.get("artifactId").getAsString(), data.get("fullResult").getAsString().length());
                if (data.has("artifactRead")) {
                    var read = data.getAsJsonObject("artifactRead");
                    if (read.has("readToEnd") && read.get("readToEnd").getAsBoolean())
                        required.putIfAbsent(read.get("artifactId").getAsString(), read.get("total").getAsInt());
                    ranges.computeIfAbsent(read.get("artifactId").getAsString(), ignored -> new ArrayList<>())
                            .add(new Range(read.get("from").getAsInt(), read.get("through").getAsInt()));
                }
                attempts = 0;
            } else if (event.kind().equals("answer_delivery_retry")) attempts++;
        }
        List<Need> unread = new ArrayList<>();
        for (var entry : required.entrySet()) {
            int through = 0;
            for (var range : ranges.getOrDefault(entry.getKey(), List.of()).stream().sorted(Comparator.comparingInt(Range::from)).toList()) {
                if (range.from() > through) break;
                through = Math.max(through, range.through());
            }
            if (through < entry.getValue()) unread.add(new Need(entry.getKey(), through, entry.getValue()));
        }
        return new Pending(List.copyOf(unread), List.copyOf(selected), attempts);
    }

    static void recordRead(JsonObject journal, String arguments, JsonObject observations) {
        if (observations == null || !observations.has("text") || !observations.has("nextOffset") || !observations.has("totalCharacters")) return;
        var args = JsonParser.parseString(arguments).getAsJsonObject();
        String artifact = UUID.fromString(args.get("artifactId").getAsString()).toString();
        int from = args.has("offset") ? args.get("offset").getAsInt() : 0;
        int through = observations.get("nextOffset").getAsInt(), total = observations.get("totalCharacters").getAsInt();
        if (from < 0 || through < from || through > total || through - from != observations.get("text").getAsString().length())
            throw new IllegalStateException("ARTIFACT_READ_RANGE_INVALID");
        var read = new JsonObject(); read.addProperty("artifactId", artifact); read.addProperty("from", from);
        read.addProperty("through", through); read.addProperty("total", total); journal.add("artifactRead", read);
        read.addProperty("readToEnd", !args.has("mode") || !args.get("mode").getAsString().equals("excerpt"));
    }
}
