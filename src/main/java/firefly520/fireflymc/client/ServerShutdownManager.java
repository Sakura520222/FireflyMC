package firefly520.fireflymc.client;

import firefly520.fireflymc.client.relay.RelayGuestJoiner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * 伪关机维护状态管理（云端 /关机、/开机 指令经事件通道下发）。
 * <p>
 * 关机时：更新状态标志，强制断开当前的"原版多人服务器连接"与"中继客机会话"，
 * 并提示玩家。单人 / 局域网 / P2P 联机 / 房主端不受影响。
 */
public final class ServerShutdownManager {
    private ServerShutdownManager() {
    }

    /**
     * 云端关机状态变更通知。
     *
     * @param shutdown true=进入关机维护，false=恢复联机
     */
    public static void onShutdownStateChanged(boolean shutdown) {
        ClientState.serverShutdown = shutdown;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (shutdown) {
                forceDisconnect();
            } else {
                showChatMessage(Component.translatable("fireflymc.server_startup.announce"));
            }
        });
    }

    /**
     * 强制断开当前的多人服务器连接与中继客机会话。
     * 单人世界、P2P 客机会话、房主端不受影响。
     * <p>
     * 这里使用 {@code Connection.disconnect(reason)} 而非 {@code Minecraft.disconnect()}。
     * 后者内部 updateScreenAndTick 会嵌套运行一帧 runTick，在 mc.execute（非 tick 上下文）
     * 调用时会导致主循环卡死、连接不关闭、玩家卡在 ProgressScreen 界面（服务端表现为
     * Timed out）。Connection.disconnect 同步关闭底层 Netty channel（可靠发送 FIN），
     * 之后由 Connection.tick -> handleDisconnection -> onDisconnect 自动显示
     * DisconnectedScreen（带维护原因 + 返回主菜单按钮），即 vanilla "被踢" 流程。
     */
    private static void forceDisconnect() {
        Minecraft mc = Minecraft.getInstance();
        // 关机提示：必须在断开前显示，断开后 player 为 null 无法显示
        showChatMessage(Component.translatable("fireflymc.server_shutdown.announce"));

        // 仅当不在单人世界且不在任何联机大厅会话时，视为"原版多人服务器"连接并断开
        ClientPacketListener listener = mc.getConnection();
        if (mc.getSingleplayerServer() == null && !RelayGuestJoiner.isInAnySession()
                && listener != null && listener.getConnection() != null) {
            listener.getConnection().disconnect(
                    Component.translatable("fireflymc.server_shutdown.message")
            );
        }

        // 断开中继客机会话（仅中继代理 activeProxy，P2P 代理保留）
        RelayGuestJoiner.stopRelayProxyIfActive("server_shutdown");
    }

    private static void showChatMessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui == null || mc.gui.getChat() == null) {
            return;
        }
        mc.gui.getChat().addMessage(message);
    }
}
