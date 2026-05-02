package firefly520.fireflymc.client.screen;

import firefly520.fireflymc.client.relay.RelayLobbyRoom;
import firefly520.fireflymc.client.relay.RelayLobbyState;
import firefly520.fireflymc.client.relay.RelayLobbyWebSocketClient;
import firefly520.fireflymc.client.relay.RelayGuestJoiner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * FireflyMC 单人世界公开大厅。
 *
 * 当前阶段展示房间列表；点击加入会提示后续实现 guest_join / 本地代理。
 */
public class RelayLobbyScreen extends Screen {
    private static final int ACCENT_PRIMARY = 0xFFFF69B4;
    private static final int ACCENT_SECONDARY = 0xFFFF1493;
    private static final int TEXT_PRIMARY = 0xFF2D2D2D;
    private static final int TEXT_SECONDARY = 0xFF666666;
    private static final int SHADOW_LIGHT = 0x30FFFFFF;
    private static final int SHADOW_DARK = 0x40000000;

    private final Screen parent;

    public RelayLobbyScreen(Screen parent) {
        super(Component.literal("FireflyMC 联机大厅"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 90;
        int buttonHeight = 24;
        int y = this.height - 48;
        int centerX = this.width / 2;

        this.addRenderableWidget(Button.builder(
                Component.literal("刷新"),
                button -> RelayLobbyWebSocketClient.getInstance().requestLobbyList()
        ).bounds(centerX - buttonWidth - 8, y, buttonWidth, buttonHeight).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("返回"),
                button -> onClose()
        ).bounds(centerX + 8, y, buttonWidth, buttonHeight).build());

        RelayLobbyWebSocketClient.getInstance().requestLobbyList();
    }

    @Override
    public void renderBackground(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 与公告弹窗一致：不调用默认背景
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int dialogWidth = Math.min(560, this.width - 40);
        int dialogHeight = Math.min(420, this.height - 80);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        drawRoundedRect(guiGraphics, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);
        drawFrostedGlassBackground(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);
        drawGradientBorder(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        Component title = Component.literal("FireflyMC 公开单人世界");
        int titleX = this.width / 2 - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title.getVisualOrderText(), titleX, dialogY + 20, ACCENT_SECONDARY, false);
        drawStarIcon(guiGraphics, titleX - 18, dialogY + 22, ACCENT_PRIMARY);
        drawStarIcon(guiGraphics, titleX + this.font.width(title) + 14, dialogY + 22, ACCENT_PRIMARY);

        int separatorY = dialogY + 52;
        drawGradientLine(guiGraphics, dialogX + 20, separatorY, dialogX + dialogWidth - 20, separatorY,
                ACCENT_PRIMARY, ACCENT_SECONDARY);

        String status = RelayLobbyState.isRefreshing() ? "正在刷新公开大厅..." : RelayLobbyState.statusMessage();
        drawCentered(guiGraphics, status, separatorY + 16, TEXT_SECONDARY);

        List<RelayLobbyRoom> rooms = RelayLobbyState.rooms();
        int listX = dialogX + 28;
        int listY = separatorY + 44;
        int rowWidth = dialogWidth - 56;
        int rowHeight = 46;

        if (rooms.isEmpty()) {
            drawCentered(guiGraphics, "暂无可加入的公开单人世界", listY + 36, TEXT_SECONDARY);
        } else {
            for (int i = 0; i < Math.min(rooms.size(), 6); i++) {
                RelayLobbyRoom room = rooms.get(i);
                int rowY = listY + i * (rowHeight + 8);
                drawRoomRow(guiGraphics, room, listX, rowY, rowWidth, rowHeight, mouseX, mouseY);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            RelayLobbyRoom clicked = getRoomAt(mouseX, mouseY);
            if (clicked != null) {
                RelayGuestJoiner.join(this, clicked);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private RelayLobbyRoom getRoomAt(double mouseX, double mouseY) {
        int dialogWidth = Math.min(560, this.width - 40);
        int dialogHeight = Math.min(420, this.height - 80);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;
        int listX = dialogX + 28;
        int listY = dialogY + 52 + 44;
        int rowWidth = dialogWidth - 56;
        int rowHeight = 46;

        List<RelayLobbyRoom> rooms = RelayLobbyState.rooms();
        for (int i = 0; i < Math.min(rooms.size(), 6); i++) {
            int rowY = listY + i * (rowHeight + 8);
            if (mouseX >= listX && mouseX <= listX + rowWidth && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                return rooms.get(i);
            }
        }
        return null;
    }

    private void drawRoomRow(GuiGraphics guiGraphics, RelayLobbyRoom room, int x, int y, int width, int height, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        int bgColor = hovered ? 0x60FFFFFF : 0x35FFFFFF;
        drawRoundedRect(guiGraphics, x, y, width, height, 6, bgColor);
        drawRoundedRect(guiGraphics, x, y, 4, height, 3, ACCENT_PRIMARY);

        guiGraphics.drawString(this.font, room.worldName(), x + 14, y + 8, TEXT_PRIMARY, false);
        guiGraphics.drawString(this.font, "房主: " + room.hostPlayerName(), x + 14, y + 26, TEXT_SECONDARY, false);

        String players = room.currentPlayers() + "/" + room.maxPlayers();
        guiGraphics.drawString(this.font, players, x + width - this.font.width(players) - 14, y + 8, ACCENT_SECONDARY, false);

        String version = "MC " + room.minecraftVersion() + " / Mod " + room.modVersion();
        guiGraphics.drawString(this.font, version, x + width - this.font.width(version) - 14, y + 26, TEXT_SECONDARY, false);
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
