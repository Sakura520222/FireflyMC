package firefly520.fireflymc.client.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceExportService.*;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** One bounded, resumable private export transfer per client connection. */
public final class ExportDownloads {
    private static final ThreadPoolExecutor FILES = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon(true).name("fireflymc-ai-export").factory());
    private static ExportReceiver receiver;
    private static UUID session;
    private static long offset, nextAt;
    private static boolean busy;
    private ExportDownloads() {}
    private static Path root() {
        var mc = Minecraft.getInstance();
        String origin = mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip;
        return mc.gameDirectory.toPath().resolve("fireflymc-ai-exports")
                .resolve(AiJson.digest(origin + "|" + AiClientNetwork.STATE.serverId + "|" + mc.player.getUUID()).substring(0, 32));
    }
    public static void restore() {
        if (Minecraft.getInstance().player == null) return;
        Path pending = root().resolve("pending.json");
        UUID current = AiClientNetwork.STATE.session;
        FILES.execute(() -> {
            try {
                if (!Files.exists(pending) || Files.isSymbolicLink(pending) || Files.size(pending) > 4096) return;
                Manifest saved = AiJson.GSON.fromJson(Files.readString(pending), Manifest.class);
                Minecraft.getInstance().execute(() -> {
                    if (current.equals(AiClientNetwork.STATE.session))
                        AiClientNetwork.send(AiProtocol.Action.EXPORT, AiClientNetwork.object("id", saved.id().toString()));
                });
            } catch (Exception ignored) { /* Incomplete files stay available for manual recovery. */ }
        });
    }
    public static void begin(JsonObject data) {
        Manifest manifest = AiJson.GSON.fromJson(data.get("manifest"), Manifest.class);
        UUID current = AiClientNetwork.STATE.session;
        String serverId = AiClientNetwork.STATE.serverId, owner = Minecraft.getInstance().player.getUUID().toString();
        Path root = root();
        busy = true;
        session = current;
        FILES.execute(() -> {
            try {
                ExportReceiver created = new ExportReceiver(root, manifest, serverId, owner);
                if (created.offset() == manifest.length()) created.finish();
                Minecraft.getInstance().execute(() -> {
                    if (!current.equals(AiClientNetwork.STATE.session)) return;
                    receiver = created; offset = created.offset(); busy = false; nextAt = 0;
                    showProgress();
                });
            } catch (Exception e) { failure(current, "EXPORT_PREPARE_FAILED"); }
        });
    }
    public static void tick() {
        if (receiver == null || busy || offset >= receiver.manifest().length() || !AiClientNetwork.STATE.ready()
                || !AiClientNetwork.STATE.session.equals(session) || System.currentTimeMillis() < nextAt) return;
        JsonObject args = AiClientNetwork.object("id", receiver.manifest().id().toString());
        args.addProperty("offset", offset);
        busy = true;
        AiClientNetwork.send(AiProtocol.Action.EXPORT_CHUNK, args);
    }
    public static void accept(JsonObject data) {
        Chunk chunk = AiJson.GSON.fromJson(data.get("chunk"), Chunk.class);
        if (receiver == null || !chunk.id().equals(receiver.manifest().id())) return;
        ExportReceiver active = receiver;
        UUID current = session;
        FILES.execute(() -> {
            try {
                active.accept(chunk);
                Minecraft.getInstance().execute(() -> {
                    if (!current.equals(AiClientNetwork.STATE.session) || receiver != active) return;
                    offset = active.offset(); busy = false; nextAt = System.currentTimeMillis() + 250;
                    showProgress();
                });
            } catch (Exception e) { failure(current, "EXPORT_VERIFY_FAILED"); }
        });
    }
    private static void showProgress() {
        JsonObject status = new JsonObject();
        status.addProperty("exportId", receiver.manifest().id().toString());
        status.addProperty("downloadedBytes", offset);
        status.addProperty("totalBytes", receiver.manifest().length());
        if (offset == receiver.manifest().length()) {
            try { status.addProperty("savedFile", receiver.completedPath().toAbsolutePath().toString()); }
            catch (Exception e) { failed(); return; }
            status.addProperty("sha256", receiver.manifest().sha256());
            status.addProperty("verified", true);
        } else status.addProperty("status", "下载中；断线后在同一专服认证即可续传");
        AiClientNetwork.STATE.detail = status;
        AiClientNetwork.STATE.uiVersion++;
    }
    private static void failure(UUID current, String code) {
        Minecraft.getInstance().execute(() -> {
            if (!current.equals(AiClientNetwork.STATE.session)) return;
            failed(); AiClientNetwork.STATE.error = code;
        });
    }
    public static void failed() { receiver = null; busy = false; }
    public static void disconnected() { receiver = null; busy = false; session = null; }
}
