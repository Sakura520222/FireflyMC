package firefly520.fireflymc.ai.agent;

import com.google.gson.*;
import firefly520.fireflymc.ai.context.ContextCompactor;
import java.util.*;

/** A bounded model view retains useful facts; the complete receipt remains in the conversation. */
final class ToolResultProjection {
    private ToolResultProjection() { }

    static JsonObject bounded(String tool, JsonObject full, UUID artifact, int budget) {
        var view = new JsonObject();
        copy(full, view, "success", "errorCode");
        String summary = full.has("summary") ? full.get("summary").getAsString() : "";
        view.addProperty("summary", summary.substring(0, Math.min(512, summary.length())));
        view.addProperty("artifactId", artifact.toString());
        view.addProperty("readRequired", true);
        view.addProperty("detail", "本任务内用 artifact_read 分页读取正文，无需玩家再回复；服务端会核对读取能力。");
        if (tool.equals("crafting_discover") && full.has("observations")) {
            JsonObject data = full.getAsJsonObject("observations");
            if (data.has("complete") && data.get("complete").getAsBoolean() && data.has("displayReport")) {
                var catalog = new JsonObject();
                copy(data, catalog, "complete", "readOnly", "capturedAtIso", "craftableCount", "query", "coverage", "independentAlternatives");
                catalog.addProperty("reportDelivered", true);
                var items = new JsonArray(); catalog.add("craftable", items); view.add("observations", catalog);
                for (var element : array(data, "craftable")) {
                    var row = new JsonObject(); copy(element.getAsJsonObject(), row, "displayName", "outputId", "outputCount", "recipeId");
                    items.add(row);
                    if (ContextCompactor.estimate(view) > budget - 100) { items.remove(items.size() - 1); break; }
                }
                catalog.addProperty("modelPreviewCount", items.size());
                view.addProperty("readRequired", false);
                view.addProperty("detail", "完整清单已直接展示在聊天。无需重复查询、逐页读取或复述列表；只按玩家问题补充有用建议。详细配料仍可按需读取产物。");
                if (ContextCompactor.estimate(view) > budget) catalog.remove("craftable");
                if (ContextCompactor.estimate(view) > budget) catalog.remove("coverage");
                return view;
            }
            if (data.has("craftable") && data.get("craftable").isJsonArray()) {
                var preview = new JsonObject();
                copy(data, preview, "snapshotId", "capturedAt", "capturedAtIso", "recipeGeneration", "query", "nextOffset", "hasMore",
                        "catalogTruncated", "stockTruncated", "independentAlternatives");
                preview.addProperty("coverage", "本页核验的一次加工备选，不能相加同时制作，不代表全部整合包。概率产物不保证获得，未扣料。");
                var rows = new JsonArray(); preview.add("craftable", rows); view.add("observations", preview);
                for (var element : data.getAsJsonArray("craftable")) {
                    if (!element.isJsonObject()) continue;
                    var source = element.getAsJsonObject(); var row = new JsonObject();
                    copy(source, row, "recipeId", "displayName", "outputId", "productSemantics");
                    var inputs = new JsonArray();
                    for (var input : array(source, "inputs")) {
                        if (input.isJsonObject() && input.getAsJsonObject().has("item"))
                            inputs.add(material(input.getAsJsonObject().getAsJsonObject("item")));
                    }
                    row.add("inputs", inputs);
                    var products = new JsonArray();
                    for (var product : array(source, "productsIncludingRemainders"))
                        if (product.isJsonObject()) products.add(material(product.getAsJsonObject()));
                    row.add("productsIncludingRemainders", products);
                    var probabilities = new JsonArray();
                    for (var chance : array(source, "probabilityOutputs")) {
                        if (!chance.isJsonObject()) continue;
                        var value = new JsonObject(); copy(chance.getAsJsonObject(), value, "chance");
                        if (chance.getAsJsonObject().has("item")) value.add("item", material(chance.getAsJsonObject().getAsJsonObject("item")));
                        probabilities.add(value);
                    }
                    row.add("probabilityOutputs", probabilities); rows.add(row);
                    if (ContextCompactor.estimate(view) > budget) { rows.remove(rows.size() - 1); break; }
                }
                preview.addProperty("visibleEntries", rows.size());
                preview.addProperty("totalEntriesThisPage", data.getAsJsonArray("craftable").size());
                // The public material summary is enough to answer this query; exact components stay retrievable.
                view.addProperty("readRequired", rows.isEmpty() || rows.size() != data.getAsJsonArray("craftable").size());
            }
        }
        while (ContextCompactor.estimate(view) > budget && !view.get("summary").getAsString().isEmpty()) {
            summary = view.get("summary").getAsString(); view.addProperty("summary", summary.substring(0, summary.length() / 2));
        }
        if (ContextCompactor.estimate(view) > budget) { view.remove("observations"); view.addProperty("readRequired", true); }
        if (ContextCompactor.estimate(view) > budget) throw new IllegalStateException("RESULT_PROJECTION_BUDGET_EXHAUSTED");
        return view;
    }

    private static JsonObject material(JsonObject source) {
        var result = new JsonObject(); copy(source, result, "id", "count", "empty", "enchanted", "damage", "customNamePresent");
        if (source.has("componentTypes")) result.addProperty("componentDetailsInFullResult", true);
        return result;
    }
    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }
    private static void copy(JsonObject from, JsonObject to, String... keys) {
        for (String key : keys) if (from.has(key)) to.add(key, from.get(key).deepCopy());
    }
}
