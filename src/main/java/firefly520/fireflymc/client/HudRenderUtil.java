package firefly520.fireflymc.client;

import net.minecraft.client.gui.GuiGraphics;

/** HUD 共享绘制工具（音乐卡片与服务器信息卡片共用） */
public final class HudRenderUtil {

    public static final int BORDER_COLOR = 0x40FFFFFF;
    public static final int BORDER_RADIUS = 4;
    public static final int BORDER_THICKNESS = 1;

    private HudRenderUtil() {}

    /** 圆角边框（自 HUDRenderer 原样抽出，逻辑不变） */
    public static void drawRoundedBorder(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        int r = Math.min(BORDER_RADIUS, Math.min(width / 2, height / 2));
        int t = BORDER_THICKNESS;

        // 1. 绘制四条直边（不包含圆角部分）
        guiGraphics.fill(x + r, y, x + width - r, y + t, BORDER_COLOR);
        guiGraphics.fill(x + r, y + height - t, x + width - r, y + height, BORDER_COLOR);
        guiGraphics.fill(x, y + r, x + t, y + height - r, BORDER_COLOR);
        guiGraphics.fill(x + width - t, y + r, x + width, y + height - r, BORDER_COLOR);

        // 2. 绘制四个圆角（使用三角函数计算像素点，更平滑）
        for (int angle = 0; angle < 90; angle += 2) {
            double rad = Math.toRadians(angle);
            int dx = (int) (r * Math.cos(rad));
            int dy = (int) (r * Math.sin(rad));

            guiGraphics.fill(x + r - dx, y + r - dy, x + r - dx + t, y + r - dy + t, BORDER_COLOR);
            guiGraphics.fill(x + width - r + dx - t, y + r - dy, x + width - r + dx, y + r - dy + t, BORDER_COLOR);
            guiGraphics.fill(x + r - dx, y + height - r + dy - t, x + r - dx + t, y + height - r + dy, BORDER_COLOR);
            guiGraphics.fill(x + width - r + dx - t, y + height - r + dy - t, x + width - r + dx, y + height - r + dy, BORDER_COLOR);
        }
    }
}
