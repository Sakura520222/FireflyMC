package firefly520.fireflymc.ai.knowledge;

import com.google.gson.*;
import java.util.*;

/** Aggregates only indexed, active recipe holders. A namespace or serializer is not an executor. */
public final class IntegrationEvidenceIndex {
    private record Mechanism(String type, String serializer, String adapter, boolean available) {}
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Map<Mechanism, Integer>> namespaces = new HashMap<>();
    public void clear() { seen.clear(); namespaces.clear(); }
    public void accept(JsonObject row) {
        String id = row.get("recipeId").getAsString();
        if (!seen.add(id)) return;
        String namespace = id.substring(0, id.indexOf(':'));
        var mechanism = new Mechanism(row.get("recipeType").getAsString(), row.get("serializerId").getAsString(),
                row.get("executionAdapter").getAsString(), row.get("executionAvailable").getAsBoolean());
        namespaces.computeIfAbsent(namespace, ignored -> new HashMap<>()).merge(mechanism, 1, Integer::sum);
    }
    public JsonObject view(String namespace) {
        var values = namespaces.getOrDefault(namespace, Map.of());
        JsonObject result = new JsonObject();
        result.addProperty("indexedRecipeCount", values.values().stream().mapToInt(Integer::intValue).sum());
        result.addProperty("executableRecipeCount", values.entrySet().stream().filter(e -> e.getKey().available).mapToInt(Map.Entry::getValue).sum());
        var rows = new JsonArray();
        values.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().type + e.getKey().serializer + e.getKey().adapter)).limit(12).forEach(entry -> {
            var row = new JsonObject(); var mechanism = entry.getKey();
            row.addProperty("recipeType", mechanism.type); row.addProperty("serializerId", mechanism.serializer);
            row.addProperty("adapter", mechanism.adapter); row.addProperty("recipeCount", entry.getValue());
            row.addProperty("state", mechanism.available ? "RUNTIME_READY" : "DISCOVERED");
            row.addProperty("behaviorVerifiedHere", false); rows.add(row);
        });
        result.add("mechanisms", rows); result.addProperty("mechanismsTruncated", values.size() > rows.size());
        result.addProperty("scope", "当前已索引配方 ID 的命名空间；仅对标出的配方开放规划，不代表该 Mod 全部玩法或真实材料已通过验收。");
        return result;
    }
}
