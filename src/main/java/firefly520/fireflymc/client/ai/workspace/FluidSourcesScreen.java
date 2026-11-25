package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The same private workbench, with an explicit and separate raw-fluid source permission review. */
public final class FluidSourcesScreen extends Screen {
    private final Screen parent;
    private JsonObject data = new JsonObject();
    private String selected = "";
    private int scroll, contentHeight;
    private boolean dragging;
    private double grab;
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int top, int bottom, String source) {}
    public FluidSourcesScreen(Screen parent) { super(Component.literal("本人的原料流体")); this.parent = parent; }
    @Override protected void init() {
        int cell = (width - 52) / 4;
        button("来源", 20, 36, cell, () -> request(AiProtocol.Action.FLUIDS_VIEW));
        button("盘点", 24 + cell, 36, cell, () -> request(AiProtocol.Action.FLUIDS_SCAN));
        button("授权所指", 28 + cell * 2, 36, cell, () -> request(AiProtocol.Action.FLUID_SOURCE_PREVIEW));
        button("携带储罐", 32 + cell * 3, 36, cell, () -> minecraft.setScreen(new EquipmentBrowserScreen(this, true)));
        int bottom = (width - 48) / 3;
        button("撤销选中来源", 20, height - 32, bottom, () -> {
            if (selected.isEmpty()) { AiClientNetwork.STATE.error = "请先选择已授权流体来源"; return; }
            AiClientNetwork.send(AiProtocol.Action.FLUID_SOURCE_REVOKE, AiClientNetwork.object("id", selected));
        });
        button("下一页", 24 + bottom, height - 32, bottom, () -> {
            if (!data.has("nextOffset")) return;
            var args = new JsonObject(); int offset = data.get("nextOffset").getAsInt();
            args.addProperty("offset", offset >= data.get("total").getAsInt() ? 0 : offset);
            if (data.has("snapshotId")) { args.add("snapshotId", data.get("snapshotId")); AiClientNetwork.send(AiProtocol.Action.FLUIDS_SCAN, args); }
            else AiClientNetwork.send(AiProtocol.Action.FLUIDS_VIEW, args);
        });
        button("返回工作台", 28 + bottom * 2, height - 32, bottom, this::onClose);
        request(AiProtocol.Action.FLUIDS_VIEW);
    }
    private void request(AiProtocol.Action action) { AiClientNetwork.send(action, new JsonObject()); }
    private void button(String label, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, Math.max(24, width), 20).build());
    }
    public void receive(JsonObject value) { data = value.deepCopy(); selected = ""; scroll = 0; AiClientNetwork.STATE.error = ""; }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf0141d2a);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xffe8cb8b);
        graphics.enableScissor(20, 64, width - 20, height - 42); hits.clear(); int y = 0;
        if (data.has("text")) y = text(graphics, data.get("text").getAsString(), y);
        if (data.has("sources")) for (var value : data.getAsJsonArray("sources")) {
            var source = value.getAsJsonObject(); String id = source.get("id").getAsString(); int top = y;
            y = text(graphics, (id.equals(selected) ? "[已选] " : "[点击选择] ") + source.get("description").getAsString()
                    + "\n取液：" + source.get("extract") + " · 回填：" + source.get("insert"), y);
            hits.add(new Hit(top, y, id));
        }
        if (data.has("fluids")) for (var value : data.getAsJsonArray("fluids")) {
            var fluid = value.getAsJsonObject(); y = text(graphics, fluid.get("id").getAsString() + " · " + fluid.get("physicalMb")
                    + " mB\n组件摘要 " + fluid.get("componentsDigest").getAsString().substring(0, 12), y);
        }
        if (data.has("refusals")) for (var value : data.getAsJsonArray("refusals"))
            y = text(graphics, "来源当前不可用：" + value.getAsJsonObject().get("reason").getAsString(), y);
        if (!AiClientNetwork.STATE.error.isEmpty()) y = text(graphics, AiClientNetwork.STATE.error, y);
        contentHeight = y; graphics.disableScissor(); var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) {
            graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 16, bar.thumbTop(), width - 10, bar.thumbTop() + bar.thumbHeight(), 0xffd3b372);
        }
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int text(GuiGraphics graphics, String value, int y) {
        for (var line : font.split(Component.literal(value), Math.max(60, width - 64))) {
            int screen = 68 + y - scroll;
            if (screen >= 52 && screen < height - 42) graphics.drawString(font, line, 24, screen, 0xffdce5ef, false);
            y += 12;
        }
        return y + 8;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(64, Math.max(1, height - 106), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && x >= width - 18 && x <= width - 8 && y >= bar.top() && y < bar.top() + bar.height() && bar.visible()) {
            dragging = true; grab = y >= bar.thumbTop() && y <= bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        if (button == 0 && x >= 20 && x < width - 20 && y >= 64 && y < height - 42)
            for (var hit : hits) if (y - 68 + scroll >= hit.top() && y - 68 + scroll < hit.bottom()) { selected = hit.source(); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) { scroll = bar().drag(y, grab); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { dragging = false; return super.mouseReleased(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) (vertical * 30), 0, bar().maximum()); return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
