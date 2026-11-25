package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Connection/owner/privacy-bound bounded upload; duplicate chunks cannot alter accepted content. */
public final class BlueprintUpload {
    public static final int MAX_CHUNK_CHARS = 2048;
    public record Ticket(UUID id, int nextOffset, int totalChars, long expiresAt) {}
    private static final class Pending {
        final UUID id = UUID.randomUUID(), session;
        final String owner, digest;
        final long epoch, expires;
        final int chars;
        final StringBuilder body = new StringBuilder();
        long byteUpperBound;
        Pending(String owner, UUID session, long epoch, int chars, String digest, long expires) {
            this.owner = owner; this.session = session; this.epoch = epoch; this.chars = chars; this.digest = digest; this.expires = expires;
        }
        Ticket ticket() { return new Ticket(id, body.length(), chars, expires); }
    }
    private final LongSupplier clock;
    private final Map<UUID, Pending> uploads = new HashMap<>();
    public BlueprintUpload(LongSupplier clock) { this.clock = clock; }
    public synchronized Ticket begin(String owner, UUID session, long epoch, int chars, String digest) {
        expire();
        if (owner == null || session == null || chars < 1 || chars > BlueprintDocument.MAX_ENCODED_BYTES
                || digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("BLUEPRINT_UPLOAD_INVALID");
        if (uploads.size() >= 8 || uploads.values().stream().anyMatch(p -> p.owner.equals(owner))) throw new IllegalStateException("BLUEPRINT_UPLOAD_BUSY");
        var upload = new Pending(owner, session, epoch, chars, digest, clock.getAsLong() + 20 * 60 * 1000);
        uploads.put(upload.id, upload); return upload.ticket();
    }
    public synchronized Ticket append(String owner, UUID session, long epoch, UUID id, int offset, String chunk) {
        Pending upload = require(owner, session, epoch, id);
        if (chunk == null || chunk.isEmpty() || chunk.length() > MAX_CHUNK_CHARS || offset < 0 || (long) offset + chunk.length() > upload.chars)
            throw new IllegalArgumentException("BLUEPRINT_CHUNK_INVALID");
        if (offset < upload.body.length()) {
            if ((long) offset + chunk.length() > upload.body.length() || !upload.body.substring(offset, offset + chunk.length()).equals(chunk))
                throw new SecurityException("BLUEPRINT_CHUNK_REUSED");
        } else if (offset == upload.body.length()) {
            long size = upload.byteUpperBound + chunk.getBytes(StandardCharsets.UTF_8).length + 4L;
            if (size > BlueprintDocument.MAX_ENCODED_BYTES) throw new IllegalArgumentException("BLUEPRINT_DATA_LIMIT");
            upload.body.append(chunk); upload.byteUpperBound = size;
        }
        else throw new IllegalStateException("BLUEPRINT_CHUNK_GAP");
        return upload.ticket();
    }
    public synchronized BlueprintDocument finish(String owner, UUID session, long epoch, UUID id) {
        Pending upload = require(owner, session, epoch, id);
        if (upload.body.length() != upload.chars) throw new IllegalStateException("BLUEPRINT_UPLOAD_INCOMPLETE");
        String text = upload.body.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > BlueprintDocument.MAX_ENCODED_BYTES || !AiJson.digest(text).equals(upload.digest))
            throw new IllegalArgumentException("BLUEPRINT_UPLOAD_DIGEST");
        // Bound nesting before Gson constructs the document. No NBT/entities or permissions are imported.
        var data = AiJson.object(text, (int) BlueprintDocument.MAX_ENCODED_BYTES, 16);
        only(data, Set.of("formatVersion", "name", "source", "sourceVersion", "sourceDigest", "origin", "blocks"));
        only(data.getAsJsonObject("origin"), Set.of("x", "y", "z"));
        if (data.getAsJsonArray("blocks").size() > BlueprintDocument.MAX_BLOCKS) throw new IllegalArgumentException("BLUEPRINT_CELL_LIMIT");
        for (var cell : data.getAsJsonArray("blocks")) {
            var block = cell.getAsJsonObject(); only(block, Set.of("position", "state"));
            only(block.getAsJsonObject("position"), Set.of("x", "y", "z"));
            only(block.getAsJsonObject("state"), Set.of("blockId", "properties"));
        }
        return AiJson.GSON.fromJson(data, BlueprintDocument.class);
    }
    public synchronized void completed(String owner, UUID session, long epoch, UUID id) { require(owner, session, epoch, id); uploads.remove(id); }
    public synchronized void disconnect(String owner) { uploads.values().removeIf(p -> p.owner.equals(owner)); }
    private Pending require(String owner, UUID session, long epoch, UUID id) {
        expire(); var pending = uploads.get(id);
        if (pending == null) throw new IllegalStateException("BLUEPRINT_UPLOAD_EXPIRED");
        if (!pending.owner.equals(owner) || !pending.session.equals(session) || pending.epoch != epoch)
            throw new SecurityException("BLUEPRINT_UPLOAD_OWNER_CHANGED");
        return pending;
    }
    private void expire() { uploads.values().removeIf(p -> p.expires < clock.getAsLong()); }
    private static void only(com.google.gson.JsonObject value, Set<String> fields) {
        if (value == null || !fields.containsAll(value.keySet())) throw new IllegalArgumentException("BLUEPRINT_UNSAFE_CONTENT");
    }
}
