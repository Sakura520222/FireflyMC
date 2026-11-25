package firefly520.fireflymc.client.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.*;
import firefly520.fireflymc.client.ai.workspace.CompanionWorkspaceScreen;
import firefly520.fireflymc.client.ai.workspace.WorkspaceReviewScreen;
import firefly520.fireflymc.client.ai.workspace.RecoveryScreen;
import firefly520.fireflymc.client.eventws.ClientEventWebSocketClient;
import firefly520.fireflymc.client.eventws.ClientEventNotificationMessage;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Sends UI intents only; this class has no model endpoint, API key or HTTP client. */
public final class AiClientNetwork {
    public static final WorkspaceClientState STATE = new WorkspaceClientState();
    public static AiClientPreferences.Data display = AiClientPreferences.Data.defaults();
    private record Pending(AiRequestPayload payload, long sentAt, int attempts) {}
    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, AiClientPreferences.Outbox> OUTBOX = new LinkedHashMap<>();
    private static final Map<String, String> APPROVED_SHARES = new HashMap<>();
    private static final Set<String> DELIVERED_SHARES = new HashSet<>();
    private static final Map<UUID, Long> EXPORT_JOBS = new HashMap<>();
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("fireflymc-ai-client-files").factory());
    private static Path preferencesPath;
    private static Object connection;
    private static int ticks;
    private static boolean openRequested;
    private static long lastNoticeAt;
    private static String pendingNotice = "";
    private static UUID mapRouteRequest, mapRouteSession;
    private static String mapRouteTask;
    private static firefly520.fireflymc.client.ai.integration.map.MapRoute mapRouteDraft;
    private AiClientNetwork() {}

    public static boolean channel() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null && !mc.hasSingleplayerServer()
                && NetworkRegistry.hasChannel(mc.getConnection(), AiRequestPayload.TYPE.id());
    }
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != connection) {
            disconnected();
            connection = mc.getConnection();
        }
        firefly520.fireflymc.client.ai.integration.map.MapViewBridge.tickActive();
        if (!channel()) { STATE.availability = "NOT_SUPPORTED"; return; }
        ticks++;
        if (!STATE.ready() && ticks % 80 == 1) send(AiProtocol.Action.HELLO, new JsonObject());
        if (STATE.ready() && !STATE.conversationId.isEmpty() && ticks % 40 == 0) sync();
        if (STATE.ready() && ticks % 5 == 0 && mc.screen instanceof CompanionWorkspaceScreen
                && PENDING.values().stream().noneMatch(p -> p.payload().action() == AiProtocol.Action.MESSAGE))
            STATE.nextMessageRead().ifPresent(args -> send(AiProtocol.Action.MESSAGE, args));
        if (STATE.ready() && ticks % 40 == 0 && mc.screen instanceof CompanionWorkspaceScreen screen && screen.showingContext()
                && STATE.detail.has("manualPending") && STATE.detail.get("manualPending").getAsBoolean()
                && PENDING.values().stream().noneMatch(pending -> pending.payload().action() == AiProtocol.Action.CONTEXT))
            send(AiProtocol.Action.CONTEXT, new JsonObject());
        ExportDownloads.tick();
        if (STATE.ready()) firefly520.fireflymc.client.ai.integration.recipe.RecipeViewBridge.INSTANCE.tick();
        if (STATE.ready()) firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.tick();
        long now = System.currentTimeMillis();
        EXPORT_JOBS.values().removeIf(deadline -> deadline < now);
        if (display.noticeIntervalSeconds() == 0) pendingNotice = "";
        if (STATE.ready() && mc.screen == null && !pendingNotice.isEmpty()
                && now - lastNoticeAt >= display.noticeIntervalSeconds() * 1000L) {
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(pendingNotice + " · K 查看工作台"), false);
            lastNoticeAt = now; pendingNotice = "";
        }
        for (var entry : List.copyOf(PENDING.entrySet())) {
            Pending pending = entry.getValue();
            if (pending.attempts() >= 3 && now - pending.sentAt() > 10000) {
                PENDING.remove(entry.getKey());
                if (pending.payload().action() == AiProtocol.Action.MESSAGE) {
                    var body = JsonParser.parseString(pending.payload().body()).getAsJsonObject();
                    if (body.has("inline")) STATE.messageReadFailed(body.get("seq").getAsLong());
                }
                if (pending.payload().action() == AiProtocol.Action.EXPORT_CHUNK) ExportDownloads.failed();
                if (pending.payload().action() == AiProtocol.Action.MAP_ROUTE && entry.getKey().equals(mapRouteRequest)) mapRouteRequest = null;
                if (pending.payload().action().name().startsWith("BLUEPRINT_"))
                    firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.failedIfCurrent("REQUEST_TIMEOUT", JsonParser.parseString(pending.payload().body()).getAsJsonObject());
                if (pending.payload().action().name().startsWith("RECOVERY_") && mc.screen instanceof RecoveryScreen screen)
                    screen.requestFailed(entry.getKey(), "REQUEST_TIMEOUT");
                STATE.error = "请求尚未确认，可重开工作台恢复；未确认输入已保留";
                continue;
            }
            if (now - pending.sentAt() < 4000 || pending.attempts() >= 3) continue;
            PacketDistributor.sendToServer(pending.payload());
            PENDING.put(entry.getKey(), new Pending(pending.payload(), now, pending.attempts() + 1));
        }
        if (ticks % 200 == 0) save();
    }
    public static void open() {
        if (STATE.ready() && channel()) {
            STATE.notice = "";
            pendingNotice = "";
            latestHistory();
            Minecraft.getInstance().setScreen(new CompanionWorkspaceScreen());
        } else {
            openRequested = true;
            if (channel()) send(AiProtocol.Action.HELLO, new JsonObject());
            else Minecraft.getInstance().gui.setOverlayMessage(
                    net.minecraft.network.chat.Component.literal("小萤仅在启用 AI 的专服认证后可用"), false);
        }
    }
    public static void openMap(net.minecraft.client.gui.screens.Screen parent) {
        if (!STATE.ready() || !channel() || STATE.serverId.isEmpty() || STATE.session.equals(AiProtocol.NO_SESSION)
                || !STATE.supportedActions.contains(AiProtocol.Action.CLIENT_POINT.name())) {
            STATE.error = "此服务器未开放私人坐标线索"; return;
        }
        var bridge = mapBridge();
        Minecraft.getInstance().setScreen(new firefly520.fireflymc.client.ai.workspace.MapWorkspaceScreen(parent, bridge, point -> {
            if (!STATE.ready() || !channel() || PENDING.size() >= 64) throw new IllegalStateException("MAP_WORKSPACE_UNAVAILABLE");
            UUID request = UUID.randomUUID(); send(request, AiProtocol.Action.CLIENT_POINT, point.clientReport());
            if (!PENDING.containsKey(request)) throw new IllegalStateException("MAP_POINT_NOT_QUEUED");
        }));
    }
    private static firefly520.fireflymc.client.ai.integration.map.MapViewBridge mapBridge() {
        return firefly520.fireflymc.client.ai.integration.map.MapViewBridge.acquire(() -> {
            var mc = Minecraft.getInstance();
            if (!STATE.ready() || !channel() || STATE.serverId.isEmpty() || STATE.session.equals(AiProtocol.NO_SESSION)) return null;
            try { return new firefly520.fireflymc.client.ai.integration.map.MapSessionState.Scope(UUID.fromString(STATE.serverId), mc.player.getUUID(), STATE.session); }
            catch (IllegalArgumentException changed) { return null; }
        });
    }
    public static void openTaskMap(net.minecraft.client.gui.screens.Screen parent, String taskId) {
        if (STATE.task(taskId).isEmpty() || !STATE.supportedActions.contains(AiProtocol.Action.MAP_ROUTE.name())) {
            STATE.error = "请先选择当前服务器提供的本人任务"; return;
        }
        openMap(parent);
        if (!(Minecraft.getInstance().screen instanceof firefly520.fireflymc.client.ai.workspace.MapWorkspaceScreen)) return;
        mapRouteDraft = mapBridge().route(); mapRouteSession = STATE.session; mapRouteTask = taskId;
        mapRouteRequest = send(AiProtocol.Action.MAP_ROUTE, object("taskId", taskId));
        if (!PENDING.containsKey(mapRouteRequest)) mapRouteRequest = null;
    }
    public static UUID send(AiProtocol.Action action, JsonObject data) {
        UUID id = UUID.randomUUID();
        send(id, action, data);
        return id;
    }
    private static void send(UUID id, AiProtocol.Action action, JsonObject data) {
        if (!channel()) return;
        if (action != AiProtocol.Action.HELLO && !STATE.ready()) return;
        if (AiProtocol.requiresAdvertisement(action) && !STATE.supportedActions.contains(action.name())) {
            STATE.error = "此服务器尚未提供该工作台能力"; return;
        }
        if (PENDING.size() >= 64) { STATE.error = "请求队列繁忙"; return; }
        data = data.deepCopy();
        if (action == AiProtocol.Action.HELLO || action == AiProtocol.Action.OPEN) {
            data.addProperty("crossChatReady", ClientEventWebSocketClient.getInstance().canSendConfirmedShare());
            data.addProperty("crossChatEndpointHash", firefly520.fireflymc.ai.storage.AiJson.digest(firefly520.fireflymc.Config.CLIENT.EVENT_NOTIFICATION_URL.get()));
        }
        AiRequestPayload payload;
        try {
            payload = new AiRequestPayload(AiProtocol.VERSION, id,
                    action == AiProtocol.Action.HELLO ? AiProtocol.NO_SESSION : STATE.session, action, data.toString());
        } catch (IllegalArgumentException e) {
            STATE.error = "输入内容超出大小或格式限制";
            var draft = OUTBOX.remove(id);
            if (draft != null && STATE.draft.isEmpty()) STATE.draft = draft.text();
            return;
        }
        PENDING.put(id, new Pending(payload, System.currentTimeMillis(), 1));
        PacketDistributor.sendToServer(payload);
    }
    public static boolean submit(String text) {
        if (text == null || text.isBlank() || text.length() > 4096) return false;
        return queue(new AiClientPreferences.Outbox(UUID.randomUUID(), text));
    }
    public static boolean answer(QuestionReplyTarget target, String text) {
        if (text.isBlank() || text.length() > 2048) { STATE.error = "任务回答需为1–2048字"; return false; }
        return queue(target.answer(UUID.randomUUID(), text));
    }
    private static boolean queue(AiClientPreferences.Outbox outgoing) {
        if (!STATE.ready() || !channel()) { STATE.error = "工作台连接尚未就绪，请稍后重试"; return false; }
        if (OUTBOX.size() >= 8 || PENDING.size() >= 64) { STATE.error = "消息发送队列已满，请稍后重试"; return false; }
        if (STATE.viewingHistory) latestHistory();
        UUID id = outgoing.requestId();
        OUTBOX.put(id, outgoing);
        STATE.draft = "";
        send(id, outgoing.action(), outgoing.body());
        if (!PENDING.containsKey(id)) {
            OUTBOX.remove(id); STATE.draft = outgoing.text(); save(); return false;
        }
        save();
        return OUTBOX.containsKey(id);
    }
    public static void earlierHistory() {
        if (!STATE.hasEarlierHistory || PENDING.values().stream().anyMatch(p ->
                Set.of(AiProtocol.Action.OPEN, AiProtocol.Action.HISTORY, AiProtocol.Action.TASK_MESSAGES).contains(p.payload().action()))) return;
        JsonObject args = new JsonObject(); args.addProperty("clientViewEpoch", STATE.earlierHistory());
        args.addProperty("beforeSeq", STATE.nextHistoryBeforeSeq);
        send(AiProtocol.Action.HISTORY, args);
    }
    public static void latestHistory() {
        JsonObject args = new JsonObject(); args.addProperty("clientViewEpoch", STATE.latestHistory());
        send(AiProtocol.Action.OPEN, args);
    }
    public static void taskConversation(String taskId) {
        if (!STATE.supportedActions.contains(AiProtocol.Action.TASK_MESSAGES.name())) {
            STATE.error = "服务器尚未提供任务关联对话跳转"; return;
        }
        var args = object("taskId", taskId); args.addProperty("clientViewEpoch", STATE.taskHistory());
        send(AiProtocol.Action.TASK_MESSAGES, args);
    }
    public static void sync() {
        if (PENDING.values().stream().anyMatch(p -> p.payload().action() == AiProtocol.Action.SYNC)) return;
        JsonObject args = new JsonObject();
        args.addProperty("afterSeq", STATE.lastSeq);
        args.addProperty("clientViewEpoch", STATE.viewEpoch);
        send(AiProtocol.Action.SYNC, args);
    }
    public static void receive(AiResponsePayload payload) {
        Pending pending = PENDING.get(payload.requestId());
        if ("availability".equals(payload.kind())) {
            if (pending == null) return;
            PENDING.remove(payload.requestId());
            JsonObject data = JsonParser.parseString(payload.body()).getAsJsonObject();
            String status = data.get("status").getAsString();
            if (!"READY".equals(status) && !STATE.conversationId.isEmpty()) {
                ExportDownloads.disconnected();
                save();
                String draft = STATE.draft;
                STATE.clearPrivate();
                STATE.draft = draft;
            }
            STATE.session = payload.sessionId();
            STATE.availability = status;
            STATE.supportedActions = actions(data);
            if (STATE.ready() && data.has("serverInstanceId")) {
                STATE.serverId = data.get("serverInstanceId").getAsString();
                loadPreferences();
                for (var out : List.copyOf(OUTBOX.values())) send(out.requestId(), out.action(), out.body());
                if (openRequested) { openRequested = false; open(); }
            }
            STATE.uiVersion++;
            return;
        }
        if (!STATE.ready() || !payload.sessionId().equals(STATE.session)) return;
        if (pending == null && !Set.of("changed", "share_result", "share_delivery", "text_delta").contains(payload.kind())
                && !(EXPORT_JOBS.containsKey(payload.requestId()) && Set.of("export", "error").contains(payload.kind()))) return;
        PENDING.remove(payload.requestId());
        JsonObject data = JsonParser.parseString(payload.body()).getAsJsonObject();
        switch (payload.kind()) {
            case "message" -> {
                var original = pending == null ? new JsonObject() : JsonParser.parseString(pending.payload().body()).getAsJsonObject();
                if (original.has("inline")) STATE.applyMessagePart(data);
                else STATE.detail = data;
            }
            case "open", "sync", "history", "task_messages" -> {
                if (pending != null) {
                    var original = JsonParser.parseString(pending.payload().body()).getAsJsonObject();
                    if (original.has("clientViewEpoch") && original.get("clientViewEpoch").getAsLong() != STATE.viewEpoch) return;
                }
                STATE.applyPage(data, Set.of("history", "task_messages").contains(payload.kind()));
                if ("task_messages".equals(payload.kind()) && Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen screen)
                    screen.focusConversation(data.get("focusTaskId").getAsString(), data.get("focusSeq").getAsLong());
                STATE.supportedActions = actions(data);
                if (data.has("recipeGeneration")) firefly520.fireflymc.client.ai.integration.recipe.RecipeViewBridge.INSTANCE
                        .bindAuthority(STATE.serverId, data.get("recipeGeneration").getAsLong());
                for (var task : STATE.tasks) if (task.has("requestId")) OUTBOX.remove(UUID.fromString(task.get("requestId").getAsString()));
                if (data.has("streams")) for (var value : data.getAsJsonArray("streams")) {
                    var metadata = value.getAsJsonObject();
                    var old = STATE.streams.get(metadata.get("taskId").getAsString());
                    if ((old == null || old.get("sequence").getAsLong() < metadata.get("sequence").getAsLong())
                            && PENDING.values().stream().noneMatch(p -> p.payload().action() == AiProtocol.Action.STREAM))
                        send(AiProtocol.Action.STREAM, object("taskId", metadata.get("taskId").getAsString()));
                }
                if (STATE.lastSeq < STATE.revision) sync();
            }
            case "submit" -> { OUTBOX.remove(payload.requestId()); save(); sync(); }
            case "client_point" -> { STATE.notice = data.get("text").getAsString(); STATE.uiVersion++; sync(); }
            case "map_route" -> {
                if (!payload.requestId().equals(mapRouteRequest) || !STATE.session.equals(mapRouteSession)) return;
                mapRouteRequest = null;
                try {
                    var route = firefly520.fireflymc.ai.storage.AiJson.GSON.fromJson(data.get("route"), firefly520.fireflymc.client.ai.integration.map.MapRoute.class);
                    if (route.taskId() == null || !route.taskId().toString().equals(mapRouteTask)) throw new IllegalArgumentException("MAP_ROUTE_TASK_CHANGED");
                    var bridge = mapBridge();
                    if (bridge.route().equals(mapRouteDraft)) {
                        bridge.setRoute(route); STATE.notice = "已载入本人任务坐标引用；它不证明可通行或当前方块状态。";
                    } else STATE.notice = "任务路线已返回；本地草稿已有修改，已保留，可再次点击任务地图载入。";
                } catch (RuntimeException invalid) { STATE.error = "任务路线格式或连接已变化，请重新选择任务。"; }
                STATE.uiVersion++;
            }
            case "changed" -> {
                if (data.has("taskId")) STATE.streams.remove(data.get("taskId").getAsString());
                STATE.notice = data.has("notice") ? data.get("notice").getAsString() : "";
                if (data.has("state") && Set.of("WAITING_APPROVAL", "WAITING_ANSWER_BLOCKING", "WAITING_EXTERNAL", "COMPLETED", "FAILED",
                        "RECOVERY_REQUIRED", "CANCELLED").contains(data.get("state").getAsString()))
                    pendingNotice = STATE.name + " · " + switch (data.get("state").getAsString()) {
                        case "WAITING_APPROVAL" -> "有待审批操作"; case "WAITING_ANSWER_BLOCKING" -> "有问题等待回答";
                        case "WAITING_EXTERNAL" -> "任务正在等待后台条件";
                        case "COMPLETED" -> "任务已完成"; case "RECOVERY_REQUIRED" -> "操作需要核对"; case "CANCELLED" -> "任务已取消";
                        default -> "任务未完成，请查看原因";
                    };
                sync();
                if (Minecraft.getInstance().screen instanceof RecoveryScreen screen) screen.changed(data);
            }
            case "recovery_view", "recovery_preview", "recovery_confirm" -> {
                if (Minecraft.getInstance().screen instanceof RecoveryScreen screen) screen.receive(payload.kind(), payload.requestId(), data);
            }
            case "text_delta", "stream" -> STATE.stream(data);
            case "memory_view" -> {
                STATE.notes = data.getAsJsonArray("notes").asList().stream().map(JsonElement::getAsJsonObject).toList();
                STATE.nextMemoryOffset = data.get("nextOffset").getAsInt(); STATE.totalMemory = data.get("total").getAsInt();
            }
            case "memory_remember", "memory_update", "memory_forget", "memory_clear" -> {
                if ("memory_remember".equals(payload.kind()) && pending != null
                        && Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen screen)
                    screen.memorySaved(JsonParser.parseString(pending.payload().body()).getAsJsonObject().get("text").getAsString());
                send(AiProtocol.Action.MEMORY_VIEW, new JsonObject());
            }
            case "materials_view", "materials_scan" -> { STATE.materialView = data; STATE.error = ""; }
            case "fluids_view", "fluids_scan" -> {
                if (Minecraft.getInstance().screen instanceof firefly520.fireflymc.client.ai.workspace.FluidSourcesScreen screen) screen.receive(data);
            }
            case "integrations" -> {
                if (payload.requestId().equals(STATE.integrationRequest)) { STATE.integrationView = data; STATE.error = ""; }
            }
            case "build_select" -> {
                if (STATE.acceptBuildRegion(data)) STATE.error = "";
            }
            case "blueprint_view", "blueprint_import", "blueprint_transform" ->
                    firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.accept(payload.kind(), data);
            case "recipe_show" -> {
                STATE.recipeView = data;
                firefly520.fireflymc.client.ai.integration.recipe.RecipeViewBridge.INSTANCE
                        .bindAuthority(STATE.serverId, data.get("recipeGeneration").getAsLong());
            }
            case "source_confirm", "source_revoke" -> send(AiProtocol.Action.MATERIALS_VIEW, new JsonObject());
            case "fluid_source_confirm", "fluid_source_revoke" -> send(AiProtocol.Action.FLUIDS_VIEW, new JsonObject());
            case "source_preview", "fluid_source_preview" -> {
                boolean fluid = payload.kind().equals("fluid_source_preview");
                if (STATE.yoloMode()) {
                    var args = object("previewId", data.get("previewId").getAsString()); args.add("digest", data.get("digest"));
                    send(fluid ? AiProtocol.Action.FLUID_SOURCE_CONFIRM : AiProtocol.Action.SOURCE_CONFIRM, args); break;
                }
                var parent = Minecraft.getInstance().screen;
                if (parent instanceof CompanionWorkspaceScreen || parent instanceof firefly520.fireflymc.client.ai.workspace.EquipmentBrowserScreen
                        || parent instanceof firefly520.fireflymc.client.ai.workspace.FluidSourcesScreen) {
                    Minecraft.getInstance().setScreen(new WorkspaceReviewScreen(parent, fluid ? "授权原料流体来源" : "授权所选物料来源", data.get("text").getAsString(),
                            "确认授权", () -> {
                        var args = object("previewId", data.get("previewId").getAsString());
                        args.add("digest", data.get("digest")); send(fluid ? AiProtocol.Action.FLUID_SOURCE_CONFIRM : AiProtocol.Action.SOURCE_CONFIRM, args);
                    }));
                }
            }
            case "equipment_view" -> {
                if (Minecraft.getInstance().screen instanceof firefly520.fireflymc.client.ai.workspace.EquipmentBrowserScreen screen) screen.receive(data);
            }
            case "material_preferences" -> {
                if (Minecraft.getInstance().screen instanceof firefly520.fireflymc.client.ai.workspace.MaterialPreferencesScreen screen) {
                    STATE.error = ""; screen.receive(data);
                }
            }
            case "carrier_select" -> {
                if (data.has("preview") && STATE.yoloMode()) {
                    var preview = data.getAsJsonObject("preview"); var args = object("operation", "confirm");
                    args.add("id", preview.get("id")); args.add("digest", preview.get("digest"));
                    send(AiProtocol.Action.CARRIER_SELECT, args); break;
                }
                if (data.has("preview") && Minecraft.getInstance().screen instanceof firefly520.fireflymc.client.ai.workspace.EquipmentBrowserScreen parent) {
                    var preview = data.getAsJsonObject("preview");
                    Minecraft.getInstance().setScreen(new WorkspaceReviewScreen(parent, "选择伙伴载体", data.get("text").getAsString(), "确认选择", () -> {
                        var args = object("operation", "confirm"); args.add("id", preview.get("id")); args.add("digest", preview.get("digest")); send(AiProtocol.Action.CARRIER_SELECT, args);
                    }));
                } else firefly520.fireflymc.client.ai.workspace.EquipmentBrowserScreen.refresh(0);
            }
            case "settings", "settings_view" -> {
                STATE.preferences(data.getAsJsonObject("preferences"));
                if (Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen screen) {
                    if ("settings".equals(payload.kind()) && pending != null
                            && JsonParser.parseString(pending.payload().body()).getAsJsonObject().has("name")) screen.settingsSaved();
                    screen.preferencesReceived();
                }
            }
            case "control" -> sync();
            case "context", "context_compact" -> {
                if (!data.has("viewVersion") || data.get("viewVersion").getAsInt() != 1) {
                    STATE.error = "上下文视图版本不匹配，请重新打开工作台"; break;
                }
                STATE.detail = data; STATE.error = "";
            }
            case "question_answer" -> { OUTBOX.remove(payload.requestId()); save(); STATE.detail = data; sync(); }
            case "question_view" -> {
                STATE.detail = data;
                if (Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen parent)
                    Minecraft.getInstance().setScreen(new firefly520.fireflymc.client.ai.workspace.TaskQuestionScreen(
                            parent, data.getAsJsonObject("question")));
            }
            case "export_pending" -> {
                EXPORT_JOBS.put(payload.requestId(), System.currentTimeMillis() + 300000);
                STATE.detail = data; STATE.notice = "正在后台准备完整导出";
            }
            case "export" -> { EXPORT_JOBS.remove(payload.requestId()); ExportDownloads.begin(data); }
            case "export_chunk" -> ExportDownloads.accept(data);
            case "approval_preview" -> {
                if (STATE.yoloMode()) { sync(); break; }
                STATE.detail = data;
                if (Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen parent) {
                    JsonObject preview = data.getAsJsonObject("preview");
                    Minecraft.getInstance().setScreen(new WorkspaceReviewScreen(parent, "批准确定的任务操作",
                            data.has("reviewText") ? data.get("reviewText").getAsString()
                                    : data.get("tool").getAsString() + "\n\n" + data.get("arguments").getAsString(),
                            "批准此操作", () -> {
                        JsonObject args = object("taskId", preview.get("taskId").getAsString());
                        args.addProperty("action", "approve");
                        args.addProperty("callDigest", preview.get("callDigest").getAsString());
                        args.addProperty("approvalToken", preview.get("token").getAsString());
                        send(AiProtocol.Action.CONTROL, args);
                    }));
                }
            }
            case "share_preview" -> {
                STATE.detail = data;
                if (Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen parent) {
                    JsonObject preview = data.getAsJsonObject("preview");
                    Minecraft.getInstance().setScreen(new WorkspaceReviewScreen(parent, "确认公开分享这一条",
                            "受众：" + preview.get("audience").getAsString() + "\n\n"
                                    + preview.get("publicText").getAsString(),
                            "确认发布", () -> {
                        APPROVED_SHARES.put(preview.get("id").getAsString(), shareConsent(preview));
                        JsonObject args = object("previewId", preview.get("id").getAsString());
                        args.addProperty("contentDigest", preview.get("contentDigest").getAsString());
                        args.addProperty("target", preview.get("target").getAsString());
                        send(AiProtocol.Action.SHARE_CONFIRM, args);
                    }));
                }
            }
            case "privacy_preview" -> {
                STATE.detail = data;
                if (Minecraft.getInstance().screen instanceof CompanionWorkspaceScreen parent) {
                    var preview = data.getAsJsonObject("preview");
                    Minecraft.getInstance().setScreen(new WorkspaceReviewScreen(parent, "删除本人的 AI 私人数据",
                            "将删除 " + preview.get("messages").getAsInt() + " 条消息及 "
                                    + preview.get("artifacts").getAsInt() + " 个产物/记忆/设置文件。\n\n"
                                    + "服务器中的方块和物品不会因此撤销。无法恢复已删除的记录。\n"
                                    + "工作区身份、永久主会话 ID 和防重放标记保留；无关旧文件不在删除范围内。",
                            "确认删除", () -> send(AiProtocol.Action.PRIVACY_CONFIRM,
                                    object("token", preview.get("token").getAsString()))));
                }
            }
            case "privacy_confirm" -> {
                firefly520.fireflymc.client.ai.integration.map.MapViewBridge.disconnected();
                mapRouteRequest = null; mapRouteSession = null; mapRouteTask = null; mapRouteDraft = null;
                firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.disconnect();
                STATE.erasePrivateViews(data.get("revision").getAsLong());
                OUTBOX.clear();
                PENDING.clear();
                APPROVED_SHARES.clear();
                DELIVERED_SHARES.clear();
                EXPORT_JOBS.clear();
                save();
                send(AiProtocol.Action.OPEN, new JsonObject());
            }
            case "share_delivery" -> relayConfirmedShare(data);
            case "share_confirm", "share_result", "share_delivery_result" -> {
                STATE.detail = data;
                if (data.has("preview")) {
                    String status = data.getAsJsonObject("preview").get("state").getAsString();
                    STATE.notice = "分享：" + status + ("RELAY_ACCEPTED".equals(status) ? "（跨服通道已接收，外部送达未确认）" : "");
                }
            }
            case "error" -> {
                EXPORT_JOBS.remove(payload.requestId());
                STATE.error = data.get("code").getAsString();
                if (pending != null && pending.payload().action() == AiProtocol.Action.MESSAGE) {
                    var original = JsonParser.parseString(pending.payload().body()).getAsJsonObject();
                    if (original.has("inline")) STATE.messageReadFailed(original.get("seq").getAsLong());
                }
                if (pending != null && pending.payload().action().name().startsWith("RECOVERY_")
                        && Minecraft.getInstance().screen instanceof RecoveryScreen screen) screen.requestFailed(payload.requestId(), STATE.error);
                if (pending != null && pending.payload().action().name().startsWith("BLUEPRINT_"))
                    firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.failedIfCurrent(STATE.error, JsonParser.parseString(pending.payload().body()).getAsJsonObject());
                if (pending != null && (pending.payload().action() == AiProtocol.Action.EXPORT
                        || pending.payload().action() == AiProtocol.Action.EXPORT_CHUNK)) ExportDownloads.failed();
                if (pending != null && Set.of(AiProtocol.Action.SUBMIT, AiProtocol.Action.QUESTION_ANSWER).contains(pending.payload().action())) {
                    var text = OUTBOX.remove(payload.requestId());
                    if (text != null && STATE.draft.isEmpty() && !"REQUEST_PREVIOUSLY_ERASED".equals(STATE.error)) STATE.draft = text.text();
                    save();
                }
            }
            default -> STATE.detail = data;
        }
        STATE.uiVersion++;
    }
    private static Set<String> actions(JsonObject data) {
        if (!data.has("actions") || !data.get("actions").isJsonArray()) return Set.of();
        return data.getAsJsonArray("actions").asList().stream().limit(128).map(JsonElement::getAsString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static void relayConfirmedShare(JsonObject data) {
        JsonObject preview = data.getAsJsonObject("preview");
        String id = preview.get("id").getAsString();
        String text = preview.get("publicText").getAsString();
        String digest = preview.get("contentDigest").getAsString();
        if (!preview.has("endpointHash") || !preview.get("endpointHash").getAsString().equals(
                firefly520.fireflymc.ai.storage.AiJson.digest(firefly520.fireflymc.Config.CLIENT.EVENT_NOTIFICATION_URL.get()))) {
            JsonObject rejected = object("previewId", id);
            rejected.addProperty("deliveryToken", data.get("deliveryToken").getAsString());
            rejected.addProperty("accepted", false);
            send(AiProtocol.Action.SHARE_DELIVERY_RESULT, rejected);
            return;
        }
        if (!shareConsent(preview).equals(APPROVED_SHARES.get(id)) || System.currentTimeMillis() >= preview.get("expiresAt").getAsLong()
                || !digest.equals(firefly520.fireflymc.ai.storage.AiJson.digest(text))
                || !DELIVERED_SHARES.add(id)) return;
        var mc = Minecraft.getInstance();
        var message = ClientEventNotificationMessage.crossChatRelay(mc, "小萤分享", text);
        UUID session = STATE.session;
        ClientEventWebSocketClient.getInstance().sendConfirmedShare(message, preview.get("endpointHash").getAsString()).whenComplete((accepted, error) ->
                mc.execute(() -> {
                    if (!session.equals(STATE.session)) return;
                    JsonObject reply = object("previewId", id);
                    reply.addProperty("deliveryToken", data.get("deliveryToken").getAsString());
                    reply.addProperty("accepted", error == null && Boolean.TRUE.equals(accepted));
                    send(AiProtocol.Action.SHARE_DELIVERY_RESULT, reply);
                }));
    }
    private static String shareConsent(JsonObject preview) {
        JsonObject immutable = new JsonObject();
        for (String key : List.of("id", "owner", "conversationId", "messageId", "messageRevision", "sourceDigest", "contentDigest",
                "target", "audience", "publicText", "truncated", "expiresAt", "channelBinding", "endpointHash"))
            immutable.add(key, preview.get(key));
        return firefly520.fireflymc.ai.storage.AiJson.digest(immutable.toString());
    }
    private static void loadPreferences() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || STATE.serverId.isEmpty()) return;
        String origin = mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (origin.isBlank()) return;
        Path path = AiClientPreferences.path(mc.gameDirectory.toPath(), origin + "|" + STATE.serverId, mc.player.getUUID());
        if (path.equals(preferencesPath)) { ExportDownloads.restore(); return; }
        preferencesPath = path;
        display = AiClientPreferences.load(path);
        STATE.draft = display.draft();
        OUTBOX.clear();
        APPROVED_SHARES.clear();
        DELIVERED_SHARES.clear();
        EXPORT_JOBS.clear();
        mapRouteRequest = null; mapRouteSession = null; mapRouteTask = null; mapRouteDraft = null;
        display.outbox().forEach(o -> OUTBOX.put(o.requestId(), o));
        ExportDownloads.restore();
    }
    public static void save() {
        if (preferencesPath == null) return;
        Path path = preferencesPath;
        var data = display.withDraft(STATE.draft, List.copyOf(OUTBOX.values()));
        FILES.execute(() -> {
            try { AiClientPreferences.save(path, data); }
            catch (Exception e) { /* Never log private drafts. The current in-memory draft remains available. */ }
        });
    }
    public static void disconnected() {
        firefly520.fireflymc.client.ai.integration.map.MapViewBridge.disconnected();
        firefly520.fireflymc.client.ai.blueprint.BlueprintClientController.INSTANCE.disconnect();
        firefly520.fireflymc.client.ai.integration.recipe.RecipeViewBridge.INSTANCE.disconnect();
        ExportDownloads.disconnected();
        save();
        STATE.clearPrivate();
        PENDING.clear();
        OUTBOX.clear();
        APPROVED_SHARES.clear();
        DELIVERED_SHARES.clear();
        EXPORT_JOBS.clear();
        preferencesPath = null;
        ticks = 0;
        openRequested = false;
        pendingNotice = "";
        lastNoticeAt = 0;
        display = AiClientPreferences.Data.defaults();
    }
    public static JsonObject object(String key, String value) { var data = new JsonObject(); data.addProperty(key, value); return data; }
}
