package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ModEventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包处理器（双端通用，无客户端引用）
 * 客户端专用处理器已移至 ClientPayloadHandler
 */
public class ModPayloadHandler {
    public static final Map<UUID, Boolean> VERIFIED_PLAYERS = new HashMap<>();

    // 服务端已确认玩家的状态存储（线程安全）
    public static final Map<UUID, Boolean> CONFIRMED_PLAYERS = new ConcurrentHashMap<>();

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
     * 服务端处理客户端发来的确认准则包
     * 取消玩家无敌状态
     */
    public static void handleConfirmRules(ConfirmRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                UUID playerUuid = serverPlayer.getUUID();
                // 取消超时任务
                ModEventHandler.cancelInvulnerabilityTimeout(playerUuid);
                // 取消玩家无敌
                serverPlayer.setInvulnerable(false);
                // 标记玩家已确认
                CONFIRMED_PLAYERS.put(playerUuid, true);
            }
        });
    }
}
