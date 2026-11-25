package firefly520.fireflymc.client.ai.workspace;

import firefly520.fireflymc.client.ai.AiClientNetwork;
import firefly520.fireflymc.client.ai.blueprint.BlueprintClientController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Same private workbench, with persistent server revisions and explicit native selected-placement import. */
public final class BlueprintBrowserScreen extends Screen {
    private final Screen parent;
    private final BlueprintClientController controller = BlueprintClientController.INSTANCE;
    private int scroll, contentHeight;
    private boolean dragging;
    private double grab;
    private final List<int[]> hits = new ArrayList<>();
    public BlueprintBrowserScreen(Screen parent) { super(Component.literal("私人蓝图与世界预览")); this.parent = parent; }
    @Override protected void init() {
        int cell = (width - 52) / 4;
        button("导入选中蓝图", 20, 36, cell, controller::importSelected);
        button("刷新列表", 24 + cell, 36, cell, () -> controller.list(0));
        button("下一页", 28 + cell * 2, 36, cell, () -> controller.list(controller.nextListOffset < controller.totalEntries ? controller.nextListOffset : 0));
        button("历史版本", 32 + cell * 3, 36, cell, controller::versions);
        button("旋转 90°", 20, 60, cell, () -> controller.transform("CLOCKWISE_90", "NONE", false));
        button("镜像", 24 + cell, 60, cell, () -> controller.transform("NONE", "FRONT_BACK", false));
        button("移到本人位置", 28 + cell * 2, 60, cell, () -> controller.transform("NONE", "NONE", true));
        button("隐藏 / 取消读取", 32 + cell * 3, 60, cell, controller::hide);
        button("上一层", 20, height - 58, cell, () -> controller.layer(-1));
        button("全部层", 24 + cell, height - 58, cell, () -> controller.layer = null);
        button("下一层", 28 + cell * 2, height - 58, cell, () -> controller.layer(1));
        button("到世界审阅", 32 + cell * 3, height - 58, cell, () -> minecraft.setScreen(null));
        int bottom = (width - 48) / 3;
        button("采用摆放修改", 20, height - 30, bottom, () -> controller.importSelected(true));
        button("预览并确认位置", 24 + bottom, height - 30, bottom, () -> {
            if (controller.selected.isEmpty()) { controller.status = "先选择服务端蓝图版本并审阅预览"; return; }
            if (controller.nativeChanged) { controller.status = "先采用摆放修改，生成新的服务端版本"; return; }
            controller.proposeRegion(false);
            minecraft.setScreen(new BuildRegionScreen(parent));
        });
        button("返回工作台", 28 + bottom * 2, height - 30, bottom, this::onClose);
        controller.list(0);
    }
    private void button(String label, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, Math.max(20, width), 18).build());
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xec141d2a); graphics.drawCenteredString(font, title, width / 2, 14, 0xffe8cb8b);
        graphics.enableScissor(20, 88, width - 20, height - 87); hits.clear(); int y = 0;
        y = text(graphics, "绿色新增，橙色拆除/替换，红色保护或数据冲突，灰蓝色尚未观察。", y);
        y = text(graphics, controller.status + (controller.layer == null ? " · 全部层" : " · 相对层 " + controller.layer), y);
        int index = 0;
        for (var entry : controller.entries) {
            int start = y;
            y = text(graphics, entry.get("name").getAsString() + " · v" + entry.get("revision").getAsLong()
                    + " · " + entry.get("blocks").getAsInt() + " 格\n" + entry.get("dimension").getAsString()
                    + (controller.selected.has("hash") && controller.selected.get("hash").equals(entry.get("hash")) ? " · 已选" : " · 点击预览"), y);
            hits.add(new int[]{start, y, index++});
        }
        if (!controller.selected.isEmpty()) y = text(graphics, "选中版本 " + controller.selected.get("revision") + "\nID "
                + controller.selected.get("id").getAsString() + "\n请回到主会话提出施工或修改要求。", y);
        contentHeight = y; graphics.disableScissor();
        var bar = bar(); scroll = Math.clamp(scroll, 0, bar.maximum());
        if (bar.visible()) {
            graphics.fill(width - 16, bar.top(), width - 10, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 15, bar.thumbTop(), width - 11, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca);
        }
        graphics.drawString(font, font.plainSubstrByWidth(AiClientNetwork.STATE.error, width - 45), 20, height - 77, 0xffeea3a3, false);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private int text(GuiGraphics graphics, String text, int offset) {
        var lines = font.split(Component.literal(text), Math.max(60, width - 55)); int y = 92 + offset - scroll;
        for (var line : lines) { if (y + 11 >= 88 && y < height - 87) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11; }
        return offset + lines.size() * 11 + 14;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(88, Math.max(1, height - 175), contentHeight, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = bar();
        if (button == 0 && bar.visible() && x >= width - 18 && y >= 88 && y < height - 87) {
            dragging = true; grab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && x >= 20 && x < width - 20 && y >= 88 && y < height - 87) {
            int offset = (int) y - 92 + scroll;
            for (var hit : hits) if (offset >= hit[0] && offset < hit[1]) { controller.select(controller.entries.get(hit[2])); return true; }
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { scroll = bar().drag(y, grab); return true; } return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) { if (button == 0) dragging = false; return super.mouseReleased(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) (vertical * 28), 0, bar().maximum()); return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (!AiClientNetwork.STATE.ready()) onClose(); }
    @Override public void onClose() { minecraft.setScreen(AiClientNetwork.STATE.ready() ? parent : null); }
}
