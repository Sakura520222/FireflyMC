package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ModEventHandler;
import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.auth.PlayerPasswordManager;
import firefly520.fireflymc.kit.StarterKitManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包处理器（双端通用，无客户端引用）
 */
public class ModPayloadHandler {
    public static final Map<UUID, Boolean> VERIFIED_PLAYERS = new ConcurrentHashMap<>();

    // 服务端已确认玩家的状态存储（线程安全）
    public static final Map<UUID, Boolean> CONFIRMED_PLAYERS = new ConcurrentHashMap<>();

    // 密码验证已通过的玩家
    public static final Map<UUID, Boolean> PASSWORD_VERIFIED_PLAYERS = new ConcurrentHashMap<>();

    /**
     * 服务端处理客户端的回复包，验证版本
     */
    public static void handleHandshakeReply(ModHandshakeReplyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.server.isSingleplayer()) {
                    return;
                }

                if (payload.modVersion().equals(FireflyMCMod.VERSION)) {
                    VERIFIED_PLAYERS.put(serverPlayer.getUUID(), true);
                    // 取消验证超时任务
                    ModEventHandler.cancelVerifyTimeout(serverPlayer.getUUID());
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
     * 仅标记规则确认，不直接解除无敌或发福利包
     */
    public static void handleConfirmRules(ConfirmRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.server.isSingleplayer()) {
                    return;
                }

                UUID playerUuid = serverPlayer.getUUID();
                // 标记玩家已确认规则
                CONFIRMED_PLAYERS.put(playerUuid, true);
                // 尝试完成加入流程
                tryCompleteJoin(serverPlayer);
            }
        });
    }

    /**
     * 服务端处理客户端发来的密码提交包
     */
    public static void handlePasswordSubmit(PasswordSubmitPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.server.isSingleplayer()) {
                    return;
                }

                UUID playerUuid = serverPlayer.getUUID();
                PlayerPasswordManager pwdMgr = PlayerPasswordManager.getInstance();

                boolean done = pwdMgr.handlePasswordSubmit(serverPlayer, payload.password());
                if (done && !pwdMgr.isPendingVerification(playerUuid)) {
                    // 密码验证通过
                    PASSWORD_VERIFIED_PLAYERS.put(playerUuid, true);
                    tryCompleteJoin(serverPlayer);
                }
            }
        });
    }

    /**
     * 统一的“尝试完成加入”检查：规则确认 + 密码验证都通过后才解除无敌和发福利包
     */
    private static void tryCompleteJoin(ServerPlayer serverPlayer) {
        UUID playerUuid = serverPlayer.getUUID();
        boolean rulesConfirmed = CONFIRMED_PLAYERS.getOrDefault(playerUuid, false);
        boolean passwordRequired = ServerConfig.SERVER.playerAuthEnabled.get();
        boolean passwordOk = !passwordRequired || PASSWORD_VERIFIED_PLAYERS.getOrDefault(playerUuid, false);

        if (rulesConfirmed && passwordOk) {
            // 取消无敌超时任务
            ModEventHandler.cancelInvulnerabilityTimeout(playerUuid);
            // 取消玩家无敌
            serverPlayer.setInvulnerable(false);
            // 给予新手福利包
            StarterKitManager.giveStarterKit(serverPlayer);
        }
    }
}
