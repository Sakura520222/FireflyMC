package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Manual two-corner editing; all coordinates, bounds and confirmations return from the server. */
public final class ManualBuildRegionScreen extends Screen {
    private final Screen parent;
    private final EditBox[] fields = new EditBox[6];
    private JsonObject displayed;
    private boolean editing, filling, dragging;
    private int scroll, contentHeight;
    private double grab;
    private String localError = "";
    public ManualBuildRegionScreen(Screen parent) { super(Component.literal("高级手工区域调整")); this.parent = parent; }
    @Override protected void init() {
        String[] previous = java.util.Arrays.stream(fields).map(field -> field == null ? "" : field.getValue()).toArray(String[]::new);
        filling = true;
        int fieldWidth = Math.max(35, (width - 92) / 3);
        for (int row = 0; row < 2; row++) for (int axis = 0; axis < 3; axis++) {
            var field = new EditBox(font, 62 + axis * (fieldWidth + 4), 53 + row * 25, fieldWidth, 20, Component.literal((row == 0 ? "起点 " : "终点 ") + "XYZ".charAt(axis)));
            field.setMaxLength(11); field.setResponder(text -> { if (!filling) editing = true; });
            field.setValue(previous[row * 3 + axis]); fields[row * 3 + axis] = addRenderableWidget(field);
        }
        filling = false;
        int cell = (width - 52) / 4;
        button("准星起点", 20, 106, cell, () -> corner(1)); button("准星终点", 24 + cell, 106, cell, () -> corner(2));
        button("应用坐标", 28 + cell * 2, 106, cell, this::apply); button("调查", 32 + cell * 3, 106, cell, () -> send("survey", new JsonObject(), false));
        int bottom = (width - 52) / 4;
        button("世界拖拽", 20, height - 29, bottom, this::dragRegion);
        button("确认区域", 24 + bottom, height - 29, bottom, () -> send("confirm", new JsonObject(), true));
        button("清除选区", 28 + bottom * 2, height - 29, bottom, () -> send("clear", new JsonObject(), true));
        button("返回", 32 + bottom * 3, height - 29, bottom, this::onClose);
        displayed = null; send("view", new JsonObject(), false);
    }
    private void button(String label, int x, int y, int width, Runnable run) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> run.run()).bounds(x, y, width, 20).build());
    }
    private void corner(int corner) { var args = new JsonObject(); args.addProperty("corner", corner); send("corner", args, true); }
    private void dragRegion() {
        if (editing) { localError = "请先应用已编辑坐标，再打开世界拖拽。"; return; }
        try { minecraft.setScreen(new BuildRegionDragScreen(this)); }
        catch (IllegalArgumentException incomplete) { localError = "请先设置两个角点并等待服务端同步，再打开拖拽。"; }
    }
    private void apply() {
        try {
            var args = new JsonObject();
            for (int row = 0; row < 2; row++) { var point = new JsonObject();
                for (int axis = 0; axis < 3; axis++) point.addProperty("xyz".substring(axis, axis + 1), Integer.parseInt(fields[row * 3 + axis].getValue().strip()));
                args.add(row == 0 ? "first" : "second", point);
            }
            send("set", args, true);
        } catch (NumberFormatException invalid) { localError = "请为两角点填写整数 X、Y、Z 坐标。"; }
    }
    private void send(String action, JsonObject args, boolean mutation) {
        var current = AiClientNetwork.STATE.buildRegionView;
        if (mutation && !current.has("privacyEpoch")) { localError = "正在同步选区，请稍候。"; return; }
        args.addProperty("action", action);
        if (mutation) {
            args.add("expectedRevision", current.get("revision")); args.add("privacyEpoch", current.get("privacyEpoch"));
            if (current.has("hash")) args.add("hash", current.get("hash"));
        }
        if (mutation) editing = false;
        localError = ""; AiClientNetwork.send(AiProtocol.Action.BUILD_SELECT, args);
    }
    @Override public void tick() {
        if (!AiClientNetwork.STATE.ready()) { onClose(); return; }
        var current = AiClientNetwork.STATE.buildRegionView;
        if (displayed != current) {
            displayed = current;
            if (!editing) { filling = true;
                for (int row = 0; row < 2; row++) { String key = row == 0 ? "first" : "second";
                    for (int axis = 0; axis < 3; axis++) fields[row * 3 + axis].setValue(current.has(key) && current.get(key).isJsonObject()
                            ? current.getAsJsonObject(key).get("xyz".substring(axis, axis + 1)).getAsString() : "");
                }
                filling = false;
            }
        }
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf4141d2a); graphics.drawCenteredString(font, title, width / 2, 14, 0xffeed098);
        graphics.drawString(font, "选择准星方块为角点，或填写坐标；返回世界可查看线框。", 20, 35, 0xffb4c3d0, false);
        graphics.drawString(font, "起点", 20, 59, 0xff92c3ef, false); graphics.drawString(font, "终点", 20, 84, 0xffd7b1ec, false);
        graphics.enableScissor(20, 137, width - 20, height - 48); int offset = 0; var data = AiClientNetwork.STATE.buildRegionView;
        offset = draw(graphics, "确认区域不批准扣料或施工。调整区域会使本人现有施工预览失效，需重新规划和审批。", offset);
        if (data.has("bounds")) {
            var bounds = data.getAsJsonObject("bounds"); var min = bounds.getAsJsonObject("minimum"); var max = bounds.getAsJsonObject("maximum");
            String size = "";
            for (String axis : java.util.List.of("x", "y", "z")) size += (size.isEmpty() ? "" : " × ") + (max.get(axis).getAsInt() - min.get(axis).getAsInt() + 1);
            offset = draw(graphics, "范围 " + size + "，共 " + bounds.get("volume") + " 格\n版本 " + data.get("revision")
                    + (data.get("confirmed").getAsBoolean() ? " · 已确认区域" : " · 待确认区域"), offset);
        } else offset = draw(graphics, "请先指定两个角点。", offset);
        if (data.has("survey")) { var survey = data.getAsJsonObject("survey");
            offset = draw(graphics, "已占用 " + survey.get("occupied") + " · 流体 " + survey.get("fluids") + " · 带数据方块 " + survey.get("dataBlocks")
                    + "\n保护拒绝 " + survey.get("protected") + " · 未加载 " + survey.get("unloaded")
                    + "\n现有容器、机器与占用不会因确认区域而被清除。", offset);
        }
        graphics.disableScissor(); contentHeight = offset; var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) { graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 15, bar.thumbTop(), width - 11, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca); }
        graphics.drawString(font, font.plainSubstrByWidth(localError.isEmpty() ? AiClientNetwork.STATE.error : localError, width - 40), 20, height - 43, 0xffeead98, false);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int draw(GuiGraphics graphics, String text, int offset) {
        var lines = font.split(Component.literal(text), Math.max(60, width - 56)); int y = 141 + offset - scroll;
        for (var line : lines) { if (y + 11 >= 137 && y < height - 48) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11; }
        return offset + lines.size() * 11 + 12;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(137, Math.max(1, height - 185), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar(); if (button == 0 && bar.visible() && x >= width - 18 && x <= width - 8 && y >= 137 && y < height - 48) {
            dragging = true; grab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        } return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { scroll = bar().drag(y, grab); return true; } return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { if (button == 0 && dragging) { dragging = false; return true; } return super.mouseReleased(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) (vertical * 28), 0, bar().maximum()); return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(AiClientNetwork.STATE.ready() ? parent : null); }
}
