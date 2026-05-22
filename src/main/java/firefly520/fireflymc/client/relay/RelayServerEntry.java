package firefly520.fireflymc.client.relay;

import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 联机大厅房间在原版服务器列表中的自定义条目。
 * <p>
 * 外观接近原版 OnlineServerEntry，但点击时走 RelayGuestJoiner.join() 流程，
 * 不写入 servers.dat。
 */
@OnlyIn(Dist.CLIENT)
public class RelayServerEntry extends ServerSelectionList.Entry {

    private static final int ACCENT_PRIMARY = 0xFFFF69B4;
    private static final int ACCENT_SECONDARY = 0xFFFF1493;
    private static final int TEXT_SECONDARY = 0xFF888888;

    private final JoinMultiplayerScreen screen;
    private final Minecraft minecraft;
    private final RelayLobbyRoom room;
    private long lastClickTime;

    public RelayServerEntry(JoinMultiplayerScreen screen, RelayLobbyRoom room) {
        this.screen = screen;
        this.minecraft = Minecraft.getInstance();
        this.room = room;
    }

    @Override
    public void render(
            GuiGraphics guiGraphics,
            int index,
            int top,
            int left,
            int width,
            int height,
            int mouseX,
            int mouseY,
            boolean hovering,
            float partialTick
    ) {
        Font font = this.minecraft.font;

        // 服务器名称 — 使用房间世界名
        String displayName = "[FireflyMC] " + room.worldName();
        guiGraphics.drawString(font, displayName, left + 32 + 3, top + 1, ACCENT_PRIMARY, false);

        // 描述行 — 房主 + 玩家数 + P2P/中继
        String desc = "房主: " + room.hostPlayerName();
        List<FormattedCharSequence> descLines = font.split(Component.literal(desc), width - 32 - 2);
        for (int i = 0; i < Math.min(descLines.size(), 1); i++) {
            guiGraphics.drawString(font, descLines.get(i), left + 32 + 3, top + 12 + 9 * i, TEXT_SECONDARY, false);
        }

        // 第三行 — 版本 + 连接方式
        String versionInfo = "MC " + room.minecraftVersion()
                + "  " + (room.p2pSupported() ? "P2P优先" : "中继")
                + "  " + room.currentPlayers() + "/" + room.maxPlayers() + " 玩家";
        guiGraphics.drawString(font, versionInfo, left + 32 + 3, top + 12 + 11, 3158064, false);

        // 绘制图标区域背景（粉色渐变方块，标识为 FireflyMC 房间）
        guiGraphics.fill(left, top, left + 32, top + 32, 0x40FF69B4);
        guiGraphics.fill(left + 2, top + 2, left + 30, top + 30, 0x60FF1493);

        // 绘制 "FF" 标识
        int iconCenterX = left + 16;
        int iconCenterY = top + 16;
        guiGraphics.drawString(font, "FF", iconCenterX - font.width("FF") / 2, iconCenterY - 4, 0xFFFFFF, false);

        // 悬停效果
        if (hovering) {
            guiGraphics.fill(left, top, left + 32, top + 32, -1601138544);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.screen.setSelected(this);
        if (Util.getMillis() - this.lastClickTime < 250L) {
            // 双击 — 通过桥接层加入
            RelayServerListBridge.joinRoom(this.screen, this.room);
        }
        this.lastClickTime = Util.getMillis();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public RelayLobbyRoom getRoom() {
        return this.room;
    }

    @Override
    public Component getNarration() {
        return Component.translatable("narrator.select",
                Component.empty()
                        .append(Component.literal("[FireflyMC] " + room.worldName()))
                        .append(Component.literal(" 房主: " + room.hostPlayerName()))
                        .append(Component.literal(" " + room.currentPlayers() + "/" + room.maxPlayers())));
    }
}
