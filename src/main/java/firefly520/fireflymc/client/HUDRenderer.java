package firefly520.fireflymc.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import firefly520.fireflymc.Config;


public class HUDRenderer
{
  private static final Component SERVER_NAME = Component.literal("FireflyMC 2.1.0");
  private static final Component WEBSITE_URL = Component.literal("https://mc.firefly520.top");
  private static final Component PLAYER_COUNT_PREFIX = Component.literal("在线人数: ");

  private static final int MAX_VISIBLE_PLAYERS = 5;
  private static final long PLAYER_SCROLL_SPEED = 1500L;


  private static final int TEXT_COLOR = 16777215;
  private static final int BORDER_COLOR = 0x40FFFFFF;  // 白色半透明
  private static final int BORDER_RADIUS = 4;
  private static final int BORDER_THICKNESS = 1;


  public static void render(GuiGraphics guiGraphics) {
    Minecraft mc = Minecraft.getInstance();


    if (mc.screen != null) {
      return;
    }

    // 同步F1隐藏GUI功能
    if (mc.options.hideGui) {
      return;
    }

    LocalPlayer player = mc.player;
    if (player == null) {
      return;
    }


    int playerCount = getPlayerCount(player);


    Font font = mc.font;
    int screenHeight = mc.getWindow().getGuiScaledHeight();


    Objects.requireNonNull(font); int lineHeight = 9 + 2;

    // 基准宽度：服务器名称宽度
    int baseWidth = font.width(SERVER_NAME);

    // 计算网址换行后的行数
    int urlLines = font.split(WEBSITE_URL, baseWidth).size();

    // 总高度 = 服务器名(1行) + 在线人数(1行) + 网址(urlLines行) + 分隔线(1行) + 玩家列表(MAX_VISIBLE_PLAYERS行)
    int playerListHeight = lineHeight * (MAX_VISIBLE_PLAYERS + 1); // +1 for separator
    int totalHeight = lineHeight * (2 + urlLines) + playerListHeight + 6;
    int x = 5;

    // 背景已设为透明
    // guiGraphics.fill(x, y - 2, x + baseWidth + 10, y + totalHeight, BACKGROUND_COLOR);

    // 从配置读取缩放值
    float scale = Config.CLIENT.HUD_SCALE.get().floatValue();

    // 计算缩放后的屏幕尺寸，用于正确计算居中位置
    int scaledHeight = (int)(screenHeight / scale);

    // 基于缩放后的屏幕尺寸计算垂直居中位置
    int y = (scaledHeight - totalHeight) / 2;

    // 应用缩放
    guiGraphics.pose().pushPose();
    guiGraphics.pose().scale(scale, scale, 1.0F);

    // 绘制圆角边框
    drawRoundedBorder(guiGraphics, x, y, baseWidth + 16, totalHeight);

    // 服务器名称
    guiGraphics.drawString(font, SERVER_NAME, x + 8, y + 3, TEXT_COLOR);
    y += lineHeight;




    // 在线人数
    MutableComponent mutableComponent = Component.literal("").append(PLAYER_COUNT_PREFIX).append(Component.literal(String.valueOf(playerCount)));
    guiGraphics.drawString(font, mutableComponent, x + 8, y, TEXT_COLOR);
    y += lineHeight;


    // 网址（跑马灯滚动）
    String urlText = WEBSITE_URL.getString();
    int urlWidth = font.width(urlText);

    if (urlWidth <= baseWidth) {
      // 文本短，不需要滚动，直接显示
      guiGraphics.drawString(font, WEBSITE_URL, x + 8, y, TEXT_COLOR);
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
      guiGraphics.drawString(font, Component.literal(visibleText), x + 8, y, TEXT_COLOR);
    }

    y += lineHeight;

    // 渲染玩家列表
    renderPlayerList(guiGraphics, font, x, y, baseWidth, lineHeight, player);

    // 恢复缩放
    guiGraphics.pose().popPose();
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

  private static List<String> getOnlinePlayerNames(LocalPlayer player) {
    List<String> playerNames = new ArrayList<>();
    ClientPacketListener connection = player.connection;

    if (connection != null) {
      try {
        Collection<?> onlinePlayers = connection.getOnlinePlayers();
        if (onlinePlayers != null) {
          playerNames = onlinePlayers.stream()
            .map(p -> {
              try {
                Object profile = p.getClass().getMethod("getProfile").invoke(p);
                if (profile != null) {
                  Object name = profile.getClass().getMethod("getName").invoke(profile);
                  return name != null ? name.toString() : null;
                }
              } catch (Exception ignored) {
              }
              return null;
            })
            .filter(name -> name != null)
            .collect(Collectors.toList());
        }
      } catch (Exception e) {
        playerNames.add(player.getName().getString());
      }
    }

    if (playerNames.isEmpty()) {
      playerNames.add(player.getName().getString());
    }

    return playerNames;
  }

  private static int renderPlayerList(GuiGraphics guiGraphics, Font font,
                                     int x, int y, int width, int lineHeight,
                                     LocalPlayer player) {
    List<String> playerNames = getOnlinePlayerNames(player);
    int totalPlayers = playerNames.size();

    // 分隔线
    guiGraphics.drawString(font, Component.literal("──在线玩家──"), x + 8, y, TEXT_COLOR);
    y += lineHeight;

    // 计算滚动偏移
    int scrollOffset = 0;
    if (totalPlayers > MAX_VISIBLE_PLAYERS) {
      long time = System.currentTimeMillis();
      int maxOffset = totalPlayers - MAX_VISIBLE_PLAYERS;
      scrollOffset = (int) ((time / PLAYER_SCROLL_SPEED) % (maxOffset + 3));
      if (scrollOffset > maxOffset) {
        scrollOffset = maxOffset;
      }
    }

    // 渲染可见玩家
    int visibleCount = Math.min(MAX_VISIBLE_PLAYERS, totalPlayers);
    for (int i = 0; i < visibleCount; i++) {
      int playerIndex = scrollOffset + i;
      if (playerIndex < totalPlayers) {
        String playerName = playerNames.get(playerIndex);
        guiGraphics.drawString(font, Component.literal(playerName), x + 8, y, TEXT_COLOR);
        y += lineHeight;
      }
    }

    return y;
  }

  private static void drawRoundedBorder(GuiGraphics guiGraphics, int x, int y, int width, int height) {
    int r = Math.min(BORDER_RADIUS, Math.min(width / 2, height / 2));
    int t = BORDER_THICKNESS;

    // 1. 绘制四条直边（不包含圆角部分）
    // 上边
    guiGraphics.fill(x + r, y, x + width - r, y + t, BORDER_COLOR);
    // 下边
    guiGraphics.fill(x + r, y + height - t, x + width - r, y + height, BORDER_COLOR);
    // 左边
    guiGraphics.fill(x, y + r, x + t, y + height - r, BORDER_COLOR);
    // 右边
    guiGraphics.fill(x + width - t, y + r, x + width, y + height - r, BORDER_COLOR);

    // 2. 绘制四个圆角（使用三角函数计算像素点，更平滑）
    for (int angle = 0; angle < 90; angle += 2) {
      double rad = Math.toRadians(angle);
      int dx = (int) (r * Math.cos(rad));
      int dy = (int) (r * Math.sin(rad));

      // 左上角
      guiGraphics.fill(x + r - dx, y + r - dy, x + r - dx + t, y + r - dy + t, BORDER_COLOR);
      // 右上角
      guiGraphics.fill(x + width - r + dx - t, y + r - dy, x + width - r + dx, y + r - dy + t, BORDER_COLOR);
      // 左下角
      guiGraphics.fill(x + r - dx, y + height - r + dy - t, x + r - dx + t, y + height - r + dy, BORDER_COLOR);
      // 右下角
      guiGraphics.fill(x + width - r + dx - t, y + height - r + dy - t, x + width - r + dx, y + height - r + dy, BORDER_COLOR);
    }
  }
}
