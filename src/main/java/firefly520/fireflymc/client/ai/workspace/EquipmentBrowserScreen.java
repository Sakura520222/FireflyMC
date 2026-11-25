package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Explicit workbench inventory grants and an independent companion-carrier selection. */
public final class EquipmentBrowserScreen extends Screen {
    private final Screen parent;
    private final boolean fluidMode;
    private JsonObject data = new JsonObject();
    private int scroll, heightOfContent;
    private boolean dragging;
    private double grab;
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int top, int bottom, String locator) {}
    public EquipmentBrowserScreen(Screen parent) { this(parent, false); }
    public EquipmentBrowserScreen(Screen parent, boolean fluidMode) {
        super(Component.literal(fluidMode ? "授权本人的携带储罐" : "本人的装备、背包与伙伴载体"));
        this.parent = parent; this.fluidMode = fluidMode;
    }
    @Override protected void init() {
        int cell = (width - 52) / 4;
        button("刷新", 20, 36, cell, () -> refresh(0));
        button("下一页装备", 24 + cell, 36, cell, () -> refresh(data.has("nextOffset") && data.get("nextOffset").getAsInt() < data.get("total").getAsInt() ? data.get("nextOffset").getAsInt() : 0));
        if (!fluidMode) button("选择所指女仆", 28 + cell * 2, 36, cell, () -> {
            AiClientNetwork.send(AiProtocol.Action.CARRIER_SELECT, AiClientNetwork.object("operation", "preview"));
        });
        if (!fluidMode) button("取消载体选择", 32 + cell * 3, 36, cell, () -> {
            if (!data.has("carrier")) return;
            var selected = data.getAsJsonObject("carrier").getAsJsonObject("selection");
            var args = AiClientNetwork.object("operation", "clear"); args.add("revision", selected.get("revision")); AiClientNetwork.send(AiProtocol.Action.CARRIER_SELECT, args);
        });
        if (!fluidMode) button("授权所选女仆背包", 20, height - 32, 140, () -> {
            if (!data.has("carrier")) return;
            var selected = data.getAsJsonObject("carrier").getAsJsonObject("selection");
            if (!selected.has("choice") || selected.get("choice").isJsonNull()) { AiClientNetwork.STATE.error = "先选择本人的女仆载体"; return; }
            AiClientNetwork.send(AiProtocol.Action.SOURCE_PREVIEW, AiClientNetwork.object("maid", selected.getAsJsonObject("choice").get("maidId").getAsString()));
        });
        button("返回工作台", width - 130, height - 32, 110, this::onClose);
        refresh(0);
    }
    private void button(String text, int x, int y, int w, Runnable run) { addRenderableWidget(Button.builder(Component.literal(text), ignored -> run.run()).bounds(x, y, Math.max(24, w), 20).build()); }
    public void receive(JsonObject value) { data = value.deepCopy(); scroll = 0; }
    public static void refresh(int offset) { var args = new JsonObject(); args.addProperty("offset", offset); AiClientNetwork.send(AiProtocol.Action.EQUIPMENT_VIEW, args); }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf0141d2a); graphics.drawCenteredString(font, title, width / 2, 14, 0xffe8cb8b);
        graphics.enableScissor(20, 64, width - 20, height - 42); hits.clear(); int y = 0;
        y = text(graphics, fluidMode ? "点击具体背包预览流体授权；不会授权宿主、物品栏或装备消耗。当前只接受匹配版本的有限储罐。"
                : "装备查询不授权消耗。点击下面具体背包可预览授权；背包移动、升级变化或领地权限变化会重新核验。", y);
        if (data.has("backpacks")) for (var value : data.getAsJsonArray("backpacks")) {
            var item = value.getAsJsonObject(); int top = y;
            y = text(graphics, "[点击授权] " + item.get("itemId").getAsString() + " · " + item.get("locator").getAsString(), y);
            hits.add(new Hit(top, y, item.get("locator").getAsString()));
        }
        if (data.has("slots")) for (var value : data.getAsJsonArray("slots")) {
            var item = value.getAsJsonObject(); y = text(graphics, item.get("source").getAsString() + " / " + item.get("slot").getAsString()
                    + " · " + item.get("itemId").getAsString() + " × " + item.get("count").getAsInt() + " · " + (item.get("active").getAsBoolean() ? "生效" : "未生效"), y);
        }
        if (!fluidMode && data.has("carrier")) {
            var carrier = data.getAsJsonObject("carrier");
            y = text(graphics, "伙伴载体\n" + (carrier.has("reason") ? carrier.get("reason").getAsString() : "")
                    + "\n" + carrier.get("selection") + "\n选择载体与材料授权独立；不接管原生战斗或其他任务。", y);
        }
        if (!AiClientNetwork.STATE.error.isEmpty()) y = text(graphics, AiClientNetwork.STATE.error, y);
        heightOfContent = y; graphics.disableScissor(); var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) { graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 16, bar.thumbTop(), width - 10, bar.thumbTop() + bar.thumbHeight(), 0xffd3b372); }
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int text(GuiGraphics graphics, String text, int y) {
        for (var line : font.split(Component.literal(text), Math.max(60, width - 64))) {
            int screenY = 68 + y - scroll;
            if (screenY >= 52 && screenY < height - 42) graphics.drawString(font, line, 24, screenY, 0xffdce5ef, false);
            y += 12;
        }
        return y + 8;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(64, Math.max(1, height - 106), heightOfContent, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && x >= width - 18 && x <= width - 8 && y >= bar.top() && y < bar.top() + bar.height() && bar.visible()) {
            dragging = true; grab = y >= bar.thumbTop() && y <= bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        if (button == 0 && x >= 20 && x < width - 20 && y >= 64 && y < height - 42)
            for (var hit : hits) if (y - 68 + scroll >= hit.top() && y - 68 + scroll < hit.bottom()) {
                AiClientNetwork.send(fluidMode ? AiProtocol.Action.FLUID_SOURCE_PREVIEW : AiProtocol.Action.SOURCE_PREVIEW,
                        AiClientNetwork.object("backpack", hit.locator())); return true;
            }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) { scroll = bar().drag(y, grab); return true; } return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { dragging = false; return super.mouseReleased(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) (vertical * 30), 0, bar().maximum()); return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
