package firefly520.fireflymc.ai.presentation;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.net.URI;
import java.util.*;

/** Display-only whitelist. Approval and executable actions are exclusively server workflow messages. */
public final class ResponseBlocks {
    public record Block(String type, String text, JsonObject data) {}
    private static final Set<String> SIMPLE = Set.of("MarkdownBlock", "CodeBlock", "QuoteBlock", "InfoCard",
            "EvidenceBlock", "ErrorBlock", "RetryStatus", "StatCard", "Checklist");
    private ResponseBlocks() {}
    public static String document(JsonArray blocks) {
        JsonObject document = new JsonObject();
        document.addProperty("firefly_ui", 1);
        JsonArray flattened = new JsonArray();
        for (var item : blocks) {
            JsonObject block = item.getAsJsonObject();
            JsonObject value = block.has("data") ? block.getAsJsonObject("data").deepCopy() : new JsonObject();
            value.add("type", block.get("type")); value.add("text", block.get("text"));
            flattened.add(value);
        }
        document.add("blocks", flattened);
        return document.toString();
    }
    public static String plainText(String text) {
        StringBuilder result = new StringBuilder();
        for (Block block : parse(text)) {
            if (block.data().has("title")) result.append(block.data().get("title").getAsString()).append('\n');
            result.append(block.text()).append('\n');
            for (String line : displayLines(block)) result.append(line).append('\n');
            if (block.data().has("value")) result.append(block.data().get("value").getAsString()).append('\n');
            if (block.data().has("columns")) {
                result.append(block.data().get("columns")).append('\n');
                block.data().getAsJsonArray("rows").forEach(row -> result.append(row).append('\n'));
            }
            if (block.data().has("url")) result.append(block.data().get("url").getAsString()).append('\n');
            for (String field : List.of("source", "version", "retrievedAt"))
                if (block.data().has(field)) result.append(field).append(": ").append(block.data().get(field).getAsString()).append('\n');
        }
        return result.toString().stripTrailing();
    }
    public static List<Block> parse(String text) {
        if (text.length() <= 12000 && text.stripLeading().startsWith("{")) {
            try {
                JsonObject root = AiJson.object(text, 12000, 12);
                if (root.has("firefly_ui") && root.get("firefly_ui").getAsInt() == 1) {
                    JsonArray items = root.getAsJsonArray("blocks");
                    if (items.size() > 32 || items.isEmpty()) throw new IllegalArgumentException("BLOCK_LIMIT");
                    List<Block> blocks = new ArrayList<>();
                    for (var item : items) blocks.add(validate(item.getAsJsonObject()));
                    return List.copyOf(blocks);
                }
            } catch (RuntimeException ignored) { /* Unknown/invalid components remain inert literal text. */ }
        }
        return List.of(new Block("MarkdownBlock", text, new JsonObject()));
    }
    /** A bounded display projection; the full message remains unchanged in the conversation. */
    public static JsonArray preview(String text, int maximum) {
        if (maximum < 256 || maximum > 4000) throw new IllegalArgumentException("COMPONENT_PREVIEW_LIMIT");
        var blocks = parse(text); var result = new JsonArray();
        if (blocks.size() == 1 && blocks.getFirst().type().equals("MarkdownBlock") && blocks.getFirst().text().equals(text)) return result;
        for (var block : blocks) {
            var data = block.data().deepCopy();
            for (String field : List.of("title", "source", "version", "retrievedAt"))
                if (data.has(field)) data.addProperty(field, shorten(data.get(field).getAsString(), 80));
            if (block.type().equals("TableBlock")) {
                var columns = new JsonArray(); for (var value : data.getAsJsonArray("columns")) columns.add(shorten(value.getAsString(), 24));
                var rows = new JsonArray();
                for (var row : data.getAsJsonArray("rows").asList().stream().limit(3).toList()) {
                    var cells = new JsonArray(); for (var cell : row.getAsJsonArray()) cells.add(shorten(cell.getAsString(), 48)); rows.add(cells);
                }
                data.add("columns", columns); data.add("rows", rows);
            }
            if (block.type().equals("StepTimeline")) {
                var steps = new JsonArray(); for (var step : data.getAsJsonArray("steps").asList().stream().limit(6).toList()) {
                    var value = step.getAsJsonObject().deepCopy(); value.addProperty("label", shorten(value.get("label").getAsString(), 80)); steps.add(value);
                }
                data.add("steps", steps);
            }
            var compact = AiJson.GSON.toJsonTree(new Block(block.type(), shorten(block.text(), 160), data));
            if (result.toString().length() + compact.toString().length() + 2 > maximum) {
                var notice = AiJson.GSON.toJsonTree(new Block("InfoCard", "较长组件请选中后查看全文。", new JsonObject()));
                if (result.toString().length() + notice.toString().length() + 2 <= maximum) result.add(notice);
                break;
            }
            result.add(compact);
        }
        return result;
    }
    private static String shorten(String text, int maximum) { return text.length() <= maximum ? text : text.substring(0, maximum) + "…"; }
    private static Block validate(JsonObject object) {
        if (!Set.of("type", "text", "language", "title", "value", "columns", "rows", "url", "version", "retrievedAt", "source",
                "itemId", "count", "componentsDigest", "recipeId", "current", "total", "steps")
                .containsAll(object.keySet())) throw new IllegalArgumentException("UNTRUSTED_ACTION");
        String type = object.get("type").getAsString();
        String text = string(object, "text", 6000);
        JsonObject data = new JsonObject();
        if (SIMPLE.contains(type)) {
            for (String field : List.of("language", "title", "value", "source", "version", "retrievedAt"))
                if (object.has(field)) data.addProperty(field, string(object, field, 240));
        } else if ("TableBlock".equals(type)) {
            JsonArray columns = object.getAsJsonArray("columns"), rows = object.getAsJsonArray("rows");
            if (columns.isEmpty() || columns.size() > 8 || rows.size() > 40) throw new IllegalArgumentException("TABLE_LIMIT");
            JsonArray safeColumns = new JsonArray(), safeRows = new JsonArray();
            for (var column : columns) safeColumns.add(bounded(column.getAsString(), 100));
            for (var row : rows) {
                if (!row.isJsonArray() || row.getAsJsonArray().size() != columns.size()) throw new IllegalArgumentException("TABLE_SHAPE");
                JsonArray safe = new JsonArray();
                for (var cell : row.getAsJsonArray()) safe.add(bounded(cell.getAsString(), 240));
                safeRows.add(safe);
            }
            data.add("columns", safeColumns); data.add("rows", safeRows);
        } else if ("SourceLink".equals(type)) {
            String url = string(object, "url", 2048);
            if (!safeUrl(url)) throw new IllegalArgumentException("URL_SCHEME");
            data.addProperty("url", url);
            for (String field : List.of("title", "version", "retrievedAt"))
                if (object.has(field)) data.addProperty(field, string(object, field, 240));
        } else if ("ItemStackBlock".equals(type)) {
            data.addProperty("itemId", resource(object, "itemId"));
            data.addProperty("count", integer(object, "count", 1, 1_000_000));
            if (object.has("componentsDigest")) {
                String digest = string(object, "componentsDigest", 64);
                if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("ITEM_COMPONENT_DIGEST");
                data.addProperty("componentsDigest", digest);
            }
        } else if ("RecipeBlock".equals(type)) {
            String recipe = resource(object, "recipeId");
            if (recipe.length() > 160) throw new IllegalArgumentException("RECIPE_COMPONENT_LIMIT");
            data.addProperty("recipeId", recipe);
        } else if ("ProgressBlock".equals(type)) {
            long total = integer(object, "total", 1, 1_000_000_000L);
            data.addProperty("total", total); data.addProperty("current", integer(object, "current", 0, total));
        } else if ("StepTimeline".equals(type)) {
            JsonArray steps = object.getAsJsonArray("steps");
            if (steps.isEmpty() || steps.size() > 32) throw new IllegalArgumentException("STEP_LIMIT");
            var safe = new JsonArray();
            for (var step : steps) {
                var entry = step.getAsJsonObject();
                if (!entry.keySet().equals(Set.of("label", "status"))) throw new IllegalArgumentException("UNTRUSTED_ACTION");
                String state = string(entry, "status", 20);
                if (!Set.of("PENDING", "ACTIVE", "DONE", "SKIPPED", "FAILED", "PAUSED").contains(state))
                    throw new IllegalArgumentException("STEP_STATUS");
                var value = new JsonObject(); value.addProperty("label", string(entry, "label", 240));
                value.addProperty("status", state); safe.add(value);
            }
            data.add("steps", safe);
        } else throw new IllegalArgumentException("UNKNOWN_COMPONENT");
        if (Set.of("ItemStackBlock", "RecipeBlock", "ProgressBlock", "StepTimeline").contains(type))
            for (String field : List.of("title", "source", "version", "retrievedAt"))
                if (object.has(field)) data.addProperty(field, string(object, field, 240));
        return new Block(type, text, data);
    }
    /** Visible advisory metadata. Actual task state and approvals come from server workflow packets. */
    public static List<String> displayLines(Block block) {
        var data = block.data();
        return switch (block.type()) {
            case "ItemStackBlock" -> data.has("componentsDigest")
                    ? List.of("物品建议：" + data.get("itemId").getAsString() + " ×" + data.get("count").getAsLong(),
                            "组件摘要：" + data.get("componentsDigest").getAsString())
                    : List.of("物品建议：" + data.get("itemId").getAsString() + " ×" + data.get("count").getAsLong());
            case "RecipeBlock" -> List.of("配方引用：" + data.get("recipeId").getAsString() + "（点击查询服务端配方）");
            case "ProgressBlock" -> List.of("计划描述进度：" + data.get("current").getAsLong() + " / " + data.get("total").getAsLong());
            case "StepTimeline" -> {
                var lines = new ArrayList<String>(); lines.add("计划步骤（实际执行状态见看板）");
                for (var step : data.getAsJsonArray("steps")) {
                    var value = step.getAsJsonObject();
                    String state = switch (value.get("status").getAsString()) {
                        case "ACTIVE" -> "进行"; case "DONE" -> "完成"; case "SKIPPED" -> "略过";
                        case "FAILED" -> "失败"; case "PAUSED" -> "暂停"; default -> "待办";
                    };
                    lines.add(state + " · " + value.get("label").getAsString());
                }
                yield List.copyOf(lines);
            }
            default -> List.of();
        };
    }
    private static String resource(JsonObject object, String field) {
        String value = string(object, field, 240);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || value.contains(":/") || value.contains(".."))
            throw new IllegalArgumentException("COMPONENT_RESOURCE_ID");
        return value;
    }
    private static long integer(JsonObject object, String field, long minimum, long maximum) {
        var value = object.getAsJsonPrimitive(field);
        if (!value.isNumber()) throw new IllegalArgumentException("COMPONENT_INTEGER");
        long count = value.getAsBigDecimal().longValueExact();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("COMPONENT_INTEGER_RANGE");
        return count;
    }
    private static String string(JsonObject object, String field, int length) {
        return object.has(field) ? bounded(object.get(field).getAsString(), length) : "";
    }
    private static String bounded(String text, int length) {
        if (text.length() > length) throw new IllegalArgumentException("BLOCK_TEXT_LIMIT");
        return text;
    }
    public static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && value.length() <= 2048 && value.chars().noneMatch(c -> c < 32);
        } catch (RuntimeException e) { return false; }
    }
}
