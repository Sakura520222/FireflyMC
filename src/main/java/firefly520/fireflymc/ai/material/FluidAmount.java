package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.storage.AiJson;

/** Exact persistent fluid/component identity, measured in native millibuckets, never item counts. */
public record FluidAmount(String unitJson, int millibuckets) {
    public static final FluidAmount EMPTY = new FluidAmount("", 0);
    public FluidAmount {
        if (unitJson == null || unitJson.length() > 262144 || millibuckets < 0
                || (millibuckets == 0) != unitJson.isEmpty()) throw new IllegalArgumentException("FLUID_AMOUNT_INVALID");
        if (millibuckets > 0) {
            var unit = AiJson.object(unitJson, 262144, 32);
            if (!unit.has("id") || !unit.get("id").isJsonPrimitive() || !unit.get("id").getAsJsonPrimitive().isString()
                    || !unit.get("id").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !unit.has("amount") || !unit.get("amount").isJsonPrimitive()
                    || !unit.get("amount").getAsJsonPrimitive().isNumber()
                    || unit.get("amount").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IllegalArgumentException("FLUID_UNIT_REQUIRED");
        }
    }
    public boolean empty() { return millibuckets == 0; }
    public String fingerprint() { return empty() ? "" : AiJson.digest(unitJson); }
    public FluidAmount withAmount(int value) { return value == 0 ? EMPTY : new FluidAmount(unitJson, value); }
}
