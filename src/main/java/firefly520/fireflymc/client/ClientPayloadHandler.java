package firefly520.fireflymc.client;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.client.auth.ClientAuthLockoutManager;
import firefly520.fireflymc.client.eventws.ClientEventNotificationConfig;
import firefly520.fireflymc.client.eventws.ClientEventNotificationMessage;
import firefly520.fireflymc.client.eventws.ClientEventWebSocketClient;
import firefly520.fireflymc.client.screen.PasswordAuthScreen;
import firefly520.fireflymc.client.screen.RulesScreen;
import firefly520.fireflymc.network.CrossChatRelayPayload;
import firefly520.fireflymc.network.AuthLockoutPayload;
import firefly520.fireflymc.network.ModHandshakePayload;
import firefly520.fireflymc.network.ModHandshakeReplyPayload;
import firefly520.fireflymc.network.PasswordPromptPayload;
import firefly520.fireflymc.network.ShowRulesPayload;
import firefly520.fireflymc.network.TitleSyncPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端专用数据包处理器
 * 此类仅客户端加载，通过 ModNetwork 中的反射调用
 */
public class ClientPayloadHandler {

    /**
     * 客户端处理服务端发来的握手包，回复版本号
     */
    public static void handleHandshake(ModHandshakePayload payload, IPayloadContext context) {
        context.reply(new ModHandshakeReplyPayload(FireflyMCMod.VERSION));
    }

    /**
     * 客户端处理服务端发来的显示准则弹窗包
     */
    public static void handleShowRules(ShowRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 结合服务端判断和客户端状态决定是否首次加入
            boolean isFirstJoin = payload.isFirstJoin() && !ClientState.hasSeenRulesThisSession;
            // 更新客户端状态
            ClientState.hasSeenRulesThisSession = true;
            // 打开准则弹窗
            Minecraft.getInstance().setScreen(new RulesScreen(isFirstJoin));
        });
    }

    /**
     * 客户端处理服务端发来的密码验证提示包，打开密码弹窗
     */
    public static void handlePasswordPrompt(PasswordPromptPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.remainingAttempts() == PasswordPromptPayload.AUTH_SUCCESS_SIGNAL) {
                // 验证成功信号，关闭密码弹窗
                Minecraft.getInstance().setScreen(null);
            } else {
                Minecraft.getInstance().setScreen(new PasswordAuthScreen(
                        payload.firstTime(),
                        payload.message(),
                        payload.remainingAttempts()
                ));
            }
        });
    }

    /**
     * 客户端处理服务端发来的称号同步包，更新本地缓存
     */
    public static void handleTitleSync(TitleSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientState.titleMap.clear();
            ClientState.titleMap.putAll(payload.titles());
        });
    }

    /**
     * 客户端处理服务端发来的密码限流包，记录本地限流。
     * <p>
     * 使用 ClientState.currentServerIp（由 ConnectScreenMixin 在放行连接时记录）
     * 作为服务器地址来源，避免依赖 disconnect 包与 payload 包的处理时序。
     */
    public static void handleAuthLockout(AuthLockoutPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            String serverIp = ClientState.currentServerIp;
            if (serverIp == null || serverIp.isEmpty()) {
                return;
            }
            String playerName = mc.getUser().getName();
            String key = ClientAuthLockoutManager.buildKey(serverIp, playerName);
            ClientAuthLockoutManager.getInstance().recordLockout(key, payload.lockoutMinutes());
        });
    }

    /**
     * 客户端处理服务端发来的跨级聊天代发包，以指定发送者名义上行到云端/QQ 群。
     * <p>
     * 服务端无云端 WebSocket 连接，AI 回复、{@code /ai} 命令的玩家消息等无法走
     * ClientChatEvent 上行，由服务端通过本 payload 委托已连云端的客户端代发 player_chat。
     */
    public static void handleCrossChatRelay(CrossChatRelayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!ClientEventNotificationConfig.crossChatEnabled()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            ClientEventWebSocketClient.getInstance().send(
                ClientEventNotificationMessage.crossChatRelay(minecraft, payload.senderName(), payload.message())
            );
        });
    }
}
