package firefly520.fireflymc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 主菜单检测器
 * 当检测到有更新时，将主菜单替换为更新通知界面
 */
public class TitleScreenDetector {
    private static boolean hasReplaced = false;

    /**
     * 监听屏幕渲染事件，检测主菜单并替换
     */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        // 只在主菜单显示
        if (!(event.getScreen() instanceof TitleScreen)) {
            hasReplaced = false;
            return;
        }

        // 防止重复替换
        if (hasReplaced) return;

        // 检查是否有更新
        if (ClientState.hasUpdateAvailable) {
            hasReplaced = true;
            // 在主线程替换屏幕
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().setScreen(new ModUpdateScreen());
            });
        }
    }
}
