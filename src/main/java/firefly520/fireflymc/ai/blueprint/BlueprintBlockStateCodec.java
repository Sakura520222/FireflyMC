package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.TreeMap;

/** Strict shared registry conversion; unknown blocks and properties are never replaced with defaults. */
public final class BlueprintBlockStateCodec {
    private BlueprintBlockStateCodec() {}

    public static BlockState resolve(BlueprintDocument.State spec) {
        ResourceLocation id = ResourceLocation.tryParse(spec.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException("BLUEPRINT_UNKNOWN_BLOCK");
        var block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = block.defaultBlockState();
        for (var entry : spec.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("BLUEPRINT_UNKNOWN_PROPERTY");
            state = property(state, property, entry.getValue());
        }
        return state;
    }

    public static BlueprintDocument.State describe(BlockState state) {
        Map<String, String> properties = new TreeMap<>();
        for (var entry : state.getValues().entrySet())
            properties.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
        return new BlueprintDocument.State(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, String name) {
        T value = property.getValue(name).orElseThrow(() -> new IllegalArgumentException("BLUEPRINT_PROPERTY_VALUE_INVALID"));
        return state.setValue(property, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyName(Property property, Comparable value) { return property.getName(value); }
}
