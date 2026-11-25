package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import firefly520.fireflymc.client.ai.integration.recipe.RecipeViewBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Workbench recipe view. Client viewers only display; crafting submits a request to the permanent conversation. */
public final class RecipeBrowserScreen extends Screen {
    private final Screen parent;
    private EditBox query;
    private String queryDraft = "", status = "";
    private int scroll, contentHeight;
    private boolean dragging;
    private double grab;
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int top, int bottom, String id) {}
    public RecipeBrowserScreen(Screen parent) { super(Component.literal("配方与用途")); this.parent = parent; }
    public RecipeBrowserScreen(Screen parent, String recipeQuery) {
        this(parent);
        if (recipeQuery == null || !recipeQuery.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || recipeQuery.length() > 160)
            throw new IllegalArgumentException("RECIPE_QUERY_INVALID");
        queryDraft = recipeQuery;
    }
    @Override protected void init() {
        query = addRenderableWidget(new EditBox(font, 20, 38, Math.max(70, width - 166), 20, Component.literal("物品名称或配方 ID")));
        query.setMaxLength(160); query.setValue(queryDraft); query.setResponder(value -> queryDraft = value);
        button("搜索", width - 140, 38, 54, () -> search(0));
        button("下一页", width - 80, 38, 60, () -> {
            var data = AiClientNetwork.STATE.recipeView;
            search(data.has("nextOffset") && data.get("nextOffset").getAsInt() < data.get("total").getAsInt() ? data.get("nextOffset").getAsInt() : 0);
        });
        int cell = (width - 56) / 5;
        button("EMI", 20, height - 34, cell, () -> display(Viewer.EMI));
        button("JEI", 24 + cell, height - 34, cell, () -> display(Viewer.JEI));
        button("交给小萤", 28 + cell * 2, height - 34, cell, this::craft);
        button("当前冲突", 32 + cell * 3, height - 34, cell, () -> {
            var bridge = RecipeViewBridge.INSTANCE;
            if (bridge.authority() == null) { status = "服务端配方代次尚未同步"; return; }
            var result = bridge.polymorphConflicts(bridge.authority());
            status = result.success() ? "当前菜单候选：" + result.value().candidates().stream().map(Candidate::backingRecipeId).toList()
                    : "当前菜单不可查询：" + result.code();
        });
        button("返回", 36 + cell * 4, height - 34, cell, this::onClose);
        if (!queryDraft.isEmpty() || AiClientNetwork.STATE.recipeView.isEmpty()) search(0);
    }
    private void button(String label, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, width, 20).build());
    }
    private void search(int offset) {
        var args = AiClientNetwork.object("query", query.getValue()); args.addProperty("offset", offset);
        AiClientNetwork.send(AiProtocol.Action.RECIPE_SHOW, args); scroll = 0;
    }
    private JsonObject recipe() {
        var data = AiClientNetwork.STATE.recipeView;
        return data.has("recipe") ? data.getAsJsonObject("recipe") : null;
    }
    private void display(Viewer viewer) {
        JsonObject recipe = recipe();
        if (recipe == null) { status = "先选择一条服务端配方"; return; }
        var output = recipe.getAsJsonObject("sampleOutput");
        if (output == null || !output.has("id")) { status = "该机制没有通用物品输出，保留内置说明"; return; }
        var bridge = RecipeViewBridge.INSTANCE;
        if (bridge.authority() == null) { status = "正在同步配方代次"; return; }
        if (recipe.get("recipeGeneration").getAsLong() != bridge.authority().recipeGeneration()) {
            status = "配方已重载，请重新搜索后查看"; return;
        }
        status = "正在定位指定配方…";
        bridge.displayExact(new DisplayRequest(bridge.authority(), viewer, recipe.get("recipeId").getAsString(), ""),
                output.get("id").getAsString()).thenAccept(result -> {
                    status = result.success() ? "已确认原生界面显示该配方" : "浏览器未打开指定配方：" + result.code() + "；内置卡片仍可查看";
                });
    }
    private void craft() {
        JsonObject recipe = recipe();
        if (recipe == null) { status = "请先选择配方"; return; }
        String adapter = recipe.get("executionAdapter").getAsString();
        if (!recipe.has("executionAvailable") || !recipe.get("executionAvailable").getAsBoolean()) {
            status = "该配方当前未开放自动加工，可先向小萤咨询"; return;
        }
        AiClientNetwork.submit("请按服务端配方 " + recipe.get("recipeId").getAsString() + " 制作一批，先核验真实材料并给出待审批计划。");
        AiClientNetwork.open();
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf4141d2a);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xffeed098);
        hits.clear(); int position = 0;
        graphics.enableScissor(20, 66, width - 20, height - 62);
        var data = AiClientNetwork.STATE.recipeView;
        if (data.has("entries")) for (var value : data.getAsJsonArray("entries")) {
            JsonObject row = value.getAsJsonObject();
            String text = row.get("recipeId").getAsString() + "\n" + itemText(row.getAsJsonObject("sampleOutput"))
                    + "\n" + (row.get("executionAdapter").getAsString().equals("NO_EXECUTION_ADAPTER") ? "仅知识/展示" : "可按材料计划加工");
            int begin = position; position = draw(graphics, text, position);
            hits.add(new Hit(begin, position, row.get("recipeId").getAsString()));
        }
        if (recipe() != null) {
            var row = recipe();
            position = draw(graphics, "服务端配方：" + row.get("recipeId").getAsString() + "\n代次 " + row.get("recipeGeneration")
                    + "\n" + row.get("note").getAsString(), position);
            position = draw(graphics, "参考产物：" + itemText(row.getAsJsonObject("sampleOutput")), position);
            if (row.has("nativeOutputs")) for (var value : row.getAsJsonArray("nativeOutputs")) {
                var output = value.getAsJsonObject();
                position = draw(graphics, "可能产物：" + itemText(output) + " · 每单位概率 "
                        + String.format(Locale.ROOT, "%.1f%%", Math.clamp(output.get("chancePerUnit").getAsDouble(), 0, 1) * 100), position);
            }
            if (row.has("outputContainer") && row.getAsJsonObject("outputContainer").has("id"))
                position = draw(graphics, "每份成品另需器皿：" + itemText(row.getAsJsonObject("outputContainer")), position);
            if (row.has("genericIngredientHints")) {
                int index = 1;
                for (var ingredient : row.getAsJsonArray("genericIngredientHints")) {
                    var choices = ingredient.getAsJsonArray().asList().stream().map(v -> itemText(v.getAsJsonObject())).toList();
                    position = draw(graphics, "配料提示 " + index++ + "：" + (choices.isEmpty() ? "空格" : String.join(" / ", choices)), position);
                }
            }
            position = draw(graphics, row.get("executionAdapter").getAsString().equals("NO_EXECUTION_ADAPTER")
                    ? "此机制目前可查询和展示，自动加工适配尚未就绪。"
                    : "可交给小萤生成真实材料计划，确认后免设备加工。", position);
        }
        graphics.disableScissor(); contentHeight = position;
        var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) {
            graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 15, bar.thumbTop(), width - 11, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca);
        }
        graphics.drawString(font, font.plainSubstrByWidth(status, width - 40), 20, height - 53, 0xffadbfcf, false);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int draw(GuiGraphics graphics, String text, int offset) {
        var lines = font.split(Component.literal(text), Math.max(60, width - 54));
        int y = 70 + offset - scroll;
        for (var line : lines) { if (y + 11 >= 66 && y < height - 62) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11; }
        return offset + lines.size() * 11 + 13;
    }
    private static String itemText(JsonObject item) {
        if (item == null || !item.has("id")) return "没有通用物品输出";
        String name = item.has("name") ? item.get("name").getAsString() : item.get("id").getAsString();
        return name + " × " + (item.has("count") ? item.get("count").getAsInt() : 1);
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(66, Math.max(1, height - 128), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && bar.visible() && x >= width - 18 && x <= width - 8 && y >= 66 && y < height - 62) {
            dragging = true; grab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && x >= 20 && x < width - 20 && y >= 66 && y < height - 62) {
            int local = (int) y - 70 + scroll;
            for (Hit hit : hits) if (local >= hit.top() && local < hit.bottom()) {
                AiClientNetwork.send(AiProtocol.Action.RECIPE_SHOW, AiClientNetwork.object("recipeId", hit.id())); scroll = 0; return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { scroll = bar().drag(y, grab); return true; } return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging) { dragging = false; return true; } return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int) (vertical * 28), 0, bar().maximum()); return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (!AiClientNetwork.STATE.ready()) onClose(); }
    @Override public void onClose() { minecraft.setScreen(AiClientNetwork.STATE.ready() ? parent : null); }
}
