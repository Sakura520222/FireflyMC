package firefly520.fireflymc.ai.processing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Detaches trusted recipe inputs from inventory objects before calling third-party recipe code.
 * ItemStack.copy() alone can retain mutable objects inside a mod's data components.
 *
 * <p>Uses the caller's current registries and persistent component codecs. The bounded binary NBT
 * round trip also detaches tags returned directly by a component encoder or its encoding cache.
 * Call on the server thread while the source snapshot and recipe generation remain valid.</p>
 */
public final class RecipeInputIsolation {
    public static final int MAX_INPUTS = 81;
    public static final int MAX_ENCODED_BYTES = 1_048_576;
    public static final int MAX_BATCH_ENCODED_BYTES = 8_388_608;
    public static final int MAX_NBT_DEPTH = 32;
    public static final int MAX_NBT_NODES = 32_768;
    private static final long MAX_DECODED_NBT_BYTES = 16_777_216L;

    private RecipeInputIsolation() {}

    /** Returns an independently decoded stack, or the canonical empty stack for an empty slot. */
    public static ItemStack copy(ItemStack input, HolderLookup.Provider registries) {
        return isolate(input, registries, new Budget(MAX_ENCODED_BYTES));
    }

    /** Every occurrence is isolated separately, including repeated references to the same stack. */
    public static List<ItemStack> copyAll(List<ItemStack> inputs, HolderLookup.Provider registries) {
        if (inputs == null || registries == null) throw failure("RECIPE_INPUT_INVALID");
        if (inputs.size() > MAX_INPUTS) throw failure("RECIPE_INPUT_TOO_MANY");
        Budget budget = new Budget(MAX_BATCH_ENCODED_BYTES);
        List<ItemStack> result = new ArrayList<>(inputs.size());
        for (ItemStack input : inputs) result.add(isolate(input, registries, budget));
        return List.copyOf(result);
    }

    private static ItemStack isolate(ItemStack input, HolderLookup.Provider registries, Budget budget) {
        if (input == null || registries == null) throw failure("RECIPE_INPUT_INVALID");
        if (input.isEmpty()) return ItemStack.EMPTY;
        try {
            int budgetAtEntry = budget.remaining;
            // ItemStack.CODEC omits transient components. Losing one is not a valid isolation.
            if (input.getComponentsPatch().entrySet().stream()
                    .anyMatch(entry -> entry.getKey().isTransient())) {
                throw failure("RECIPE_INPUT_NON_PERSISTENT_COMPONENT");
            }
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            Tag encoded = ItemStack.CODEC.encodeStart(ops, input).result()
                    .orElseThrow(() -> failure("RECIPE_INPUT_CODEC_FAILED"));
            Tag detachedTag = detachedTag(encoded, budget, MAX_ENCODED_BYTES);
            ItemStack detached = ItemStack.CODEC.parse(ops, detachedTag).result()
                    .orElseThrow(() -> failure("RECIPE_INPUT_CODEC_FAILED"));
            detached = detachDefaultNames(input, detached, detachedTag, registries, budget, budgetAtEntry);
            detached = firefly520.fireflymc.ai.processing.advanced.BuildingDynamicComponents.detachDefaults(input, detached, registries, bytes -> {
                if (bytes < 0 || bytes > budget.remaining || bytes > MAX_ENCODED_BYTES - (budgetAtEntry - budget.remaining))
                    throw failure("RECIPE_INPUT_TOO_LARGE");
                budget.remaining -= bytes;
            });
            // Also detects nested transient components and codecs which silently discard state.
            if (!equivalent(input, detached, registries)) {
                throw failure("RECIPE_INPUT_ROUND_TRIP_MISMATCH");
            }
            rejectSharedMutableDefaults(input, detached);
            return detached;
        } catch (IsolationException failure) {
            throw failure;
        } catch (StackOverflowError tooDeep) {
            // A component codec can recurse before it has produced an NBT tree to inspect.
            throw failure("RECIPE_INPUT_TOO_DEEP");
        } catch (IOException | RuntimeException codecFailure) {
            // Codec errors can include private component contents; never retain their text/cause.
            throw failure("RECIPE_INPUT_CODEC_FAILED");
        }
    }

    private static Tag detachedTag(Tag encoded, Budget budget, int maximumBytes) throws IOException {
        validateTree(encoded);
        if (maximumBytes < 1) throw failure("RECIPE_INPUT_TOO_LARGE");
        LimitedOutput output = new LimitedOutput(Math.min(maximumBytes, budget.remaining));
        try (DataOutputStream data = new DataOutputStream(output)) { NbtIo.writeAnyTag(encoded, data); }
        byte[] bytes = output.bytes(); budget.remaining -= bytes.length;
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Tag value = NbtIo.readAnyTag(data, new NbtAccounter(MAX_DECODED_NBT_BYTES, MAX_NBT_DEPTH));
            if (data.available() != 0) throw failure("RECIPE_INPUT_CODEC_FAILED");
            return value;
        }
    }

    /**
     * A mod may register a mutable vanilla name in Item.components(). ItemStack's patch codec then
     * restores that same default object. Normal set/applyPatch would discard an equal deep copy.
     * Clone only the virtual stack's prototype names and preserve its original decoded patch;
     * forcing a default into the patch would change KeepNBT and other patch-sensitive recipes.
     * All other components still pass the normal equivalence and shared-mutable-value checks.
     */
    private static ItemStack detachDefaultNames(ItemStack source, ItemStack detached, Tag decodedStack, HolderLookup.Provider registries,
                                               Budget budget, int budgetAtEntry) throws IOException {
        boolean changed = false;
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        var prototype = DataComponentMap.builder();
        for (var component : detached.getPrototype()) putComponent(prototype, component.type(), component.value());
        var names = new CompoundTag();
        for (DataComponentType<Component> type : List.of(DataComponents.CUSTOM_NAME, DataComponents.ITEM_NAME)) {
            Component name = detached.getPrototype().get(type);
            if (name == null) continue;
            Tag encoded = ComponentSerialization.CODEC.encodeStart(ops, name).result().orElseThrow(() -> failure("RECIPE_INPUT_CODEC_FAILED"));
            Tag clone = detachedTag(encoded, budget, MAX_ENCODED_BYTES - (budgetAtEntry - budget.remaining));
            // The native codec may construct MutableComponent with List.of() for absent siblings.
            // copy() restores its public append contract; all referenced contents were already decoded independently.
            Component copy = ComponentSerialization.CODEC.parse(ops, clone).result().orElseThrow(() -> failure("RECIPE_INPUT_CODEC_FAILED")).copy();
            if (copy == name) throw failure("RECIPE_INPUT_SHARED_COMPONENT");
            prototype.set(type, copy);
            names.put(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString(), clone);
            changed = true;
        }
        if (!changed) return detached;
        validateTree(names);
        if (!(decodedStack instanceof CompoundTag stackTag)) throw failure("RECIPE_INPUT_CODEC_FAILED");
        DataComponentPatch patch = DataComponentPatch.CODEC.parse(ops, stackTag.getCompound("components")).result()
                .orElseThrow(() -> failure("RECIPE_INPUT_CODEC_FAILED"));
        var map = new PatchedDataComponentMap(prototype.build());
        map.restorePatch(patch);
        try {
            // Locked MC 1.21.1 constructor retains the isolated prototype and still executes the
            // item's native verifyComponentsAfterLoad hook. No global/default map is modified.
            var constructor = ItemStack.class.getDeclaredConstructor(net.minecraft.world.level.ItemLike.class, int.class, PatchedDataComponentMap.class);
            if (!constructor.trySetAccessible()) throw failure("RECIPE_INPUT_CODEC_FAILED");
            return constructor.newInstance(source.getItem(), source.getCount(), map);
        } catch (ReflectiveOperationException failure) { throw failure("RECIPE_INPUT_CODEC_FAILED"); }
    }
    @SuppressWarnings("unchecked")
    private static void putComponent(DataComponentMap.Builder builder, DataComponentType<?> type, Object value) {
        builder.set((DataComponentType<Object>) type, value);
    }

    private static boolean equivalent(ItemStack source, ItemStack copy, HolderLookup.Provider registries) {
        if (source.getCount() != copy.getCount() || !ItemStack.isSameItem(source, copy)
                || !source.getComponents().keySet().equals(copy.getComponents().keySet())) return false;
        for (var component : source.getComponents()) {
            Object left = component.value(), right = copy.get(component.type());
            // Psi 108 compares SpellGrid by object identity. Only its exact registered spell codec
            // may prove equal serialized semantics plus a detached mutable spell graph instead.
            if (firefly520.fireflymc.ai.processing.advanced.PsiSpellComponents.isSpellComponent(component.type())) {
                if (!firefly520.fireflymc.ai.processing.advanced.PsiSpellComponents.equivalentDetached(
                        component.type(), left, right, registries)) return false;
                continue;
            }
            if (java.util.Objects.equals(left, right)) continue;
            if (firefly520.fireflymc.ai.processing.advanced.BuildingDynamicComponents.equivalentDetached(
                    component.type(), left, right, registries)) continue;
            if (firefly520.fireflymc.ai.processing.advanced.PsiComponentReferences.equivalentBulletContents(
                    component.type(), left, right, registries)) continue;
            // Vanilla normalizes a literal Component argument of a translatable name into a String
            // during decoding. Only these known name types may use their identical canonical text
            // codec as equivalence; arbitrary mod components still require their full value equality.
            if ((component.type() == net.minecraft.core.component.DataComponents.CUSTOM_NAME
                    || component.type() == net.minecraft.core.component.DataComponents.ITEM_NAME)
                    && left instanceof net.minecraft.network.chat.MutableComponent a
                    && right instanceof net.minecraft.network.chat.MutableComponent b
                    && a.getString().equals(b.getString()) && a.getStyle().equals(b.getStyle())) {
                var ops = registries.createSerializationContext(NbtOps.INSTANCE);
                var encodedA = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(ops, a).result();
                var encodedB = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(ops, b).result();
                if (encodedA.isPresent() && encodedA.equals(encodedB)) continue;
            }
            return false;
        }
        return true;
    }

    private static void rejectSharedMutableDefaults(ItemStack input, ItemStack detached) {
        // The stack codec stores a patch against item defaults. A mod's mutable default can
        // therefore still be shared after decoding; refuse that input instead of promising isolation.
        ArrayDeque<StackPair> pending = new ArrayDeque<>();
        pending.addLast(new StackPair(input, detached));
        int inspected = 0;
        while (!pending.isEmpty()) {
            if (++inspected > MAX_NBT_NODES) throw failure("RECIPE_INPUT_TOO_LARGE");
            StackPair pair = pending.removeLast();
            for (var component : pair.source.getComponents()) {
                Object value = component.value();
                Object copied = pair.copy.get(component.type());
                if (value == copied) {
                    ResourceLocation typeId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
                    if (value instanceof Component || value instanceof CustomData
                            || value instanceof ItemContainerContents contents && contents.getSlots() > 0) {
                        throw new IsolationException("RECIPE_INPUT_SHARED_COMPONENT", typeId);
                    }
                    if (typeId == null) throw failure("RECIPE_INPUT_CODEC_FAILED");
                    if (!typeId.getNamespace().equals("minecraft") && !isImmutableValue(value)
                            && !firefly520.fireflymc.ai.processing.advanced.ApotheosisComponentReferences.trustedSharedReference(
                                    component.type(), typeId, value)
                            && !firefly520.fireflymc.ai.processing.advanced.PsiComponentReferences.trustedSharedReference(
                                    component.type(), typeId, value)) {
                        throw new IsolationException("RECIPE_INPUT_SHARED_COMPONENT", typeId);
                    }
                }
                if (value instanceof ItemContainerContents sourceContents
                        && copied instanceof ItemContainerContents copiedContents) {
                    requireNodeSpace(inspected, pending.size(), sourceContents.getSlots());
                    for (int slot = 0; slot < sourceContents.getSlots(); slot++) {
                        pending.addLast(new StackPair(sourceContents.getStackInSlot(slot),
                                copiedContents.getStackInSlot(slot)));
                    }
                }
            }
        }
    }

    private static boolean isImmutableValue(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof Enum<?>
                || value instanceof ResourceLocation || value instanceof Holder<?>;
    }

    private static void validateTree(Tag root) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.addLast(new Node(root, 0));
        int nodes = 0;
        long minimumBytes = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (node.depth > MAX_NBT_DEPTH) throw failure("RECIPE_INPUT_TOO_DEEP");
            if (++nodes > MAX_NBT_NODES) throw failure("RECIPE_INPUT_TOO_LARGE");
            Tag tag = node.tag;
            if (tag == null) throw failure("RECIPE_INPUT_CODEC_FAILED");
            minimumBytes++;
            if (tag instanceof CompoundTag compound) {
                requireNodeSpace(nodes, pending.size(), compound.size());
                for (String key : compound.getAllKeys()) {
                    minimumBytes += key.length();
                    pending.addLast(new Node(compound.get(key), node.depth + 1));
                }
            } else if (tag instanceof ListTag list) {
                requireNodeSpace(nodes, pending.size(), list.size());
                for (Tag child : list) pending.addLast(new Node(child, node.depth + 1));
            } else if (tag instanceof ByteArrayTag array) {
                minimumBytes += array.size();
            } else if (tag instanceof IntArrayTag array) {
                minimumBytes += 4L * array.size();
            } else if (tag instanceof LongArrayTag array) {
                minimumBytes += 8L * array.size();
            } else if (tag instanceof StringTag string) {
                minimumBytes += string.getAsString().length();
            }
            if (minimumBytes > MAX_ENCODED_BYTES) throw failure("RECIPE_INPUT_TOO_LARGE");
        }
    }

    private static void requireNodeSpace(int visited, int pending, int added) {
        if ((long) visited + pending + added > MAX_NBT_NODES) {
            throw failure("RECIPE_INPUT_TOO_LARGE");
        }
    }

    private static IsolationException failure(String code) {
        return new IsolationException(code);
    }

    /** The only exposed error detail is a stable code; no original component or codec error text. */
    public static final class IsolationException extends IllegalArgumentException {
        private final ResourceLocation componentType;
        private IsolationException(String code) {
            this(code, null);
        }
        private IsolationException(String code, ResourceLocation componentType) {
            super(code);
            this.componentType = componentType;
        }

        /** Safe diagnostic registry ID only; never contains any component contents or codec exception text. */
        public ResourceLocation componentType() { return componentType; }

        public String code() {
            return getMessage();
        }
    }

    private record Node(Tag tag, int depth) {}
    private record StackPair(ItemStack source, ItemStack copy) {}

    private static final class Budget {
        int remaining;

        Budget(int remaining) {
            this.remaining = remaining;
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;

        LimitedOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) {
            requireCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            requireCapacity(length);
            bytes.write(value, offset, length);
        }

        private void requireCapacity(int additional) {
            if (additional < 0 || additional > limit - bytes.size()) {
                throw failure("RECIPE_INPUT_TOO_LARGE");
            }
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
