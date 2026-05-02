package firefly520.fireflymc.client.relay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guest 加入公开房间流程。
 */
public final class RelayGuestJoiner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayGuestJoiner.class);
    private static RelayGuestProxy activeProxy;

    private RelayGuestJoiner() {
    }

    public static void join(Screen parent, RelayLobbyRoom room) {
        Minecraft mc = Minecraft.getInstance();
        RelayLobbyState.setStatusMessage("正在加入房间: " + room.worldName());

        String playerName = mc.getUser().getName();
        String playerUuid = mc.getUser().getProfileId().toString();
        RelayLobbyWebSocketClient.getInstance()
                .joinRoom(room, playerName, playerUuid)
                .whenComplete((guestSessionId, error) -> mc.execute(() -> {
                    if (error != null) {
                        RelayLobbyState.setStatusMessage("加入失败: " + error.getMessage());
                        LOGGER.warn("[FireflyMC] 加入公开房间失败: {}", error.getMessage());
                        return;
                    }
                    try {
                        startProxyAndConnect(parent, room, guestSessionId);
                    } catch (Exception e) {
                        RelayLobbyState.setStatusMessage("连接失败: " + e.getMessage());
                        LOGGER.warn("[FireflyMC] 启动本地代理失败: {}", e.getMessage());
                    }
                }));
    }

    private static void startProxyAndConnect(Screen parent, RelayLobbyRoom room, String guestSessionId) throws Exception {
        if (activeProxy != null) {
            activeProxy.stop();
        }

        activeProxy = new RelayGuestProxy(room.roomId(), guestSessionId);
        int port = activeProxy.start();
        RelayLobbyWebSocketClient.getInstance().setGuestProxy(activeProxy);

        String addressText = "127.0.0.1:" + port;
        ServerAddress address = ServerAddress.parseString(addressText);
        ServerData serverData = new ServerData("FireflyMC - " + room.worldName(), addressText, ServerData.Type.OTHER);
        RelayLobbyState.setStatusMessage("正在连接本地代理: " + addressText);
        LOGGER.info("[FireflyMC] 正在通过本地代理加入房间: roomId={}, address={}", room.roomId(), addressText);

        ConnectScreen.startConnecting(
                parent,
                Minecraft.getInstance(),
                address,
                serverData,
                false,
                null
        );
    }
}
