package firefly520.fireflymc.ai.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LitematicSourceValidatorTest {
    @TempDir Path directory;
    private static final int DATA_VERSION = 3955;

    @Test void contiguousPackedEntriesCrossTheLongBoundaryAndRetainSignedSize() throws Exception {
        // Five palette values require 3 bits. Index 20 is 3 (bits 60..62);
        // index 21 is 4 (bits 63..65), whose high two bits are in the second long.
        CompoundTag root = root(-22, 1, 1, 5, new long[] {3L << 60, 2L});
        Path file = write(root, "crossing.litematic");
        var source = LitematicSourceValidator.read(file, DATA_VERSION);
        var region = source.regions().get("main");
        assertEquals(new BlueprintDocument.Position(-22, 1, 1), region.signedSize());
        assertEquals(0, region.paletteIndex(19));
        assertEquals(3, region.paletteIndex(20));
        assertEquals(4, region.paletteIndex(21));
        assertEquals(22, source.cells());
        LitematicSourceValidator.verifyUnchanged(source.stamp());
    }

    @Test void outOfRangePackedIdsAreRejectedInsteadOfBecomingAir() {
        IOException failure = assertThrows(IOException.class,
                () -> LitematicSourceValidator.validate(root(1, 1, 1, 2, new long[] {3}), null, DATA_VERSION));
        assertEquals("LITEMATIC_PALETTE_INDEX_INVALID", failure.getMessage());
    }

    @Test void truncatedAndExtraPackedStorageAreBothRejected() {
        for (long[] packed : List.of(new long[0], new long[] {0, 0})) {
            IOException failure = assertThrows(IOException.class,
                    () -> LitematicSourceValidator.validate(root(1, 1, 1, 1, packed), null, DATA_VERSION));
            assertEquals("LITEMATIC_PACKED_LENGTH_INVALID", failure.getMessage());
        }
    }

    @Test void malformedPropertiesDimensionsAndUnreviewedDataVersionsAreRejected() {
        CompoundTag malformed = root(1, 1, 1, 1, new long[] {0});
        CompoundTag properties = new CompoundTag();
        properties.putInt("axis", 1);
        malformed.getCompound("Regions").getCompound("main").getList("BlockStatePalette", 10)
                .getCompound(0).put("Properties", properties);
        assertThrows(IOException.class, () -> LitematicSourceValidator.validate(malformed, null, DATA_VERSION));
        assertThrows(IOException.class,
                () -> LitematicSourceValidator.validate(root(0, 1, 1, 1, new long[] {0}), null, DATA_VERSION));
        assertThrows(IOException.class,
                () -> LitematicSourceValidator.validate(root(512, 512, 512, 1, new long[] {0}), null, DATA_VERSION));
        CompoundTag old = root(1, 1, 1, 1, new long[] {0});
        old.putInt("MinecraftDataVersion", DATA_VERSION - 1);
        assertEquals("LITEMATIC_DATA_VERSION_UNSUPPORTED",
                assertThrows(IOException.class, () -> LitematicSourceValidator.validate(old, null, DATA_VERSION)).getMessage());
        CompoundTag future = root(1, 1, 1, 1, new long[] {0});
        future.putInt("SubVersion", 2);
        assertEquals("LITEMATIC_SUBVERSION_UNSUPPORTED",
                assertThrows(IOException.class, () -> LitematicSourceValidator.validate(future, null, DATA_VERSION)).getMessage());
    }

    @Test void nbtDepthAndDecodedByteBudgetsAreEnforced() throws Exception {
        CompoundTag deep = new CompoundTag(), current = deep;
        for (int i = 0; i < 40; i++) {
            CompoundTag child = new CompoundTag(); current.put("child", child); current = child;
        }
        byte[] bytes = compressed(deep);
        assertThrows(IOException.class, () -> LitematicSourceValidator.decode(bytes, 65536, 32));
        CompoundTag large = new CompoundTag();
        large.putByteArray("payload", new byte[8192]);
        byte[] bomb = compressed(large);
        assertTrue(bomb.length < 512);
        assertThrows(IOException.class, () -> LitematicSourceValidator.decode(bomb, 512, 32));
    }

    @Test void compressedFileBudgetPrecedesDecompression() throws Exception {
        Path file = directory.resolve("too-large.litematic");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength((long) LitematicSourceValidator.MAX_COMPRESSED_BYTES + 1);
        }
        assertEquals("LITEMATIC_FILE_LIMIT",
                assertThrows(IOException.class, () -> LitematicSourceValidator.read(file, DATA_VERSION)).getMessage());
    }

    @Test void sameSizeSameTimestampReplacementStillFailsDigestReadback() throws Exception {
        Path file = write(root(1, 1, 1, 1, new long[] {0}), "changed.litematic");
        var stamp = LitematicSourceValidator.read(file, DATA_VERSION).stamp();
        byte[] changed = Files.readAllBytes(file);
        changed[changed.length - 1] ^= 1;
        Files.write(file, changed);
        Files.setLastModifiedTime(file, stamp.modified());
        assertEquals("LITEMATIC_SOURCE_CHANGED",
                assertThrows(IOException.class, () -> LitematicSourceValidator.verifyUnchanged(stamp)).getMessage());
    }

    private Path write(CompoundTag tag, String name) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, compressed(tag));
        return file;
    }
    private static byte[] compressed(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }
    private static CompoundTag root(int x, int y, int z, int paletteSize, long[] packed) {
        CompoundTag root = new CompoundTag(), regions = new CompoundTag(), region = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", DATA_VERSION);
        root.put("Regions", regions);
        regions.put("main", region);
        region.put("Position", position(0, 0, 0));
        region.put("Size", position(x, y, z));
        ListTag palette = new ListTag();
        String[] ids = {"minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:oak_planks", "minecraft:glass"};
        for (int i = 0; i < paletteSize; i++) {
            CompoundTag state = new CompoundTag(); state.putString("Name", ids[i]); palette.add(state);
        }
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", packed);
        return root;
    }
    private static CompoundTag position(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z);
        return tag;
    }
}
