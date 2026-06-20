package firefly520.fireflymc.client.screen;

import firefly520.fireflymc.network.PasswordSubmitPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nonnull;

/**
 * 密码验证弹窗
 * <p>
 * 首次加入：设置至少6位纯数字密码
 * 后续加入：验证密码
 */
public class PasswordAuthScreen extends Screen {
    private static final int
            ACCENT_PRIMARY = 0xFFFF69B4,
            ACCENT_SECONDARY = 0xFFFF1493,
            TEXT_PRIMARY = 0xFF2D2D2D,
            TEXT_SECONDARY = 0xFF666666,
            SHADOW_DARK = 0x40000000;

    private final boolean firstTime;
    private final String promptMessage;
    private final int remainingAttempts;

    private EditBox passwordInput;
    private String errorMessage = null;
    public PasswordAuthScreen(boolean firstTime, String message, int remainingAttempts) {
        super(Component.literal(firstTime ? "设置服务器密码" : "验证服务器密码"));
        this.firstTime = firstTime;
        this.promptMessage = message;
        this.remainingAttempts = remainingAttempts;
    }

    @Override
    protected void init() {
        super.init();

        int dialogWidth = Math.min(360, this.width - 40);
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - 200) / 2;

        // 密码输入框
        this.passwordInput = new EditBox(
                this.font,
                dialogX + 30,
                dialogY + 80,
                dialogWidth - 60,
                20,
                Component.literal("密码")
        );
        this.passwordInput.setMaxLength(32);
        this.passwordInput.setHint(Component.literal("至少6位纯数字").withStyle(s -> s.withColor(TEXT_SECONDARY)));
        this.passwordInput.setResponder(text -> errorMessage = null);
        this.addRenderableWidget(this.passwordInput);

        // 提交按钮
        int buttonWidth = 120;
        int buttonHeight = 24;
        int buttonX = this.width / 2 - buttonWidth / 2;
        int buttonY = dialogY + 120;

        this.addRenderableWidget(
                Button.builder(
                        Component.literal(firstTime ? "§a确认设置" : "§a确认验证"),
                        button -> onSubmit()
                ).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build()
        );

        // 输入框默认聚焦，方便玩家直接输入
        this.setFocused(this.passwordInput);
    }

    @Override
    public void renderBackground(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 不调用 super，去掉默认黑色遮罩
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 计算弹窗尺寸
        int dialogWidth = Math.min(360, this.width - 40);
        int dialogHeight = 180;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        // 绘制阴影
        drawRoundedRect(guiGraphics, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);

        // 绘制毛玻璃背景
        drawFrostedGlassBackground(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        // 绘制边框
        drawGradientBorder(guiGraphics, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        // 绘制标题
        String title = firstTime ? "设置服务器密码" : "验证服务器密码";
        int titleX = this.width / 2 - this.font.width(title) / 2;
        guiGraphics.drawString(this.font, title, titleX, dialogY + 18, ACCENT_SECONDARY, false);

        // 绘制提示信息
        String info = firstTime ? "首次加入，请设置密码（至少6位纯数字）" : promptMessage;
        int infoX = this.width / 2 - this.font.width(info) / 2;
        guiGraphics.drawString(this.font, info, infoX, dialogY + 45, TEXT_PRIMARY, false);

        // 绘制剩余尝试次数（非首次时）
        if (!firstTime && remainingAttempts > 0) {
            String attempts = "剩余尝试次数: " + remainingAttempts;
            int attemptsX = this.width / 2 - this.font.width(attempts) / 2;
            guiGraphics.drawString(this.font, attempts, attemptsX, dialogY + 60, TEXT_SECONDARY, false);
        }

        // 绘制错误信息
        if (errorMessage != null) {
            int errX = this.width / 2 - this.font.width(errorMessage) / 2;
            guiGraphics.drawString(this.font, errorMessage, errX, dialogY + 155, 0xFFFF3333, false);
        }

        // 保持输入框始终聚焦（提交按钮的点击事件是即时触发的，不依赖持续焦点，因此不受影响）
        if (this.passwordInput != null && !this.passwordInput.isFocused()) {
            this.setFocused(this.passwordInput);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车键一键提交（主键盘回车 + 小键盘回车）
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onSubmit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onSubmit() {
        String input = this.passwordInput.getValue();

        // 客户端格式校验
        if (input == null || input.length() < 6) {
            errorMessage = "§c密码长度不能少于6位";
            return;
        }
        for (char c : input.toCharArray()) {
            if (c < '0' || c > '9') {
                errorMessage = "§c密码只能包含纯数字";
                return;
            }
        }

        // 发送密码到服务端
        PacketDistributor.sendToServer(new PasswordSubmitPayload(input));
    }

    // ========== 绘制辅助方法 ==========

    private void drawRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + w, y + h - r, color);
        g.fill(x + r, y, x + w - r, y + r, color);
        g.fill(x + r, y + h - r, x + w - r, y + h, color);
        fillCircle(g, x + r, y + r, r, color);
        fillCircle(g, x + w - r, y + r, r, color);
        fillCircle(g, x + r, y + h - r, r, color);
        fillCircle(g, x + w - r, y + h - r, r, color);
    }

    private void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            for (int j = -r; j <= r; j++) {
                if (i * i + j * j <= r * r) {
                    g.fill(cx + i, cy + j, cx + i + 1, cy + j + 1, color);
                }
            }
        }
    }

    private void drawFrostedGlassBackground(GuiGraphics g, int x, int y, int w, int h, int r) {
        int bgColor = 0xF0FDFDFF;
        drawRoundedRect(g, x, y, w, h, r, bgColor);
    }

    private void drawGradientBorder(GuiGraphics g, int x, int y, int w, int h, int r) {
        // 顶部
        g.fill(x + r, y, x + w - r, y + 2, ACCENT_PRIMARY);
        // 底部
        g.fill(x + r, y + h - 2, x + w - r, y + h, ACCENT_PRIMARY);
        // 左侧
        g.fill(x, y + r, x + 2, y + h - r, ACCENT_PRIMARY);
        // 右侧
        g.fill(x + w - 2, y + r, x + w, y + h - r, ACCENT_PRIMARY);
    }
}
