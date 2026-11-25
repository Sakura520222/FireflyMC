package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Current server evidence, separate from the user's installation and historical acceptance reports. */
public final class IntegrationStatusScreen extends Screen {
    private final Screen parent;
    private EditBox query;
    private String draft = "";
    private int scroll, contentHeight;
    private boolean dragging;
    private double grab;
    public IntegrationStatusScreen(Screen parent) { super(Component.literal("模组适配状态")); this.parent = parent; }
    @Override protected void init() {
        query = addRenderableWidget(new EditBox(font, 20, 37, Math.max(70, width - 166), 20, Component.literal("模组名称或 ID")));
        query.setMaxLength(160); query.setValue(draft); query.setResponder(value -> draft = value);
        button("搜索", width - 140, 37, 54, () -> request(0));
        button("下一页", width - 80, 37, 60, () -> {
            var data = AiClientNetwork.STATE.integrationView;
            request(data.has("nextOffset") && data.get("nextOffset").getAsInt() < data.get("total").getAsInt()
                    && data.get("query").getAsString().equals(query.getValue().strip().toLowerCase(java.util.Locale.ROOT))
                    ? data.get("nextOffset").getAsInt() : 0);
        });
        button("刷新", 20, height - 29, 64, () -> request(0));
        button("返回", width - 84, height - 29, 64, this::onClose); request(0);
    }
    private void button(String text, int x, int y, int width, Runnable run) {
        addRenderableWidget(Button.builder(Component.literal(text), b -> run.run()).bounds(x, y, width, 20).build());
    }
    private void request(int offset) {
        var args = AiClientNetwork.object("query", query.getValue()); args.addProperty("offset", offset);
        AiClientNetwork.STATE.integrationRequest = AiClientNetwork.send(AiProtocol.Action.INTEGRATIONS, args); scroll = 0;
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf4141d2a); graphics.drawCenteredString(font, title, width / 2, 14, 0xffeed098);
        graphics.enableScissor(20, 65, width - 20, height - 52); int offset = 0;
        var data = AiClientNetwork.STATE.integrationView;
        offset = draw(graphics, "已加载只表示服务器发现该 Mod。适配按具体机制列出；实际执行仍需材料、权限及审批。", offset);
        if (data.has("indexState") && !data.get("indexState").getAsString().equals("READY"))
            offset = draw(graphics, "配方正在分批索引，当前数量尚不完整。", offset);
        if (data.has("entries")) for (var value : data.getAsJsonArray("entries")) {
            var row = value.getAsJsonObject(); var recipes = row.getAsJsonObject("recipes");
            offset = draw(graphics, row.get("name").getAsString() + " · " + row.get("modId").getAsString()
                    + "\n版本 " + row.get("version").getAsString() + " · 已发现（专服实际加载）"
                    + "\n此命名空间已索引配方 " + recipes.get("indexedRecipeCount") + "，开放规划 " + recipes.get("executableRecipeCount"), offset);
            for (var capability : row.getAsJsonArray("capabilities")) {
                var item = capability.getAsJsonObject();
                offset = draw(graphics, "• " + item.get("capability").getAsString() + "：" + state(item.get("state").getAsString())
                        + "\n" + item.get("scope").getAsString(), offset);
            }
            for (var mechanism : recipes.getAsJsonArray("mechanisms")) {
                var item = mechanism.getAsJsonObject();
                offset = draw(graphics, "• " + item.get("recipeType").getAsString() + " · " + item.get("recipeCount") + " 条 · "
                        + state(item.get("state").getAsString()) + "\n适配：" + item.get("adapter").getAsString(), offset);
            }
            if (recipes.get("mechanismsTruncated").getAsBoolean()) offset = draw(graphics, "更多机制请在配方浏览中按 ID 查询。", offset);
        }
        graphics.disableScissor(); contentHeight = offset;
        var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) { graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 15, bar.thumbTop(), width - 11, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca); }
        String footer = AiClientNetwork.STATE.error.isBlank() ? "当前连接未做完整行为验收；历史测试范围见实施记录。" : AiClientNetwork.STATE.error;
        graphics.drawString(font, font.plainSubstrByWidth(footer, width - 40), 20, height - 46, 0xffadbfcf, false);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private static String state(String state) { return switch (state) {
        case "RUNTIME_READY" -> "运行就绪"; case "INTERFACE_MATCHED" -> "接口匹配"; case "VERSION_UNSUPPORTED" -> "版本不支持";
        case "ABSENT" -> "未加载"; case "API_UNAVAILABLE", "FAILED" -> "接口不可用"; case "VERIFIED" -> "验证通过"; default -> "已发现，尚未就绪";
    }; }
    private int draw(GuiGraphics graphics, String text, int offset) {
        var lines = font.split(Component.literal(text), Math.max(60, width - 54)); int y = 69 + offset - scroll;
        for (var line : lines) { if (y + 11 >= 65 && y < height - 52) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11; }
        return offset + lines.size() * 11 + 12;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(65, Math.max(1, height - 117), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && bar.visible() && x >= width - 18 && x <= width - 8 && y >= 65 && y < height - 52) {
            dragging = true; grab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        return super.mouseClicked(x, y, button);
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
