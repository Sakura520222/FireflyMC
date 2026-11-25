package firefly520.fireflymc.ai.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** UTF-8, forced writes and atomic publication. Never logs document contents. */
public final class AiJson {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private AiJson() {}

    /** Reject excessive nesting before Gson can recurse through untrusted provider/tool data. */
    public static JsonObject object(String text, int maximumCharacters, int maximumDepth) {
        if (text == null || text.length() > maximumCharacters) throw new IllegalArgumentException("JSON_SIZE");
        boolean quoted = false, escaped = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > maximumDepth) throw new IllegalArgumentException("JSON_DEPTH"); }
            else if (c == '}' || c == ']') depth--;
            if (depth < 0) throw new IllegalArgumentException("JSON_STRUCTURE");
        }
        if (depth != 0 || quoted) throw new IllegalArgumentException("JSON_STRUCTURE");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stable hashes for persisted plans: object/map iteration order is not part of their meaning. */
    public static String canonical(Object value) { return GSON.toJson(sorted(GSON.toJsonTree(value))); }
    private static com.google.gson.JsonElement sorted(com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            new java.util.TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) -> result.add(key, sorted(child)));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(child -> result.add(sorted(child))); return result;
        }
        return value.deepCopy();
    }

    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writeFully(channel, (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
