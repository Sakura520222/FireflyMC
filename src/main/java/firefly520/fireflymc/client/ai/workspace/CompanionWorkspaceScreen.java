package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** The sole interactive AI surface. Closing it only preserves the draft; it never cancels server work. */
public final class CompanionWorkspaceScreen extends Screen {
    private enum Tab { CHAT, BOARD, ARTIFACTS, MEMORY, MATERIALS, SETTINGS }
    private static final int BG = 0xee111925, PANEL = 0xff1b2738, BORDER = 0xff39475b;
    private Tab tab = Tab.CHAT;
    private MultiLineEditBox input;
    private Button sendButton;
    private Button yoloButton, approvalButton;
    private WorkspaceComposerLayout composer;
    private QuestionReplyTarget replyTarget;
    private boolean sendAsNewMessage;
    private boolean replyChosenExplicitly;
    private EditBox name;
    private MultiLineEditBox personality;
    private String selectedTask = "", selectedNote = "";
    private String selectedSource = "";
    private long selectedMessage = -1;
    private long focusSequence = -1;
    private int scroll;
    private final ChatScrollAnchor chatScroll = new ChatScrollAnchor();
    private CompactButtonFlow topButtons;
    private int contentX, contentY, contentWidth, contentBottom;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, List<SafeRichText.Line>> wrapped = new LinkedHashMap<>(32, .75f, true);
    private boolean draggingScrollbar;
    private double scrollbarGrab;
    private long layoutVersion = -1;
    private int layoutHeight, wrapWidth;
    private boolean recordDeaths, recordAdvancements;
    private boolean nameDirty, personalityDirty, eventsDirty, applyingSettings, settingsLoaded;
    private record Row(int y, int height, String id, long seq, String text, String role, List<SafeRichText.Line> lines) {}

    public CompanionWorkspaceScreen() {
        super(Component.literal("小萤工作台"));
    }
    @Override protected void init() {
        layoutVersion = -1;
        draggingScrollbar = false;
        int margin = width < 440 ? 8 : 20;
        contentX = Math.min(margin + 10, Math.max(0, width / 8));
        contentWidth = Math.max(1, width - contentX * 2);
        composer = WorkspaceComposerLayout.of(contentX, contentWidth, height,
                Math.max(font.width("发送"), font.width("复制")), tab == Tab.CHAT && !AiClientNetwork.STATE.questions.isEmpty());
        contentBottom = tab == Tab.SETTINGS ? height - 36 : composer.contentBottom();
        topButtons = new CompactButtonFlow(contentX, 26, contentWidth);
        String[] labels = {"对话", "看板", "资料", "记忆", "物料", "设置"};
        for (int i = 0; i < labels.length; i++) {
            final Tab target = Tab.values()[i];
            var navigation = topButton(labels[i], () -> switchTab(target));
            if (target == tab) {
                navigation.setMessage(Component.literal(labels[i]).withStyle(net.minecraft.ChatFormatting.GOLD));
                navigation.setTooltip(Tooltip.create(Component.literal("当前页面：" + labels[i])));
            }
        }
        yoloButton = topButton("YOLO：关", this::toggleYolo);
        yoloButton.setTooltip(Tooltip.create(Component.literal("开启后直接执行，无需逐次授权、确认或批准；再次点击关闭。")));
        topButtons.sectionGap();
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(width - margin - CompactButtonFlow.HEIGHT, 7, CompactButtonFlow.HEIGHT, CompactButtonFlow.HEIGHT).build());
        if (tab == Tab.SETTINGS) {
            setupSettings();
        } else {
            input = addRenderableWidget(new MultiLineEditBox(font, composer.inputX(), composer.inputY(),
                    composer.inputWidth(), composer.inputHeight(), Component.literal(tab == Tab.MEMORY ? "保存一条记忆" : "发给小萤"),
                    Component.literal("主会话输入")));
            input.setCharacterLimit(tab == Tab.MEMORY ? 1024 : 4096);
            input.setValue(AiClientNetwork.STATE.draft);
            input.setValueListener(value -> AiClientNetwork.STATE.draft = value);
            sendButton = addRenderableWidget(Button.builder(Component.literal(tab == Tab.MEMORY ? "记住" : "发送"), b -> submit())
                    .bounds(composer.actionX(), composer.inputY(), composer.actionWidth(), composer.buttonHeight()).build());
            addRenderableWidget(Button.builder(Component.literal("复制"), b -> copySelected())
                    .bounds(composer.actionX(), composer.copyY(), composer.actionWidth(), composer.buttonHeight()).build());
            if (tab == Tab.CHAT) {
                toolbar("回到底部", this::latest);
                approvalButton = topButton("批准操作", this::approvePending);
                toolbar("回复问题", this::selectQuestionReply);
                toolbar("新消息", () -> { replyTarget = null; sendAsNewMessage = true; syncQuestionReply(); });
                toolbar("分享本服", () -> share("SERVER_CHAT"));
                toolbar("分享跨服", () -> share("CROSS_CHAT"));
                toolbar("检索", () -> {
                    String query = input.getValue().strip();
                    if (query.isEmpty() || query.length() > 160) { AiClientNetwork.STATE.error = "在输入框中填写 1–160 字关键词或任务 ID"; return; }
                    AiClientNetwork.send(AiProtocol.Action.SEARCH, AiClientNetwork.object("query", query));
                    switchTab(Tab.ARTIFACTS);
                });
                if (AiClientNetwork.STATE.buildRegionView.has("proposal"))
                    toolbar("建筑预览", () -> minecraft.setScreen(new BuildRegionScreen(this)));
            } else if (tab == Tab.BOARD) {
                toolbar("关联对话", () -> {
                    if (AiClientNetwork.STATE.task(selectedTask).isEmpty()) { AiClientNetwork.STATE.error = "请先选择任务"; return; }
                    AiClientNetwork.taskConversation(selectedTask);
                });
                for (String action : List.of("approve", "deny", "pause", "resume", "cancel")) {
                    String label = switch (action) {
                        case "approve" -> "批准"; case "deny" -> "拒绝"; case "pause" -> "暂停"; case "resume" -> "继续"; default -> "取消";
                    };
                    toolbar(label, () -> control(action));
                }
                toolbar("核对操作", () -> {
                    if (AiClientNetwork.STATE.task(selectedTask).isEmpty()) { AiClientNetwork.STATE.error = "请先选择任务"; return; }
                    minecraft.setScreen(new RecoveryScreen(this, selectedTask));
                });
                toolbar("任务地图", () -> AiClientNetwork.openTaskMap(this, selectedTask));
            } else if (tab == Tab.MATERIALS) {
                toolbar("来源", () -> AiClientNetwork.send(AiProtocol.Action.MATERIALS_VIEW, new JsonObject()));
                toolbar("盘点", () -> {
                    AiClientNetwork.STATE.error = "正在分批盘点真实库存…";
                    AiClientNetwork.send(AiProtocol.Action.MATERIALS_SCAN, new JsonObject());
                });
                toolbar("添加所指",
                        () -> AiClientNetwork.send(AiProtocol.Action.SOURCE_PREVIEW, new JsonObject()));
                toolbar("撤销选中", () -> {
                    if (selectedSource.isEmpty()) { AiClientNetwork.STATE.error = "请先在来源列表中选择容器"; return; }
                    AiClientNetwork.send(AiProtocol.Action.SOURCE_REVOKE, AiClientNetwork.object("id", selectedSource));
                });
                toolbar("下一页", () -> {
                    var view = AiClientNetwork.STATE.materialView;
                    if (!view.has("nextOffset")) return;
                    int offset = view.get("nextOffset").getAsInt();
                    if (offset >= view.get("total").getAsInt()) offset = 0;
                    JsonObject args = new JsonObject(); args.addProperty("offset", offset);
                    if (view.has("snapshotId")) { args.add("snapshotId", view.get("snapshotId")); AiClientNetwork.send(AiProtocol.Action.MATERIALS_SCAN, args); }
                    else AiClientNetwork.send(AiProtocol.Action.MATERIALS_VIEW, args);
                    scroll = 0;
                });
                toolbar("装备与背包", () -> minecraft.setScreen(new EquipmentBrowserScreen(this)));
                toolbar("保留材料", () -> minecraft.setScreen(new MaterialPreferencesScreen(this)));
                toolbar("原料流体", () -> minecraft.setScreen(new FluidSourcesScreen(this)));
            } else if (tab == Tab.MEMORY) {
                toolbar("刷新", () -> AiClientNetwork.send(AiProtocol.Action.MEMORY_VIEW, new JsonObject()));
                toolbar("更新选中", () -> {
                    var selected = AiClientNetwork.STATE.notes.stream().filter(n -> n.get("id").getAsString().equals(selectedNote)).findFirst();
                    if (selected.isEmpty() || input.getValue().isBlank()) { AiClientNetwork.STATE.error = "请选择记忆并在输入框中修改"; return; }
                    var args = AiClientNetwork.object("id", selectedNote);
                    args.addProperty("text", input.getValue()); args.add("expectedUpdatedAt", selected.get().get("updatedAt"));
                    AiClientNetwork.send(AiProtocol.Action.MEMORY_UPDATE, args);
                });
                toolbar("忘记选中", () -> {
                    if (!selectedNote.isEmpty()) AiClientNetwork.send(AiProtocol.Action.MEMORY_FORGET, AiClientNetwork.object("id", selectedNote));
                });
                toolbar("清除记忆", () -> minecraft.setScreen(new ConfirmScreen(
                        confirmed -> { if (confirmed) AiClientNetwork.send(AiProtocol.Action.MEMORY_CLEAR, new JsonObject()); minecraft.setScreen(this); },
                        Component.literal("清除本人长期记忆？"), Component.literal("完整主会话不会被删除。"))));
                toolbar("下一页", () -> {
                    JsonObject args = new JsonObject();
                    args.addProperty("offset", AiClientNetwork.STATE.nextMemoryOffset < AiClientNetwork.STATE.totalMemory
                            ? AiClientNetwork.STATE.nextMemoryOffset : 0);
                    AiClientNetwork.send(AiProtocol.Action.MEMORY_VIEW, args);
                    scroll = 0;
                });
            } else {
                toolbar("上下文", () -> AiClientNetwork.send(AiProtocol.Action.CONTEXT, new JsonObject()));
                if (AiClientNetwork.STATE.supportedActions.contains(AiProtocol.Action.CONTEXT_COMPACT.name()))
                    toolbar("压缩", () -> {
                        var body = new JsonObject(); body.addProperty("viewVersion", 1);
                        AiClientNetwork.send(AiProtocol.Action.CONTEXT_COMPACT, body);
                    });
                toolbar("导出", () -> AiClientNetwork.send(AiProtocol.Action.EXPORT, new JsonObject()));
                toolbar("读取产物", () -> {
                    try { UUID.fromString(input.getValue().trim()); AiClientNetwork.send(AiProtocol.Action.ARTIFACT,
                            AiClientNetwork.object("id", input.getValue().trim())); }
                    catch (IllegalArgumentException e) { AiClientNetwork.STATE.error = "请输入产物 ID"; }
                });
                toolbar("导出记录", () -> AiClientNetwork.send(AiProtocol.Action.EXPORT_LIST, new JsonObject()));
                toolbar("本人事件", () -> AiClientNetwork.send(AiProtocol.Action.EVENTS, new JsonObject()));
                toolbar("清除事件", () -> minecraft.setScreen(new ConfirmScreen(
                        confirmed -> { if (confirmed) AiClientNetwork.send(AiProtocol.Action.EVENTS_CLEAR, new JsonObject()); minecraft.setScreen(this); },
                        Component.literal("清除本人事件记录？"), Component.literal("工作台对话与任务历史保持不变。"))));
                toolbar("下一页", () -> detailPage(false));
                toolbar("首页", () -> detailPage(true));
                toolbar("配方浏览", () -> minecraft.setScreen(new RecipeBrowserScreen(this)));
                toolbar("私人蓝图", () -> minecraft.setScreen(new BlueprintBrowserScreen(this)));
                toolbar("适配状态", () -> minecraft.setScreen(new IntegrationStatusScreen(this)));
                toolbar("施工预览", () -> minecraft.setScreen(new BuildRegionScreen(this)));
                toolbar("地图", () -> AiClientNetwork.openMap(this));
            }
        }
    }
    private void detailPage(boolean first) {
        var detail = AiClientNetwork.STATE.detail;
        if (detail.has("nextBeforeSeq")) {
            long before = first ? Long.MAX_VALUE : detail.get("nextBeforeSeq").getAsLong();
            if (before <= 0) return;
            JsonObject args = new JsonObject(); args.addProperty("beforeSeq", before);
            if (detail.has("query")) { args.add("query", detail.get("query")); AiClientNetwork.send(AiProtocol.Action.SEARCH, args); }
            else AiClientNetwork.send(AiProtocol.Action.EVENTS, args);
            scroll = 0; return;
        }
        if (!detail.has("nextOffset") || !detail.has("length")) return;
        int offset = first ? 0 : detail.get("nextOffset").getAsInt();
        if (!first && offset >= detail.get("length").getAsInt()) return;
        JsonObject args = new JsonObject(); args.addProperty("offset", offset);
        if (detail.has("context")) {
            if (!first && detail.has("viewDigest")) args.add("viewDigest", detail.get("viewDigest"));
            AiClientNetwork.send(AiProtocol.Action.CONTEXT, args); scroll = 0; return;
        }
        if (detail.has("seq")) {
            args.add("seq", detail.get("seq")); AiClientNetwork.send(AiProtocol.Action.MESSAGE, args);
        } else if (detail.has("id")) {
            args.add("id", detail.get("id")); AiClientNetwork.send(AiProtocol.Action.ARTIFACT, args);
        }
        scroll = 0;
    }
    private void button(String text, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, width, 20).build());
    }
    private void toolbar(String text, Runnable action) {
        topButton(text, action);
    }
    private Button topButton(String text, Runnable action) {
        var bounds = topButtons.next(font.width(text));
        var control = addRenderableWidget(Button.builder(Component.literal(text), b -> action.run())
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height()).build());
        control.setTooltip(Tooltip.create(Component.literal(text)));
        contentY = topButtons.bottom() + 5;
        return control;
    }
    private void latest() {
        if (AiClientNetwork.STATE.viewingHistory) AiClientNetwork.latestHistory();
        chatScroll.latest(); scroll = Integer.MAX_VALUE; layoutVersion = -1;
    }
    private void switchTab(Tab target) {
        if (input != null) AiClientNetwork.STATE.draft = input.getValue();
        tab = target;
        input = null;
        scroll = 0;
        if (target == Tab.CHAT && !AiClientNetwork.STATE.viewingHistory) chatScroll.latest();
        rebuildWidgets();
        if (target == Tab.MEMORY) AiClientNetwork.send(AiProtocol.Action.MEMORY_VIEW, new JsonObject());
        if (target == Tab.MATERIALS) AiClientNetwork.send(AiProtocol.Action.MATERIALS_VIEW, new JsonObject());
        if (target == Tab.SETTINGS) AiClientNetwork.send(AiProtocol.Action.SETTINGS_VIEW, new JsonObject());
    }
    public void preferencesReceived() {
        if (tab == Tab.SETTINGS && name != null && personality != null) {
            var preferences = AiClientNetwork.STATE.preferences;
            applyingSettings = true;
            if (!nameDirty) name.setValue(preferences.get("name").getAsString());
            if (!personalityDirty) personality.setValue(preferences.has("personality") ? preferences.get("personality").getAsString() : "");
            applyingSettings = false;
            settingsLoaded = true;
        }
    }
    public void settingsSaved() { nameDirty = false; personalityDirty = false; eventsDirty = false; }
    public boolean showingContext() { return tab == Tab.ARTIFACTS && AiClientNetwork.STATE.detail.has("context"); }
    public void memorySaved(String savedText) {
        if (tab == Tab.MEMORY && input != null && input.getValue().equals(savedText)) input.setValue("");
    }
    private void setupSettings() {
        var preferences = AiClientNetwork.STATE.preferences;
        String nameDraft = nameDirty && name != null ? name.getValue() : null;
        String personalityDraft = personalityDirty && personality != null ? personality.getValue() : null;
        settingsLoaded = settingsLoaded || preferences.has("personality");
        int formTop = contentY + 10;
        int nameLabelWidth = Math.min(64, contentWidth / 3);
        name = addRenderableWidget(new EditBox(font, contentX + nameLabelWidth, formTop, Math.max(1, Math.min(180, contentWidth - nameLabelWidth)), 20,
                Component.literal("伙伴名称")));
        name.setMaxLength(32);
        name.setValue(nameDraft != null ? nameDraft : preferences.has("name") ? preferences.get("name").getAsString() : "小萤");
        name.setResponder(value -> { if (!applyingSettings) nameDirty = true; });
        personality = addRenderableWidget(new MultiLineEditBox(font, contentX, formTop + 38, contentWidth,
                Math.min(64, Math.max(28, height - 260)), Component.literal("称呼、风格与偏好"), Component.literal("伙伴风格")));
        personality.setCharacterLimit(2048);
        personality.setValue(personalityDraft != null ? personalityDraft : preferences.has("personality") ? preferences.get("personality").getAsString() : "");
        personality.setValueListener(value -> { if (!applyingSettings) personalityDirty = true; });
        if (!eventsDirty) {
            recordDeaths = preferences.has("recordDeaths") && preferences.get("recordDeaths").getAsBoolean();
            recordAdvancements = preferences.has("recordAdvancements") && preferences.get("recordAdvancements").getAsBoolean();
        }
        int y = Math.max(personality.getY() + personality.getHeight() + 10, Math.min(188, height - 90));
        addRenderableWidget(Button.builder(Component.literal("本人死亡记录：" + (recordDeaths ? "开" : "关")), b -> {
            recordDeaths = !recordDeaths;
            eventsDirty = true;
            b.setMessage(Component.literal("本人死亡记录：" + (recordDeaths ? "开" : "关")));
        }).bounds(contentX, y, Math.min(146, contentWidth / 2 - 4), 20).build());
        addRenderableWidget(Button.builder(Component.literal("本人进度记录：" + (recordAdvancements ? "开" : "关")), b -> {
            recordAdvancements = !recordAdvancements;
            eventsDirty = true;
            b.setMessage(Component.literal("本人进度记录：" + (recordAdvancements ? "开" : "关")));
        }).bounds(contentX + contentWidth / 2, y, Math.min(146, contentWidth / 2 - 4), 20).build());
        button("保存设置", contentX, height - 31, 76, () -> {
            if (!settingsLoaded) { AiClientNetwork.STATE.error = "正在读取完整设置，请稍候"; return; }
            JsonObject data = new JsonObject();
            data.addProperty("version", 1);
            data.addProperty("name", name.getValue());
            data.addProperty("personality", personality.getValue());
            data.addProperty("recordDeaths", recordDeaths);
            data.addProperty("recordAdvancements", recordAdvancements);
            AiClientNetwork.send(AiProtocol.Action.SETTINGS, data);
            AiClientNetwork.save();
        });
        button("HUD 布局", contentX + 82, height - 31, 72, () -> minecraft.setScreen(new HudLayoutScreen(this)));
        button("隐私删除", contentX + 160, height - 31, 72,
                () -> AiClientNetwork.send(AiProtocol.Action.PRIVACY_PREVIEW, new JsonObject()));
    }
    private void submit() {
        if (input == null || !AiClientNetwork.STATE.ready()) return;
        String value = input.getValue();
        if (value.isBlank()) return;
        if (tab == Tab.MEMORY) {
            AiClientNetwork.send(AiProtocol.Action.MEMORY_REMEMBER, AiClientNetwork.object("text", value));
            return; // Keep the note until the server acknowledges it; an error must not erase input.
        } else {
            syncQuestionReply();
            if (tab == Tab.CHAT && replyTarget != null) {
                if (!replyTarget.current(AiClientNetwork.STATE.questions)) {
                    AiClientNetwork.STATE.error = "此问题已回答或版本变化；请选择当前问题，或点击「新消息」"; return;
                }
                if (!AiClientNetwork.answer(replyTarget, value)) return;
            } else if (!AiClientNetwork.submit(value)) return;
        }
        input.setValue("");
        messageSent();
    }
    public void messageSent() {
        chatScroll.latest();
        if (tab != Tab.CHAT) switchTab(Tab.CHAT);
        scroll = Integer.MAX_VALUE;
    }
    public void focusConversation(String taskId, long sequence) {
        selectedTask = taskId; focusSequence = sequence; selectedMessage = sequence;
        chatScroll.history();
        if (tab != Tab.CHAT) switchTab(Tab.CHAT);
        layoutVersion = -1;
    }
    private void selectQuestionReply() {
        var questions = AiClientNetwork.STATE.questions;
        if (questions.isEmpty()) { AiClientNetwork.STATE.error = "当前没有待回答的问题"; return; }
        int next = 0;
        if (replyTarget != null) for (int i = 0; i < questions.size(); i++)
            if (questions.get(i).get("id").getAsString().equals(replyTarget.id().toString())) next = (i + 1) % questions.size();
        if (!questions.get(next).has("digest")) {
            AiClientNetwork.send(AiProtocol.Action.QUESTION_VIEW, AiClientNetwork.object("id", questions.get(next).get("id").getAsString())); return;
        }
        replyTarget = QuestionReplyTarget.from(questions.get(next)); sendAsNewMessage = false; replyChosenExplicitly = true;
        AiClientNetwork.STATE.error = ""; syncQuestionReply();
    }
    private void syncQuestionReply() {
        if (tab != Tab.CHAT) return;
        if (replyTarget != null && !replyTarget.current(AiClientNetwork.STATE.questions)
                && input != null && input.getValue().isBlank()) { replyTarget = null; replyChosenExplicitly = false; }
        if (!sendAsNewMessage && !replyChosenExplicitly && input != null && input.getValue().isBlank())
            replyTarget = QuestionReplyTarget.onlyPending(AiClientNetwork.STATE.questions).orElse(null);
        if (replyTarget == null && !sendAsNewMessage)
            replyTarget = QuestionReplyTarget.onlyPending(AiClientNetwork.STATE.questions).orElse(null);
        if (sendButton != null) sendButton.setMessage(Component.literal(replyTarget == null ? "发送" : "回答"));
    }
    private void control(String action) {
        var selected = AiClientNetwork.STATE.task(selectedTask);
        if (selected.isEmpty()) { AiClientNetwork.STATE.error = "请先选择任务"; return; }
        var task = selected.get();
        JsonObject args = AiClientNetwork.object("taskId", selectedTask);
        args.addProperty("action", action);
        if (task.has("callDigest")) args.addProperty("callDigest", task.get("callDigest").getAsString());
        if ("approve".equals(action)) {
            AiClientNetwork.send(AiProtocol.Action.APPROVAL_PREVIEW, AiClientNetwork.object("taskId", selectedTask));
        } else AiClientNetwork.send(AiProtocol.Action.CONTROL, args);
    }
    private void toggleYolo() {
        var args = new JsonObject(); args.addProperty("yoloMode", !AiClientNetwork.STATE.yoloMode());
        AiClientNetwork.send(AiProtocol.Action.SETTINGS, args);
    }
    private void approvePending() {
        var pending = AiClientNetwork.STATE.task(selectedTask)
                .filter(task -> "WAITING_APPROVAL".equals(task.get("state").getAsString()))
                .or(() -> AiClientNetwork.STATE.tasks.stream().filter(task -> "WAITING_APPROVAL".equals(task.get("state").getAsString())).findFirst());
        if (pending.isEmpty()) { AiClientNetwork.STATE.error = "当前没有待批准操作"; return; }
        selectedTask = pending.get().get("taskId").getAsString(); control("approve");
    }
    private void share(String target) {
        var event = AiClientNetwork.STATE.events.get(selectedMessage);
        if (event == null || !event.has("role") || !"assistant".equals(event.get("role").getAsString())) {
            AiClientNetwork.STATE.error = "请选择一条小萤回答"; return;
        }
        if (!AiClientNetwork.STATE.shareTargets.contains(target)) {
            AiClientNetwork.STATE.error = "该分享频道当前不可用"; return;
        }
        JsonObject args = AiClientNetwork.object("messageId", event.get("messageId").getAsString());
        args.addProperty("target", target);
        AiClientNetwork.send(AiProtocol.Action.SHARE_PREVIEW, args);
    }
    private void copySelected() {
        if (minecraft == null) return;
        String text = rows.stream().filter(r -> r.seq == selectedMessage || !selectedTask.isEmpty() && r.id.equals(selectedTask)
                || !selectedNote.isEmpty() && r.id.equals(selectedNote)).findFirst().map(Row::text)
                .orElseGet(() -> AiClientNetwork.STATE.detail.toString());
        minecraft.keyboardHandler.setClipboard(text);
    }
    @Override public void tick() {
        if (!AiClientNetwork.STATE.ready()) { onClose(); return; }
        if (input != null && input.getValue().isEmpty() && !AiClientNetwork.STATE.draft.isEmpty())
            input.setValue(AiClientNetwork.STATE.draft);
        syncQuestionReply();
        if (tab != Tab.SETTINGS) {
            composer = WorkspaceComposerLayout.of(contentX, contentWidth, height,
                    Math.max(font.width("发送"), font.width("复制")), tab == Tab.CHAT && (replyTarget != null
                    || AiClientNetwork.STATE.questions.size() > 1 && !sendAsNewMessage));
            contentBottom = composer.contentBottom();
        }
        boolean automatic = AiClientNetwork.STATE.yoloMode();
        if (yoloButton != null) yoloButton.setMessage(Component.literal("YOLO：" + (automatic ? "开" : "关"))
                .withStyle(automatic ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY));
        if (approvalButton != null) {
            long count = AiClientNetwork.STATE.tasks.stream().filter(task -> "WAITING_APPROVAL".equals(task.get("state").getAsString())).count();
            approvalButton.active = !automatic && count > 0;
            approvalButton.setTooltip(Tooltip.create(Component.literal(count > 0 ? "待批准操作：" + count + "；点击直接查看并批准" : "当前没有待批准操作")));
        }
        if (tab == Tab.CHAT && !chatScroll.following() && !draggingScrollbar && scroll <= 48)
            AiClientNetwork.earlierHistory();
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BG);
        graphics.drawString(font, font.plainSubstrByWidth(AiClientNetwork.STATE.name + "  /  私人工作台", Math.max(1, contentWidth - 24)),
                contentX, 12, 0xfff0cc88, false);
        if (tab == Tab.SETTINGS) {
            graphics.drawString(font, "名称", contentX, name.getY() + 6, 0xffd8e2ec, false);
            graphics.drawString(font, "风格", contentX, personality.getY() - 12, 0xffd8e2ec, false);
        } else {
            renderRows(graphics);
            if (tab == Tab.CHAT && replyTarget != null)
                graphics.drawString(font, font.plainSubstrByWidth("回复问题：" + replyTarget.prompt() + "  · 点击「新消息」可退出回答", contentWidth),
                        contentX, composer.hintY(), 0xffedcf8c, false);
            else if (tab == Tab.CHAT && AiClientNetwork.STATE.questions.size() > 1 && !sendAsNewMessage)
                graphics.drawString(font, "有多个待答问题，请点击「回复问题」选择，或发送新消息", contentX, composer.hintY(), 0xffedcf8c, false);
        }
        String status = AiClientNetwork.STATE.error.isBlank()
                ? (AiClientNetwork.STATE.conversationId.isEmpty() ? "正在加载对话…" : "Ctrl+Enter 发送 · 关闭后继续执行")
                : AiClientNetwork.STATE.error;
        graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(1, contentWidth - font.width("4096/4096") - 8)), contentX, height - 12,
                AiClientNetwork.STATE.error.isBlank() ? 0xff889bac : 0xffffb0a0, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    private void renderRows(GuiGraphics graphics) {
        if (layoutVersion != AiClientNetwork.STATE.uiVersion) rebuildRows();
        int maximum = Math.max(0, layoutHeight - (contentBottom - contentY));
        scroll = tab == Tab.CHAT && !draggingScrollbar ? chatScroll.layout(scroll, maximum) : Math.clamp(scroll, 0, maximum);
        graphics.enableScissor(contentX, contentY, contentX + contentWidth, contentBottom);
        int low = 0, high = rows.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rows.get(mid).y + rows.get(mid).height < scroll) low = mid + 1; else high = mid;
        }
        for (int index = low; index < rows.size(); index++) {
            Row row = rows.get(index);
            int top = contentY + row.y - scroll;
            if (top > contentBottom) break;
            boolean selected = row.seq == selectedMessage || !row.id.isEmpty()
                    && (row.id.equals(selectedTask) || row.id.equals(selectedNote) || row.id.equals(selectedSource));
            graphics.fill(contentX, top, contentX + contentWidth, top + row.height - 5, PANEL);
            if (selected) graphics.renderOutline(contentX, top, contentWidth, row.height - 5, 0xffdbbd7c);
            String label = switch (row.role) {
                case "user" -> "你"; case "assistant" -> AiClientNetwork.STATE.name; case "tool" -> "工具结果 · 点击选择复制";
                case "task" -> "任务 " + row.id.substring(0, 8); case "memory" -> "本人记忆";
                case "question" -> AiClientNetwork.STATE.questions.stream().anyMatch(q -> q.get("id").getAsString().equals(row.id))
                        ? "待回答 · 点击查看" : "任务问题记录 · 点击查看";
                case "notice" -> "工作台通知"; default -> "资料";
            };
            graphics.drawString(font, label, contentX + 8, top + 6, 0xffa8d4c3, false);
            int lineY = top + 20;
            int first = Math.max(0, (contentY - lineY) / 11);
            for (int i = first; i < row.lines.size() && lineY + i * 11 < contentBottom; i++) {
                var line = row.lines.get(i); int at = lineY + i * 11;
                graphics.drawString(font, line.text(), contentX + 8 + line.inset(), at, 0xffdbe3ef, false);
                if (!line.itemId().isEmpty()) {
                    var id = net.minecraft.resources.ResourceLocation.tryParse(line.itemId());
                    var item = id == null ? Optional.<net.minecraft.world.item.Item>empty() : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
                    if (item.isPresent() && item.get() != net.minecraft.world.item.Items.AIR) {
                        var icon = new net.minecraft.world.item.ItemStack(item.get());
                        graphics.renderItem(icon, contentX + 8, at);
                        graphics.renderItemDecorations(font, icon, contentX + 8, at, line.itemCount() > 99 ? "99+" : Integer.toString(line.itemCount()));
                    } else graphics.drawString(font, "?", contentX + 12, at, 0xffffb0a0, false);
                }
                if (line.total() > 0) {
                    int barWidth = Math.max(20, Math.min(220, contentWidth - 32));
                    graphics.fill(contentX + 8, at + 1, contentX + 8 + barWidth, at + 6, 0xff0d1825);
                    int fill = (int) (line.current() * barWidth / line.total());
                    graphics.fill(contentX + 8, at + 1, contentX + 8 + fill, at + 6, 0xff84b8ab);
                }
            }
        }
        graphics.disableScissor();
        var bar = scrollbar();
        if (bar.visible()) {
            int x = contentX + contentWidth - 7;
            graphics.fill(x, bar.top(), x + 7, bar.top() + bar.height(), 0xff101a28);
            graphics.fill(x + 1, bar.thumbTop(), x + 6, bar.thumbTop() + bar.thumbHeight(),
                    draggingScrollbar ? 0xffe8cc91 : 0xff8197ad);
        }
    }
    private ScrollGeometry scrollbar() { return ScrollGeometry.of(contentY, contentBottom - contentY, layoutHeight, scroll); }
    private void rebuildRows() {
        if (wrapWidth != contentWidth) { wrapped.clear(); wrapWidth = contentWidth; }
        long anchor = -1; int anchorOffset = 0;
        if (tab == Tab.CHAT && !chatScroll.following()) for (Row row : rows) {
            if (row.seq > 0 && row.y + row.height > scroll) { anchor = row.seq; anchorOffset = scroll - row.y; break; }
        }
        rows.clear();
        int y = 0;
        if (tab == Tab.CHAT) {
            for (JsonObject event : AiClientNetwork.STATE.messages()) {
                String text = event.get("text").getAsString();
                if (event.has("blocks")) {
                    text = firefly520.fireflymc.ai.presentation.ResponseBlocks.document(event.getAsJsonArray("blocks"));
                }
                String role = event.get("role").getAsString();
                if (text.isBlank()) continue;
                y = addRow(y, event.has("questionId") ? event.get("questionId").getAsString() : event.get("messageId").getAsString(),
                        event.get("seq").getAsLong(), text, role);
            }
            for (JsonObject stream : AiClientNetwork.STATE.viewingHistory ? List.<JsonObject>of() : AiClientNetwork.STATE.streams.values()) {
                String text = stream.get("text").getAsString();
                if (!text.isBlank()) y = addRow(y, "", -3, text + "\n▍", "assistant");
            }
        } else if (tab == Tab.BOARD) {
            for (var question : AiClientNetwork.STATE.questions) y = addRow(y, question.get("id").getAsString(), -4,
                    (question.get("blocking").getAsBoolean() ? "阻塞问题" : "可稍后回答") + "\n"
                            + question.get("prompt").getAsString(), "question");
            for (var task : AiClientNetwork.STATE.tasks) {
                String text = task.get("state").getAsString() + " · " + task.get("prompt").getAsString();
                if (task.has("construction")) {
                    var build = task.getAsJsonObject("construction");
                    text += "\n当前施工计划：已核验 " + build.get("verifiedOperations") + " / " + build.get("plannedOperations") + " 次放置";
                    if (build.get("unsettledOperations").getAsInt() > 0) text += " · 待核对 " + build.get("unsettledOperations");
                }
                if (task.has("materials")) {
                    var material = task.getAsJsonObject("materials");
                    text += "\n托管原料 " + material.get("heldInputUnits") + " · 待交付 " + material.get("undeliveredUnits");
                    if (material.get("recoveryRequired").getAsBoolean()) text += "\n需要对账；普通继续不会重放不确定操作。";
                }
                if (!task.get("reason").getAsString().isBlank()) text += "\n" + task.get("reason").getAsString();
                y = addRow(y, task.get("taskId").getAsString(), -2, text, "task");
            }
        } else if (tab == Tab.MATERIALS) {
            var view = AiClientNetwork.STATE.materialView;
            if (view.has("text")) y = addRow(y, "", -2, view.get("text").getAsString(), "material");
            if (view.has("sources")) for (var value : view.getAsJsonArray("sources")) {
                var source = value.getAsJsonObject();
                y = addRow(y, source.get("id").getAsString(), -6, source.get("description").getAsString()
                        + "\n取料：" + source.get("extract") + " · 归仓授权：" + source.get("insert"), "source");
            }
            if (view.has("items")) for (var value : view.getAsJsonArray("items")) {
                var item = value.getAsJsonObject();
                y = addRow(y, "", -2, item.get("id").getAsString() + " · 槽位 " + item.get("slot")
                        + "\n实际 " + item.get("physical") + " · 可用 " + item.get("available")
                        + (item.has("protectedByOwner") && item.get("protectedByOwner").getAsBoolean() ? " · 本人保留" : "")
                        + "\n组件摘要 " + item.get("componentsDigest").getAsString().substring(0, 12), "material");
            }
            if (view.has("refusals")) for (var value : view.getAsJsonArray("refusals"))
                y = addRow(y, "", -2, "来源当前不可用：" + value.getAsJsonObject().get("reason").getAsString(), "material");
        } else if (tab == Tab.MEMORY) {
            for (var note : AiClientNetwork.STATE.notes)
                y = addRow(y, note.get("id").getAsString(), -2, note.get("text").getAsString()
                        + "\n" + ("AI_RECORDED".equals(note.get("confidence").getAsString()) ? "小萤记录 · " : "本人确认 · ")
                        + note.get("source").getAsString(), "memory");
        } else {
            var detail = AiClientNetwork.STATE.detail;
            if (detail.has("matches")) {
                for (var value : detail.getAsJsonArray("matches")) {
                    var hit = value.getAsJsonObject();
                    y = addRow(y, hit.get("messageId").getAsString(), hit.get("seq").getAsLong(),
                            "seq:" + hit.get("seq") + " · 点击读取完整证据\n" + hit.get("excerpt").getAsString(), "search");
                }
                if (detail.getAsJsonArray("matches").isEmpty()) y = addRow(y, "", -2, "没有找到匹配记录。", "artifact");
            } else if (detail.has("exports")) {
                for (var value : detail.getAsJsonArray("exports")) {
                    var export = value.getAsJsonObject();
                    y = addRow(y, export.get("id").getAsString(), -5,
                            "点击下载 / 续传\n导出 " + export.get("id").getAsString() + "\n会话版本 " + export.get("revision")
                                    + " · " + export.get("length") + " 字节", "export");
                }
            } else {
            String text = detail.has("blocks") ? firefly520.fireflymc.ai.presentation.ResponseBlocks.document(detail.getAsJsonArray("blocks"))
                    : detail.has("text") ? detail.get("text").getAsString()
                    : new GsonBuilder().setPrettyPrinting().create().toJson(detail);
            y = addRow(y, "", -2, text, "artifact");
            if (detail.has("length") && detail.has("nextOffset"))
                y = addRow(y, "", -2, "字符 " + (detail.has("offset") ? detail.get("offset") : 0) + "–" + detail.get("nextOffset")
                        + " / " + detail.get("length"), "artifact");
            }
        }
        layoutHeight = y;
        if (anchor > 0) for (Row row : rows) if (row.seq == anchor) { scroll = row.y + anchorOffset; break; }
        if (tab == Tab.CHAT && focusSequence > 0) {
            for (Row row : rows) if (row.seq == focusSequence) {
                scroll = Math.clamp(row.y, 0, scrollbar().maximum()); focusSequence = -1; break;
            }
        }
        layoutVersion = AiClientNetwork.STATE.uiVersion;
    }
    private int addRow(int y, String id, long seq, String text, String role) {
        List<SafeRichText.Line> lines = wrapped.computeIfAbsent(text,
                value -> SafeRichText.layout(value, font, Math.max(30, contentWidth - 30)));
        if (lines.stream().noneMatch(CompanionWorkspaceScreen::visibleLine)) return y;
        while (wrapped.size() > 2048) wrapped.remove(wrapped.keySet().iterator().next());
        int height = 29 + lines.size() * 11;
        rows.add(new Row(y, height, id, seq, text, role, lines));
        return y + height;
    }
    private static boolean visibleLine(SafeRichText.Line line) {
        if (!line.itemId().isEmpty() || line.total() > 0) return true;
        // FormattedCharSequence stops when the sink first encounters a visible character.
        return !line.text().accept((index, style, codepoint) -> Character.isWhitespace(codepoint)
                || Character.getType(codepoint) == Character.FORMAT);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && tab != Tab.SETTINGS && x >= contentX + contentWidth - 10
                && x <= contentX + contentWidth && y >= contentY && y < contentBottom) {
            var bar = scrollbar();
            if (bar.visible()) {
                draggingScrollbar = true;
                scrollbarGrab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight()
                        ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
                scroll = bar.drag(y, scrollbarGrab);
                if (tab == Tab.CHAT) chatScroll.manual(scroll, bar.maximum());
                return true;
            }
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && x >= contentX && x <= contentX + contentWidth && y >= contentY && y <= contentBottom) {
            int local = (int) y - contentY + scroll;
            for (Row row : rows) if (local >= row.y && local < row.y + row.height) {
                int lineIndex = (local - row.y - 20) / 11;
                if (local >= row.y + 20 && lineIndex >= 0 && lineIndex < row.lines.size()) {
                    var line = row.lines.get(lineIndex);
                    if (!line.recipeId().isEmpty()) { minecraft.setScreen(new RecipeBrowserScreen(this, line.recipeId())); return true; }
                    var style = font.getSplitter().componentStyleAtWidth(line.text(), (int) x - contentX - 8 - line.inset());
                    if (style != null && style.getClickEvent() != null && style.getClickEvent().getAction() == net.minecraft.network.chat.ClickEvent.Action.OPEN_URL
                            && firefly520.fireflymc.ai.presentation.ResponseBlocks.safeUrl(style.getClickEvent().getValue())) {
                        net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, style.getClickEvent().getValue(), false);
                        return true;
                    }
                }
                if ("question".equals(row.role)) {
                    AiClientNetwork.send(AiProtocol.Action.QUESTION_VIEW, AiClientNetwork.object("id", row.id));
                    return true;
                }
                if ("export".equals(row.role)) {
                    AiClientNetwork.send(AiProtocol.Action.EXPORT, AiClientNetwork.object("id", row.id));
                    return true;
                }
                if ("search".equals(row.role)) {
                    JsonObject args = new JsonObject(); args.addProperty("seq", row.seq);
                    AiClientNetwork.send(AiProtocol.Action.MESSAGE, args); scroll = 0; return true;
                }
                if ("source".equals(row.role)) { selectedSource = row.id; return true; }
                selectedMessage = row.seq;
                if (tab == Tab.BOARD) selectedTask = row.id;
                if (tab == Tab.MEMORY) {
                    selectedNote = row.id;
                    AiClientNetwork.STATE.notes.stream().filter(n -> n.get("id").getAsString().equals(selectedNote))
                            .findFirst().ifPresent(note -> input.setValue(note.get("text").getAsString()));
                }
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (tab != Tab.SETTINGS && x >= contentX && x <= contentX + contentWidth && y >= contentY && y <= contentBottom) {
            scroll = Math.clamp(scroll - (int) (vertical * 28), 0, scrollbar().maximum());
            if (tab == Tab.CHAT) {
                chatScroll.manual(scroll, scrollbar().maximum());
                if (vertical > 0 && scroll <= 48 && AiClientNetwork.STATE.hasEarlierHistory) {
                    chatScroll.history(); AiClientNetwork.earlierHistory();
                }
            }
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && draggingScrollbar) {
            scroll = scrollbar().drag(y, scrollbarGrab);
            if (tab == Tab.CHAT) chatScroll.manual(scroll, scrollbar().maximum());
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && draggingScrollbar) { draggingScrollbar = false; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) { submit(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { if (input != null) AiClientNetwork.STATE.draft = input.getValue(); AiClientNetwork.save(); }
    @Override public void onClose() { removed(); minecraft.setScreen(null); }
}
