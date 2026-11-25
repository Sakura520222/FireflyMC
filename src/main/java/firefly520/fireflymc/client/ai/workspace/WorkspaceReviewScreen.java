package firefly520.fireflymc.client.ai.workspace;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;

/** Inert final preview. The action is constructed by trusted client code, never decoded from model text. */
public final class WorkspaceReviewScreen extends Screen {
    private final Screen parent;
    private final String text;
    private final String confirmLabel;
    private final Runnable confirm;
    private int scroll, maximum;
    private Button approve;
    private List<FormattedCharSequence> lines;
    private boolean dragging;
    private double grab;
    public WorkspaceReviewScreen(Screen parent, String title, String text, String confirmLabel, Runnable confirm) {
        super(Component.literal(title));
        this.parent = parent; this.text = text; this.confirmLabel = confirmLabel; this.confirm = confirm;
    }
    @Override protected void init() {
        lines = font.split(Component.literal(text), Math.max(80, width - 64));
        maximum = Math.max(0, lines.size() * 12 - (height - 112));
        approve = addRenderableWidget(Button.builder(Component.literal(confirmLabel), b -> {
            approve.active = false;
            confirm.run();
            minecraft.setScreen(parent);
        }).bounds(width / 2 + 6, height - 38, 100, 22).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(width / 2 - 106, height - 38, 100, 22).build());
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xf4141d2a);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xfff1d19a);
        graphics.enableScissor(24, 44, width - 24, height - 58);
        int y = 48 - scroll;
        for (var line : lines) {
            if (y >= 40 && y < height - 58) graphics.drawString(font, line, 32, y, 0xffdce5ef, false);
            y += 12;
        }
        graphics.disableScissor();
        var bar = scrollbar();
        if (bar.visible()) {
            graphics.fill(width - 23, bar.top(), width - 17, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 22, bar.thumbTop(), width - 18, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca);
        }
        approve.active = scroll >= maximum;
        if (!approve.active) graphics.drawCenteredString(font, "向下滚动查看完整内容后确认", width / 2, height - 54, 0xffa6b5c6);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int) (vertical * 30), 0, maximum);
        return true;
    }
    private ScrollGeometry scrollbar() { return ScrollGeometry.of(44, Math.max(1, height - 112), lines.size() * 12, scroll); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        var bar = scrollbar();
        if (button == 0 && bar.visible() && x >= width - 25 && x <= width - 15 && y >= 44 && y <= height - 68) {
            dragging = true;
            grab = y >= bar.thumbTop() && y < bar.thumbTop() + bar.thumbHeight() ? y - bar.thumbTop() : bar.thumbHeight() / 2.0;
            scroll = bar.drag(y, grab); return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { scroll = scrollbar().drag(y, grab); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging) { dragging = false; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
