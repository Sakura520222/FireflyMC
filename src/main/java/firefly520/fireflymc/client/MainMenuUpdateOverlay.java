package firefly520.fireflymc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.Util;
import java.net.URI;

/**
 * 主菜单更新通知覆盖层
 * 全屏显示更新通知，阻止主菜单交互
 */
public class MainMenuUpdateOverlay {

    private static final Minecraft mc = Minecraft.getInstance();

    /**
     * 渲染全屏更新通知
     */
    public static void onRenderScreen(ScreenEvent.Render.Post event) {
        // 只在主菜单显示
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }

        if (!ClientState.hasUpdateAvailable) {
            return;
        }

        GuiGraphics guiGraphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = event.getScreen().width;
        int screenHeight = event.getScreen().height;

        // 全屏半透明黑色背景
        int bgColor = 0xDD000000;
        guiGraphics.fill(0, 0, screenWidth, screenHeight, bgColor);

        // 中央通知框
        int boxWidth = 400;
        int boxHeight = 200;
        int boxX = (screenWidth - boxWidth) / 2;
        int boxY = (screenHeight - boxHeight) / 2;

        // 绘制通知框背景
        int boxBgColor = 0xFF222222;
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, boxBgColor);

        // 绘制樱花粉色边框
        int borderColor = 0xFFFFC0CB;
        int borderWidth = 3;
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + borderWidth, borderColor);
        guiGraphics.fill(boxX, boxY + boxHeight - borderWidth, boxX + boxWidth, boxY + boxHeight, borderColor);
        guiGraphics.fill(boxX, boxY, boxX + borderWidth, boxY + boxHeight, borderColor);
        guiGraphics.fill(boxX + boxWidth - borderWidth, boxY, boxX + boxWidth, boxY + boxHeight, borderColor);

        // 标题
        String title = "§d§l发现新版本！";
        int titleWidth = font.width(title);
        guiGraphics.drawString(font, title, boxX + (boxWidth - titleWidth) / 2, boxY + 25, 0xFFFFFF);

        // 分隔线
        guiGraphics.fill(boxX + 30, boxY + 55, boxX + boxWidth - 30, boxY + 56, borderColor);

        // 版本信息
        String versionText = ClientState.updateVersion != null ? ClientState.updateVersion : "最新版本";
        String modName = "§eFireflyMC 模组";
        String currentVer = "§f当前版本: §72.2.0";
        String latestVer = "§a最新版本: §f" + versionText;
        String desc = "§7检测到新版本可用，建议更新以获得最佳体验";

        int textY = boxY + 70;
        int centerX = boxX + boxWidth / 2;

        guiGraphics.drawString(font, modName, centerX - font.width(modName) / 2, textY, 0xFFFFFF);
        guiGraphics.drawString(font, currentVer, centerX - font.width(currentVer) / 2, textY + 20, 0xFFFFFF);
        guiGraphics.drawString(font, latestVer, centerX - font.width(latestVer) / 2, textY + 40, 0xFFFFFF);
        guiGraphics.drawString(font, desc, centerX - font.width(desc) / 2, textY + 65, 0xFFFFFF);

        // 下载按钮区域
        int buttonWidth = 160;
        int buttonHeight = 30;
        int buttonX = centerX - buttonWidth / 2;
        int buttonY = boxY + boxHeight - 50;

        // 保存按钮区域
        ClientState.updateNotificationX = buttonX;
        ClientState.updateNotificationY = buttonY;
        ClientState.updateNotificationWidth = buttonWidth;
        ClientState.updateNotificationHeight = buttonHeight;

        // 绘制按钮背景（樱花粉色）
        int buttonColor = 0xFFFF69B4;
        guiGraphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, buttonColor);

        // 按钮边框
        int buttonBorder = 0xFFFF1493;
        guiGraphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 2, buttonBorder);
        guiGraphics.fill(buttonX, buttonY + buttonHeight - 2, buttonX + buttonWidth, buttonY + buttonHeight, buttonBorder);
        guiGraphics.fill(buttonX, buttonY, buttonX + 2, buttonY + buttonHeight, buttonBorder);
        guiGraphics.fill(buttonX + buttonWidth - 2, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, buttonBorder);

        // 按钮文字
        String buttonText = "§f§l立即下载更新";
        int buttonTextWidth = font.width(buttonText);
        guiGraphics.drawString(font, buttonText, buttonX + (buttonWidth - buttonTextWidth) / 2, buttonY + 9, 0xFFFFFF);

        // 跳过按钮（右上角小按钮）
        int skipSize = 20;
        int skipX = boxX + boxWidth - skipSize - 10;
        int skipY = boxY + 10;

        ClientState.updateNotificationSkipX = skipX;
        ClientState.updateNotificationSkipY = skipY;
        ClientState.updateNotificationSkipSize = skipSize;

        guiGraphics.fill(skipX, skipY, skipX + skipSize, skipY + skipSize, 0x44FFFFFF);
        guiGraphics.drawString(font, "✕", skipX + 6, skipY + 5, 0xFFFFFF);
    }

    /**
     * 处理鼠标点击事件
     */
    public static void onMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        // 只在主菜单处理
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }

        if (!ClientState.hasUpdateAvailable) {
            return;
        }

        // 只响应左键点击 (button 0)
        if (event.getButton() != 0) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        // 检查是否点击了下载按钮
        if (mouseX >= ClientState.updateNotificationX &&
            mouseX <= ClientState.updateNotificationX + ClientState.updateNotificationWidth &&
            mouseY >= ClientState.updateNotificationY &&
            mouseY <= ClientState.updateNotificationY + ClientState.updateNotificationHeight) {

            System.out.println("[FireflyMC] Download button clicked!");
            // 打开下载链接
            if (ClientState.updateUrl != null && !ClientState.updateUrl.isEmpty()) {
                try {
                    Util.getPlatform().openUri(URI.create(ClientState.updateUrl));
                    // 点击后隐藏通知
                    ClientState.hasUpdateAvailable = false;
                } catch (Exception e) {
                    System.out.println("[FireflyMC] Failed to open URL: " + e.getMessage());
                }
            }
            event.setCanceled(true);
            return;
        }

        // 检查是否点击了跳过按钮
        if (mouseX >= ClientState.updateNotificationSkipX &&
            mouseX <= ClientState.updateNotificationSkipX + ClientState.updateNotificationSkipSize &&
            mouseY >= ClientState.updateNotificationSkipY &&
            mouseY <= ClientState.updateNotificationSkipY + ClientState.updateNotificationSkipSize) {

            System.out.println("[FireflyMC] Skip button clicked!");
            ClientState.hasUpdateAvailable = false;
            event.setCanceled(true);
            return;
        }

        // 点击其他地方也阻止，让用户必须处理更新通知
        event.setCanceled(true);
    }

    /**
     * 处理鼠标释放事件
     */
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        // 只在主菜单处理
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }

        if (!ClientState.hasUpdateAvailable) {
            return;
        }

        // 只响应左键 (button 0)
        if (event.getButton() != 0) {
            return;
        }

        // 在更新通知显示期间，阻止所有鼠标事件
        event.setCanceled(true);
    }

    /**
     * 处理键盘事件 - 允许ESC关闭
     */
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        // 只在主菜单处理
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }

        if (!ClientState.hasUpdateAvailable) {
            return;
        }

        // ESC键关闭通知
        if (event.getKeyCode() == 256) { // ESC = 256
            ClientState.hasUpdateAvailable = false;
            event.setCanceled(true);
            return;
        }

        // 其他按键也阻止
        event.setCanceled(true);
    }
}
