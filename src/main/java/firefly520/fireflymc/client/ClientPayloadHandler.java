package firefly520.fireflymc.client;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.client.screen.PasswordAuthScreen;
import firefly520.fireflymc.client.screen.RulesScreen;
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
}
