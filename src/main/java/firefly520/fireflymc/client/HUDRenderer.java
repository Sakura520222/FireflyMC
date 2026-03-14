package firefly520.fireflymc.client;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;


public class HUDRenderer
{
  private static final Component SERVER_NAME = Component.literal("FireflyMC 2.0.0");
  private static final Component WEBSITE_URL = Component.literal("https://mc.firefly520.top");
  private static final Component PLAYER_COUNT_PREFIX = Component.literal("在线人数: ");


  private static final int TEXT_COLOR = 16777215;

  private static final int BACKGROUND_COLOR = -2147483648;


  public static void render(GuiGraphics guiGraphics) {
    Minecraft mc = Minecraft.getInstance();


    if (mc.screen != null) {
      return;
    }


    LocalPlayer player = mc.player;
    if (player == null) {
      return;
    }


    int playerCount = getPlayerCount(player);


    Font font = mc.font;
    int screenWidth = mc.getWindow().getGuiScaledWidth();
    int screenHeight = mc.getWindow().getGuiScaledHeight();


    Objects.requireNonNull(font); int lineHeight = 9 + 2;

    // 基准宽度：服务器名称宽度
    int baseWidth = font.width(SERVER_NAME);

    // 计算网址换行后的行数
    int urlLines = font.split(WEBSITE_URL, baseWidth).size();

    // 总高度 = 服务器名(1行) + 在线人数(1行) + 网址(urlLines行)
    int totalHeight = lineHeight * (2 + urlLines) + 4;
    int startY = (screenHeight - totalHeight) / 2;
    int x = 5;


    // 背景已设为透明
    // guiGraphics.fill(x, startY - 2, x + baseWidth + 10, startY + totalHeight, BACKGROUND_COLOR);


    int y = startY;


    // 服务器名称
    guiGraphics.drawString(font, SERVER_NAME, x + 5, y, TEXT_COLOR);
    y += lineHeight;




    // 在线人数
    MutableComponent mutableComponent = Component.literal("").append(PLAYER_COUNT_PREFIX).append(Component.literal(String.valueOf(playerCount)));
    guiGraphics.drawString(font, mutableComponent, x + 5, y, TEXT_COLOR);
    y += lineHeight;


    // 网址（跑马灯滚动）
    String urlText = WEBSITE_URL.getString();
    int urlWidth = font.width(urlText);

    if (urlWidth <= baseWidth) {
      // 文本短，不需要滚动，直接显示
      guiGraphics.drawString(font, WEBSITE_URL, x + 5, y, TEXT_COLOR);
    } else {
      // 跑马灯效果：循环滚动显示网址
      long time = System.currentTimeMillis();
      int scrollSpeed = 200; // 每个位置显示200毫秒
      int cycle = urlText.length() + 5; // 滚动周期（字符数+空格缓冲）
      int offset = (int) ((time / scrollSpeed) % cycle);

      // 构造滚动文本：在末尾添加空格和开头部分以实现循环
      String scrollText = urlText + "     " + urlText.substring(0, Math.min(offset, urlText.length()));

      // 从offset位置开始截取最多能显示的字符
      int maxChars = 0;
      int testWidth = 0;
      for (int i = offset; i < scrollText.length(); i++) {
        int charWidth = font.width(scrollText.substring(i, i + 1));
        if (testWidth + charWidth > baseWidth) break;
        testWidth += charWidth;
        maxChars++;
      }

      String visibleText = scrollText.substring(offset, Math.min(offset + maxChars, scrollText.length()));
      guiGraphics.drawString(font, Component.literal(visibleText), x + 5, y, TEXT_COLOR);
    }
  }





  private static int getPlayerCount(LocalPlayer player) {
    ClientPacketListener connection = player.connection;
    if (connection != null) {

      try {
        if (connection.getOnlinePlayers() != null) {
          return connection.getOnlinePlayers().size();
        }
      } catch (Exception e) {

        return 1;
      }
    }


    return 1;
  }
}
