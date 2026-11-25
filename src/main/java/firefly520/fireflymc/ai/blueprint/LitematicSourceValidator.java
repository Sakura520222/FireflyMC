package firefly520.fireflymc.ai.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/** Bounded IO/parser only. Registry validation is deliberately deferred to the game thread. */
public final class LitematicSourceValidator {
    public static final int MAX_REGIONS = 64;
    public static final int MAX_AXIS = 512;
    public static final int MAX_PALETTE = 4096;
    public static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
    public static final long MAX_DECODED_BYTES = 64L * 1024 * 1024;
    private LitematicSourceValidator() {}

    public record Stamp(Path requestedPath, Path path, long size, FileTime modified, String fileKey, String digest) {}
    public record Source(Stamp stamp, int formatVersion, int dataVersion, Map<String, Region> regions, long cells) {}

    public static final class Region {
        private final BlueprintDocument.Position position;
        private final BlueprintDocument.Position signedSize;
        private final List<BlueprintDocument.State> palette;
        private final long[] packed;
        private final int bits;
        private final int cells;
        private Region(BlueprintDocument.Position position, BlueprintDocument.Position signedSize,
                       List<BlueprintDocument.State> palette, long[] packed, int bits, int cells) {
            this.position = position; this.signedSize = signedSize;
            this.palette = List.copyOf(palette); this.packed = packed.clone(); this.bits = bits; this.cells = cells;
        }
        public BlueprintDocument.Position position() { return position; }
        public BlueprintDocument.Position signedSize() { return signedSize; }
        public List<BlueprintDocument.State> palette() { return palette; }
        public int cells() { return cells; }
        public int paletteIndex(int index) {
            if (index < 0 || index >= cells) throw new IndexOutOfBoundsException();
            long start = (long) index * bits;
            int word = (int) (start >>> 6), shift = (int) (start & 63);
            long value = packed[word] >>> shift;
            if (shift + bits > 64) value |= packed[word + 1] << (64 - shift);
            return (int) (value & ((1L << bits) - 1));
        }
    }

    public static Source read(Path selectedFile, int expectedDataVersion) throws IOException {
        Captured captured = capture(selectedFile);
        return validate(decode(captured.bytes, MAX_DECODED_BYTES, 32), captured.stamp, expectedDataVersion);
    }

    static CompoundTag decode(byte[] compressed, long maximumDecoded, int maximumDepth) throws IOException {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             var bounded = new BudgetStream(gzip, maximumDecoded);
             var input = new DataInputStream(bounded)) {
            CompoundTag root = NbtIo.read(input, new NbtAccounter(maximumDecoded, maximumDepth));
            if (input.read() != -1) throw new IOException("LITEMATIC_TRAILING_DATA");
            return root;
        } catch (RuntimeException malformed) {
            throw new IOException("LITEMATIC_NBT_INVALID", malformed);
        }
    }

    public static void verifyUnchanged(Stamp stamp) throws IOException {
        Captured current = capture(stamp.requestedPath());
        if (!current.stamp.equals(stamp)) throw new IOException("LITEMATIC_SOURCE_CHANGED");
    }

    static Source validate(CompoundTag root, Stamp stamp, int expectedDataVersion) throws IOException {
        require(root != null, "LITEMATIC_NBT_INVALID");
        require(root.contains("Version", Tag.TAG_INT) && root.contains("MinecraftDataVersion", Tag.TAG_INT),
                "LITEMATIC_VERSION_MISSING");
        int version = root.getInt("Version"), dataVersion = root.getInt("MinecraftDataVersion");
        require(version == 6 || version == 7, "LITEMATIC_VERSION_UNSUPPORTED");
        require(!root.contains("SubVersion") || root.contains("SubVersion", Tag.TAG_INT)
                && root.getInt("SubVersion") >= 0 && root.getInt("SubVersion") <= 1, "LITEMATIC_SUBVERSION_UNSUPPORTED");
        require(dataVersion == expectedDataVersion, "LITEMATIC_DATA_VERSION_UNSUPPORTED");
        require(root.contains("Regions", Tag.TAG_COMPOUND), "LITEMATIC_REGIONS_INVALID");
        CompoundTag regionsTag = root.getCompound("Regions");
        require(!regionsTag.isEmpty() && regionsTag.getAllKeys().size() <= MAX_REGIONS, "LITEMATIC_REGION_LIMIT");
        Map<String, Region> regions = new TreeMap<>();
        long total = 0, paletteBytes = 0;
        for (String name : regionsTag.getAllKeys()) {
            require(!name.isBlank() && name.length() <= 128 && name.chars().noneMatch(Character::isISOControl),
                    "LITEMATIC_REGION_NAME_INVALID");
            require(regionsTag.contains(name, Tag.TAG_COMPOUND), "LITEMATIC_REGION_INVALID");
            CompoundTag region = regionsTag.getCompound(name);
            BlueprintDocument.Position position = position(region, "Position");
            BlueprintDocument.Position size = position(region, "Size");
            int sx = axis(size.x()), sy = axis(size.y()), sz = axis(size.z());
            long volume = (long) sx * sy * sz;
            total += volume;
            require(total <= BlueprintDocument.MAX_BLOCKS, "LITEMATIC_CELL_LIMIT");
            require(region.contains("BlockStatePalette", Tag.TAG_LIST)
                    && region.contains("BlockStates", Tag.TAG_LONG_ARRAY), "LITEMATIC_CONTAINER_INVALID");
            ListTag paletteTag = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            require(!paletteTag.isEmpty() && paletteTag.size() <= MAX_PALETTE, "LITEMATIC_PALETTE_LIMIT");
            List<BlueprintDocument.State> palette = new ArrayList<>();
            for (int i = 0; i < paletteTag.size(); i++) {
                CompoundTag state = paletteTag.getCompound(i);
                require(state.contains("Name", Tag.TAG_STRING), "LITEMATIC_BLOCK_ID_INVALID");
                Map<String, String> properties = new TreeMap<>();
                if (state.contains("Properties")) {
                    require(state.contains("Properties", Tag.TAG_COMPOUND), "LITEMATIC_PROPERTIES_INVALID");
                    CompoundTag propertyTag = state.getCompound("Properties");
                    require(propertyTag.getAllKeys().size() <= 32, "LITEMATIC_PROPERTY_LIMIT");
                    for (String key : propertyTag.getAllKeys()) {
                        require(propertyTag.contains(key, Tag.TAG_STRING), "LITEMATIC_PROPERTY_TYPE_INVALID");
                        properties.put(key, propertyTag.getString(key));
                    }
                }
                try {
                    var spec = new BlueprintDocument.State(state.getString("Name"), properties);
                    paletteBytes += spec.estimatedBytes();
                    require(paletteBytes <= BlueprintDocument.MAX_ENCODED_BYTES, "LITEMATIC_PALETTE_DATA_LIMIT");
                    palette.add(spec);
                } catch (IllegalArgumentException invalid) { throw new IOException("LITEMATIC_STATE_INVALID", invalid); }
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long[] packed = region.getLongArray("BlockStates");
            require(packed.length == (volume * bits + 63) / 64, "LITEMATIC_PACKED_LENGTH_INVALID");
            var result = new Region(position, size, palette, packed, bits, (int) volume);
            for (int i = 0; i < result.cells(); i++)
                require(result.paletteIndex(i) < palette.size(), "LITEMATIC_PALETTE_INDEX_INVALID");
            regions.put(name, result);
        }
        return new Source(stamp, version, dataVersion, Collections.unmodifiableMap(regions), total);
    }

    private record Captured(Stamp stamp, byte[] bytes) {}
    private static Captured capture(Path path) throws IOException {
        Path real = path.toRealPath();
        require(real.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic"),
                "LITEMATIC_FILE_TYPE_UNSUPPORTED");
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class);
        require(before.isRegularFile() && before.size() > 0 && before.size() <= MAX_COMPRESSED_BYTES,
                "LITEMATIC_FILE_LIMIT");
        byte[] bytes;
        try (InputStream input = new BudgetStream(Files.newInputStream(real), MAX_COMPRESSED_BYTES);
             var output = new ByteArrayOutputStream((int) Math.min(before.size(), 65536))) {
            input.transferTo(output);
            bytes = output.toByteArray();
        }
        BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class);
        require(before.size() == after.size() && bytes.length == before.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && java.util.Objects.equals(before.fileKey(), after.fileKey()), "LITEMATIC_SOURCE_CHANGED");
        return new Captured(new Stamp(path.toAbsolutePath().normalize(), real, after.size(), after.lastModifiedTime(),
                String.valueOf(after.fileKey()), digest(bytes)), bytes);
    }

    private static BlueprintDocument.Position position(CompoundTag parent, String name) throws IOException {
        require(parent.contains(name, Tag.TAG_COMPOUND), "LITEMATIC_POSITION_INVALID");
        CompoundTag tag = parent.getCompound(name);
        require(tag.contains("x", Tag.TAG_INT) && tag.contains("y", Tag.TAG_INT) && tag.contains("z", Tag.TAG_INT),
                "LITEMATIC_POSITION_INVALID");
        return new BlueprintDocument.Position(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }
    private static int axis(int value) throws IOException {
        require(value != 0 && Math.abs((long) value) <= MAX_AXIS, "LITEMATIC_AXIS_LIMIT");
        return Math.abs(value);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
    private static final class BudgetStream extends FilterInputStream {
        private final long maximum;
        private long count;
        private BudgetStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        private void consume(int amount) throws IOException {
            if (amount > 0 && (count += amount) > maximum) throw new IOException("LITEMATIC_BYTE_LIMIT");
        }
        @Override public int read() throws IOException {
            int result = in.read(); if (result >= 0) consume(1); return result;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int result = in.read(bytes, offset, (int) Math.min(length, Math.max(1, maximum - count + 1)));
            consume(result); return result;
        }
        @Override public long skip(long amount) throws IOException {
            long skipped = 0;
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, amount))];
            while (skipped < amount) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, amount - skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
    }
}
