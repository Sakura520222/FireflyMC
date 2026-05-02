package firefly520.fireflymc.client.screen;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.SingleplayerRelayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;

/**
 * 单人世界公开联机确认弹窗。
 *
 * 阶段一仅负责用户确认、调用 openToLAN 并展示 LAN 端口；公开大厅和中继将在后续阶段接入。
 */
public class SingleplayerSharePromptScreen extends Screen {
    private static final int ACCENT_PRIMARY = 0xFFFF69B4;
    private static final int ACCENT_SECONDARY = 0xFFFF1493;
    private static final int TEXT_PRIMARY = 0xFF2D2D2D;
    private static final int TEXT_SECONDARY = 0xFF666666;
    private static final int WARNING_COLOR = 0xFFFFAA00;
    private static final int SHADOW_LIGHT = 0x30FFFFFF;
    private static final int SHADOW_DARK = 0x40000000;

    private final Screen parent;
    private final String worldName;

    public SingleplayerSharePromptScreen(Screen parent, String worldName) {
        super(Component.literal("单人世界联机"));
        this.parent = parent;
        this.worldName = worldName == null || worldName.isBlank() ? "我的单人世界" : worldName;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 140;
        int buttonHeight = 24;
        int spacing = 12;
        int totalWidth = buttonWidth * 2 + spacing;
        int startX = this.width / 2 - totalWidth / 2;
        int dialogHeight = Math.min(300, this.height - 80);
        int dialogY = (this.height - dialogHeight) / 2;
        int y = dialogY + dialogHeight - 54;

        this.addRenderableWidget(Button.builder(
                Component.literal("§a开启联机"),
                button -> onStartHosting()
        ).bounds(startX, y, buttonWidth, buttonHeight).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§7暂不开启"),
                button -> onSkip()
        ).bounds(startX + buttonWidth + spacing, y, buttonWidth, buttonHeight).build());
    }

    // 与公告弹窗一致：不调用默认背景，避免额外遮罩/层级覆盖
    @Override
    public void renderBackground(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 不调用 super.renderBackground()
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int dialogWidth = Math.min(520, this.width - 40);
        int dialogHeight = Math.min(300, this.height - 80);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        // 复用公告弹窗风格：阴影 + 毛玻璃 + 渐变边框，全部在 widget 前绘制
        drawRoundedRect(guiGraphics, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);
        drawFrostedGlassBackground(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);
        drawGradientBorder(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        Component title = Component.literal("FireflyMC 单人世界联机");
        int titleX = this.width / 2 - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title.getVisualOrderText(),
                (float)titleX, (float)(dialogY + 20), ACCENT_SECONDARY, false);

        drawStarIcon(guiGraphics, titleX - 18, dialogY + 22, ACCENT_PRIMARY);
        drawStarIcon(guiGraphics, titleX + this.font.width(title) + 14, dialogY + 22, ACCENT_PRIMARY);

        int separatorY = dialogY + 52;
        drawGradientLine(guiGraphics, dialogX + 20, separatorY, dialogX + dialogWidth - 20, separatorY,
                ACCENT_PRIMARY, ACCENT_SECONDARY);

        drawCentered(guiGraphics, "是否将当前单人世界公开到 FireflyMC 联机大厅？", separatorY + 22, TEXT_PRIMARY);
        drawCentered(guiGraphics, "公开名称：" + worldName, separatorY + 44, ACCENT_PRIMARY);

        int textX = dialogX + 34;
        int textY = separatorY + 78;
        guiGraphics.drawString(this.font, "开启后，其他安装 FireflyMC 的玩家将可在多人列表看到并加入。", textX, textY, TEXT_SECONDARY, false);
        guiGraphics.drawString(this.font, "阶段一会先打开本机 LAN 端口并记录端口，暂不接入中继大厅。", textX, textY + 18, TEXT_SECONDARY, false);
        guiGraphics.drawString(this.font, "请注意：公开房间后陌生玩家可能进入并影响你的存档。", textX, textY + 36, WARNING_COLOR, false);

        int port = ClientState.singleplayerRelayLanPort;
        if (ClientState.isSingleplayerRelayHosting && port > 0) {
            drawCentered(guiGraphics, "LAN 已准备，端口：" + port, textY + 70, 0xFF228B22);
        } else if (ClientState.isSingleplayerRelayHosting) {
            drawCentered(guiGraphics, "LAN 已请求开启，正在等待端口读取", textY + 70, 0xFF228B22);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void onStartHosting() {
        ClientState.hasHandledSingleplayerRelayPrompt = true;
        SingleplayerRelayManager.getInstance().startHosting();
        Minecraft.getInstance().setScreen(parent);
    }

    private void onSkip() {
        ClientState.hasHandledSingleplayerRelayPrompt = true;
        Minecraft.getInstance().setScreen(parent);
    }

    private void drawCentered(GuiGraphics guiGraphics, String text, int y, int color) {
        guiGraphics.drawString(this.font, text, this.width / 2 - this.font.width(text) / 2, y, color, false);
    }

    private void drawRoundedRect(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color) {
        guiGraphics.fill(x + radius, y, x + width - radius, y + height, color);
        guiGraphics.fill(x, y + radius, x + width, y + height - radius, color);
        guiGraphics.fill(x + radius, y, x + width - radius, y + radius, color);
        guiGraphics.fill(x + radius, y + height - radius, x + width - radius, y + height, color);
        fillCircle(guiGraphics, x + radius, y + radius, radius, color);
        fillCircle(guiGraphics, x + width - radius, y + radius, radius, color);
        fillCircle(guiGraphics, x + radius, y + height - radius, radius, color);
        fillCircle(guiGraphics, x + width - radius, y + height - radius, radius, color);
    }

    private void fillCircle(GuiGraphics guiGraphics, int centerX, int centerY, int radius, int color) {
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                if (i * i + j * j <= radius * radius) {
                    guiGraphics.fill(centerX + i, centerY + j, centerX + i + 1, centerY + j + 1, color);
                }
            }
        }
    }

    private void drawFrostedGlassBackground(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius) {
        drawRoundedRect(guiGraphics, x, y, width, height, radius, 0xDDFAFAFA);
        drawRoundedRect(guiGraphics, x + 1, y + 1, width - 2, height - 2, radius - 1, 0x40FFFFFF);
        drawRoundedRect(guiGraphics, x + 2, y + 2, width - 4, height / 2 - 2, radius - 2, SHADOW_LIGHT);
    }

    private void drawGradientBorder(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius) {
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int color = lerpColor(ACCENT_PRIMARY, ACCENT_SECONDARY, ratio);
            guiGraphics.fill(x + radius, y + i, x + width - radius, y + i + 1, color);
        }
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int color = lerpColor(ACCENT_SECONDARY, ACCENT_PRIMARY, ratio);
            guiGraphics.fill(x + radius, y + height - 3 + i, x + width - radius, y + height - 2 + i, color);
        }
        for (int i = 0; i < 3; i++) {
            guiGraphics.fill(x + i, y + radius, x + i + 1, y + height - radius, ACCENT_PRIMARY);
        }
        for (int i = 0; i < 3; i++) {
            guiGraphics.fill(x + width - 3 + i, y + radius, x + width - 2 + i, y + height - radius, ACCENT_SECONDARY);
        }
    }

    private void drawGradientLine(GuiGraphics guiGraphics, int x1, int y, int x2, int y2, int color1, int color2) {
        int length = x2 - x1;
        for (int i = 0; i < length; i++) {
            float ratio = i / (float)length;
            int color = lerpColor(color1, color2, ratio);
            guiGraphics.fill(x1 + i, y, x1 + i + 1, y + 1, color);
        }
    }

    private void drawStarIcon(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.fill(x + 4, y, x + 6, y + 1, color);
        guiGraphics.fill(x + 3, y + 1, x + 7, y + 2, color);
        guiGraphics.fill(x + 2, y + 2, x + 8, y + 3, color);
        guiGraphics.fill(x + 1, y + 3, x + 9, y + 4, color);
        guiGraphics.fill(x + 2, y + 4, x + 8, y + 5, color);
        guiGraphics.fill(x + 3, y + 5, x + 7, y + 6, color);
        guiGraphics.fill(x + 4, y + 6, x + 6, y + 7, color);
        guiGraphics.fill(x + 3, y + 7, x + 4, y + 8, color);
        guiGraphics.fill(x + 5, y + 7, x + 6, y + 8, color);
    }

    private int lerpColor(int color1, int color2, float ratio) {
        int a1 = (color1 >> 24) & 0xFF;
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int a2 = (color2 >> 24) & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int a = (int)(a1 + (a2 - a1) * ratio);
        int r = (int)(r1 + (r2 - r1) * ratio);
        int g = (int)(g1 + (g2 - g1) * ratio);
        int b = (int)(b1 + (b2 - b1) * ratio);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
