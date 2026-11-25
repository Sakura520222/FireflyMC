package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Private recovery for actual journals. Only the server constructs execution previews. */
public final class RecoveryScreen extends Screen {
    private static final List<String> ACTIONS = List.of("MATERIAL_RECONCILE", "MIXED_RECONCILE", "MATERIAL_RETURN", "MIXED_RETURN", "MIXED_RESUME", "TRAVEL_RECONCILE", "MAID_RECONCILE", "COMPASS_RECONCILE", "MAID_TRANSPORT_RECONCILE", "BUILD_RECONCILE", "TREE_RECONCILE");
    private final Screen parent;
    private final String taskId;
    private final UUID session;
    private final Map<String, Button> actionButtons = new LinkedHashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private JsonObject data = new JsonObject();
    private UUID viewRequest, previewRequest, confirmRequest;
    private String selectedOperation = "", selectedSource = "", previewOperation = "", previewAction = "";
    private String status = "正在读取本人的物料记录…";
    private int offset, scroll, contentHeight;
    private int actionRows = 1;
    private boolean dragging;
    private double grab;
    private Button sourceButton, nextButton;
    private record Hit(int top, int bottom, String operationId) {}

    public RecoveryScreen(Screen parent, String taskId) {
        super(Component.literal("核对任务操作"));
        this.parent = parent;
        this.taskId = taskId;
        this.session = AiClientNetwork.STATE.session;
    }

    @Override protected void init() {
        dragging = false;
        int cell = (width - 48) / 3;
        button("刷新本页", 20, 36, cell, () -> refresh(offset));
        button("首页", 24 + cell, 36, cell, () -> refresh(0));
        nextButton = button("下一页", 28 + cell * 2, 36, cell, () -> {
            int next = number(data, "nextOffset");
            if (next > offset && next < number(data, "total")) refresh(next);
        });
        sourceButton = button("流体目标：原记录", 20, 62, width - 40, this::cycleSource);
        int actionWidth = (width - 40 - (ACTIONS.size() - 1) * 4) / ACTIONS.size();
        actionButtons.clear();
        for (int i = 0; i < ACTIONS.size(); i++) {
            String action = ACTIONS.get(i);
            actionButtons.put(action, button(actionLabel(action), 20 + i * (actionWidth + 4), height - 58,
                    actionWidth, () -> preview(action)));
        }
        button("返回工作台", width - 130, height - 32, 110, this::onClose);
        updateButtons();
        refresh(offset);
    }

    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run())
                .bounds(x, y, Math.max(24, width), 20).build());
    }

    private boolean bound() {
        return AiClientNetwork.STATE.ready() && session.equals(AiClientNetwork.STATE.session);
    }

    private boolean supports(AiProtocol.Action action) {
        return AiClientNetwork.STATE.supportedActions.contains(action.name());
    }

    private void refresh(int nextOffset) {
        if (!bound()) return;
        if (!supports(AiProtocol.Action.RECOVERY_VIEW)) { status = "此服务器尚未提供物料恢复能力。"; return; }
        offset = Math.max(0, nextOffset);
        JsonObject args = AiClientNetwork.object("taskId", taskId);
        args.addProperty("offset", offset);
        viewRequest = AiClientNetwork.send(AiProtocol.Action.RECOVERY_VIEW, args);
    }

    public void receive(String kind, UUID requestId, JsonObject value) {
        if (!bound()) return;
        if ("recovery_view".equals(kind) && requestId.equals(viewRequest) && taskId.equals(string(value, "taskId"))) {
            viewRequest = null;
            data = value.deepCopy();
            if (operations().stream().noneMatch(operation -> selectedOperation.equals(string(operation, "operationId")))) selectedOperation = "";
            if (!selectedSource.isEmpty() && sources().stream().noneMatch(source -> selectedSource.equals(string(source, "id")))) {
                selectedSource = "";
                status = "先前选择的退回来源已不可用，已改回原来源；请重新核对预览。";
            }
            scroll = 0;
            if (confirmRequest == null && previewRequest == null && status.startsWith("正在读取")) status = "选择记录查看原因，再预览可用操作。";
            updateButtons();
        } else if ("recovery_preview".equals(kind) && requestId.equals(previewRequest)) {
            previewRequest = null;
            if (!taskId.equals(string(value, "taskId")) || !previewOperation.equals(selectedOperation) || !previewOperation.equals(string(value, "operationId"))
                    || !previewAction.equals(string(value, "action")) || string(value, "token").isEmpty() || string(value, "digest").isEmpty()) {
                status = "预览与所选记录不一致，请刷新后重试。";
                updateButtons();
                return;
            }
            String token = string(value, "token"), digest = string(value, "digest");
            long expiresAt = value.has("expiresAt") ? value.get("expiresAt").getAsLong() : 0;
            status = "请核对完整预览；取消只关闭预览。";
            updateButtons();
            minecraft.setScreen(new WorkspaceReviewScreen(this,
                    value.has("title") ? string(value, "title") : "确认物料恢复操作", string(value, "text"), "确认此操作", () -> {
                if (!bound() || !supports(AiProtocol.Action.RECOVERY_CONFIRM) || expiresAt <= System.currentTimeMillis()) {
                    status = "预览已失效，请重新预览。";
                    return;
                }
                JsonObject args = AiClientNetwork.object("token", token);
                args.addProperty("digest", digest);
                confirmRequest = AiClientNetwork.send(AiProtocol.Action.RECOVERY_CONFIRM, args);
                status = "正在提交确认，尚未确认执行结果。";
                updateButtons();
            }));
        } else if ("recovery_confirm".equals(kind) && requestId.equals(confirmRequest)) {
            confirmRequest = null;
            status = "QUEUED".equals(string(value, "status"))
                    ? "已排队，执行结果尚未确认；请等待私人任务更新或刷新本页。"
                    : "服务器已受理请求，请刷新查看实际物料状态。";
            AiClientNetwork.STATE.notice = status;
            updateButtons();
            refresh(offset);
        }
    }

    public void requestFailed(UUID requestId, String code) {
        if (!requestId.equals(viewRequest) && !requestId.equals(previewRequest) && !requestId.equals(confirmRequest)) return;
        if (requestId.equals(viewRequest)) viewRequest = null;
        if (requestId.equals(previewRequest)) previewRequest = null;
        if (requestId.equals(confirmRequest)) confirmRequest = null;
        status = switch (code) {
            case "REQUEST_TIMEOUT" -> "请求尚未确认；请刷新核对实际状态，避免重复确认。";
            case "RECOVERY_PREVIEW_EXPIRED", "PREVIEW_EXPIRED", "INVALID_TOKEN", "RECOVERY_TOKEN_INVALID" -> "预览已过期或失效，请刷新后重新预览。";
            case "RECOVERY_BUSY", "RESOURCE_BUSY", "WORKSPACE_BUSY" -> "该记录正在处理中，请稍后刷新。";
            case "NOT_SUPPORTED", "UNSUPPORTED_ACTION" -> "此服务器尚未提供物料恢复能力。";
            default -> "服务器未接受本次操作；请刷新核对记录与可用动作。";
        };
        updateButtons();
    }

    public void changed(JsonObject change) {
        if (bound() && taskId.equals(string(change, "taskId"))) refresh(offset);
    }

    private List<JsonObject> operations() { return objects(data, "operations"); }
    private List<JsonObject> sources() {
        return objects(data, "targetSources").stream().filter(source -> !source.has("revoked") || !source.get("revoked").getAsBoolean()).toList();
    }
    private Optional<JsonObject> selected() {
        return operations().stream().filter(operation -> selectedOperation.equals(string(operation, "operationId"))).findFirst();
    }
    private static List<JsonObject> objects(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return List.of();
        return object.getAsJsonArray(key).asList().stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).toList();
    }
    private boolean offers(JsonObject operation, String action) {
        if (!Set.of("ITEMS", "MIXED", "TRAVEL", "MAID", "MAID_CONTROL", "COMPASS", "EXPLORATION").contains(string(operation, "kind")) || !operation.has("actions")) return false;
        return operation.getAsJsonArray("actions").asList().stream().anyMatch(value -> value.isJsonPrimitive() && action.equals(value.getAsString()));
    }
    private void updateButtons() {
        var operation = selected();
        actionButtons.forEach((action, button) -> {
            button.visible = operation.isPresent() && offers(operation.get(), action);
            button.active = button.visible && previewRequest == null && confirmRequest == null && bound()
                    && supports(AiProtocol.Action.RECOVERY_PREVIEW) && supports(AiProtocol.Action.RECOVERY_CONFIRM);
        });
        var visible = actionButtons.values().stream().filter(button -> button.visible).toList();
        int columns = Math.max(1, Math.min(4, visible.size())); actionRows = Math.max(1, (visible.size() + columns - 1) / columns);
        int actionWidth = Math.max(24, (width - 40 - (columns - 1) * 4) / columns);
        for (int index = 0; index < visible.size(); index++) {
            var button = visible.get(index); button.setX(20 + index % columns * (actionWidth + 4));
            button.setY(height - 58 - (actionRows - 1) * 24 + index / columns * 24); button.setWidth(actionWidth);
        }
        if (nextButton != null) nextButton.active = number(data, "nextOffset") > offset && number(data, "nextOffset") < number(data, "total");
        if (sourceButton != null) {
            sourceButton.active = previewRequest == null && confirmRequest == null && !sources().isEmpty();
            sourceButton.setMessage(Component.literal("流体目标：" + font.plainSubstrByWidth(sourceLabel(), Math.max(40, width - 120)) + "  [切换]"));
        }
    }
    private String sourceLabel() {
        return sources().stream().filter(source -> selectedSource.equals(string(source, "id"))).findFirst()
                .map(source -> string(source, "label")).orElse("按原记录分配");
    }
    private void cycleSource() {
        var available = sources();
        int selectedIndex = -1;
        for (int i = 0; i < available.size(); i++) if (selectedSource.equals(string(available.get(i), "id"))) selectedIndex = i;
        selectedSource = selectedIndex + 1 < available.size() ? string(available.get(selectedIndex + 1), "id") : "";
        updateButtons();
    }
    private void preview(String action) {
        var operation = selected();
        if (operation.isEmpty() || !offers(operation.get(), action) || !bound() || previewRequest != null || confirmRequest != null
                || !supports(AiProtocol.Action.RECOVERY_PREVIEW) || !supports(AiProtocol.Action.RECOVERY_CONFIRM)) return;
        JsonObject args = AiClientNetwork.object("taskId", taskId);
        args.addProperty("operationId", selectedOperation);
        args.addProperty("action", action);
        if ((action.equals("MIXED_RETURN") || action.equals("MIXED_RESUME")) && !selectedSource.isEmpty()) args.addProperty("targetSourceId", selectedSource);
        previewOperation = selectedOperation;
        previewAction = action;
        status = "正在生成完整预览，尚未执行操作。";
        previewRequest = AiClientNetwork.send(AiProtocol.Action.RECOVERY_PREVIEW, args);
        updateButtons();
    }

    @Override public void tick() {
        if (!bound()) minecraft.setScreen(null);
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf0141d2a);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xffe8cb8b);
        graphics.enableScissor(20, 90, width - 20, bottom());
        hits.clear();
        int y = text(graphics, "仅显示此任务本人的记录。关闭页面不会取消后台处理。\n流体目标：" + sourceLabel(), 0);
        y = text(graphics, status, y);
        if (data.has("total")) y = text(graphics, "记录 " + (operations().isEmpty() ? 0 : offset + 1) + "–" + (offset + operations().size()) + " / " + number(data, "total"), y);
        if (data.has("operations") && operations().isEmpty()) y = text(graphics, "此任务没有可显示的物料恢复记录。", y);
        int index = offset;
        for (var operation : operations()) {
            int top = y;
            String id = string(operation, "operationId");
            boolean selected = id.equals(selectedOperation);
            y = text(graphics, (selected ? "▶ " : "○ ") + "记录 " + (++index) + " · " + kindLabel(string(operation, "kind"))
                    + "\n" + (operation.has("stateText") ? string(operation, "stateText") : stateLabel(string(operation, "state"))) + "\n" + reasonLabel(operation), y);
            if (selected && ACTIONS.stream().noneMatch(action -> offers(operation, action))) y = text(graphics, "当前记录只读；服务端未提供可确认的恢复操作。", y);
            hits.add(new Hit(top, y, id));
        }
        contentHeight = y;
        graphics.disableScissor();
        var bar = bar();
        scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) {
            graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 16, bar.thumbTop(), width - 10, bar.thumbTop() + bar.thumbHeight(), 0xffd3b372);
        }
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int text(GuiGraphics graphics, String text, int y) {
        for (var component : SafeRichText.lines(text)) for (var line : font.split(component, Math.max(60, width - 64))) {
            int screenY = 94 + y - scroll;
            if (screenY >= 82 && screenY < bottom()) graphics.drawString(font, line, 24, screenY, 0xffdce5ef, false);
            y += 12;
        }
        return y + 8;
    }
    private int bottom() { return Math.max(91, height - 64 - (actionRows - 1) * 24); }
    private ScrollGeometry bar() { return ScrollGeometry.of(90, Math.max(1, bottom() - 90), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && bar.visible() && x >= width - 18 && x <= width - 8 && y >= bar.top() && y < bar.top() + bar.height()) {
            dragging = true;
            grab = y >= bar.thumbTop() && y <= bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab);
            return true;
        }
        if (button == 0 && previewRequest == null && confirmRequest == null && x >= 20 && x < width - 20 && y >= 90 && y < bottom()) for (var hit : hits) {
            if (y - 94 + scroll >= hit.top() && y - 94 + scroll < hit.bottom()) {
                selectedOperation = hit.operationId();
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) { scroll = bar().drag(y, grab); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int) (vertical * 30), 0, bar().maximum());
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(bound() ? parent : null); }

    private static String actionLabel(String action) {
        return switch (action) {
            case "MATERIAL_RECONCILE" -> "核对物品"; case "MIXED_RECONCILE" -> "核对物料";
            case "MATERIAL_RETURN" -> "退回物品"; case "MIXED_RETURN" -> "退回物料";
            case "MIXED_RESUME" -> "继续加工"; case "TRAVEL_RECONCILE" -> "核对旅行"; case "MAID_RECONCILE" -> "核对女仆";
            case "COMPASS_RECONCILE" -> "核对搜索"; case "MAID_TRANSPORT_RECONCILE" -> "核对搬运"; case "BUILD_RECONCILE" -> "核对施工"; case "TREE_RECONCILE" -> "核对采集"; default -> "查看";
        };
    }
    private static String kindLabel(String kind) {
        return switch (kind) { case "ITEMS" -> "物品物料"; case "MIXED" -> "物品与流体"; case "BUILD" -> "施工";
            case "REMOVAL" -> "拆除"; case "TRAVEL" -> "指路石原生操作"; case "MAID", "MAID_CONTROL" -> "女仆控制";
            case "COMPASS", "EXPLORATION" -> "原生罗盘搜索"; default -> "待核对记录"; };
    }
    private static String stateLabel(String state) {
        return switch (state) {
            case "RECOVERY_REQUIRED" -> "需要核对"; case "RESERVED" -> "已预留"; case "ESCROWED" -> "托管中";
            case "COMMITTED", "COMPLETED" -> "已记录完成"; case "RETURNED", "RELEASED" -> "已退回或释放";
            case "QUEUED" -> "已排队"; case "RUNNING" -> "执行中"; case "RETURNING" -> "退回处理中";
            case "SAVED" -> "原生回执已保存"; case "PREPARED" -> "临时目标准备就绪（尚未旅行）";
            case "RECONCILED_CURRENT_STATE" -> "当前状态已核对，原计划需重规划"; case "FAILED" -> "实际原生操作失败";
            default -> "以服务端记录为准";
        };
    }
    private static String reasonLabel(JsonObject operation) {
        if (operation.has("reasonText") && !string(operation, "reasonText").isBlank()) return string(operation, "reasonText");
        return switch (string(operation, "reason")) {
            case "RESTART_UNCERTAIN", "SERVER_RESTART" -> "重启后的物料归属和保存证据需要重新核对。";
            case "WORLD_SAVE_UNCONFIRMED", "NATIVE_SAVE_UNCONFIRMED", "SAVE_UNCONFIRMED" -> "原生保存尚未证实，当前记录仍受恢复保护。";
            case "SOURCE_REVOKED", "PERMISSION_CHANGED" -> "来源授权或访问权限已变化，需要服务端重新核验。";
            case "" -> "请按服务端提供的可用动作预览；记录不会因刷新而清除。";
            default -> "执行或保存证据仍需核对；仅能使用服务端提供的恢复动作。";
        };
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
    private static int number(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }
}
