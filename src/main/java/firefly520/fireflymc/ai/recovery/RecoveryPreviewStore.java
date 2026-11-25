package firefly520.fireflymc.ai.recovery;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import java.util.function.LongSupplier;

/** Connection-bound, ephemeral approvals. No nonce is written to an artifact or conversation. */
public final class RecoveryPreviewStore {
    public record Scope(String owner, UUID serverInstanceId, UUID conversationId, long privacyEpoch, UUID session) {
        public Scope {
            UUID.fromString(owner); Objects.requireNonNull(serverInstanceId);
            Objects.requireNonNull(conversationId); Objects.requireNonNull(session);
        }
    }
    public record Preview(UUID token, Scope scope, UUID taskId, UUID operationId, String action,
                          String resourceDigest, String toolName, JsonObject arguments, String text,
                          String digest, long expiresAt) {
        public Preview { arguments = arguments.deepCopy(); }
        @Override public JsonObject arguments() { return arguments.deepCopy(); }
    }
    private final LongSupplier clock;
    private final long lifetimeMillis;
    private final Map<UUID, Preview> pending = new LinkedHashMap<>();
    public RecoveryPreviewStore(LongSupplier clock) { this(clock, 120000); }
    RecoveryPreviewStore(LongSupplier clock, long lifetimeMillis) {
        this.clock = Objects.requireNonNull(clock);
        if (lifetimeMillis < 1) throw new IllegalArgumentException("RECOVERY_PREVIEW_TTL");
        this.lifetimeMillis = lifetimeMillis;
    }
    public synchronized Preview create(Scope scope, UUID task, UUID operation, String action,
                                       String resourceDigest, String toolName, JsonObject arguments, String text) {
        pending.values().removeIf(value -> value.expiresAt() <= clock.getAsLong());
        if (pending.size() >= 256 || pending.values().stream().filter(p -> p.scope().owner().equals(scope.owner())).count() >= 8)
            throw new IllegalStateException("RECOVERY_PREVIEW_LIMIT");
        Objects.requireNonNull(task); Objects.requireNonNull(operation); Objects.requireNonNull(text);
        UUID nonce = UUID.randomUUID(); long expires = Math.addExact(clock.getAsLong(), lifetimeMillis);
        JsonObject body = new JsonObject();
        body.add("scope", AiJson.GSON.toJsonTree(scope)); body.addProperty("taskId", task.toString());
        body.addProperty("operationId", operation.toString()); body.addProperty("action", action);
        body.addProperty("resourceDigest", resourceDigest); body.addProperty("toolName", toolName);
        body.add("arguments", arguments.deepCopy()); body.addProperty("text", text);
        body.addProperty("expiresAt", expires); body.addProperty("nonce", nonce.toString());
        Preview preview = new Preview(nonce, scope, task, operation, action, resourceDigest, toolName,
                arguments, text, AiJson.digest(AiJson.canonical(body)), expires);
        pending.put(nonce, preview); return preview;
    }
    public synchronized Preview pending(Scope scope, UUID token, String digest) {
        Preview value = pending.get(token);
        if (value == null || !value.scope().equals(scope) || value.expiresAt() <= clock.getAsLong()
                || !value.digest().equals(digest)) throw new IllegalStateException("RECOVERY_PREVIEW_STALE");
        return value;
    }
    public synchronized Preview consume(Scope scope, UUID token, String digest, String currentResources) {
        Preview value = pending(scope, token, digest);
        if (!value.resourceDigest().equals(currentResources)) throw new IllegalStateException("RECOVERY_REVIEW_CHANGED");
        pending.remove(token); return value;
    }
    public synchronized void invalidate(String owner, UUID session) {
        pending.values().removeIf(p -> p.scope().owner().equals(owner) && p.scope().session().equals(session));
    }
    public synchronized void clear() { pending.clear(); }
}
