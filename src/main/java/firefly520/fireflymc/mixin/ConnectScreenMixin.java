package firefly520.fireflymc.mixin;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.auth.ClientAuthLockoutManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：拦截原版"连接到多人服务器"的动作。
 * <p>
 * 当处于伪关机维护状态（{@link ClientState#serverShutdown}）且当前连接非联机大厅
 * 发起（{@link ClientState#isLobbyInitiatedConnection} 为 false）时，在 startConnecting
 * 入口取消连接，并显示维护提示界面。
 * <p>
 * 联机大厅发起的连接（P2P/中继本地代理，连 127.0.0.1）由调用方置位
 * isLobbyInitiatedConnection 后放行，交由 RelayGuestJoiner 层按关机策略处理。
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(
        method = "startConnecting",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void fireflymc$onStartConnecting(
            Screen parent, Minecraft mc, ServerAddress address,
            ServerData serverData, boolean quickPlay, TransferState transferState,
            CallbackInfo ci) {
        // 1. 伪关机维护拦截（大厅发起的连接放行）
        if (ClientState.serverShutdown && !ClientState.isLobbyInitiatedConnection) {
            ci.cancel();
            Component title = Component.translatable("fireflymc.server_shutdown.title");
            Component message = Component.translatable("fireflymc.server_shutdown.message");
            mc.setScreen(new DisconnectedScreen(parent, title, message));
            return;
        }

        // 2. 密码限流拦截（大厅发起的本地代理连接不经密码验证，放行）
        if (!ClientState.isLobbyInitiatedConnection && serverData != null
                && serverData.ip != null && !serverData.ip.isEmpty()) {
            String key = ClientAuthLockoutManager.buildKey(serverData.ip, mc.getUser().getName());
            long remaining = ClientAuthLockoutManager.getInstance().getRemainingMillis(key);
            if (remaining > 0) {
                ci.cancel();
                // 向上取整为分钟，避免显示 0 分钟
                long remainingMinutes = (remaining + 59_999L) / 60_000L;
                Component title = Component.translatable("fireflymc.auth_lockout.title");
                Component message = Component.translatable("fireflymc.auth_lockout.message", remainingMinutes);
                mc.setScreen(new DisconnectedScreen(parent, title, message));
                return;
            }
        }

        // 3. 放行：记录当前连接目标地址，供密码限流 payload 拼 key
        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            ClientState.currentServerIp = serverData.ip;
        }
    }
}
