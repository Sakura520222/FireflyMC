package firefly520.fireflymc.ai.processing.advanced;

import com.mojang.serialization.Codec;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import java.io.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** Exact persistent atlas components; native mutable prototype defaults never escape into recipes. */
public final class BuildingDynamicComponents {
    private static final Map<String, String> TYPES = Map.of(
            "map_atlases:map_collection", "pepjebs.mapatlases.map_collection.MapCollection",
            "map_atlases:empty_maps", "pepjebs.mapatlases.map_collection.EmptyMaps",
            "map_atlases:selected_slices", "pepjebs.mapatlases.map_collection.SelectedSlices");
    private BuildingDynamicComponents() { }

    public static ItemStack detachDefaults(ItemStack source, ItemStack decoded, HolderLookup.Provider registries) {
        return detachDefaults(source, decoded, registries, ignored -> { });
    }
    public static ItemStack detachDefaults(ItemStack source, ItemStack decoded, HolderLookup.Provider registries,
                                          java.util.function.IntConsumer encodedBudget) {
        if (source.isEmpty() || decoded.isEmpty()) return decoded;
        DataComponentMap.Builder prototype = null;
        for (var component : decoded.getComponents()) {
            DataComponentType<?> type = component.type(); Object original = source.get(type), value = component.value();
            if (original != value || !known(type, value)) continue;
            Object copy = copy(type, value, registries, encodedBudget);
            if (copy == value) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_SHARED_DEFAULT");
            if (prototype == null) {
                prototype = DataComponentMap.builder();
                for (var entry : decoded.getPrototype()) set(prototype, entry.type(), entry.value());
            }
            set(prototype, type, copy);
        }
        if (prototype == null) return decoded;
        var components = new PatchedDataComponentMap(prototype.build()); components.restorePatch(decoded.getComponentsPatch());
        try {
            var constructor = ItemStack.class.getDeclaredConstructor(ItemLike.class, int.class, PatchedDataComponentMap.class);
            if (!constructor.trySetAccessible()) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_API_UNAVAILABLE");
            return constructor.newInstance(decoded.getItem(), decoded.getCount(), components);
        } catch (ReflectiveOperationException failed) { throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_API_UNAVAILABLE"); }
    }
    public static boolean equivalentDetached(DataComponentType<?> type, Object left, Object right, HolderLookup.Provider registries) {
        if (left == right || !known(type, left) || !known(type, right)) return false;
        try { return Arrays.equals(encoded(type, left, registries), encoded(type, right, registries)); }
        catch (RuntimeException invalid) { return false; }
    }
    private static boolean known(DataComponentType<?> type, Object value) {
        ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id != null && value != null && TYPES.getOrDefault(id.toString(), "").equals(value.getClass().getName())
                && AdvancedReflection.version("map_atlases", "1.21-6.6.0");
    }
    @SuppressWarnings("unchecked")
    private static void set(DataComponentMap.Builder builder, DataComponentType<?> type, Object value) { builder.set((DataComponentType<Object>) type, value); }
    @SuppressWarnings("unchecked")
    private static Object copy(DataComponentType<?> type, Object value, HolderLookup.Provider registries,
                               java.util.function.IntConsumer encodedBudget) {
        byte[] bytes = encoded(type, value, registries);
        encodedBudget.accept(bytes.length);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Tag data = NbtIo.readAnyTag(input, new NbtAccounter(16_777_216L, RecipeInputIsolation.MAX_NBT_DEPTH));
            if (input.available() != 0) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID");
            Codec<Object> codec = (Codec<Object>) type.codec();
            return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), data).result()
                    .orElseThrow(() -> AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID"));
        } catch (IOException invalid) { throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID"); }
    }
    @SuppressWarnings("unchecked")
    private static byte[] encoded(DataComponentType<?> type, Object value, HolderLookup.Provider registries) {
        Codec<Object> codec = (Codec<Object>) type.codec();
        if (codec == null) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID");
        Tag tag = codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).result()
                .orElseThrow(() -> AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID"));
        validate(tag);
        var output = new LimitedBytes();
        try (var writer = new DataOutputStream(output)) { NbtIo.writeAnyTag(tag, writer); }
        catch (IOException invalid) { throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_CODEC_INVALID"); }
        return output.toByteArray();
    }
    private record Node(Tag tag, int depth) { }
    private static void validate(Tag root) {
        var queue = new ArrayDeque<Node>(); queue.add(new Node(root, 0)); int nodes = 0;
        while (!queue.isEmpty()) {
            Node current = queue.removeLast();
            if (++nodes > RecipeInputIsolation.MAX_NBT_NODES || current.depth() > RecipeInputIsolation.MAX_NBT_DEPTH)
                throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_TOO_LARGE");
            if (current.tag() instanceof CompoundTag compound) {
                if (compound.size() > RecipeInputIsolation.MAX_NBT_NODES) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_TOO_LARGE");
                for (String key : compound.getAllKeys()) queue.add(new Node(compound.get(key), current.depth() + 1));
            } else if (current.tag() instanceof ListTag list) {
                if (list.size() > RecipeInputIsolation.MAX_NBT_NODES) throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_TOO_LARGE");
                for (Tag child : list) queue.add(new Node(child, current.depth() + 1));
            }
        }
    }
    private static final class LimitedBytes extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) { require(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) { require(length); super.write(bytes, offset, length); }
        private void require(int length) {
            if (length < 0 || count > RecipeInputIsolation.MAX_ENCODED_BYTES - length)
                throw AdvancedReflection.failure("BUILDING_DYNAMIC_COMPONENT_TOO_LARGE");
        }
    }
}
