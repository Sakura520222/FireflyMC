package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable private blueprint revisions. A durable conversation event makes a saved revision visible. */
public final class BlueprintRepository {
    /** Nonblocking check for a game-thread dispatcher; IO publishes invalidation after durable metadata changes. */
    public static final class ReviewLease {
        private volatile boolean valid = true;
        private final firefly520.fireflymc.ai.conversation.ConversationStore conversation;
        private final long privacyEpoch;
        private final long regionSequence;
        private ReviewLease(firefly520.fireflymc.ai.conversation.ConversationStore conversation) {
            this.conversation = conversation; privacyEpoch = conversation.privacyEpoch();
            regionSequence = conversation.regionSequence();
        }
        public void check() {
            if (!valid || privacyEpoch != conversation.privacyEpoch() || regionSequence != conversation.regionSequence())
                throw new IllegalStateException("BLUEPRINT_REVIEW_CHANGED");
        }
    }
    private static final Map<firefly520.fireflymc.ai.conversation.ConversationStore, Map<UUID, List<java.lang.ref.WeakReference<ReviewLease>>>> LEASES = new WeakHashMap<>();
    public record Entry(UUID id, long revision, UUID storageId, UUID requestId, UUID taskId, String owner,
                        String dimension, String name, int blocks, long createdAt, String documentHash, String hash) {}
    public record Revision(Entry entry, BlueprintDocument document) {}
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private final Workspace workspace;
    private final long privacyEpoch;
    public BlueprintRepository(Workspace workspace) {
        this.workspace = workspace;
        synchronized (workspace.conversation()) { privacyEpoch = workspace.conversation().privacyEpoch(); }
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (privacyEpoch != workspace.conversation().privacyEpoch()) throw new IllegalStateException("BLUEPRINT_PRIVACY_CHANGED");
    }
    public List<Entry> history(Actor actor) {
        synchronized (workspace.conversation()) {
            access(actor);
            return workspace.conversation().getSnapshot(privacyEpoch).events().stream().filter(e -> e.kind().equals("blueprint_revision"))
                    .map(e -> AiJson.GSON.fromJson(e.data(), Entry.class)).toList();
        }
    }
    public List<Entry> latest(Actor actor) {
        var values = new LinkedHashMap<UUID, Entry>();
        for (Entry entry : history(actor)) values.put(entry.id(), entry);
        return values.values().stream().sorted(Comparator.comparingLong(Entry::createdAt).reversed()).toList();
    }
    public Revision save(Actor actor, UUID request, UUID id, long expectedRevision, String expectedHash, UUID task,
                         String dimension, BlueprintDocument document) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            if (request == null || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("BLUEPRINT_CONTEXT_INVALID");
            var history = history(actor);
            String documentHash = AiJson.digest(AiJson.canonical(document));
            for (var previous : history) if (previous.requestId().equals(request)) {
                if (!previous.documentHash().equals(documentHash) || !previous.dimension().equals(dimension)
                        || !Objects.equals(previous.taskId(), task) || id != null && !id.equals(previous.id()))
                    throw new SecurityException("BLUEPRINT_REQUEST_REUSED");
                return read(actor, previous.id(), previous.revision(), previous.hash());
            }
            Entry previous = null;
            if (id != null) for (Entry candidate : history) if (candidate.id().equals(id)) previous = candidate;
            if (id != null && previous == null || previous != null && (previous.revision() != expectedRevision || !previous.hash().equals(expectedHash))
                    || id == null && (expectedRevision != 0 || expectedHash != null && !expectedHash.isEmpty()))
                throw new IllegalStateException("BLUEPRINT_REVISION_CHANGED");
            if (id == null && latest(actor).size() >= 128) throw new IllegalStateException("BLUEPRINT_COUNT_LIMIT");
            UUID identity = id == null ? UUID.randomUUID() : id;
            var unsigned = new Entry(identity, previous == null ? 1 : Math.addExact(previous.revision(), 1), UUID.randomUUID(), request, task,
                    actor.owner(), dimension, document.name(), document.blocks().size(), System.currentTimeMillis(), documentHash, "");
            Entry entry = hashed(unsigned, AiJson.digest(AiJson.canonical(unsigned)));
            var revision = new Revision(entry, document);
            byte[] bytes = AiJson.GSON.toJson(revision).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > BlueprintDocument.MAX_ENCODED_BYTES) throw new IllegalArgumentException("BLUEPRINT_DATA_LIMIT");
            Path directory = directory();
            long used = 0;
            try (var files = Files.list(directory)) {
                var values = files.toList();
                if (values.size() >= 8192) throw new IllegalStateException("BLUEPRINT_REVISION_LIMIT");
                for (Path path : values) {
                    safe(path);
                    if (Files.isRegularFile(path)) used = Math.addExact(used, Files.size(path));
                }
            }
            if (used + bytes.length > MAX_TOTAL_BYTES) throw new IllegalStateException("BLUEPRINT_STORAGE_LIMIT");
            Path file = directory.resolve(entry.storageId() + ".json"); safe(file);
            if (Files.exists(file)) throw new IOException("BLUEPRINT_STORAGE_COLLISION");
            AiJson.write(file, revision);
            workspace.conversation().append(List.of(new Draft(task, "blueprint_revision", null, AiJson.GSON.toJsonTree(entry).getAsJsonObject(), null)));
            invalidateLeases(entry.id());
            return revision;
        }
    }
    public Revision read(Actor actor, UUID id, long revision, String hash) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            Entry entry = history(actor).stream().filter(e -> e.id().equals(id) && e.revision() == revision).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("BLUEPRINT_NOT_FOUND"));
            if (!entry.owner().equals(actor.owner()) || !entry.hash().equals(hash)
                    || !AiJson.digest(AiJson.canonical(hashed(entry, ""))).equals(hash)) throw new SecurityException("BLUEPRINT_VERSION_MISMATCH");
            Path file = directory().resolve(entry.storageId() + ".json"); safe(file);
            if (!Files.isRegularFile(file) || Files.size(file) > BlueprintDocument.MAX_ENCODED_BYTES) throw new IOException("BLUEPRINT_STORAGE_INVALID");
            Revision saved = AiJson.GSON.fromJson(Files.readString(file), Revision.class);
            if (!saved.entry().equals(entry) || !AiJson.digest(AiJson.canonical(saved.document())).equals(entry.documentHash()))
                throw new IOException("BLUEPRINT_CONTENT_CHANGED");
            return saved;
        }
    }
    public boolean current(Actor actor, Entry entry) {
        return latest(actor).stream().anyMatch(value -> value.id().equals(entry.id()) && value.hash().equals(entry.hash()));
    }
    /** A client preview edit invalidates review authority even before its replacement document is uploaded. */
    public String invalidateReview(Actor actor, UUID id, String hash) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor); currentEntry(actor, id, hash);
            var data = new com.google.gson.JsonObject(); data.addProperty("id", id.toString()); data.addProperty("hash", hash);
            workspace.conversation().append(List.of(new Draft(null, "blueprint_review_invalidated", null, data, null)));
            invalidateLeases(id);
            return reviewStamp(actor, id, hash);
        }
    }
    public String reviewStamp(Actor actor, UUID id, String hash) {
        synchronized (workspace.conversation()) {
            access(actor); currentEntry(actor, id, hash);
            long invalidation = workspace.conversation().getSnapshot(privacyEpoch).events().stream()
                    .filter(e -> e.kind().equals("blueprint_review_invalidated") && e.data().get("id").getAsString().equals(id.toString()))
                    .mapToLong(e -> e.seq()).max().orElse(0);
            return AiJson.digest(workspace.metadata().serverInstanceId() + ":" + actor.owner() + ":" + hash + ":" + invalidation
                    + ":region:" + workspace.conversation().regionSequence());
        }
    }
    private Entry currentEntry(Actor actor, UUID id, String hash) {
        return latest(actor).stream().filter(e -> e.id().equals(id) && e.hash().equals(hash)).findFirst()
                .orElseThrow(() -> new IllegalStateException("BLUEPRINT_VERSION_NOT_CURRENT"));
    }
    public ReviewLease reviewLease(Actor actor, UUID id, String hash, String stamp) {
        synchronized (workspace.conversation()) {
            if (!reviewStamp(actor, id, hash).equals(stamp)) throw new IllegalStateException("BLUEPRINT_REVIEW_CHANGED");
            var lease = new ReviewLease(workspace.conversation());
            synchronized (LEASES) {
                var rows = LEASES.computeIfAbsent(workspace.conversation(), ignored -> new HashMap<>()).computeIfAbsent(id, ignored -> new ArrayList<>());
                rows.removeIf(reference -> reference.get() == null);
                if (rows.size() >= 128) throw new IllegalStateException("BLUEPRINT_LEASE_LIMIT");
                rows.add(new java.lang.ref.WeakReference<>(lease));
            }
            return lease;
        }
    }
    private void invalidateLeases(UUID id) {
        synchronized (LEASES) {
            var byId = LEASES.get(workspace.conversation());
            if (byId == null) return;
            var rows = byId.remove(id);
            if (rows != null) for (var reference : rows) { var lease = reference.get(); if (lease != null) lease.valid = false; }
        }
    }
    private Path directory() throws IOException {
        Path path = workspace.directory().resolve("blueprints"); safe(path); Files.createDirectories(path); safe(path); return path;
    }
    private void safe(Path path) throws IOException {
        Path root = workspace.directory().toRealPath();
        if (Files.isSymbolicLink(path) || Files.exists(path) && !path.toRealPath().startsWith(root)
                || !Files.exists(path) && !path.getParent().toRealPath().startsWith(root)) throw new IOException("UNSAFE_BLUEPRINT_PATH");
    }
    private static Entry hashed(Entry e, String hash) {
        return new Entry(e.id(), e.revision(), e.storageId(), e.requestId(), e.taskId(), e.owner(), e.dimension(), e.name(), e.blocks(),
                e.createdAt(), e.documentHash(), hash);
    }
}
