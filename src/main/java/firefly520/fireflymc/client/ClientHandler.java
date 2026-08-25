package firefly520.fireflymc.client;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.music.MusicHudRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

public class ClientHandler {

  /** 左侧纵向 stack：统一 x=5、统一宽度（两卡测量宽度的最大值）、间隔 4px、整体垂直居中 */
  public static void onRenderGui(RenderGuiEvent.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.options.hideGui || mc.screen != null || mc.player == null) {
      return;
    }
    float scale = Config.CLIENT.HUD_SCALE.get().floatValue();
    int scaledHeight = (int) (mc.getWindow().getGuiScaledHeight() / scale);

    boolean musicVisible = MusicHudRenderer.isVisible();
    boolean serverVisible = HUDRenderer.isServerHudVisible(mc);

    int musicWidth = musicVisible ? MusicHudRenderer.measureWidth(mc) : 0;
    int serverWidth = serverVisible ? HUDRenderer.measureWidth(mc) : 0;
    // 统一宽度：两卡按同一宽度渲染，左右边缘完全对齐
    int unifiedWidth = Math.max(musicWidth, serverWidth);

    int musicHeight = musicVisible ? MusicHudRenderer.measureHeight() : 0;
    int serverHeight = serverVisible ? HUDRenderer.measureTotalHeight(mc) : 0;
    int gap = (musicHeight > 0 && serverHeight > 0) ? 4 : 0;
    int total = musicHeight + gap + serverHeight;

    int topY = (scaledHeight - total) / 2;

    if (musicHeight > 0) {
      MusicHudRenderer.renderAt(event.getGuiGraphics(), topY, unifiedWidth);
    }
    if (serverHeight > 0) {
      HUDRenderer.renderAt(event.getGuiGraphics(), topY + musicHeight + gap, unifiedWidth);
    }
  }
}
