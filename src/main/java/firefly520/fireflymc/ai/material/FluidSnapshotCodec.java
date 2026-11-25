package firefly520.fireflymc.ai.material;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.fluids.FluidStack;

/** Main-thread registry boundary. Unknown/transient component loss is a refusal, not plain water. */
public final class FluidSnapshotCodec {
    private FluidSnapshotCodec() {}
    public static FluidAmount capture(FluidStack source, HolderLookup.Provider registries) {
        if (source == null) throw new IllegalArgumentException("FLUID_STACK_NULL");
        if (source.isEmpty()) return FluidAmount.EMPTY;
        FluidStack unit = firefly520.fireflymc.ai.processing.special.FluidMaterial.isolate(source.copyWithAmount(1), registries);
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var encoded = FluidStack.CODEC.encodeStart(ops, unit).result()
                .orElseThrow(() -> new IllegalArgumentException("FLUID_CODEC_FAILED"));
        FluidStack restored = FluidStack.CODEC.parse(ops, encoded).result()
                .orElseThrow(() -> new IllegalArgumentException("FLUID_CODEC_UNAVAILABLE"));
        if (restored.getAmount() != 1 || !FluidStack.isSameFluidSameComponents(unit, restored))
            throw new IllegalArgumentException("FLUID_COMPONENT_ROUNDTRIP_FAILED");
        return new FluidAmount(AiJson.GSON.toJson(sorted(encoded)), source.getAmount());
    }
    public static FluidStack restore(FluidAmount fluid, HolderLookup.Provider registries) {
        if (fluid.empty()) return FluidStack.EMPTY;
        var unit = FluidStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                AiJson.object(fluid.unitJson(), 262144, 32)).result()
                .orElseThrow(() -> new IllegalArgumentException("FLUID_CODEC_UNAVAILABLE"));
        if (!capture(unit, registries).unitJson().equals(fluid.unitJson()))
            throw new IllegalArgumentException("FLUID_REGISTRY_OR_COMPONENT_CHANGED");
        return firefly520.fireflymc.ai.processing.special.FluidMaterial.isolate(unit, registries).copyWithAmount(fluid.millibuckets());
    }
    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            var fields = new TreeMap<String, JsonElement>();
            value.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            JsonObject result = new JsonObject(); fields.forEach(result::add); return result;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray(); value.getAsJsonArray().forEach(entry -> array.add(sorted(entry))); return array;
        }
        return value.deepCopy();
    }
}
