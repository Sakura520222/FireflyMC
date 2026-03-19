package firefly520.fireflymc.event.websocket;

import firefly520.fireflymc.ai.AIChatEventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端消息广播服务
 *
 * 将WebSocket接收到的服务端消息广播给所有在线玩家
 */
public class ServerMessageBroadcaster {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMessageBroadcaster.class);

    // 默认樱粉色 #FFB7C5
    private static final TextColor DEFAULT_COLOR = TextColor.fromRgb(0xFFB7C5);

    /**
     * 广播消息到所有在线玩家
     *
     * @param server Minecraft服务器实例
     * @param message 服务端消息
     */
    public static void broadcast(MinecraftServer server, ServerMessage message) {
        if (server == null) {
            LOGGER.warn("[FireflyMC] 无法广播消息: 服务器实例为空");
            return;
        }

        if (!message.isValidChatMessage()) {
            LOGGER.warn("[FireflyMC] 忽略无效消息: {}", message.toJson());
            return;
        }

        // 确保在主线程执行
        server.execute(() -> {
            TextColor color = parseColor(message.getColor());
            String sender = message.getSenderOrDefault();

            Component senderComponent = Component.literal(sender)
                    .withStyle(style -> style.withColor(color));

            Component fullChatMessage = Component.literal("<")
                    .append(senderComponent)
                    .append("> ")
                    .append(Component.literal(message.getMessage()));

            // 广播给所有在线玩家
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.displayClientMessage(fullChatMessage, false);
            }

            LOGGER.info("[FireflyMC] 广播服务端消息: <{}> {}", sender, message.getMessage());

            // 记录到AI上下文
            AIChatEventHandler.recordWebSocketMessage(server, message);

            // 检测唤醒词：消息包含"小樱"时触发AI回复
            if (message.getMessage().contains("小樱")) {
                AIChatEventHandler.triggerAIReplyNoPlayer(server, message.getMessage());
            }
        });
    }

    /**
     * 解析颜色字符串
     *
     * @param colorHex 十六进制颜色字符串 (如 "#FFB7C5")
     * @return TextColor对象，解析失败返回默认颜色
     */
    private static TextColor parseColor(String colorHex) {
        if (colorHex == null || colorHex.isEmpty()) {
            return DEFAULT_COLOR;
        }

        try {
            // 移除 # 前缀
            String hex = colorHex.startsWith("#") ? colorHex.substring(1) : colorHex;

            // 解析RGB
            int rgb = Integer.parseInt(hex, 16);
            return TextColor.fromRgb(rgb);
        } catch (NumberFormatException e) {
            LOGGER.debug("[FireflyMC] 无效的颜色格式: {}, 使用默认颜色", colorHex);
            return DEFAULT_COLOR;
        }
    }
}
