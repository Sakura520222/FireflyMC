package firefly520.fireflymc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import javax.annotation.Nonnull;
import java.net.URI;

/**
 * 模组更新通知界面
 * 现代化设计风格：毛玻璃背景、柔和渐变、优雅动画
 */
public class ModUpdateScreen extends Screen {
    private final Minecraft mc = Minecraft.getInstance();

    // 动画相关
    private float animationProgress = 0f;
    private long openTime = System.currentTimeMillis();

    // 配色方案 - 渐变樱花粉
    private static final int
        ACCENT_PRIMARY = 0xFFFF69B4,   // 热粉红（主色）
        ACCENT_SECONDARY = 0xFFFF1493, // 深粉红（强调）
        TEXT_PRIMARY = 0xFF2D2D2D,     // 主文字
        TEXT_SECONDARY = 0xFF666666,   // 次要文字
        SHADOW_LIGHT = 0x30FFFFFF;     // 高光

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
        // 计算动画进度（平滑淡入，400ms）
        long elapsed = System.currentTimeMillis() - openTime;
        animationProgress = Mth.clamp(elapsed / 400f, 0f, 1f);
        float easedAlpha = easeOutCubic(animationProgress);

        // 1. 半透明深色背景遮罩（带动画）
        int bgAlpha = (int)(180 * easedAlpha);
        guiGraphics.fill(0, 0, width, height, (bgAlpha << 24) | 0x000000);

        // 2. 中央通知框（带缩放动画）
        float scale = 0.9f + 0.1f * easedAlpha; // 从0.9缩放到1.0
        int boxWidth = 420;
        int boxHeight = 300;
        float scaledWidth = boxWidth * scale;
        float scaledHeight = boxHeight * scale;
        int boxX = (int)((width - scaledWidth) / 2);
        int boxY = (int)((height - scaledHeight) / 2);

        guiGraphics.pose().pushPose();

        // 绘制阴影（多层营造深度）
        int shadowOffset = (int)(8 * easedAlpha);
        drawRoundedRect(guiGraphics, boxX + shadowOffset, boxY + shadowOffset,
                (int)scaledWidth, (int)scaledHeight, 12, 0x20000000);

        // 绘制毛玻璃效果背景
        drawFrostedGlassBackground(guiGraphics, boxX, boxY, (int)scaledWidth, (int)scaledHeight, 12);

        // 绘制渐变边框
        drawGradientBorder(guiGraphics, boxX, boxY, (int)scaledWidth, (int)scaledHeight, 12);

        // 恢复正常坐标系
        guiGraphics.pose().popPose();

        // 3. 文字内容（使用固定坐标系）
        Font font = mc.font;
        int centerX = boxX + (int)scaledWidth / 2;

        // 标题区域 - 带图标效果
        String title = "发现新版本";
        int titleY = boxY + 35;
        int titleWidth = font.width(title);
        guiGraphics.drawString(font, title, centerX - titleWidth / 2, titleY, ACCENT_SECONDARY, false);

        // 装饰性星星图标
        drawStarIcon(guiGraphics, centerX - titleWidth / 2 - 20, titleY + 2, ACCENT_PRIMARY);
        drawStarIcon(guiGraphics, centerX + titleWidth / 2 + 12, titleY + 2, ACCENT_PRIMARY);

        // 渐变分隔线
        int separatorY = boxY + 60;
        drawGradientLine(guiGraphics, boxX + 40, separatorY, boxX + (int)scaledWidth - 40, separatorY,
                ACCENT_PRIMARY, ACCENT_SECONDARY);

        // 版本信息卡片
        String versionText = ClientState.updateVersion != null ? ClientState.updateVersion : "最新版本";
        String modName = "FireflyMC 模组";
        String currentVer = "当前版本  2.3.4";
        String latestVer = "最新版本  " + versionText;
        String desc = "检测到新版本可用，请更新以继续游玩FireflyMC";

        int cardY = separatorY + 25;

        // 模组名称（大号）
        guiGraphics.drawString(font, modName, centerX - font.width(modName) / 2, cardY, ACCENT_PRIMARY, false);

        // 版本对比卡片
        int cardWidth = (int)scaledWidth - 60;
        int cardHeight = 70;
        int cardX = boxX + 30;
        int cardInnerY = cardY + 20;

        // 卡片背景（浅色半透明）
        drawRoundedRect(guiGraphics, cardX, cardInnerY, cardWidth, cardHeight, 8, 0x30FFFFFF);

        // 当前版本
        guiGraphics.drawString(font, currentVer, cardX + 20, cardInnerY + 12, TEXT_PRIMARY, false);
        // 最新版本（带强调色背景标记）
        String verHighlight = "NEW";
        int verHighlightWidth = font.width(verHighlight);
        int latestVerX = cardX + 20;
        guiGraphics.drawString(font, latestVer, latestVerX + verHighlightWidth + 8, cardInnerY + 38, ACCENT_SECONDARY, false);
        drawRoundedRect(guiGraphics, latestVerX, cardInnerY + 36, verHighlightWidth + 4, 12, 3, ACCENT_PRIMARY);
        guiGraphics.drawString(font, verHighlight, latestVerX + 2, cardInnerY + 37, 0xFFFFFFFF, false);

        // 描述文字
        int descY = cardInnerY + cardHeight + 12;
        guiGraphics.drawString(font, desc, centerX - font.width(desc) / 2, descY, TEXT_SECONDARY, false);

        // 安装提示（分成两行显示）
        int installTipY = descY + 20;
        String tipLine1 = "下载后请添加至整合包的mod文件夹";
        String tipLine2 = "并删除旧版本，然后重启游戏即可";
        guiGraphics.drawString(font, tipLine1, centerX - font.width(tipLine1) / 2, installTipY, TEXT_SECONDARY, false);
        guiGraphics.drawString(font, tipLine2, centerX - font.width(tipLine2) / 2, installTipY + 14, TEXT_SECONDARY, false);

        // 4. 现代化下载按钮
        int buttonWidth = 170;
        int buttonHeight = 38;
        int buttonX = centerX - buttonWidth / 2;
        int buttonY = boxY + (int)scaledHeight - 55;

        // 按钮阴影
        drawRoundedRect(guiGraphics, buttonX + 3, buttonY + 3, buttonWidth, buttonHeight, 10, 0x30000000);

        // 检测悬停状态
        boolean isHovered = mouseX >= buttonX && mouseX <= buttonX + buttonWidth &&
                          mouseY >= buttonY && mouseY <= buttonY + buttonHeight;

        // 按钮渐变背景
        drawButtonGradient(guiGraphics, buttonX, buttonY, buttonWidth, buttonHeight, 10, isHovered);

        // 按钮边框
        drawRoundedBorder(guiGraphics, buttonX, buttonY, buttonWidth, buttonHeight, 10, ACCENT_SECONDARY, 2);

        // 按钮文字
        String buttonText = "立即下载更新";
        int buttonTextWidth = font.width(buttonText);
        guiGraphics.drawString(font, buttonText, buttonX + (buttonWidth - buttonTextWidth) / 2, buttonY + 12, 0xFFFFFFFF, false);

        // 保存按钮区域（用于点击检测）
        ClientState.updateNotificationX = buttonX;
        ClientState.updateNotificationY = buttonY;
        ClientState.updateNotificationWidth = buttonWidth;
        ClientState.updateNotificationHeight = buttonHeight;

        // 5. 优雅的跳过按钮（右上角圆形）
        int skipSize = 28;
        int skipX = boxX + (int)scaledWidth - skipSize - 15;
        int skipY = boxY + 15;

        ClientState.updateNotificationSkipX = skipX;
        ClientState.updateNotificationSkipY = skipY;
        ClientState.updateNotificationSkipSize = skipSize;

        boolean skipHovered = mouseX >= skipX && mouseX <= skipX + skipSize &&
                             mouseY >= skipY && mouseY <= skipY + skipSize;

        // 圆形跳过按钮背景
        fillCircle(guiGraphics, skipX + skipSize / 2, skipY + skipSize / 2, skipSize / 2,
                   skipHovered ? 0x60FFFFFF : 0x40FFFFFF);
        // X 图标
        String xMark = "✕";
        guiGraphics.drawString(font, xMark, skipX + skipSize / 2 - font.width(xMark) / 2,
                              skipY + skipSize / 2 - 5, skipHovered ? 0xFFFFFFFF : 0xDDFFFFFF, false);
    }

    // ==================== 辅助绘制方法 ====================

    /**
     * 缓动函数 - 淡出立方体
     */
    private float easeOutCubic(float t) {
        return 1 - (float)Math.pow(1 - t, 3);
    }

    /**
     * 绘制毛玻璃效果背景
     */
    private void drawFrostedGlassBackground(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius) {
        // 多层半透明叠加创造毛玻璃效果
        drawRoundedRect(guiGraphics, x, y, width, height, radius, 0xDDFAFAFA);
        drawRoundedRect(guiGraphics, x + 1, y + 1, width - 2, height - 2, radius - 1, 0x40FFFFFF);

        // 内部高光
        drawRoundedRect(guiGraphics, x + 2, y + 2, width - 4, height / 2 - 2, radius - 2, SHADOW_LIGHT);
    }

    /**
     * 绘制渐变边框
     */
    private void drawGradientBorder(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius) {
        // 顶部渐变
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int color = lerpColor(ACCENT_PRIMARY, ACCENT_SECONDARY, ratio);
            guiGraphics.fill(x + radius, y + i, x + width - radius, y + i + 1, color);
        }
        // 底部渐变
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int color = lerpColor(ACCENT_SECONDARY, ACCENT_PRIMARY, ratio);
            guiGraphics.fill(x + radius, y + height - 3 + i, x + width - radius, y + height - 2 + i, color);
        }
        // 左边
        for (int i = 0; i < 3; i++) {
            guiGraphics.fill(x + i, y + radius, x + i + 1, y + height - radius, ACCENT_PRIMARY);
        }
        // 右边
        for (int i = 0; i < 3; i++) {
            guiGraphics.fill(x + width - 3 + i, y + radius, x + width - 2 + i, y + height - radius, ACCENT_SECONDARY);
        }
    }

    /**
     * 绘制渐变按钮背景
     */
    private void drawButtonGradient(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, boolean isHovered) {
        // 从上到下的渐变
        int steps = height;
        for (int i = 0; i < steps; i++) {
            float ratio = i / (float)steps;
            int color;
            if (isHovered) {
                // 悬停时更亮
                color = lerpColor(0xFFFF85C0, 0xFFDB4092, ratio);
            } else {
                color = lerpColor(ACCENT_PRIMARY, ACCENT_SECONDARY, ratio);
            }

            // 限制在圆角矩形内
            int y1 = y + i;
            for (int xi = 0; xi < width; xi++) {
                int x1 = x + xi;
                if (isInsideRoundedRect(xi, i, width, height, radius)) {
                    guiGraphics.fill(x1, y1, x1 + 1, y1 + 1, color);
                }
            }
        }
    }

    /**
     * 绘制渐变线条
     */
    private void drawGradientLine(GuiGraphics guiGraphics, int x1, int y, int x2, int y2, int color1, int color2) {
        int length = x2 - x1;
        for (int i = 0; i < length; i++) {
            float ratio = i / (float)length;
            int color = lerpColor(color1, color2, ratio);
            guiGraphics.fill(x1 + i, y, x1 + i + 1, y + 1, color);
        }
    }

    /**
     * 绘制星星装饰图标
     */
    private void drawStarIcon(GuiGraphics guiGraphics, int x, int y, int color) {
        // 简化的星星图案
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

    /**
     * 颜色插值
     */
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

    /**
     * 判断点是否在圆角矩形内
     */
    private boolean isInsideRoundedRect(int x, int y, int width, int height, int radius) {
        if (x < radius && y < radius) {
            return (x - radius) * (x - radius) + (y - radius) * (y - radius) <= radius * radius;
        }
        if (x >= width - radius && y < radius) {
            return (x - (width - radius)) * (x - (width - radius)) + (y - radius) * (y - radius) <= radius * radius;
        }
        if (x < radius && y >= height - radius) {
            return (x - radius) * (x - radius) + (y - (height - radius)) * (y - (height - radius)) <= radius * radius;
        }
        if (x >= width - radius && y >= height - radius) {
            return (x - (width - radius)) * (x - (width - radius)) + (y - (height - radius)) * (y - (height - radius)) <= radius * radius;
        }
        return true;
    }

    /**
     * 绘制圆角矩形
     */
    private void drawRoundedRect(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color) {
        // 中间部分
        guiGraphics.fill(x + radius, y, x + width - radius, y + height, color);
        guiGraphics.fill(x, y + radius, x + width, y + height - radius, color);
        // 四个角的圆
        fillCircle(guiGraphics, x + radius, y + radius, radius, color);
        fillCircle(guiGraphics, x + width - radius, y + radius, radius, color);
        fillCircle(guiGraphics, x + radius, y + height - radius, radius, color);
        fillCircle(guiGraphics, x + width - radius, y + height - radius, radius, color);
    }

    /**
     * 绘制圆角边框
     */
    private void drawRoundedBorder(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color, int thickness) {
        // 上边
        guiGraphics.fill(x + radius, y, x + width - radius, y + thickness, color);
        // 下边
        guiGraphics.fill(x + radius, y + height - thickness, x + width - radius, y + height, color);
        // 左边
        guiGraphics.fill(x, y + radius, x + thickness, y + height - radius, color);
        // 右边
        guiGraphics.fill(x + width - thickness, y + radius, x + width, y + height - radius, color);
    }

    /**
     * 填充圆形
     */
    private void fillCircle(GuiGraphics guiGraphics, int centerX, int centerY, int radius, int color) {
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                if (i * i + j * j <= radius * radius) {
                    guiGraphics.fill(centerX + i, centerY + j, centerX + i + 1, centerY + j + 1, color);
                }
            }
        }
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
