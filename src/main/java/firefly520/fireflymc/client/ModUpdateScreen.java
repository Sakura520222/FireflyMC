package firefly520.fireflymc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import javax.annotation.Nonnull;
import java.net.URI;

/**
 * 模组更新通知界面
 * 完全覆盖主菜单，强制用户处理更新通知
 */
public class ModUpdateScreen extends Screen {
    private final Minecraft mc = Minecraft.getInstance();

    public ModUpdateScreen() {
        super(Component.literal("模组更新"));
    }

    @Override
    protected void init() {
        super.init();
        // 按钮通过手动渲染和处理点击来保持原有 UI 风格
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. 全屏不透明黑色背景
        guiGraphics.fill(0, 0, width, height, 0xFF000000);

        // 2. 中央通知框
        int boxWidth = 400;
        int boxHeight = 220;
        int boxX = (width - boxWidth) / 2;
        int boxY = (height - boxHeight) / 2;

        // 通知框背景
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xFF222222);

        // 樱花粉色边框
        int borderColor = 0xFFFFC0CB;
        int borderWidth = 3;
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + borderWidth, borderColor);
        guiGraphics.fill(boxX, boxY + boxHeight - borderWidth, boxX + boxWidth, boxY + boxHeight, borderColor);
        guiGraphics.fill(boxX, boxY, boxX + borderWidth, boxY + boxHeight, borderColor);
        guiGraphics.fill(boxX + boxWidth - borderWidth, boxY, boxX + boxWidth, boxY + boxHeight, borderColor);

        // 3. 文字内容
        Font font = mc.font;

        // 标题
        String title = "§d§l发现新版本！";
        int titleWidth = font.width(title);
        guiGraphics.drawString(font, title, boxX + (boxWidth - titleWidth) / 2, boxY + 25, 0xFFFFFF);

        // 分隔线
        guiGraphics.fill(boxX + 30, boxY + 55, boxX + boxWidth - 30, boxY + 56, borderColor);

        // 版本信息
        String versionText = ClientState.updateVersion != null ? ClientState.updateVersion : "最新版本";
        String currentVer = "§f当前版本: §72.3.1";
        String latestVer = "§a最新版本: §f" + versionText;
        String desc = "§7检测到新版本可用，请更新以继续游玩FireflyMC";

        int textY = boxY + 70;
        int centerX = boxX + boxWidth / 2;

        guiGraphics.drawString(font, "§eFireflyMC 模组", centerX - font.width("§eFireflyMC 模组") / 2, textY, 0xFFFFFF);
        guiGraphics.drawString(font, currentVer, centerX - font.width(currentVer) / 2, textY + 20, 0xFFFFFF);
        guiGraphics.drawString(font, latestVer, centerX - font.width(latestVer) / 2, textY + 40, 0xFFFFFF);
        guiGraphics.drawString(font, desc, centerX - font.width(desc) / 2, textY + 65, 0xFFFFFF);

        // 安装提示
        String installTip = "§c下载后请添加至整合包的mod文件夹，并删除旧版本，然后重启游戏即可";
        guiGraphics.drawString(font, installTip, centerX - font.width(installTip) / 2, textY + 85, 0xFFFFFF);

        // 4. 下载按钮
        int buttonWidth = 160;
        int buttonHeight = 30;
        int buttonX = centerX - buttonWidth / 2;
        int buttonY = boxY + boxHeight - 50;

        int buttonColor = 0xFFFF69B4;
        guiGraphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, buttonColor);

        int buttonBorder = 0xFFFF1493;
        guiGraphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 2, buttonBorder);
        guiGraphics.fill(buttonX, buttonY + buttonHeight - 2, buttonX + buttonWidth, buttonY + buttonHeight, buttonBorder);
        guiGraphics.fill(buttonX, buttonY, buttonX + 2, buttonY + buttonHeight, buttonBorder);
        guiGraphics.fill(buttonX + buttonWidth - 2, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, buttonBorder);

        String buttonText = "§f§l立即下载更新";
        int buttonTextWidth = font.width(buttonText);
        guiGraphics.drawString(font, buttonText, buttonX + (buttonWidth - buttonTextWidth) / 2, buttonY + 9, 0xFFFFFF);

        // 保存按钮区域（用于点击检测）
        ClientState.updateNotificationX = buttonX;
        ClientState.updateNotificationY = buttonY;
        ClientState.updateNotificationWidth = buttonWidth;
        ClientState.updateNotificationHeight = buttonHeight;

        // 5. 跳过按钮（右上角）
        int skipSize = 20;
        int skipX = boxX + boxWidth - skipSize - 10;
        int skipY = boxY + 10;

        ClientState.updateNotificationSkipX = skipX;
        ClientState.updateNotificationSkipY = skipY;
        ClientState.updateNotificationSkipSize = skipSize;

        guiGraphics.fill(skipX, skipY, skipX + skipSize, skipY + skipSize, 0x44FFFFFF);
        guiGraphics.drawString(font, "✕", skipX + 6, skipY + 5, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false; // 只响应左键

        // 检查下载按钮
        if (mouseX >= ClientState.updateNotificationX &&
            mouseX <= ClientState.updateNotificationX + ClientState.updateNotificationWidth &&
            mouseY >= ClientState.updateNotificationY &&
            mouseY <= ClientState.updateNotificationY + ClientState.updateNotificationHeight) {

            if (ClientState.updateUrl != null && !ClientState.updateUrl.isEmpty()) {
                try {
                    Util.getPlatform().openUri(URI.create(ClientState.updateUrl));
                    ClientState.hasUpdateAvailable = false;
                } catch (Exception e) {
                    System.out.println("[FireflyMC] Failed to open URL: " + e.getMessage());
                }
            }
            // 返回主菜单
            mc.setScreen(new TitleScreen());
            return true;
        }

        // 检查跳过按钮
        if (mouseX >= ClientState.updateNotificationSkipX &&
            mouseX <= ClientState.updateNotificationSkipX + ClientState.updateNotificationSkipSize &&
            mouseY >= ClientState.updateNotificationSkipY &&
            mouseY <= ClientState.updateNotificationSkipY + ClientState.updateNotificationSkipSize) {

            mc.setScreen(new TitleScreen());
            return true;
        }

        // 其他区域不处理（保持显示）
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 返回主菜单（下次还会显示）
        if (keyCode == 256) {
            mc.setScreen(new TitleScreen());
            return true;
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
