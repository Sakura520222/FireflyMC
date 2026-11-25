package firefly520.fireflymc.ai.blueprint;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Content only. Source labels and digests never grant ownership, permission or execution approval. */
public record BlueprintDocument(int formatVersion, String name, String source, String sourceVersion,
                                String sourceDigest, Position origin, List<Block> blocks) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BLOCKS = 262_144;
    public static final long MAX_ENCODED_BYTES = 16L * 1024 * 1024;
    public static final int MAX_COORDINATE = 30_000_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_./+-]+");

    public BlueprintDocument {
        if (formatVersion != FORMAT_VERSION) throw new IllegalArgumentException("BLUEPRINT_PROTOCOL_UNSUPPORTED");
        text(name, 128); text(source, 64); text(sourceVersion, 64);
        if (sourceDigest == null || !sourceDigest.isEmpty() && !sourceDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("BLUEPRINT_SOURCE_DIGEST_INVALID");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("BLUEPRINT_CELL_LIMIT");
        checkWorld(origin);
        long bytes = 1024L + 6L * (name.length() + source.length() + sourceVersion.length());
        var positions = new HashSet<Position>();
        for (Block block : blocks) {
            Objects.requireNonNull(block, "block");
            if (!positions.add(block.position())) throw new IllegalArgumentException("BLUEPRINT_DUPLICATE_POSITION");
            checkWorld(origin.add(block.position()));
            bytes += block.estimatedBytes();
            if (bytes > MAX_ENCODED_BYTES) throw new IllegalArgumentException("BLUEPRINT_DATA_LIMIT");
        }
        blocks = List.copyOf(blocks);
    }

    public record Position(int x, int y, int z) {
        public Position add(Position other) {
            try { return new Position(Math.addExact(x, other.x), Math.addExact(y, other.y), Math.addExact(z, other.z)); }
            catch (ArithmeticException exception) { throw new IllegalArgumentException("BLUEPRINT_COORDINATE_OVERFLOW"); }
        }
        public Position subtract(Position other) {
            try { return new Position(Math.subtractExact(x, other.x), Math.subtractExact(y, other.y), Math.subtractExact(z, other.z)); }
            catch (ArithmeticException exception) { throw new IllegalArgumentException("BLUEPRINT_COORDINATE_OVERFLOW"); }
        }
    }

    public record State(String blockId, Map<String, String> properties) {
        public State {
            if (blockId == null || blockId.length() > 256 || !ID.matcher(blockId).matches())
                throw new IllegalArgumentException("BLUEPRINT_BLOCK_ID_INVALID");
            Objects.requireNonNull(properties, "properties");
            if (properties.size() > 32) throw new IllegalArgumentException("BLUEPRINT_PROPERTY_LIMIT");
            var copy = new TreeMap<String, String>();
            properties.forEach((key, value) -> {
                if (key == null || key.length() > 64 || !PROPERTY.matcher(key).matches()
                        || value == null || value.length() > 128 || !VALUE.matcher(value).matches())
                    throw new IllegalArgumentException("BLUEPRINT_PROPERTY_INVALID");
                copy.put(key, value);
            });
            properties = java.util.Collections.unmodifiableMap(copy);
        }
        public long estimatedBytes() {
            long bytes = 128 + 6L * blockId.length();
            for (var entry : properties.entrySet()) bytes += 32 + 6L * (entry.getKey().length() + entry.getValue().length());
            return bytes;
        }
    }

    /** Positions are relative to document.origin; AIR remains explicit and grants no demolition permission. */
    public record Block(Position position, State state) {
        public Block { Objects.requireNonNull(position); Objects.requireNonNull(state); }
        public long estimatedBytes() { return 128 + state.estimatedBytes(); }
    }

    public static void checkWorld(Position position) {
        if (Math.abs((long) position.x) > MAX_COORDINATE || Math.abs((long) position.y) > MAX_COORDINATE
                || Math.abs((long) position.z) > MAX_COORDINATE)
            throw new IllegalArgumentException("BLUEPRINT_COORDINATE_LIMIT");
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("BLUEPRINT_TEXT_INVALID");
    }
}
