package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.storage.AiJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;

/** Bounded immutable snapshots of selected storage entries only, never a whole server SavedData. */
final class StorageDynamicNbt {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private StorageDynamicNbt() {}
    static String pack(CompoundTag tag) {
        canonical(tag);
        var output = new LimitedOutput();
        try (var data = new DataOutputStream(output)) { NbtIo.writeAnyTag(tag, data); }
        catch (IOException failure) { throw new IllegalStateException("STORAGE_DYNAMIC_NBT_ENCODING_FAILED", failure); }
        return Base64.getEncoder().encodeToString(output.bytes());
    }
    static CompoundTag unpack(String encoded) {
        if (encoded == null || encoded.length() > MAX_BYTES * 4L / 3 + 8) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_LIMIT");
        try (var data = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            var tag = NbtIo.readAnyTag(data, new NbtAccounter(16 * 1024 * 1024L, 32));
            if (!(tag instanceof CompoundTag result) || data.available() != 0) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_INVALID");
            canonical(result); return result;
        } catch (IOException failure) { throw new IllegalStateException("STORAGE_DYNAMIC_NBT_ENCODING_FAILED", failure); }
    }
    static String digest(Map<String, String> entries) {
        var hashes = new TreeMap<String, String>();
        entries.forEach((id, data) -> hashes.put(id, AiJson.digest(canonical(unpack(data)))));
        return AiJson.digest(AiJson.canonical(hashes));
    }
    private static String canonical(Tag tag) {
        var output = new LimitedOutput(); var active = Collections.newSetFromMap(new IdentityHashMap<Tag, Boolean>());
        try (var data = new DataOutputStream(output)) { write(tag, data, active, new int[1], 0); }
        catch (IOException failure) { throw new IllegalStateException("STORAGE_DYNAMIC_NBT_ENCODING_FAILED", failure); }
        return Base64.getEncoder().encodeToString(output.bytes());
    }
    private static void write(Tag tag, DataOutputStream data, Set<Tag> active, int[] nodes, int depth) throws IOException {
        if (tag == null || depth > 32 || ++nodes[0] > 32768) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_LIMIT");
        data.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            if (!active.add(tag)) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_CYCLE");
            try {
                var keys = new TreeSet<>(compound.getAllKeys()); data.writeInt(keys.size());
                for (String key : keys) { text(data, key); write(compound.get(key), data, active, nodes, depth + 1); }
            } finally { active.remove(tag); }
        } else if (tag instanceof ListTag list) {
            if (!active.add(tag)) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_CYCLE");
            try { data.writeInt(list.size()); for (Tag value : list) write(value, data, active, nodes, depth + 1); }
            finally { active.remove(tag); }
        } else if (tag instanceof ByteArrayTag array) {
            byte[] value = array.getAsByteArray(); data.writeInt(value.length); data.write(value);
        } else if (tag instanceof IntArrayTag array) {
            int[] value = array.getAsIntArray(); data.writeInt(value.length); for (int item : value) data.writeInt(item);
        } else if (tag instanceof LongArrayTag array) {
            long[] value = array.getAsLongArray(); data.writeInt(value.length); for (long item : value) data.writeLong(item);
        } else if (tag instanceof StringTag || tag instanceof NumericTag) text(data, tag.getAsString());
        else if (tag.getId() != Tag.TAG_END) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_TYPE_UNSUPPORTED");
    }
    private static void text(DataOutputStream data, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes);
    }
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public void write(int value) { if (bytes.size() >= MAX_BYTES) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_LIMIT"); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) {
            if (length < 0 || bytes.size() + (long) length > MAX_BYTES) throw new IllegalArgumentException("STORAGE_DYNAMIC_NBT_LIMIT");
            bytes.write(value, offset, length);
        }
        byte[] bytes() { return bytes.toByteArray(); }
    }
}
