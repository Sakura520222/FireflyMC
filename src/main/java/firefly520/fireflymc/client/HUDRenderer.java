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
import firefly520.fireflymc.FireflyMCMod;


public class HUDRenderer
{
  private static final Component SERVER_NAME = Component.literal("FireflyMC " + FireflyMCMod.VERSION);
  private static final Component WEBSITE_URL = Component.literal("https://mc.firefly520.top");
  private static final Component PLAYER_COUNT_PREFIX = Component.literal("在线人数: ");

  private static final int MAX_VISIBLE_PLAYERS = 5;
  private static final long PLAYER_SCROLL_SPEED = 1500L;

  /** 与音乐卡片一致的左右内边距（统一视觉规格） */
  private static final int PADDING_LEFT = 8;
  private static final int PADDING_RIGHT = 8;

  private static final int TEXT_COLOR = 16777215;

  /** 玩家条目：UUID字符串 + 玩家名 */
  private record PlayerEntry(String uuid, String name) {}


  /** 服务器信息卡可见性（F1/打开界面/未发布单人世界/无玩家） */
  public static boolean isServerHudVisible(Minecraft mc) {
    if (mc.options.hideGui) {
      return false;
    }
    if (mc.screen != null) {
      return false;
    }
    if (mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isPublished()) {
      return false;
    }
    return mc.player != null;
  }

  /** 缩放后坐标系的卡片总高度（供 ClientHandler 纵向 stack 布局计算）。
   *  URL 为单行（放得下直接显示、超宽滚动），不换行。 */
  public static int measureTotalHeight(Minecraft mc) {
    List<PlayerEntry> players = getOnlinePlayers(mc.player);
    int lineHeight = 9 + 2;
    int visiblePlayerCount = Math.min(players.size(), MAX_VISIBLE_PLAYERS);
    int playerListHeight = lineHeight * (visiblePlayerCount + 1);
    return lineHeight * 3 + playerListHeight + 6;
  }

  /** 卡片自身测量宽度（供 ClientHandler 统一取两卡最大值；含称号+玩家名的最长行） */
  public static int measureWidth(Minecraft mc) {
    Font font = mc.font;
    int baseWidth = font.width(SERVER_NAME);
    for (PlayerEntry pe : getOnlinePlayers(mc.player)) {
      String t = ClientState.titleMap.get(pe.uuid());
      StringBuilder sb = new StringBuilder();
      if (t != null && !t.isEmpty()) {
        sb.append("§7[§r").append(t).append("§7]§r");
      }
      sb.append(pe.name());
      baseWidth = Math.max(baseWidth, font.width(sb.toString()));
    }
    return baseWidth + 16;
  }

  /** 在缩放后坐标系 (x=5, startY) 按统一宽度渲染本卡片（可见性由 ClientHandler 统一负责） */
  public static void renderAt(GuiGraphics guiGraphics, int startY, int width) {
    Minecraft mc = Minecraft.getInstance();

    LocalPlayer player = mc.player;

    List<PlayerEntry> players = getOnlinePlayers(player);
    int playerCount = players.size();

    Font font = mc.font;

    Objects.requireNonNull(font); int lineHeight = 9 + 2;

    int x = 5;
    int contentX = x + PADDING_LEFT;
    int contentWidth = width - PADDING_LEFT - PADDING_RIGHT;

    // 总高度 = 服务器名(1行) + 在线人数(1行) + 网址(1行，超宽滚动不换行) + 分隔线(1行) + 可见玩家行
    int visiblePlayerCount = Math.min(playerCount, MAX_VISIBLE_PLAYERS);
    int playerListHeight = lineHeight * (visiblePlayerCount + 1); // +1 for separator
    int totalHeight = lineHeight * 3 + playerListHeight + 6;

    // 从配置读取缩放值
    float scale = Config.CLIENT.HUD_SCALE.get().floatValue();

    // 应用缩放
    guiGraphics.pose().pushPose();
    guiGraphics.pose().scale(scale, scale, 1.0F);

    // y 由 ClientHandler 纵向 stack 布局传入（缩放后坐标系）
    int y = startY;

    // 绘制圆角边框（宽度 = stack 统一宽度）
    HudRenderUtil.drawRoundedBorder(guiGraphics, x, y, width, totalHeight);

    // 服务器名称
    guiGraphics.drawString(font, SERVER_NAME, contentX, y + 3, TEXT_COLOR);
    y += lineHeight;

    // 在线人数
    MutableComponent mutableComponent = Component.literal("").append(PLAYER_COUNT_PREFIX).append(Component.literal(String.valueOf(playerCount)));
    guiGraphics.drawString(font, mutableComponent, contentX, y, TEXT_COLOR);
    y += lineHeight;

    // 网址：放得下直接显示；超宽平滑滚动（完整原始文本 + 像素平移 + scissor 裁 viewport，
    // 不做 substr 截取——字符边界取整近似会在滚动终点裁掉末字符的最后几个像素）
    String urlText = WEBSITE_URL.getString();
    int urlWidth = font.width(urlText);
    if (urlWidth <= contentWidth) {
      guiGraphics.drawString(font, WEBSITE_URL, contentX, y, TEXT_COLOR);
    } else {
      // +1px 安全余量：保证最后一个 glyph 的边界像素完整进入 viewport
      int maxOffset = Math.max(0, urlWidth - contentWidth + 1);
      // 起点静置 1.5s → 从 0 滚到 maxOffset（25ms/px）→ 终点静置 1.5s → 回起点
      long cycle = 1500 + maxOffset * 25L + 1500;
      long t = System.currentTimeMillis() % cycle;
      int offset;
      if (t < 1500) {
        offset = 0;
      } else if (t < 1500 + maxOffset * 25L) {
        offset = (int) ((t - 1500) / 25);
      } else {
        offset = maxOffset;
      }
      offset = Math.min(offset, maxOffset); // 终点 clamp：最后像素完整显示后才允许回起点

      // scissor 是 framebuffer 级坐标（gui-scaled），不受 pose scale 影响 → 逻辑坐标 ×scale 换算
      guiGraphics.enableScissor((int) (contentX * scale), (int) (y * scale),
              (int) ((contentX + contentWidth) * scale), (int) ((y + lineHeight) * scale));
      guiGraphics.drawString(font, urlText, contentX - offset, y, TEXT_COLOR);
      guiGraphics.disableScissor();
    }

    y += lineHeight;

    // 渲染玩家列表（分隔线自适应 contentWidth；长称号+玩家名使用完整宽度并在超宽时截断）
    renderPlayerList(guiGraphics, font, contentX, contentWidth, y, lineHeight, players);

    // 恢复缩放
    guiGraphics.pose().popPose();
  }

  private static List<PlayerEntry> getOnlinePlayers(LocalPlayer player) {
    List<PlayerEntry> players = new ArrayList<>();
    ClientPacketListener connection = player.connection;

    if (connection != null) {
      try {
        Collection<?> onlinePlayers = connection.getOnlinePlayers();
        if (onlinePlayers != null) {
          players = onlinePlayers.stream()
            .map(p -> {
              try {
                Object profile = p.getClass().getMethod("getProfile").invoke(p);
                if (profile != null) {
                  Object name = profile.getClass().getMethod("getName").invoke(profile);
                  Object id = profile.getClass().getMethod("getId").invoke(profile);
                  String uuid = id != null ? id.toString() : "";
                  return name != null ? new PlayerEntry(uuid, name.toString()) : null;
                }
              } catch (Exception ignored) {
              }
              return null;
            })
            .filter(e -> e != null)
            .collect(Collectors.toList());
        }
      } catch (Exception e) {
        players.add(new PlayerEntry(player.getUUID().toString(), player.getName().getString()));
      }
    }

    if (players.isEmpty()) {
      players.add(new PlayerEntry(player.getUUID().toString(), player.getName().getString()));
    }

    return players;
  }

  private static int renderPlayerList(GuiGraphics guiGraphics, Font font,
                                     int contentX, int contentWidth, int y, int lineHeight,
                                     List<PlayerEntry> players) {
    int totalPlayers = players.size();

    // 分隔线：根据 contentWidth 动态延长 ──── 在线玩家 ────
    guiGraphics.drawString(font, Component.literal(separatorLine(font, "在线玩家", contentWidth)), contentX, y, TEXT_COLOR);
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

    // 渲染可见玩家（称号 + 玩家名，超宽时截断到 contentWidth）
    int visibleCount = Math.min(MAX_VISIBLE_PLAYERS, totalPlayers);
    for (int i = 0; i < visibleCount; i++) {
      int playerIndex = scrollOffset + i;
      if (playerIndex < totalPlayers) {
        PlayerEntry entry = players.get(playerIndex);
        String title = ClientState.titleMap.get(entry.uuid());
        String displayText;
        if (title != null && !title.isEmpty()) {
          displayText = "§7[§r" + title + "§7]§r" + entry.name();
        } else {
          displayText = entry.name();
        }
        String clipped = font.plainSubstrByWidth(displayText, contentWidth);
        guiGraphics.drawString(font, Component.literal(clipped), contentX, y, TEXT_COLOR);
        y += lineHeight;
      }
    }

    return y;
  }

  /** 自适应分隔线：──── 在线玩家 ────，横线数量按 contentWidth 动态计算 */
  private static String separatorLine(Font font, String label, int contentWidth) {
    String unit = "─";
    int unitWidth = font.width(unit);
    int labelWidth = font.width(label);
    if (unitWidth <= 0 || contentWidth <= labelWidth + 4) {
      return label;
    }
    int side = Math.max(1, (contentWidth - labelWidth - 4) / 2 / unitWidth);
    return unit.repeat(side) + label + unit.repeat(side);
  }
}
