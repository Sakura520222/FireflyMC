package firefly520.fireflymc.ai.workspace;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/** Explicit privacy deletion only; never walks old/unrecognized files or changes the world itself. */
public final class WorkspacePrivacyService {
    public record Preview(UUID token, String owner, UUID conversationId, long revision,
                          int messages, int artifacts, long expiresAt) {}
    private final Map<UUID, Preview> pending = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Predicate<WorkspaceManager.Workspace> resourcesSettled;
    public WorkspacePrivacyService(LongSupplier clock, Predicate<WorkspaceManager.Workspace> resourcesSettled) {
        this.clock = clock; this.resourcesSettled = resourcesSettled;
    }
    public Preview preview(Actor actor, WorkspaceManager.Workspace workspace) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            ensureSettled(workspace);
            var snapshot = workspace.conversation().getSnapshot(0);
            Preview preview = new Preview(UUID.randomUUID(), actor.owner(), workspace.primaryConversationId(), snapshot.revision(),
                    (int) snapshot.events().stream().filter(e -> e.message() != null).count(),
                    existingFiles(workspace).size(), clock.getAsLong() + 120000);
            pending.values().removeIf(p -> p.expiresAt < clock.getAsLong() || p.owner.equals(actor.owner()));
            if (pending.size() >= 128) throw new IllegalStateException("PRIVACY_PREVIEW_LIMIT");
            pending.put(preview.token, preview);
            return preview;
        }
    }
    public long confirm(Actor actor, WorkspaceManager.Workspace workspace, UUID token) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            Preview preview = pending.get(token);
            if (preview == null || !preview.owner.equals(actor.owner()) || !preview.conversationId.equals(workspace.primaryConversationId())
                    || preview.expiresAt <= clock.getAsLong() || preview.revision != workspace.conversation().revision())
                throw new SecurityException("PRIVACY_PREVIEW_CHANGED");
            ensureSettled(workspace);
            var files = existingFiles(workspace); // Validate the entire bounded set before deleting any file.
            for (Path file : files) Files.deleteIfExists(file);
            long revision = workspace.conversation().erase(preview.revision);
            pending.remove(token);
            return revision;
        }
    }
    private void ensureSettled(WorkspaceManager.Workspace workspace) throws IOException {
        if (workspace.conversation().tasks().stream().anyMatch(t -> !t.state().terminal()) || !resourcesSettled.test(workspace))
            throw new IllegalStateException("ACTIVE_TASK_OR_ESCROW_REQUIRES_SETTLEMENT");
        if (firefly520.fireflymc.ai.blueprint.BuildJournal.hasUnresolvedWorldEffects(workspace))
            throw new IllegalStateException("BUILD_RECONCILIATION_REQUIRED");
        Path shares = workspace.directory().resolve("shares.json");
        if (Files.exists(shares) && Files.readString(shares).contains("\"DISPATCHING\""))
            throw new IllegalStateException("SHARE_DELIVERY_IN_PROGRESS");
    }
    private static List<Path> existingFiles(WorkspaceManager.Workspace workspace) throws IOException {
        Path root = workspace.directory().toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        for (String name : List.of("artifacts", "compactions", "exports", "processing", "materials", "fluids", "mixed-materials", "blueprints", "travel", "maid-control", "maid-farm", "maid-combat", "maid-transport", "tree-harvest", "exploration")) {
            Path folder = root.resolve(name);
            if (!Files.exists(folder)) continue;
            try (var walk = Files.walk(folder)) {
                for (Path path : walk.toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root) || Files.isSymbolicLink(path))
                        throw new IOException("UNSAFE_WORKSPACE_FILE");
                    String file = path.getFileName().toString();
                    boolean nativeJournal = Set.of("travel", "maid-control", "maid-farm", "maid-combat", "maid-transport", "tree-harvest", "exploration").contains(name);
                    boolean owned = !nativeJournal && !name.equals("processing") && !name.equals("materials") && !name.equals("fluids") && !name.equals("mixed-materials") && !name.equals("blueprints")
                            || nativeJournal && (file.equals("journal.jsonl") || file.matches("incomplete-[0-9a-f-]{36}\\.bin"))
                            || name.equals("exploration") && file.equals("preparation-journal.jsonl")
                            || name.equals("fluids") && Set.of("grants.json", "journal.jsonl").contains(file)
                            || name.equals("mixed-materials") && file.equals("journal.jsonl")
                            || name.equals("blueprints") && file.matches("[0-9a-f-]{36}\\.json")
                            || name.equals("processing") && file.matches("[0-9a-f-]{36}\\.json")
                            || name.equals("materials") && (Set.of("journal.jsonl", "grants.json").contains(file)
                            || file.matches("incomplete-[0-9a-f-]{36}\\.bin"));
                    if (Files.isRegularFile(path) && owned) files.add(path);
                }
            }
        }
        for (String name : List.of("memory.json", "preferences.json", "personal-events.jsonl",
                "approvals.json", "shares.json", "questions.json", "carrier.json", "material-preferences.json")) {
            Path path = root.resolve(name);
            if (Files.isSymbolicLink(path)) throw new IOException("UNSAFE_WORKSPACE_FILE");
            if (Files.exists(path)) files.add(path);
        }
        if (files.size() > 100000) throw new IOException("PRIVACY_FILE_LIMIT");
        return List.copyOf(files);
    }
}
