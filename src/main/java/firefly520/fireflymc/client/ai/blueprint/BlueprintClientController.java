package firefly520.fireflymc.client.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;
import java.util.concurrent.*;

/** Private server documents only. Native placement edits are proposals, never world-write authority. */
public final class BlueprintClientController {
    public static final BlueprintClientController INSTANCE = new BlueprintClientController();
    private final ThreadPoolExecutor files = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon(true).name("fireflymc-blueprint-files").factory(), new ThreadPoolExecutor.AbortPolicy());
    public final ForgematicaBridge bridge = new ForgematicaBridge(files);
    public List<JsonObject> entries = List.of();
    public int nextListOffset, totalEntries;
    public JsonObject selected = new JsonObject();
    public BlueprintDocument preview;
    public String status = "", dimension = "";
    public boolean forgeVisible;
    public boolean nativeChanged;
    public Integer layer;
    public final Map<BlueprintDocument.Position, String> observations = new LinkedHashMap<>();
    private long epoch, nextSend;
    private long requestDeadline;
    private String uploadText;
    private JsonObject ticket;
    private JsonObject importBase;
    private JsonObject downloadHeader;
    private List<BlueprintDocument.Block> downloading;
    private boolean waiting, inspectNext;
    private boolean proposeAfterTransform;
    private int nextOffset, inspectOffset;
    private BlueprintClientController() { NeoForge.EVENT_BUS.addListener(BlueprintGhostRenderer::render); }
    public void list(int offset) {
        if (!supported()) return;
        var args = new JsonObject(); args.addProperty("offset", offset);
        send(AiProtocol.Action.BLUEPRINT_VIEW, args);
    }
    public void select(JsonObject entry) {
        if (!supported()) return;
        hide(); selected = entry.deepCopy(); dimension = entry.get("dimension").getAsString();
        downloading = new ArrayList<>(); nextOffset = 0; waiting = false; status = "正在读取服务端蓝图…";
    }
    public void importSelected() {
        importSelected(false);
    }
    public void importSelected(boolean replaceSelectedVersion) {
        if (!supported()) return;
        if (uploadText != null || waiting) { status = "请等待当前传输或先取消预览"; return; }
        importBase = replaceSelectedVersion && !selected.isEmpty() ? reference() : null;
        long expected = epoch; status = "正在读取 Forgematica 当前选中蓝图…";
        bridge.importSelected().whenComplete((outcome, error) -> Minecraft.getInstance().execute(() -> {
            if (expected != epoch || !AiClientNetwork.STATE.ready()) return;
            if (error != null || !outcome.success()) { status = "导入失败：" + (error == null ? outcome.code() : "BRIDGE_FAILED"); return; }
            try { files.execute(() -> {
                String encoded = AiJson.GSON.toJson(outcome.value()); String digest = AiJson.digest(encoded);
                Minecraft.getInstance().execute(() -> {
                    if (expected != epoch || !AiClientNetwork.STATE.ready()) return;
                    uploadText = encoded; ticket = null; waiting = true;
                    var args = AiClientNetwork.object("phase", "begin"); args.addProperty("totalChars", encoded.length()); args.addProperty("digest", digest);
                    send(AiProtocol.Action.BLUEPRINT_IMPORT, args); status = "上传私人蓝图，等待服务端核验…";
                });
            }); } catch (RejectedExecutionException busy) { status = "蓝图读取队列繁忙"; }
        }));
    }
    public void accept(String kind, JsonObject data) {
        if (!data.has("clientEpoch") || data.get("clientEpoch").getAsLong() != epoch) return;
        if (kind.equals("blueprint_import")) {
            waiting = false;
            if (data.has("complete")) { uploadText = null; ticket = null; importBase = null; select(data); list(0); }
            else if (data.has("cancelled")) { uploadText = null; ticket = null; }
            else ticket = data.deepCopy();
        } else if (kind.equals("blueprint_transform")) {
            if (data.has("reviewInvalidated")) status = "服务端已标记原生预览发生变化；采用摆放修改后需重新审阅";
            else { boolean propose = proposeAfterTransform; proposeAfterTransform = false; select(data); list(0); if (propose) proposeRegion(false); }
        }
        else if (data.has("entries")) {
            entries = data.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject).toList();
            nextListOffset = data.get("nextOffset").getAsInt(); totalEntries = data.get("total").getAsInt();
        }
        else if (same(data)) {
            int offset = data.get("offset").getAsInt();
            if (data.has("inspection") && data.get("inspection").getAsBoolean()) {
                if (offset != inspectOffset) return;
                for (var value : data.getAsJsonArray("blocks")) {
                    var cell = value.getAsJsonObject(); var pos = AiJson.GSON.fromJson(cell.get("position"), BlueprintDocument.Position.class);
                    if (cell.has("observation")) observations.put(pos, cell.getAsJsonObject("observation").get("status").getAsString());
                }
                while (observations.size() > 8192) observations.remove(observations.keySet().iterator().next());
                inspectOffset = data.get("nextOffset").getAsInt(); waiting = false;
                if (inspectOffset >= data.get("total").getAsInt()) inspectNext = false;
            } else if (downloading != null && offset == nextOffset) {
                downloadHeader = data.deepCopy();
                for (var value : data.getAsJsonArray("blocks")) downloading.add(AiJson.GSON.fromJson(value, BlueprintDocument.Block.class));
                nextOffset = data.get("nextOffset").getAsInt(); waiting = false;
                if (nextOffset != downloading.size() || downloading.size() > BlueprintDocument.MAX_BLOCKS) { failed("BLUEPRINT_PAGE_INVALID"); return; }
                status = "读取蓝图 " + nextOffset + " / " + data.get("total").getAsInt();
                if (nextOffset == data.get("total").getAsInt()) completeDownload();
            }
        }
    }
    private void completeDownload() {
        var blocks = List.copyOf(downloading); var header = downloadHeader.deepCopy(); long expected = epoch;
        downloading = null; waiting = true;
        try { files.execute(() -> {
            try {
                var document = new BlueprintDocument(1, header.get("name").getAsString(), header.get("source").getAsString(),
                        header.get("sourceVersion").getAsString(), header.get("sourceDigest").getAsString(),
                        AiJson.GSON.fromJson(header.get("origin"), BlueprintDocument.Position.class), blocks);
                if (!AiJson.digest(AiJson.canonical(document)).equals(header.get("documentHash").getAsString())) throw new IllegalStateException("BLUEPRINT_DIGEST_MISMATCH");
                Minecraft.getInstance().execute(() -> {
                    if (expected != epoch || !AiClientNetwork.STATE.ready()) return;
                    waiting = false; preview = document; BlueprintGhostRenderer.begin(document); inspectNext = true; inspectOffset = 0;
                    status = "服务端版本已核验，正在展示预览；尚未批准施工";
                    bridge.showPreview(document).thenAccept(result -> {
                        if (expected != epoch) return;
                        forgeVisible = result.success();
                        if (!result.success()) status = "使用内置预览（Forgematica：" + result.code() + "）";
                    });
                });
            } catch (Exception error) { Minecraft.getInstance().execute(() -> { if (expected == epoch) failed("BLUEPRINT_CONTENT_INVALID"); }); }
        }); } catch (RejectedExecutionException busy) { failed("BLUEPRINT_QUEUE_FULL"); }
    }
    public void tick() {
        bridge.tick(); BlueprintGhostRenderer.tick();
        if (waiting && requestDeadline > 0 && System.currentTimeMillis() > requestDeadline) { failed("REQUEST_TIMEOUT"); return; }
        if (!bridge.drainChanges().isEmpty()) {
            boolean first = !nativeChanged;
            nativeChanged = true;
            status = "原生摆放已修改，尚未成为服务端版本；点击“采用摆放修改”提交重新核验";
            if (first && !selected.isEmpty()) { var args = reference(); args.addProperty("invalidateOnly", true); send(AiProtocol.Action.BLUEPRINT_TRANSFORM, args); }
        }
        if (waiting || System.currentTimeMillis() < nextSend || !AiClientNetwork.STATE.ready()) return;
        nextSend = System.currentTimeMillis() + 160;
        if (uploadText != null && ticket != null) {
            var args = new JsonObject(); args.addProperty("uploadId", ticket.get("id").getAsString());
            int offset = ticket.get("nextOffset").getAsInt();
            if (offset < uploadText.length()) {
                args.addProperty("phase", "chunk"); args.addProperty("offset", offset);
                args.addProperty("text", uploadText.substring(offset, Math.min(uploadText.length(), offset + BlueprintUpload.MAX_CHUNK_CHARS)));
            } else {
                args.addProperty("phase", "commit");
                if (importBase != null) importBase.entrySet().forEach(entry -> args.add(entry.getKey(), entry.getValue()));
            }
            send(AiProtocol.Action.BLUEPRINT_IMPORT, args); waiting = true;
        } else if (downloading != null || inspectNext && preview != null) {
            var args = reference(); args.addProperty("offset", downloading != null ? nextOffset : inspectOffset);
            args.addProperty("inspect", downloading == null);
            send(AiProtocol.Action.BLUEPRINT_VIEW, args); waiting = true;
        }
    }
    public void transform(String rotation, String mirror, boolean here) {
        if (!supported()) return;
        if (nativeChanged) { status = "请先采用摆放修改，或重新选择服务端版本后再变换"; return; }
        if (selected.isEmpty() || waiting) return;
        var args = reference(); args.addProperty("rotation", rotation); args.addProperty("mirror", mirror);
        if (here && Minecraft.getInstance().player != null) {
            var pos = Minecraft.getInstance().player.blockPosition(); var origin = new JsonArray();
            origin.add(pos.getX()); origin.add(pos.getY()); origin.add(pos.getZ()); args.add("origin", origin);
        }
        send(AiProtocol.Action.BLUEPRINT_TRANSFORM, args); waiting = true; status = "正在建立新的服务端版本…";
    }
    public void previewProposal(JsonObject proposal) {
        if (proposal.isEmpty() || proposalReady(proposal)) return;
        var entry = new JsonObject();
        entry.add("id", proposal.get("blueprintId")); entry.add("revision", proposal.get("blueprintRevision"));
        entry.add("hash", proposal.get("blueprintHash")); entry.add("documentHash", proposal.get("documentHash"));
        entry.add("dimension", proposal.get("dimension")); select(entry);
    }
    public boolean proposalReady(JsonObject proposal) {
        return !proposal.isEmpty() && (!proposal.has("blueprintCurrent") || proposal.get("blueprintCurrent").getAsBoolean())
                && preview != null && !nativeChanged && !selected.isEmpty()
                && selected.get("id").equals(proposal.get("blueprintId")) && selected.get("revision").equals(proposal.get("blueprintRevision"))
                && selected.get("hash").equals(proposal.get("blueprintHash")) && selected.has("documentHash")
                && selected.get("documentHash").equals(proposal.get("documentHash"));
    }
    public void proposeRegion(boolean nearPlayer) {
        if (!supported() || selected.isEmpty() || nativeChanged) { status = "先选择并保存服务端固定蓝图版本"; return; }
        var args = new JsonObject(); args.addProperty("action", "propose"); args.add("blueprintId", selected.get("id"));
        args.add("blueprintRevision", selected.get("revision")); args.add("blueprintHash", selected.get("hash")); args.addProperty("nearPlayer", nearPlayer);
        AiClientNetwork.send(AiProtocol.Action.BUILD_SELECT, args); status = "正在计算蓝图位置、范围与现场差异…";
    }
    public void rotateProposal() { if (waiting || nativeChanged || selected.isEmpty()) return; proposeAfterTransform = true; transform("CLOCKWISE_90", "NONE", false); }
    public void confirmProposal(JsonObject proposal) {
        if (!proposalReady(proposal)) { status = "固定版本预览尚未加载，或摆放已变更；请先重新预览"; return; }
        var region = AiClientNetwork.STATE.buildRegionView;
        var args = new JsonObject(); args.addProperty("action", "proposal_confirm"); args.add("proposalId", proposal.get("id"));
        args.add("proposalHash", proposal.get("hash")); args.add("documentHash", proposal.get("documentHash"));
        args.add("privacyEpoch", proposal.get("privacyEpoch")); args.add("expectedRevision", region.get("revision"));
        AiClientNetwork.send(AiProtocol.Action.BUILD_SELECT, args); status = "正在重核固定位置与现场；此确认不授权扣料或施工…";
    }
    private JsonObject reference() {
        var args = new JsonObject(); for (String key : List.of("id", "revision", "hash")) args.add(key, selected.get(key)); return args;
    }
    private boolean same(JsonObject data) { return !selected.isEmpty() && data.has("id") && selected.get("id").equals(data.get("id")) && selected.get("hash").equals(data.get("hash")); }
    public void failed(String code) {
        if (uploadText != null && AiClientNetwork.STATE.ready()) send(AiProtocol.Action.BLUEPRINT_IMPORT, AiClientNetwork.object("phase", "cancel"));
        waiting = false; downloading = null; inspectNext = false; uploadText = null; ticket = null; proposeAfterTransform = false; status = "蓝图操作未完成：" + code;
    }
    public void failedIfCurrent(String code, JsonObject args) { if (args.has("clientEpoch") && args.get("clientEpoch").getAsLong() == epoch) failed(code); }
    public void hide() {
        if (uploadText != null && AiClientNetwork.STATE.ready()) send(AiProtocol.Action.BLUEPRINT_IMPORT, AiClientNetwork.object("phase", "cancel"));
        uploadText = null; ticket = null; importBase = null;
        epoch++; waiting = false; requestDeadline = 0; downloading = null; preview = null; observations.clear(); forgeVisible = false; nativeChanged = false; layer = null; inspectNext = false;
        proposeAfterTransform = false;
        bridge.cancelActive(); bridge.clearOwnedPreviews(); BlueprintGhostRenderer.clear();
    }
    public void disconnect() { hide(); bridge.disconnect(); entries = List.of(); selected = new JsonObject(); uploadText = null; ticket = null; status = ""; dimension = ""; }
    public void layer(int delta) {
        if (nativeChanged) { status = "请先采用摆放修改，避免丢失尚未提交的原生变换"; return; }
        if (preview == null) return;
        int min = preview.blocks().stream().mapToInt(b -> b.position().y()).min().orElse(0), max = preview.blocks().stream().mapToInt(b -> b.position().y()).max().orElse(0);
        layer = Math.clamp(layer == null ? min : layer + delta, min, max);
        if (forgeVisible) { bridge.clearOwnedPreviews(); forgeVisible = false; }
    }
    public void versions() {
        if (selected.isEmpty()) return;
        send(AiProtocol.Action.BLUEPRINT_VIEW, AiClientNetwork.object("historyId", selected.get("id").getAsString()));
    }
    private void send(AiProtocol.Action action, JsonObject args) {
        args.addProperty("clientEpoch", epoch); requestDeadline = System.currentTimeMillis() + 20000; AiClientNetwork.send(action, args);
    }
    private boolean supported() {
        if (AiClientNetwork.STATE.ready() && AiClientNetwork.STATE.supportedActions.containsAll(Set.of("BLUEPRINT_VIEW", "BLUEPRINT_IMPORT", "BLUEPRINT_TRANSFORM"))) return true;
        status = "此服务器尚未开放蓝图工作台"; return false;
    }
}
