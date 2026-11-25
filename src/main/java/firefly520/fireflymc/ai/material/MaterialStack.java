package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.storage.AiJson;

/** Immutable, persistent item/component identity. Count is separate from the canonical unit codec. */
public record MaterialStack(String itemJson, int count) {
    public static final MaterialStack EMPTY = new MaterialStack("", 0);
    public MaterialStack {
        if (itemJson == null || itemJson.length() > 1_048_576 || count < 0
                || (count == 0) != itemJson.isEmpty()) throw new IllegalArgumentException("MATERIAL_STACK_INVALID");
        if (count > 0) {
            var unit = AiJson.object(itemJson, 1_048_576, 32);
            if (!unit.has("id") || !unit.get("id").isJsonPrimitive()
                    || unit.has("count") && unit.get("count").getAsInt() != 1)
                throw new IllegalArgumentException("MATERIAL_UNIT_REQUIRED");
        }
    }
    public boolean empty() { return count == 0; }
    public String fingerprint() { return empty() ? "" : AiJson.digest(itemJson); }
    public MaterialStack withCount(int value) { return value == 0 ? EMPTY : new MaterialStack(itemJson, value); }
}
