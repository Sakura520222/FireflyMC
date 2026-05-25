package firefly520.fireflymc.client.eventws;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
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
