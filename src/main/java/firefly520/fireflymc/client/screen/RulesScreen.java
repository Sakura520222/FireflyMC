package firefly520.fireflymc.client.screen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.RulesContent;
import firefly520.fireflymc.client.RulesLoader;
import firefly520.fireflymc.network.ConfirmRulesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import java.net.URI;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务器准则弹窗Screen
 * 现代化樱花主题风格：毛玻璃背景、柔和渐变、优雅动画
 * 支持从网络异步加载公告内容
 */
public class RulesScreen extends Screen {
    // 官网地址
    private static final String WEBSITE_URL = "https://mc.firefly520.top";

    // 现代化樱花主题配色
    private static final int
        ACCENT_PRIMARY = 0xFFFF69B4, // 热粉红（主色）
        ACCENT_SECONDARY = 0xFFFF1493, // 深粉红（强调）
        TEXT_PRIMARY = 0xFF2D2D2D,   // 主文字
        TEXT_SECONDARY = 0xFF666666, // 次要文字
        HIGHLIGHT_COLOR = 0xFFFF1493, // 深粉色强调色
        SHADOW_LIGHT = 0x30FFFFFF,   // 高光
        SHADOW_DARK = 0x40000000,    // 阴影
        SCROLLBAR_BG = 0x20888888,   // 滚动条背景
        SCROLLBAR_THUMB = 0x80FFC0CB; // 滚动条滑块

    // 自动关闭时间：3秒（60 tick）
    private static final int AUTO_CLOSE_TICKS = 3 * 20;

    private final boolean isFirstJoin;
    private CompletableFuture<RulesContent> rulesFuture;
    private RulesContent rules;
    private boolean rulesLoaded = false;
    private String loadError = null;
    private int tickCount = 0;

    // 滚动状态变量
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private int visibleHeight;

    public RulesScreen(boolean isFirstJoin) {
        super(Component.literal("服务器准则"));
        this.isFirstJoin = isFirstJoin;
        // 异步加载公告，不阻塞主线程
        this.rulesFuture = CompletableFuture.supplyAsync(RulesLoader::loadRules);
        this.rulesFuture.whenComplete((r, e) -> {
            Minecraft.getInstance().execute(() -> {
                if (e != null || r == null) {
                    this.loadError = "无法加载服务器公告，请检查网络连接";
                } else {
                    this.rules = r;
                }
                this.rulesLoaded = true;
            });
        });
    }

    @Override
    protected void init() {
        super.init();

        // 首次加入：显示确认按钮和官网按钮
        if (isFirstJoin) {
            int buttonWidth = 120;
            int buttonHeight = 25;
            int buttonSpacing = 10;
            int totalWidth = buttonWidth * 2 + buttonSpacing;
            int startX = this.width / 2 - totalWidth / 2;

            // 访问官网按钮（左侧）
            this.addRenderableWidget(
                    Button.builder(
                            Component.literal("§b访问官网"),
                            button -> onVisitWebsite()
                    ).bounds(
                            startX,
                            this.height - 80,
                            buttonWidth,
                            buttonHeight
                    ).build()
            );

            // 同意准则按钮（右侧）
            this.addRenderableWidget(
                    Button.builder(
                            Component.literal("§a我已阅读并同意准则"),
                            button -> onConfirm()
                    ).bounds(
                            startX + buttonWidth + buttonSpacing,
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
        scrollOffset -= (int)(verticalAmount * 20);

        // 限制滚动范围
        int maxScroll = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        return true;
    }

    // 【关键】重写 renderBackground，去掉默认黑色遮罩
    @Override
    public void renderBackground(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 不调用 super.renderBackground()，去掉默认的黑色遮罩
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 计算弹窗尺寸
        int dialogWidth = Math.min(520, this.width - 40);
        int dialogHeight = Math.min(420, this.height - 80);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        // 绘制阴影
        drawRoundedRect(guiGraphics, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);

        // 绘制毛玻璃效果背景
        drawFrostedGlassBackground(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        // 绘制渐变边框
        drawGradientBorder(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        // 绘制标题
        Component title;
        if (rulesLoaded && rules != null) {
            title = Component.literal("FireflyMC 服务器公告 " + rules.version());
        } else {
            title = Component.literal("FireflyMC 服务器公告");
        }
        int titleX = this.width / 2 - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title.getVisualOrderText(),
                (float)titleX, (float)(dialogY + 20), ACCENT_SECONDARY, false);

        // 装饰性星星图标
        drawStarIcon(guiGraphics, titleX - 18, dialogY + 22, ACCENT_PRIMARY);
        drawStarIcon(guiGraphics, titleX + this.font.width(title) + 14, dialogY + 22, ACCENT_PRIMARY);

        // 绘制更新日期和官网（标题下方，固定位置，居中对齐）
        if (rulesLoaded && rules != null) {
            int infoY = dialogY + 36;
            StringBuilder info = new StringBuilder();
            if (!rules.updateDate().isEmpty()) {
                info.append(rules.updateDate());
            }
            if (!rules.website().isEmpty()) {
                if (info.length() > 0) info.append("  |  ");
                info.append(rules.website());
            }
            if (info.length() > 0) {
                Component infoText = Component.literal(info.toString());
                // 先计算缩放后的宽度，实现居中对齐
                float scale = 0.8f;
                int textWidth = this.font.width(infoText);
                int scaledWidth = (int)(textWidth * scale);
                int infoX = (this.width - scaledWidth) / 2;
                guiGraphics.pose().pushPose();
                guiGraphics.pose().scale(scale, scale, 1.0f);
                float scaledX = infoX / scale;
                float scaledY = infoY / scale;
                guiGraphics.drawString(this.font, infoText.getVisualOrderText(),
                        scaledX, scaledY, TEXT_SECONDARY, false);
                guiGraphics.pose().popPose();
            }
        }

        // 绘制渐变分隔线
        int separatorY = dialogY + 52;
        drawGradientLine(guiGraphics, dialogX + 20, separatorY, dialogX + dialogWidth - 20, separatorY,
                ACCENT_PRIMARY, ACCENT_SECONDARY);

        // 计算内容区域边界
        int contentTopY = separatorY + 15;
        int contentBottomY = dialogY + dialogHeight - 60;
        visibleHeight = contentBottomY - contentTopY;

        // 加载中状态显示
        if (!rulesLoaded) {
            Component loadingText = Component.literal("§e正在加载公告...");
            int loadingX = this.width / 2 - this.font.width(loadingText) / 2;
            guiGraphics.drawString(this.font, loadingText.getVisualOrderText(),
                    (float)loadingX, (float)(separatorY + 30), 0xFFFFFF00, false);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        // 错误状态显示
        if (loadError != null) {
            Component errorText = Component.literal("§c" + loadError);
            int errorX = this.width / 2 - this.font.width(errorText) / 2;
            guiGraphics.drawString(this.font, errorText.getVisualOrderText(),
                    (float)errorX, (float)(separatorY + 30), 0xFFFF0000, false);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        // 设置裁剪区域
        guiGraphics.enableScissor(dialogX, contentTopY, dialogX + dialogWidth, contentBottomY);

        // 应用滚动偏移
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, -scrollOffset, 0);

        // 绘制准则内容
        int contentY = contentTopY;
        int lineHeight = 16;
        int startX = dialogX + 25;

        // 记录内容起始位置，用于计算总高度
        int contentStartY = contentTopY;

        // 渲染章节内容（只使用网络数据）
        for (RulesContent.Section section : rules.sections()) {
            // 绘制章节标题
            guiGraphics.drawString(this.font,
                Component.literal(section.title()).getVisualOrderText(),
                (float)startX, (float)contentY, HIGHLIGHT_COLOR, false);
            contentY += lineHeight;

            // 绘制章节内容
            for (String line : section.lines()) {
                contentY = drawWrappedText(guiGraphics,
                    Component.literal("●" + line), startX, contentY,
                    dialogWidth - 50, lineHeight);
            }
            contentY += lineHeight / 2;
        }

        // 绘制说明
        if (!rules.description().isEmpty()) {
            contentY += lineHeight / 2;
            contentY = drawWrappedText(guiGraphics,
                Component.literal("▷ " + rules.description()),
                startX, contentY, dialogWidth - 50, lineHeight);
        }

        // 绘制联系方式
        if (!rules.contact().isEmpty()) {
            contentY += lineHeight / 2;
            contentY = drawWrappedText(guiGraphics,
                Component.literal(rules.contact()),
                startX, contentY, dialogWidth - 50, lineHeight);
        }

        // 记录内容总高度
        contentHeight = contentY - contentStartY;

        // 恢复 pose 状态
        guiGraphics.pose().popPose();

        // 关闭裁剪
        guiGraphics.disableScissor();

        // 绘制滚动条（当内容超出时）
        if (contentHeight > visibleHeight) {
            drawScrollbar(guiGraphics, dialogX, dialogWidth, contentTopY, visibleHeight, contentHeight);
        }

        // 非首次加入：显示倒计时（手动居中，禁用阴影）
        if (!isFirstJoin) {
            int remainingSeconds = (AUTO_CLOSE_TICKS - tickCount) / 20 + 1;
            Component countdown = Component.literal("§e" + remainingSeconds + " 秒后自动关闭");
            int countdownX = this.width / 2 - this.font.width(countdown) / 2;
            guiGraphics.drawString(this.font, countdown.getVisualOrderText(),
                    (float)countdownX, (float)(dialogY + dialogHeight + 20), 0xFFFFFF00, false);
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

    private void onVisitWebsite() {
        // 打开官网链接
        try {
            Util.getPlatform().openUri(URI.create(WEBSITE_URL));
        } catch (Exception e) {
            // URL打开失败
        }
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
     * 绘制滚动条（现代化样式）
     */
    private void drawScrollbar(GuiGraphics guiGraphics, int dialogX, int dialogWidth, int contentTopY, int visibleHeight, int contentHeight) {
        int scrollbarWidth = 6;
        int scrollbarX = dialogX + dialogWidth - 12;
        int scrollbarHeight = visibleHeight;

        float scrollRatio = (float) visibleHeight / contentHeight;
        int thumbHeight = Math.max(20, (int)(scrollbarHeight * scrollRatio));

        // 计算滑块位置
        int thumbY;
        if (contentHeight > visibleHeight) {
            thumbY = contentTopY + (int)((float) scrollOffset / (contentHeight - visibleHeight) * (scrollbarHeight - thumbHeight));
        } else {
            thumbY = contentTopY;
        }

        // 绘制滚动条背景（半透明）
        drawRoundedRect(guiGraphics, scrollbarX, contentTopY, scrollbarWidth, scrollbarHeight, 3, SCROLLBAR_BG);

        // 绘制滚动滑块（樱花粉色渐变）
        drawRoundedRect(guiGraphics, scrollbarX, thumbY, scrollbarWidth, thumbHeight, 3, SCROLLBAR_THUMB);
        // 滑块高光
        drawRoundedRect(guiGraphics, scrollbarX + 1, thumbY + 1, scrollbarWidth - 2, 2, 1, 0x40FFFFFF);
    }

    /**
     * 绘制自动换行的文本
     * 使用 font.split() 方法实现标准文本换行，禁用阴影效果避免重影
     */
    private int drawWrappedText(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int lineHeight) {
        // 使用 font.split() 将文本按宽度分割成多行
        List<FormattedCharSequence> lines = this.font.split(text, maxWidth);

        // 逐行渲染，每行一次 drawString 调用，显式禁用阴影效果
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, (float)x, (float)y, TEXT_PRIMARY, false);
            y += lineHeight;
        }
        return y;
    }

    // ==================== 新增辅助绘制方法 ====================

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
}
