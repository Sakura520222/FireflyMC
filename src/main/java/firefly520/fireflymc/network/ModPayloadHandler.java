package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.screen.RulesScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包处理器
 */
public class ModPayloadHandler {
    public static final Map<UUID, Boolean> VERIFIED_PLAYERS = new HashMap<>();

    // 服务端已确认玩家的状态存储（线程安全）
    public static final Map<UUID, Boolean> CONFIRMED_PLAYERS = new ConcurrentHashMap<>();

    /**
     * 客户端处理服务端发来的握手包，回复版本号
     */
    public static void handleHandshake(ModHandshakePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        context.reply(new ModHandshakeReplyPayload(FireflyMCMod.VERSION));
    }

    /**
     * 服务端处理客户端的回复包，验证版本
     */
    public static void handleHandshakeReply(ModHandshakeReplyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (payload.modVersion().equals(FireflyMCMod.VERSION)) {
                    VERIFIED_PLAYERS.put(serverPlayer.getUUID(), true);
                } else {
                    serverPlayer.connection.disconnect(Component.literal(
                        "§cFireflyMC模组版本不匹配！\n" +
                        "服务端版本：" + FireflyMCMod.VERSION + "\n" +
                        "你的客户端版本：" + payload.modVersion()
                    ));
                }
            }
        }).exceptionally(e -> {
            context.disconnect(Component.literal("§cFireflyMC模组验证失败！"));
            return null;
        });
    }

    /**
     * 客户端处理服务端发来的显示准则弹窗包
     * 打开RulesScreen显示服务器准则
     */
    public static void handleShowRules(ShowRulesPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
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
     * 服务端处理客户端发来的确认准则包
     * 取消玩家无敌状态
     */
    public static void handleConfirmRules(ConfirmRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                // 取消玩家无敌
                serverPlayer.setInvulnerable(false);
                // 标记玩家已确认
                CONFIRMED_PLAYERS.put(serverPlayer.getUUID(), true);
            }
        });
    }
}
