package firefly520.fireflymc.ai.material;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Versioned, lossless operation deltas. The enclosing journal still commits/checksums every event. */
public final class MaterialJournalCodec {
    public static final String FULL = "material_operation", DELTA = "material_operation_delta";
    private static final Set<String> ARRAYS = Set.of("claims", "escrow", "products", "delivered", "sampledOutputs", "worldDebits", "externalDebits", "nativeSaves");
    private static final Set<String> SCALARS = Set.of("state", "intent", "reason", "samplingEvidence", "revision");
    private static final Set<String> IDENTITY = Set.of("version", "operationId", "owner", "taskId", "planHash", "policy");
    private static final int MAX_ARRAY = 16384;
    public record Encoded(String kind, JsonObject data) {}
    private MaterialJournalCodec() {}

    public static Encoded encode(JsonObject previous, JsonObject current) {
        if (previous == null) return new Encoded(FULL, current);
        var known = new HashSet<>(IDENTITY); known.addAll(SCALARS); known.addAll(ARRAYS);
        if (!known.containsAll(current.keySet()) || !known.containsAll(previous.keySet())) return new Encoded(FULL, current);
        for (String field : IDENTITY) if (!Objects.equals(previous.get(field), current.get(field)))
            throw new IllegalStateException("MATERIAL_DELTA_IDENTITY_CHANGED");
        long base = previous.get("revision").getAsLong();
        if (current.get("revision").getAsLong() != Math.addExact(base, 1)) throw new IllegalStateException("MATERIAL_DELTA_REVISION");
        JsonObject delta = new JsonObject(), fields = new JsonObject(), arrays = new JsonObject();
        JsonArray remove = new JsonArray(); delta.addProperty("version", 1);
        delta.add("operationId", current.get("operationId").deepCopy()); delta.addProperty("baseRevision", base);
        for (String field : SCALARS) {
            if (Objects.equals(previous.get(field), current.get(field))) continue;
            if (!current.has(field)) remove.add(field); else fields.add(field, current.get(field).deepCopy());
        }
        for (String field : ARRAYS) {
            JsonArray before = previous.has(field) ? previous.getAsJsonArray(field) : new JsonArray();
            JsonArray after = current.getAsJsonArray(field);
            if (after == null || after.size() > MAX_ARRAY) throw new IllegalStateException("MATERIAL_DELTA_ARRAY_LIMIT");
            if (before.equals(after)) continue;
            JsonObject change = new JsonObject(); JsonArray entries = new JsonArray(); change.addProperty("length", after.size());
            for (int i = 0; i < after.size(); i++) if (i >= before.size() || !before.get(i).equals(after.get(i))) {
                JsonObject entry = new JsonObject(); entry.addProperty("index", i); entry.add("value", after.get(i).deepCopy()); entries.add(entry);
            }
            change.add("entries", entries); arrays.add(field, change);
        }
        delta.add("fields", fields); delta.add("remove", remove); delta.add("arrays", arrays);
        // Small changes can remain ordinary snapshots; either representation restores exactly.
        return delta.toString().length() + 64 < current.toString().length()
                ? new Encoded(DELTA, delta) : new Encoded(FULL, current);
    }

    public static JsonObject decode(JsonObject previous, String kind, JsonObject payload) throws IOException {
        if (FULL.equals(kind)) {
            try {
                if (previous != null) {
                    for (String key : IDENTITY) if (!Objects.equals(previous.get(key), payload.get(key))) throw new IllegalArgumentException("identity");
                    if (integer(payload.get("revision")) != Math.addExact(integer(previous.get("revision")), 1)) throw new IllegalArgumentException("revision");
                } else if (integer(payload.get("revision")) != 1) throw new IllegalArgumentException("first revision");
                return payload.deepCopy();
            } catch (RuntimeException malformed) { throw new IOException("MATERIAL_JOURNAL_SNAPSHOT_INVALID", malformed); }
        }
        if (!DELTA.equals(kind)) throw new IOException("MATERIAL_JOURNAL_EVENT");
        try {
            if (previous == null || !payload.keySet().equals(Set.of("version", "operationId", "baseRevision", "fields", "remove", "arrays"))
                    || integer(payload.get("version")) != 1 || !previous.get("operationId").equals(payload.get("operationId"))
                    || integer(payload.get("baseRevision")) != previous.get("revision").getAsLong())
                throw new IllegalArgumentException("identity");
            JsonObject result = previous.deepCopy(); var changed = new HashSet<String>();
            for (var entry : payload.getAsJsonObject("fields").entrySet()) {
                if (!SCALARS.contains(entry.getKey()) || !changed.add(entry.getKey())) throw new IllegalArgumentException("field");
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
            for (var field : payload.getAsJsonArray("remove")) {
                if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString() || !field.getAsString().equals("intent")
                        || !changed.add(field.getAsString())) throw new IllegalArgumentException("remove");
                result.remove(field.getAsString());
            }
            for (var entry : payload.getAsJsonObject("arrays").entrySet()) {
                if (!ARRAYS.contains(entry.getKey())) throw new IllegalArgumentException("array field");
                JsonObject change = entry.getValue().getAsJsonObject();
                if (!change.keySet().equals(Set.of("length", "entries"))) throw new IllegalArgumentException("array keys");
                int length = Math.toIntExact(integer(change.get("length")));
                if (length < 0 || length > MAX_ARRAY || change.getAsJsonArray("entries").size() > MAX_ARRAY) throw new IllegalArgumentException("array limit");
                JsonArray before = result.has(entry.getKey()) ? result.getAsJsonArray(entry.getKey()) : new JsonArray();
                JsonArray after = new JsonArray();
                for (int i = 0; i < length; i++) after.add(i < before.size() ? before.get(i).deepCopy() : JsonNull.INSTANCE);
                var indices = new HashSet<Integer>();
                for (var value : change.getAsJsonArray("entries")) {
                    JsonObject item = value.getAsJsonObject();
                    if (!item.keySet().equals(Set.of("index", "value"))) throw new IllegalArgumentException("entry keys");
                    int index = Math.toIntExact(integer(item.get("index")));
                    if (index < 0 || index >= length || !indices.add(index) || item.get("value").isJsonNull()) throw new IllegalArgumentException("entry index");
                    after.set(index, item.get("value").deepCopy());
                }
                if (after.asList().stream().anyMatch(JsonElement::isJsonNull)) throw new IllegalArgumentException("missing entry");
                result.add(entry.getKey(), after);
            }
            if (integer(result.get("revision")) != Math.addExact(previous.get("revision").getAsLong(), 1)) throw new IllegalArgumentException("revision");
            return result;
        } catch (RuntimeException malformed) { throw new IOException("MATERIAL_JOURNAL_DELTA_INVALID", malformed); }
    }
    private static long integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("number");
        return value.getAsBigDecimal().longValueExact();
    }

    /** Offline/export reader; the visitor receives each full historical state in original event order. */
    public static void replay(JsonArray events, java.util.function.Consumer<JsonObject> visitor) throws IOException {
        Map<String, JsonObject> states = new HashMap<>();
        for (var value : events) {
            var event = value.getAsJsonObject(); var payload = event.getAsJsonObject("data");
            String id = payload.get("operationId").getAsString();
            var state = decode(states.get(id), event.get("kind").getAsString(), payload);
            states.put(id, state); visitor.accept(state.deepCopy());
        }
    }
}
