package firefly520.fireflymc.ai.integration.equipment;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Persisted optional carrier selection only. It never authorizes inventory or world actions. */
public final class CarrierRepository {
    public record Choice(UUID maidId, String dimension, String nativeTaskId) {
        public Choice {
            if (maidId == null || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    || nativeTaskId == null || !nativeTaskId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
                throw new IllegalArgumentException("CARRIER_CHOICE_INVALID");
        }
        public String digest() { return AiJson.digest(AiJson.GSON.toJson(this)); }
    }
    public record Selection(int version, String owner, long revision, Choice choice) {}
    public record Preview(UUID id, String owner, long revision, Choice choice, String digest, long expiresAt) {}
    private final Workspace workspace;
    private final LongSupplier clock;
    private final long privacyEpoch;
    private final Map<UUID, Preview> pending = new HashMap<>();
    private volatile Selection selection;

    public CarrierRepository(Workspace workspace, LongSupplier clock) throws IOException {
        this.workspace = workspace;
        this.clock = clock;
        synchronized (workspace.conversation()) {
            privacyEpoch = workspace.conversation().privacyEpoch();
            Path file = file();
            selection = Files.exists(file) ? AiJson.GSON.fromJson(Files.readString(file), Selection.class)
                    : new Selection(1, workspace.owner(), 0, null);
            if (selection == null || selection.version() != 1 || !workspace.owner().equals(selection.owner())
                    || selection.revision() < 0) throw new IOException("CARRIER_SELECTION_CORRUPT");
        }
    }
    private Path file() throws IOException {
        Path file = workspace.directory().resolve("carrier.json");
        if (Files.isSymbolicLink(workspace.directory()) || Files.isSymbolicLink(file))
            throw new IOException("CARRIER_UNSAFE_PATH");
        return file;
    }
    public Selection selection(Actor actor) { access(actor); return selection; }
    public synchronized Preview preview(Actor actor, Choice choice) {
        access(actor);
        pending.values().removeIf(p -> p.expiresAt() < clock.getAsLong());
        if (pending.size() >= 4) throw new IllegalStateException("CARRIER_PREVIEW_LIMIT");
        var result = new Preview(UUID.randomUUID(), workspace.owner(), selection.revision(), choice,
                choice.digest(), clock.getAsLong() + 120000);
        pending.put(result.id(), result);
        return result;
    }
    public synchronized Preview pending(Actor actor, UUID id, String digest) {
        access(actor);
        var preview = pending.get(id);
        if (preview == null || preview.expiresAt() < clock.getAsLong() || preview.revision() != selection.revision()
                || !preview.digest().equals(digest)) throw new IllegalStateException("CARRIER_PREVIEW_STALE");
        return preview;
    }
    /** Native owner/entity/task was revalidated on the server thread immediately before this write. */
    public synchronized Selection confirm(Actor actor, UUID id, String digest, Choice observed) throws IOException {
        synchronized (workspace.conversation()) {
            var preview = pending(actor, id, digest);
            if (!preview.choice().equals(observed)) throw new IllegalStateException("CARRIER_PREVIEW_CHANGED");
            Selection next = new Selection(1, workspace.owner(), selection.revision() + 1, observed);
            persist(next, "carrier_selected");
            pending.clear();
            return next;
        }
    }
    public synchronized Selection clear(Actor actor, long expectedRevision) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            if (selection.revision() != expectedRevision) throw new IllegalStateException("CARRIER_SELECTION_CHANGED");
            Selection next = new Selection(1, workspace.owner(), selection.revision() + 1, null);
            persist(next, "carrier_cleared");
            pending.clear();
            return next;
        }
    }
    private void persist(Selection next, String event) throws IOException {
        AiJson.write(file(), next);
        selection = next;
        workspace.conversation().append(List.of(new ConversationStore.Draft(null, event, null,
                AiJson.GSON.toJsonTree(next).getAsJsonObject(), null)));
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != privacyEpoch)
            throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
}
