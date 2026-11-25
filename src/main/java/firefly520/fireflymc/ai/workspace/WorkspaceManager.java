package firefly520.fireflymc.ai.workspace;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Server-instance identity lives with the world, not the process or a player nickname. */
public final class WorkspaceManager implements AutoCloseable {
    public record Identity(int version, UUID serverInstanceId) {}
    public record Metadata(int version, UUID serverInstanceId, String owner, UUID primaryConversationId) {}
    public record Workspace(Metadata metadata, Path directory, ConversationStore conversation) {
        public String owner() { return metadata.owner(); }
        public UUID primaryConversationId() { return metadata.primaryConversationId(); }
        public Path artifact(UUID id) {
            Path root = directory.resolve("artifacts"), file = root.resolve(id + ".json");
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(file)) throw new SecurityException("UNSAFE_ARTIFACT_PATH");
            return file;
        }
        public UUID saveArtifact(Object value) throws IOException {
            UUID id = UUID.randomUUID();
            AiJson.write(artifact(id), value);
            return id;
        }
    }

    private final Path root;
    private final Identity identity;
    private final Map<String, Workspace> loaded = new HashMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;

    public WorkspaceManager(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        lockChannel = FileChannel.open(this.root.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("AI workspace already open");
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            throw e;
        }
        lock = acquired;
        try {
            Path file = this.root.resolve("server.json");
            if (Files.exists(file)) {
                identity = AiJson.GSON.fromJson(Files.readString(file), Identity.class);
                if (identity.version != 1 || identity.serverInstanceId == null)
                    throw new IOException("Unsupported AI world identity");
            } else {
                identity = new Identity(1, UUID.randomUUID());
                AiJson.write(file, identity);
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public synchronized Workspace getWorkspace(Actor actor) throws IOException {
        actor.requireAccess(actor.owner());
        return load(actor.owner());
    }

    public synchronized Workspace getWorkspace(UUID playerUuid) throws IOException {
        return load(playerUuid.toString());
    }

    /** Trusted lifecycle access. Do not expose this method to client-supplied identifiers. */
    public synchronized Workspace load(String owner) throws IOException {
        new Actor(owner, true, false, 4);
        if (loaded.containsKey(owner)) return loaded.get(owner);
        Path directory = root.resolve("workspace").resolve(owner);
        Files.createDirectories(directory);
        Path file = directory.resolve("workspace.json");
        Metadata metadata;
        if (Files.exists(file)) {
            metadata = AiJson.GSON.fromJson(Files.readString(file), Metadata.class);
            if (metadata.version != 1 || !identity.serverInstanceId.equals(metadata.serverInstanceId)
                    || !owner.equals(metadata.owner) || metadata.primaryConversationId == null)
                throw new IOException("AI workspace identity mismatch");
        } else {
            metadata = new Metadata(1, identity.serverInstanceId, owner, UUID.randomUUID());
            AiJson.write(file, metadata);
        }
        Workspace workspace = new Workspace(metadata, directory, new ConversationStore(
                directory.resolve("conversation").resolve(metadata.primaryConversationId + ".jsonl")));
        loaded.put(owner, workspace);
        return workspace;
    }

    public synchronized List<Workspace> all() throws IOException {
        Path directory = root.resolve("workspace");
        if (Files.exists(directory)) {
            try (var stream = Files.list(directory)) {
                for (Path child : stream.filter(Files::isDirectory).toList()) {
                    String owner = child.getFileName().toString();
                    try { UUID.fromString(owner); } catch (IllegalArgumentException ignored) { continue; }
                    load(owner);
                }
            }
        }
        return List.copyOf(loaded.values());
    }

    public UUID serverInstanceId() { return identity.serverInstanceId; }

    @Override
    public void close() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { lockChannel.close(); }
    }
}
