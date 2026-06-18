package firefly520.fireflymc.client.eventws;

import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端玩家事件通知入口。
 */
public final class ClientEventNotificationEvents {
    private static final long DEATH_DEDUPLICATION_WINDOW_MILLIS = 1000L;
    private static final long CHAT_UPLINK_DEDUPLICATE_WINDOW_MILLIS = 1000L;
    private static final long MC_CHAT_DEDUPLICATE_WINDOW_MILLIS = 1000L;

    private static int lastDeathPlayerId = Integer.MIN_VALUE;
    private static String lastDeathMessage = "";
    private static long lastDeathNotificationAt;
    private static String lastChatUplinkMessage = "";
    private static long lastChatUplinkAt;
    private static String lastMcChatKey = "";
    private static long lastMcChatAt;

    private ClientEventNotificationEvents() {
    }

    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientEventWebSocketClient client = ClientEventWebSocketClient.getInstance();

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            client.send(ClientEventNotificationMessage.singleplayerEnter(minecraft, event.getPlayer(), resolveWorldName(server)));
        } else {
            client.send(ClientEventNotificationMessage.multiplayerJoin(minecraft, event.getPlayer()));
        }
    }

    public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 关机维护期间保持事件通知通道，以便接收云端下发的 server_startup 通知。
        // 否则 WebSocket 关闭后开机下行无法送达，ClientState.serverShutdown 将永久停留为 true，
        // 玩家无法重新加入多人服务器（ConnectScreenMixin 会持续拦截 startConnecting）。
        if (ClientState.serverShutdown) {
            return;
        }
        ClientEventWebSocketClient.getInstance().close();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        ClientEventWebSocketClient.getInstance().onClientTick();
    }

    public static void notifyPlayerDeath(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String messageText = message == null ? "" : message.getString();
        long now = System.currentTimeMillis();
        if (lastDeathPlayerId == minecraft.player.getId()
            && lastDeathMessage.equals(messageText)
            && now - lastDeathNotificationAt < DEATH_DEDUPLICATION_WINDOW_MILLIS) {
            return;
        }

        lastDeathPlayerId = minecraft.player.getId();
        lastDeathMessage = messageText;
        lastDeathNotificationAt = now;

        ClientEventWebSocketClient.getInstance().send(
            ClientEventNotificationMessage.playerDeath(minecraft, minecraft.player, message)
        );
    }

    public static void notifyAdvancementEarned(String advancementId, Component title, Component description) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ClientEventWebSocketClient.getInstance().send(
            ClientEventNotificationMessage.advancementEarned(minecraft, minecraft.player, advancementId, title, description)
        );
    }

    /**
     * 玩家在游戏内发送聊天时，旁路转发到 QQ 群（跨级聊天上行）。
     * 不取消事件，聊天照常发送到服务端。
     */
    public static void onClientChat(ClientChatEvent event) {
        if (!ClientEventNotificationConfig.crossChatEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        // 短窗口去重：避免同一消息被重复上行（如 ClientChatEvent 重复触发），
        // 否则云端会按不同 eventId 处理两次，向其他玩家广播两遍 [MC]。
        long now = System.currentTimeMillis();
        if (message.equals(lastChatUplinkMessage)
            && now - lastChatUplinkAt < CHAT_UPLINK_DEDUPLICATE_WINDOW_MILLIS) {
            return;
        }
        lastChatUplinkMessage = message;
        lastChatUplinkAt = now;
        ClientEventWebSocketClient.getInstance().send(
            ClientEventNotificationMessage.playerChat(minecraft, minecraft.player, message)
        );
    }

    /**
     * 收到云端推送的 QQ 群消息，显示到游戏内聊天框（跨级聊天下行）。
     */
    public static void onQQChatReceived(String sender, String message) {
        if (!ClientEventNotificationConfig.crossChatEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String displaySender = (sender == null || sender.isBlank()) ? "QQ" : sender;
        String safeMessage = message == null ? "" : message;
        minecraft.execute(() -> {
            Component text = Component.literal("§b[QQ]§r ")
                .append(Component.literal(displaySender))
                .append(Component.literal(": "))
                .append(Component.literal(safeMessage));
            if (minecraft.gui != null && minecraft.gui.getChat() != null) {
                minecraft.gui.getChat().addMessage(text);
            }
        });
    }

    /**
     * 收到云端推送的其他 mod 客户端聊天，显示到游戏内聊天框（mod 端互通下行）。
     */
    public static void onMCChatReceived(String playerName, String message, String worldTag) {
        if (!ClientEventNotificationConfig.crossChatEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String displayPlayer = (playerName == null || playerName.isBlank()) ? "MC" : playerName;
        String tag = (worldTag == null || worldTag.isBlank()) ? "" : " (" + worldTag + ")";
        String safeMessage = message == null ? "" : message;
        // 短窗口去重：避免重复下行使 [MC] 消息显示两遍（如云端连接残留或网络重发）。
        // key 用 SOH 控制符(U+0001，编辑器中不可见、显示为相邻引号)分隔三个字段，
        // 防止不同字段组合拼接出相同 key 造成误去重（如 player="A"/msg="BC" 与 player="AB"/msg="C"）。
        String key = displayPlayer + "" + safeMessage + "" + tag;
        long now = System.currentTimeMillis();
        if (key.equals(lastMcChatKey)
            && now - lastMcChatAt < MC_CHAT_DEDUPLICATE_WINDOW_MILLIS) {
            return;
        }
        lastMcChatKey = key;
        lastMcChatAt = now;
        minecraft.execute(() -> {
            Component text = Component.literal("§a[MC]§r ")
                .append(Component.literal(displayPlayer))
                .append(Component.literal(tag + ": "))
                .append(Component.literal(safeMessage));
            if (minecraft.gui != null && minecraft.gui.getChat() != null) {
                minecraft.gui.getChat().addMessage(text);
            }
        });
    }

    private static String resolveWorldName(IntegratedServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // 使用降级名称
        }
        return "我的单人世界";
    }

}
