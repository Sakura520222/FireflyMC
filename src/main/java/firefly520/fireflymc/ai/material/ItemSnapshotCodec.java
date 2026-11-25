package firefly520.fireflymc.ai.material;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.storage.AiJson;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.TreeMap;

/** Main-thread codec boundary; persistent component round-trips also break mutable component aliases. */
public final class ItemSnapshotCodec {
    private ItemSnapshotCodec() {}

    public static MaterialStack capture(ItemStack source, HolderLookup.Provider registries) {
        if (source == null) throw new IllegalArgumentException("MATERIAL_STACK_NULL");
        if (source.isEmpty()) return MaterialStack.EMPTY;
        int count = source.getCount();
        // Some handlers expose counts larger than a normal ItemStack codec permits.
        // Isolate exactly one unit; aggregate count remains authoritative in the handler/ledger.
        ItemStack unit = source.copyWithCount(1);
        unit = RecipeInputIsolation.copy(unit, registries);
        JsonElement encoded = ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), unit)
                .result().orElseThrow(() -> new IllegalArgumentException("MATERIAL_CODEC_FAILED"));
        String canonical = AiJson.GSON.toJson(sorted(encoded));
        return new MaterialStack(canonical, count);
    }

    public static ItemStack restore(MaterialStack snapshot, HolderLookup.Provider registries) {
        if (snapshot.empty()) return ItemStack.EMPTY;
        ItemStack unit = ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                AiJson.object(snapshot.itemJson(), 1_048_576, 32)).result()
                .orElseThrow(() -> new IllegalArgumentException("MATERIAL_CODEC_UNAVAILABLE"));
        unit = RecipeInputIsolation.copy(unit, registries);
        if (!capture(unit, registries).itemJson().equals(snapshot.itemJson()))
            throw new IllegalArgumentException("MATERIAL_REGISTRY_OR_COMPONENT_CHANGED");
        unit.setCount(snapshot.count());
        return unit;
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            var map = new TreeMap<String, JsonElement>();
            value.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), sorted(entry.getValue())));
            JsonObject result = new JsonObject();
            map.forEach(result::add);
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(entry -> array.add(sorted(entry)));
            return array;
        }
        return value.deepCopy();
    }
}
