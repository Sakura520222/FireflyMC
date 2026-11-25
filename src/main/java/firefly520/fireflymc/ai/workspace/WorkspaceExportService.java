package firefly520.fireflymc.ai.workspace;

import firefly520.fireflymc.ai.memory.MemoryStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Immutable owner-only export. Live authorization tokens and unrecognized files are excluded. */
public final class WorkspaceExportService {
    public static final int CHUNK_BYTES = 8192;
    public static final long MAX_EXPORT_BYTES = 2L * 1024 * 1024 * 1024;
    public record Manifest(int version, UUID id, UUID serverInstanceId, String owner, UUID conversationId,
                           long revision, long length, String sha256, long createdAt) {}
    public record Chunk(UUID id, long offset, long nextOffset, long length, String base64) {}
    private final Workspace workspace;
    private final java.util.function.Supplier<String> materialSnapshot;
    private final java.util.function.Supplier<String> mixedSnapshot;
    private final Map<String, java.util.function.Supplier<String>> nativeSnapshots;
    private final java.util.function.BooleanSupplier compassCoordinatesVisible;
    public WorkspaceExportService(Workspace workspace) { this(workspace, null); }
    public WorkspaceExportService(Workspace workspace, java.util.function.Supplier<String> materialSnapshot) {
        this(workspace, materialSnapshot, null);
    }
    public WorkspaceExportService(Workspace workspace, java.util.function.Supplier<String> materialSnapshot,
                                  java.util.function.Supplier<String> mixedSnapshot) {
        this(workspace, materialSnapshot, mixedSnapshot, Map.of());
    }
    public WorkspaceExportService(Workspace workspace, java.util.function.Supplier<String> materialSnapshot,
                                  java.util.function.Supplier<String> mixedSnapshot,
                                  Map<String, java.util.function.Supplier<String>> nativeSnapshots) {
        this(workspace, materialSnapshot, mixedSnapshot, nativeSnapshots, () -> false);
    }
    public WorkspaceExportService(Workspace workspace, java.util.function.Supplier<String> materialSnapshot,
                                  java.util.function.Supplier<String> mixedSnapshot,
                                  Map<String, java.util.function.Supplier<String>> nativeSnapshots,
                                  java.util.function.BooleanSupplier compassCoordinatesVisible) {
        this.workspace = workspace; this.materialSnapshot = materialSnapshot; this.mixedSnapshot = mixedSnapshot;
        this.compassCoordinatesVisible = java.util.Objects.requireNonNull(compassCoordinatesVisible);
        this.nativeSnapshots = Map.copyOf(nativeSnapshots);
        if (nativeSnapshots.keySet().stream().anyMatch(name -> !Set.of("travel-journal.json", "maid-control-journal.json", "maid-farm-journal.json", "maid-combat-journal.json", "maid-transport-journal.json", "tree-harvest-journal.json", "exploration-journal.json", "compass-preparation-journal.json").contains(name)))
            throw new IllegalArgumentException("EXPORT_NATIVE_SOURCE_NAME");
    }
    private Path path(UUID id, String suffix) throws IOException {
        Path root = workspace.directory().resolve("exports");
        if (Files.isSymbolicLink(root)) throw new IOException("UNSAFE_EXPORT_PATH");
        Path file = root.resolve(id + suffix);
        if (Files.isSymbolicLink(file)) throw new IOException("UNSAFE_EXPORT_PATH");
        return file;
    }
    public Manifest prepare(Actor actor, UUID requestId) throws IOException {
        actor.requireAccess(workspace.owner());
        Path manifestPath = path(requestId, ".json");
        if (Files.exists(manifestPath)) return manifest(actor, requestId);
        var snapshot = workspace.conversation().getSnapshot(0);
        var compassArtifacts = new HashMap<String, String>();
        for (var event : snapshot.events()) {
            String key = switch (event.kind()) {
                case "compass_search_plan" -> "planId";
                case "compass_prepared_search_plan" -> "preparationId";
                case "compass_reconcile_preview" -> "reviewId";
                default -> "";
            };
            if (!key.isEmpty() && event.data() != null && event.data().has(key))
                compassArtifacts.put(UUID.fromString(event.data().get(key).getAsString()) + ".json", event.kind());
        }
        Path temporary = path(requestId, ".tmp"), target = path(requestId, ".zip");
        Files.createDirectories(temporary.getParent());
        try (var files = Files.list(temporary.getParent())) {
            if (files.filter(p -> p.getFileName().toString().endsWith(".zip")).count() >= 8)
                throw new IllegalStateException("EXPORT_LIMIT_REUSE_EXISTING");
        }
        // A previous interrupted export has never been advertised as downloadable.
        try (var output = Files.newOutputStream(temporary); var zip = new ZipOutputStream(new FilterOutputStream(output) {
            private long count;
            @Override public void write(int value) throws IOException { checkSize(1); out.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { checkSize(length); out.write(bytes, offset, length); }
            private void checkSize(int length) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("EXPORT_INTERRUPTED");
                if ((count += length) > MAX_EXPORT_BYTES) throw new IOException("EXPORT_TOO_LARGE");
            }
        }, StandardCharsets.UTF_8)) {
            entry(zip, "workspace.json", AiJson.GSON.toJson(workspace.metadata()));
            entry(zip, "tasks.json", AiJson.GSON.toJson(snapshot.tasks()));
            entry(zip, "profile.json", AiJson.GSON.toJson(WorkspacePreferences.load(workspace)));
            entry(zip, "memory.json", AiJson.GSON.toJson(new MemoryStore(workspace).view(actor)));
            entry(zip, "fluid-sources.json", AiJson.GSON.toJson(new firefly520.fireflymc.ai.material.MaterialSourceGrants(
                    workspace, System::currentTimeMillis, firefly520.fireflymc.ai.material.MaterialSourceGrants.Domain.FLUIDS).list(actor)));
            if (materialSnapshot != null) {
                entry(zip, "material-journal.json", materialSnapshot.get());
                entry(zip, "material-journal-format.txt", "FireflyMC material journal: lossless event stream\n"
                        + "Keep all events in seq order and one current state per operationId. material_operation replaces the full state.\n"
                        + "material_operation_delta version 1 requires the matching baseRevision: replace fields, remove listed fields, then apply arrays.\n"
                        + "Each array entry sets an exact index; length truncates or extends the array, and every added index must have a value.\n"
                        + "The resulting revision must be baseRevision + 1. Identity/owner/task/plan/policy cannot change.\n"
                        + "No historical result is discarded. MaterialJournalCodec.replay reconstructs every full state; deltas are not model context summaries.\n");
            }
            if (mixedSnapshot != null) entry(zip, "mixed-fluid-journals.json", mixedSnapshot.get());
            for (var source : nativeSnapshots.entrySet()) entry(zip, source.getKey(), source.getValue().get());
            zip.putNextEntry(new ZipEntry("timeline.jsonl"));
            for (var event : snapshot.events()) {
                zip.write((AiJson.GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            zip.closeEntry();
            Path observations = workspace.directory().resolve("personal-events.jsonl");
            Path carrier = workspace.directory().resolve("carrier.json");
            if (Files.exists(carrier)) synchronized (workspace.conversation()) { copy(zip, carrier, "carrier.json"); }
            Path materials = workspace.directory().resolve("material-preferences.json");
            if (Files.exists(materials)) synchronized (workspace.conversation()) { copy(zip, materials, "material-preferences.json"); }
            if (Files.exists(observations)) {
                synchronized (workspace.conversation()) { copy(zip, observations, "personal-events.jsonl"); }
            }
            // UUID artifacts and numeric summary versions are produced by this implementation.
            for (String folder : List.of("artifacts", "compactions", "processing", "blueprints")) {
                Path root = workspace.directory().resolve(folder);
                if (Files.isSymbolicLink(root)) throw new IOException("UNSAFE_EXPORT_SOURCE");
                if (!Files.exists(root)) continue;
                try (var stream = Files.list(root)) {
                    List<Path> files = stream.sorted().toList();
                    if (files.size() > 100000) throw new IOException("EXPORT_FILE_LIMIT");
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (!name.matches("[0-9a-f-]{36}\\.json|[0-9]+\\.json|latest\\.json")) continue;
                        if (folder.equals("artifacts") && compassArtifacts.containsKey(name)) {
                            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                    || Files.size(file) > 16 * 1024 * 1024) throw new IOException("UNSAFE_EXPORT_COMPASS_ARTIFACT");
                            var original = AiJson.object(Files.readString(file), 16 * 1024 * 1024, 64);
                            var projected = firefly520.fireflymc.ai.integration.exploration.CompassPrivacyProjection.artifact(
                                    original, compassArtifacts.get(name), compassCoordinatesVisible.getAsBoolean());
                            entry(zip, folder + "/" + name, AiJson.GSON.toJson(projected));
                        } else copy(zip, file, folder + "/" + name);
                    }
                }
            }
            entry(zip, "README.txt", "FireflyMC AI 4.0 private export\nConversation revision: " + snapshot.revision()
                    + "\nTimeline is complete at the revision above; artifacts and preferences record their own versions."
                    + "\nNo model credentials, live approval tokens, server configuration or other players' files are included."
                    + "\nCompass artifacts respect the current server coordinate policy; marked export projections are not executable approvals. Original server journals are unchanged.\n");
        } catch (Exception e) {
            Files.deleteIfExists(temporary);
            if (e instanceof IOException io) throw io;
            throw new IOException("EXPORT_FAILED");
        }
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        long length = Files.size(temporary);
        if (length > MAX_EXPORT_BYTES) throw new IOException("EXPORT_TOO_LARGE");
        String digest = digest(temporary);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Manifest manifest = new Manifest(1, requestId, workspace.metadata().serverInstanceId(), workspace.owner(),
                workspace.primaryConversationId(), snapshot.revision(), length, digest, System.currentTimeMillis());
        AiJson.write(manifestPath, manifest);
        return manifest;
    }
    public Manifest manifest(Actor actor, UUID id) throws IOException {
        actor.requireAccess(workspace.owner());
        Manifest value = AiJson.GSON.fromJson(Files.readString(path(id, ".json")), Manifest.class);
        if (value == null || value.version() != 1 || !id.equals(value.id()) || !actor.owner().equals(value.owner())
                || !workspace.metadata().serverInstanceId().equals(value.serverInstanceId())
                || value.length() != Files.size(path(id, ".zip"))) throw new IOException("INVALID_EXPORT");
        return value;
    }
    public List<Manifest> list(Actor actor) throws IOException {
        actor.requireAccess(workspace.owner());
        Path root = path(new UUID(0, 0), ".json").getParent();
        if (!Files.exists(root)) return List.of();
        List<Manifest> result = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).toList()) {
                result.add(manifest(actor, UUID.fromString(file.getFileName().toString().substring(0, 36))));
            }
        }
        return result.stream().sorted(Comparator.comparingLong(Manifest::createdAt).reversed()).limit(8).toList();
    }
    public Chunk chunk(Actor actor, UUID id, long offset) throws IOException {
        Manifest manifest = manifest(actor, id);
        if (offset < 0 || offset > manifest.length()) throw new IllegalArgumentException("EXPORT_OFFSET");
        byte[] bytes = new byte[(int) Math.min(CHUNK_BYTES, manifest.length() - offset)];
        try (var channel = FileChannel.open(path(id, ".zip"), StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new EOFException("EXPORT_CHANGED");
        }
        return new Chunk(id, offset, offset + bytes.length, manifest.length(), Base64.getEncoder().encodeToString(bytes));
    }
    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static void copy(ZipOutputStream zip, Path file, String name) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("UNSAFE_EXPORT_SOURCE");
        zip.putNextEntry(new ZipEntry(name));
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("EXPORT_INTERRUPTED");
                zip.write(buffer, 0, count);
            }
        }
        zip.closeEntry();
    }
    public static String digest(Path file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = new byte[65536];
                for (int count; (count = input.read(bytes)) != -1;) hash.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
