package firefly520.fireflymc.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import firefly520.fireflymc.client.ClientState;
import net.minecraft.util.Mth;
import firefly520.fireflymc.network.ConfirmRulesPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务器准则弹窗Screen
 * 樱花主题风格
 */
public class RulesScreen extends Screen {
    // 樱花主题颜色
    private static final int BORDER_COLOR = 0xFFFFC0CB;      // 樱花粉边框
    private static final int BACKGROUND_COLOR = 0x40FFFFFF;  // 半透明白色背景
    private static final int TITLE_COLOR = 0xFFFF69B4;       // 樱花粉标题
    private static final int TEXT_COLOR = 0xFF333333;        // 深灰色文字
    private static final int HIGHLIGHT_COLOR = 0xFFFF1493;   // 深粉色强调色

    // 自动关闭时间：3秒（60 tick）
    private static final int AUTO_CLOSE_TICKS = 3 * 20;

    private final boolean isFirstJoin;
    private int tickCount = 0;

    // 滚动状态变量
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private int visibleHeight;

    public RulesScreen(boolean isFirstJoin) {
        super(Component.literal("服务器准则"));
        this.isFirstJoin = isFirstJoin;
    }

    @Override
    protected void init() {
        super.init();

        // 首次加入：显示确认按钮
        if (isFirstJoin) {
            int buttonWidth = 200;
            int buttonHeight = 25;
            this.addRenderableWidget(
                    Button.builder(
                            Component.translatable("fireflymc.rules.confirm"),
                            button -> onConfirm()
                    ).bounds(
                            this.width / 2 - buttonWidth / 2,
                            this.height - 80,
                            buttonWidth,
                            buttonHeight
                    ).build()
            );
        }
    }

    @Override
    public void tick() {
        super.tick();

        // 非首次加入：倒计时自动关闭
        if (!isFirstJoin) {
            tickCount++;
            if (tickCount >= AUTO_CLOSE_TICKS) {
                // 发送确认包并关闭
                PacketDistributor.sendToServer(new ConfirmRulesPayload());
                onClose();
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 每次滚动20像素（约一行文字高度）
        // 注意：verticalAmount 向上滚动为正，向下滚动为负，需要反转
        scrollOffset -= (int)(verticalAmount * 20);

        // 限制滚动范围
        int maxScroll = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        return true;
    }

    // 【关键】重写 renderBackground，去掉默认黑色遮罩
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 不调用 super.renderBackground()，去掉默认的黑色遮罩
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 计算弹窗尺寸
        int dialogWidth = Math.min(500, this.width - 40);
        int dialogHeight = Math.min(400, this.height - 80);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        // 绘制圆角背景
        drawRoundedRect(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 8, BACKGROUND_COLOR);

        // 绘制樱花粉色边框
        drawRoundedBorder(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 8, BORDER_COLOR, 2);

        // 绘制标题
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("fireflymc.rules.title"),
                this.width / 2,
                dialogY + 20,
                TITLE_COLOR
        );

        // 绘制分隔线
        int separatorY = dialogY + 45;
        guiGraphics.fill(dialogX + 10, separatorY, dialogX + dialogWidth - 10, separatorY + 1, BORDER_COLOR);

        // 计算内容区域边界
        int contentTopY = separatorY + 15;
        int contentBottomY = dialogY + dialogHeight - 60; // 留出底部信息空间
        visibleHeight = contentBottomY - contentTopY;

        // 设置裁剪区域
        guiGraphics.enableScissor(dialogX, contentTopY, dialogX + dialogWidth, contentBottomY);

        // 应用滚动偏移
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, -scrollOffset, 0);

        // 绘制准则内容
        int contentY = contentTopY;
        int lineHeight = 16;
        int startX = dialogX + 25;

        // 记录内容起始位置，用于计算总高度（在绘制任何内容之前）
        int contentStartY = contentTopY;

        // 行1：行为准则标题
        guiGraphics.drawString(this.font,
                Component.translatable("fireflymc.rules.section1"),
                startX, contentY, HIGHLIGHT_COLOR);
        contentY += lineHeight;

        // 行1-1到1-3
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section1_1"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section1_2"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section1_3"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY += lineHeight / 2;

        // 行2：领地规范标题
        guiGraphics.drawString(this.font,
                Component.translatable("fireflymc.rules.section2"),
                startX, contentY, HIGHLIGHT_COLOR);
        contentY += lineHeight;

        // 行2-1到2-7（完整内容）
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_1"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_2"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_3"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_4"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_5"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_6"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section2_7"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY += lineHeight / 2;

        // 行3：游戏守则标题
        guiGraphics.drawString(this.font,
                Component.translatable("fireflymc.rules.section3"),
                startX, contentY, HIGHLIGHT_COLOR);
        contentY += lineHeight;

        // 行3-1到3-4
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section3_1"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section3_4"),
                startX, contentY, dialogWidth - 50, lineHeight);
        contentY += lineHeight / 2;

        // 行4：违规处置标题
        guiGraphics.drawString(this.font,
                Component.translatable("fireflymc.rules.section4"),
                startX, contentY, HIGHLIGHT_COLOR);
        contentY += lineHeight;

        // 行4-1
        contentY = drawWrappedText(guiGraphics,
                Component.translatable("fireflymc.rules.section4_1"),
                startX, contentY, dialogWidth - 50, lineHeight);

        // 记录内容总高度
        contentHeight = contentY - contentStartY;

        // 恢复 pose 状态
        guiGraphics.pose().popPose();

        // 关闭裁剪
        guiGraphics.disableScissor();

        // 绘制滚动条（当内容超出时）
        if (contentHeight > visibleHeight) {
            int scrollbarWidth = 4;
            int scrollbarX = dialogX + dialogWidth - 10;
            int scrollbarHeight = visibleHeight;

            // 强制转换为float，避免整数除法
            float scrollRatio = (float) visibleHeight / contentHeight;
            int thumbHeight = (int)(scrollbarHeight * scrollRatio);

            // 计算滑块位置
            int thumbY;
            if (contentHeight > visibleHeight) {
                thumbY = contentTopY + (int)((float) scrollOffset / (contentHeight - visibleHeight) * (scrollbarHeight - thumbHeight));
            } else {
                thumbY = contentTopY;
            }

            // 绘制滚动条背景
            guiGraphics.fill(scrollbarX, contentTopY, scrollbarX + scrollbarWidth, contentTopY + scrollbarHeight, 0x40888888);
            // 绘制滚动滑块（樱花粉色）
            guiGraphics.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, 0x80FFC0CB);
        }

        // 底部信息
        int footerY = dialogY + dialogHeight - 50;
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("fireflymc.rules.contact"),
                this.width / 2,
                footerY,
                0xFF666666
        );

        // 非首次加入：显示倒计时
        if (!isFirstJoin) {
            int remainingSeconds = (AUTO_CLOSE_TICKS - tickCount) / 20 + 1;
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("fireflymc.rules.countdown", remainingSeconds),
                    this.width / 2,
                    dialogY + dialogHeight + 20,
                    0xFFFFFF00
            );
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // 首次加入：禁止ESC关闭
        return !isFirstJoin;
    }

    @Override
    public boolean isPauseScreen() {
        // 多人游戏不暂停
        return false;
    }

    @Override
    public void onClose() {
        // 更新客户端状态
        ClientState.hasSeenRulesThisSession = true;
        super.onClose();
    }

    private void onConfirm() {
        // 发送确认包
        PacketDistributor.sendToServer(new ConfirmRulesPayload());
        onClose();
    }

    /**
     * 绘制圆角矩形
     */
    private void drawRoundedRect(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius, int color) {
        guiGraphics.fill(x + radius, y, x + width - radius, y + height, color);
        guiGraphics.fill(x, y + radius, x + width, y + height - radius, color);
        guiGraphics.fill(x + radius, y, x + width - radius, y + radius, color);
        guiGraphics.fill(x + radius, y + height - radius, x + width - radius, y + height, color);
        // 绘制四个角的圆
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
     * 填充圆形（简化版，用多个小矩形模拟）
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

    /**
     * 绘制自动换行的文本
     */
    private int drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int lineHeight) {
        String plainText = text.getString();
        if (this.font.width(plainText) <= maxWidth) {
            guiGraphics.drawString(this.font, text, x, y, TEXT_COLOR);
            return y + lineHeight;
        }
        // 简单的换行处理
        int currentX = x;
        int currentY = y;
        for (char c : plainText.toCharArray()) {
            int charWidth = this.font.width(String.valueOf(c));
            if (currentX + charWidth > x + maxWidth) {
                currentX = x;
                currentY += lineHeight;
            }
            guiGraphics.drawString(this.font, Component.literal(String.valueOf(c)), currentX, currentY, TEXT_COLOR);
            currentX += charWidth;
        }
        return currentY + lineHeight;
    }
}
