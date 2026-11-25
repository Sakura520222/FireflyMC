package firefly520.fireflymc.client.ai.workspace;

import firefly520.fireflymc.client.ai.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.UnaryOperator;

/** Display-only settings. Dragging a preview never manipulates the workspace or task state. */
public final class HudLayoutScreen extends Screen {
    private final Screen parent;
    private boolean dragging;
    private double dragX, dragY;
    private int left, top, previewWidth, previewHeight;
    public HudLayoutScreen(Screen parent) { super(Component.literal("HUD 布局与提示")); this.parent = parent; }
    @Override protected void init() {
        int buttonWidth = Math.max(64, (width - 48) / 3), x = 20, y = height - 80;
        var data = AiClientNetwork.display;
        add("缩放 " + Math.round(data.scale() * 100) + "%", x, y, buttonWidth, () -> change(old -> copy(old,
                old.scale() >= 2 ? .5 : old.scale() + .25, old.opacity(), old.collapsed(), old.autoHide(), old.noticeIntervalSeconds())));
        add("透明 " + Math.round(data.opacity() * 100) + "%", x + buttonWidth + 4, y, buttonWidth, () -> change(old -> copy(old,
                old.scale(), old.opacity() >= .99 ? .2 : Math.min(1, old.opacity() + .2), old.collapsed(), old.autoHide(), old.noticeIntervalSeconds())));
        add("折叠：" + (data.collapsed() ? "开" : "关"), x + (buttonWidth + 4) * 2, y, buttonWidth, () -> change(old -> copy(old,
                old.scale(), old.opacity(), !old.collapsed(), old.autoHide(), old.noticeIntervalSeconds())));
        add("自动隐藏：" + (data.autoHide() ? "开" : "关"), x, y + 24, buttonWidth, () -> change(old -> copy(old,
                old.scale(), old.opacity(), old.collapsed(), !old.autoHide(), old.noticeIntervalSeconds())));
        add("提示：" + (data.noticeIntervalSeconds() == 0 ? "关闭" : data.noticeIntervalSeconds() + "秒"), x + buttonWidth + 4, y + 24, buttonWidth,
                () -> change(old -> copy(old, old.scale(), old.opacity(), old.collapsed(), old.autoHide(),
                        switch (old.noticeIntervalSeconds()) { case 0 -> 15; case 15 -> 30; case 30 -> 60; default -> 0; })));
        add("切换角落锚点", x + (buttonWidth + 4) * 2, y + 24, buttonWidth, () -> change(old ->
                old.x() < .5 ? old.y() > .5 ? old.move(.02, .02) : old.move(.98, .02)
                        : old.y() < .5 ? old.move(.98, .78) : old.move(.02, .78)));
        add("返回工作台", 20, height - 28, 100, this::onClose);
        add("恢复默认布局", width - 120, height - 28, 100, () -> {
            var defaults = AiClientPreferences.Data.defaults();
            AiClientNetwork.display = defaults.withDraft(AiClientNetwork.STATE.draft, AiClientNetwork.display.outbox());
            AiClientNetwork.save(); rebuildWidgets();
        });
    }
    private static AiClientPreferences.Data copy(AiClientPreferences.Data old, double scale, double opacity,
                                               boolean collapsed, boolean hide, int interval) {
        return new AiClientPreferences.Data(1, old.x(), old.y(), scale, opacity, collapsed, hide, old.draft(), old.outbox(), interval);
    }
    private void change(UnaryOperator<AiClientPreferences.Data> operation) {
        AiClientNetwork.display = operation.apply(AiClientNetwork.display); AiClientNetwork.save(); rebuildWidgets();
    }
    private void add(String text, int x, int y, int w, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float tick) {}
    @Override public void render(GuiGraphics graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xee111925);
        graphics.drawString(font, title, 20, 12, 0xffefcf88, false);
        graphics.drawString(font, "拖动预览调整位置", 20, 28, 0xffadbdc9, false);
        var data = AiClientNetwork.display;
        double scale = Math.min(data.scale(), width / 210.0);
        previewWidth = (int) (210 * scale); previewHeight = (int) ((data.collapsed() ? 24 : 55) * scale);
        left = (int) ((width - previewWidth) * data.x()); top = (int) ((height - previewHeight) * data.y());
        graphics.pose().pushPose(); graphics.pose().translate(left, top, 0); graphics.pose().scale((float) scale, (float) scale, 1);
        graphics.fill(0, 0, 210, data.collapsed() ? 24 : 55, ((int) (data.opacity() * 255) << 24) | 0x1c3047);
        graphics.drawString(font, AiClientNetwork.STATE.name + " · HUD 位置预览", 9, 8, 0xffefcf88, false);
        if (!data.collapsed()) graphics.drawString(font, "任务 / 进度 / 待处理问题", 9, 28, 0xffb8dacc, false);
        graphics.pose().popPose();
        super.render(graphics, x, y, tick);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && x >= left && x < left + previewWidth && y >= top && y < top + previewHeight) {
            dragging = true; dragX = x - left; dragY = y - top; return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(x, y, button, dx, dy);
        AiClientNetwork.display = AiClientNetwork.display.move((x - dragX) / Math.max(1, width - previewWidth),
                (y - dragY) / Math.max(1, height - previewHeight));
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (dragging) { dragging = false; AiClientNetwork.save(); return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public void tick() { if (!AiClientNetwork.STATE.ready()) minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { AiClientNetwork.save(); minecraft.setScreen(parent); }
}
