package firefly520.fireflymc.client.eventws;

import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
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

    /**
     * 事件通知 WebSocket 连接（含断线重连）成功后回调。
     * 立即上报当前在线状态（presence），使云端在服务重启或客户端重连后能恢复在线玩家列表。
     * 此方法可能在 EventNotify 后台线程被调用，内部切换到客户端主线程读取玩家信息。
     */
    public static void onEventNotifyConnected() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.player == null) {
                return;
            }
            ClientEventWebSocketClient.getInstance().send(
                ClientEventNotificationMessage.presence(minecraft, minecraft.player)
            );
        });
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
        // 同一服务器/世界内的消息，vanilla 聊天已广播给本机玩家，无需再以 [MC] 重复展示；
        // [MC] 下行仅服务于跨服务器 / 单人 / QQ 互通场景。
        String localTag = resolveLocalWorldTag(minecraft);
        if (localTag != null && localTag.equals(worldTag)) {
            return;
        }
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

    /**
     * 构造本机当前所处世界的标识，格式与云端 worldTag 完全一致
     * （"多人: &lt;serverName|serverAddress&gt;" 或 "单人: &lt;worldName&gt;"）。
     * 用于判断收到的 mc_chat 是否来自本机所在的同一会话——若是，vanilla 已显示该消息，
     * 应跳过 [MC] 下行以避免重复。返回 null 表示本机不在可识别的世界（如主菜单）。
     */
    private static String resolveLocalWorldTag(Minecraft minecraft) {
        ServerData current = minecraft.getCurrentServer();
        if (current != null) {
            String server = (current.name != null && !current.name.isBlank()) ? current.name : current.ip;
            if (server != null && !server.isBlank()) {
                return "多人: " + server;
            }
        }
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            return "单人: " + resolveWorldName(integrated);
        }
        return null;
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
