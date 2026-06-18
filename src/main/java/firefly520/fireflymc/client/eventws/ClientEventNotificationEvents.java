package firefly520.fireflymc.client.eventws;

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

    private static int lastDeathPlayerId = Integer.MIN_VALUE;
    private static String lastDeathMessage = "";
    private static long lastDeathNotificationAt;

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
