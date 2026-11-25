package firefly520.fireflymc.ai.blueprint.ownership;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Stable structural NBT digest, independent of compound insertion order and compressed bytes. */
public final class CanonicalChunkPayload {
    private CanonicalChunkPayload() {}
    public static String digest(CompoundTag payload) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) { write(payload, output, true, 0); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException | IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void write(Tag tag, DataOutputStream out, boolean root, int depth) throws IOException {
        if (depth > 512) throw new IllegalArgumentException("OWNERSHIP_NBT_DEPTH");
        out.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> {}
            case Tag.TAG_BYTE -> out.writeByte(((NumericTag) tag).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((NumericTag) tag).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((NumericTag) tag).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((NumericTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> out.writeInt(Float.floatToRawIntBits(((NumericTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> out.writeLong(Double.doubleToRawLongBits(((NumericTag) tag).getAsDouble()));
            case Tag.TAG_STRING -> string(tag.getAsString(), out);
            case Tag.TAG_BYTE_ARRAY -> { byte[] values = ((ByteArrayTag) tag).getAsByteArray(); out.writeInt(values.length); out.write(values); }
            case Tag.TAG_INT_ARRAY -> { int[] values = ((IntArrayTag) tag).getAsIntArray(); out.writeInt(values.length); for (int value : values) out.writeInt(value); }
            case Tag.TAG_LONG_ARRAY -> { long[] values = ((LongArrayTag) tag).getAsLongArray(); out.writeInt(values.length); for (long value : values) out.writeLong(value); }
            case Tag.TAG_LIST -> { var values = (ListTag) tag; out.writeByte(values.getElementType()); out.writeInt(values.size()); for (Tag value : values) write(value, out, false, depth + 1); }
            case Tag.TAG_COMPOUND -> {
                var compound = (CompoundTag) tag; var names = new TreeSet<>(compound.getAllKeys());
                if (root) names.remove(OwnershipNbt.FIELD); out.writeInt(names.size());
                for (String name : names) { string(name, out); write(Objects.requireNonNull(compound.get(name)), out, false, depth + 1); }
            }
            default -> throw new IllegalArgumentException("OWNERSHIP_NBT_TYPE");
        }
    }
    private static void string(String value, DataOutputStream out) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
}
