package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Player-controlled input protection. Models can read this policy but cannot grant themselves exceptions. */
public final class MaterialPreferences {
    public record Snapshot(int schema, String owner, long revision, Set<String> protectedItems) {
        public Snapshot {
            UUID.fromString(owner); protectedItems = Collections.unmodifiableSet(new TreeSet<>(protectedItems));
            if (schema != 1 || revision < 0 || protectedItems.size() > 128 || protectedItems.stream().anyMatch(id -> id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))
                throw new IllegalArgumentException("MATERIAL_PREFERENCES_INVALID");
        }
    }
    private final Workspace workspace;
    private final long privacyEpoch;
    private volatile Snapshot current;
    public MaterialPreferences(Workspace workspace) throws IOException {
        this.workspace = workspace; privacyEpoch = workspace.conversation().privacyEpoch();
        Path path = path();
        if (Files.exists(path)) {
            if (Files.size(path) > 65536) throw new IOException("MATERIAL_PREFERENCES_SIZE");
            current = AiJson.GSON.fromJson(Files.readString(path), Snapshot.class);
            if (!current.owner().equals(workspace.owner())) throw new IOException("MATERIAL_PREFERENCES_OWNER");
        } else current = new Snapshot(1, workspace.owner(), 0, Set.of());
    }
    private Path path() {
        Path file = workspace.directory().resolve("material-preferences.json");
        if (Files.isSymbolicLink(file)) throw new SecurityException("MATERIAL_PREFERENCES_PATH"); return file;
    }
    private void active() { if (privacyEpoch != workspace.conversation().privacyEpoch()) throw new IllegalStateException("MATERIAL_PREFERENCES_STALE"); }
    public Snapshot view(Actor actor) { actor.requireAccess(workspace.owner()); active(); return current; }
    public boolean protects(String itemId) { active(); return current.protectedItems().contains(itemId); }
    public Snapshot save(Actor actor, long expectedRevision, Set<String> protectedItems) throws IOException {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner()); active();
            if (current.revision() != expectedRevision) throw new IllegalStateException("MATERIAL_PREFERENCES_CHANGED");
            var next = new Snapshot(1, workspace.owner(), Math.addExact(current.revision(), 1), protectedItems);
            AiJson.write(path(), next); current = next; return next;
        }
    }
}
