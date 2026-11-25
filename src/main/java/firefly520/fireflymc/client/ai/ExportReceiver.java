package firefly520.fireflymc.client.ai;

import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceExportService;
import firefly520.fireflymc.ai.workspace.WorkspaceExportService.Manifest;
import firefly520.fireflymc.ai.workspace.WorkspaceExportService.Chunk;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Runs on the client file worker. A committed byte offset survives disconnects and process restarts. */
public final class ExportReceiver {
    private record Progress(Manifest manifest, long offset) {}
    private final Path root;
    private final Manifest manifest;
    private long offset;
    public ExportReceiver(Path root, Manifest manifest, String serverId, String owner) throws IOException {
        if (manifest.version() != 1 || !serverId.equals(manifest.serverInstanceId().toString())
                || !owner.equals(manifest.owner()) || manifest.length() < 1
                || manifest.length() > WorkspaceExportService.MAX_EXPORT_BYTES
                || !manifest.sha256().matches("[0-9a-f]{64}"))
            throw new IOException("INVALID_EXPORT_MANIFEST");
        this.root = root.toAbsolutePath().normalize();
        this.manifest = manifest;
        if (Files.isSymbolicLink(this.root)) throw new IOException("UNSAFE_EXPORT_DIRECTORY");
        Files.createDirectories(this.root);
        if (Files.exists(path(".progress.json"))) {
            Progress saved = AiJson.GSON.fromJson(Files.readString(path(".progress.json")), Progress.class);
            if (!manifest.equals(saved.manifest()) || saved.offset() < 0 || saved.offset() > manifest.length())
                throw new IOException("EXPORT_CHECKPOINT_MISMATCH");
            offset = saved.offset();
        }
        if (Files.exists(path(".zip"))) {
            if (!WorkspaceExportService.digest(path(".zip")).equals(manifest.sha256())) throw new IOException("EXPORT_FILE_CONFLICT");
            offset = manifest.length();
        } else if (Files.exists(path(".part"))) {
            try (var channel = FileChannel.open(path(".part"), StandardOpenOption.WRITE)) {
                if (channel.size() < offset) throw new IOException("EXPORT_CHECKPOINT_TRUNCATED");
                channel.truncate(offset);
            }
        } else if (offset != 0) throw new IOException("EXPORT_CHECKPOINT_MISSING");
        AiJson.write(this.root.resolve("pending.json"), manifest);
    }
    private Path path(String extension) throws IOException {
        Path file = root.resolve(manifest.id() + extension);
        if (Files.isSymbolicLink(file)) throw new IOException("UNSAFE_EXPORT_FILE");
        return file;
    }
    public synchronized void accept(Chunk chunk) throws IOException {
        if (!chunk.id().equals(manifest.id()) || chunk.length() != manifest.length()) throw new IOException("EXPORT_ID_CHANGED");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(chunk.base64()); }
        catch (IllegalArgumentException e) { throw new IOException("EXPORT_ENCODING"); }
        if (bytes.length > WorkspaceExportService.CHUNK_BYTES || chunk.offset() < 0
                || chunk.nextOffset() != chunk.offset() + bytes.length || chunk.nextOffset() > manifest.length())
            throw new IOException("INVALID_EXPORT_CHUNK");
        if (chunk.nextOffset() <= offset) return; // Already committed retry.
        if (chunk.offset() != offset || bytes.length == 0) throw new IOException("EXPORT_OUT_OF_ORDER");
        try (var channel = FileChannel.open(path(".part"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        offset = chunk.nextOffset();
        AiJson.write(path(".progress.json"), new Progress(manifest, offset));
        if (offset == manifest.length()) finish();
    }
    public synchronized void finish() throws IOException {
        if (offset != manifest.length()) throw new IOException("EXPORT_NOT_COMPLETE");
        if (!Files.exists(path(".zip"))) {
            if (!WorkspaceExportService.digest(path(".part")).equals(manifest.sha256())) throw new IOException("EXPORT_DIGEST_MISMATCH");
            Files.move(path(".part"), path(".zip"), StandardCopyOption.ATOMIC_MOVE);
        }
        Files.deleteIfExists(root.resolve("pending.json"));
    }
    public long offset() { return offset; }
    public Manifest manifest() { return manifest; }
    public Path completedPath() throws IOException { return path(".zip"); }
}
