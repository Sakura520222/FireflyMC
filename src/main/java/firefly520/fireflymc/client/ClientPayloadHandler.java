package firefly520.fireflymc.client;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.client.screen.RulesScreen;
import firefly520.fireflymc.network.ModHandshakePayload;
import firefly520.fireflymc.network.ModHandshakeReplyPayload;
import firefly520.fireflymc.network.ShowRulesPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端专用数据包处理器
 * 此类仅客户端加载，通过 ModNetwork 中的反射调用
 */
public class ClientPayloadHandler {

    /**
     * 客户端处理服务端发来的握手包，回复版本号
     * 通过反射从 ModNetwork 调用
     */
    public static void handleHandshake(ModHandshakePayload payload, IPayloadContext context) {
        context.reply(new ModHandshakeReplyPayload(FireflyMCMod.VERSION));
    }

    /**
     * 客户端处理服务端发来的显示准则弹窗包
     * 打开 RulesScreen 显示服务器准则
     * 通过反射从 ModNetwork 调用
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
}
