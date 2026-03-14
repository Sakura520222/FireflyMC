package firefly520.fireflymc;

import firefly520.fireflymc.network.ModHandshakePayload;
import firefly520.fireflymc.network.ModPayloadHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 全局事件处理器
 */
public class ModEventHandler {

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ModPayloadHandler.VERIFIED_PLAYERS.remove(serverPlayer.getUUID());

            // 发送握手检测包
            PacketDistributor.sendToPlayer(serverPlayer, new ModHandshakePayload());

            // 5秒后检查验证状态
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    // 在主线程中执行检查
                    serverPlayer.server.execute(() -> {
                        if (!ModPayloadHandler.VERIFIED_PLAYERS.getOrDefault(serverPlayer.getUUID(), false)) {
                            serverPlayer.connection.disconnect(Component.literal(
                                "§c你未安装FireflyMC模组，无法进入本服务器！\n" +
                                "请安装FireflyMC " + FireflyMCMod.VERSION + " 版本后重试。\n" +
                                "§e下载地址: https://mc.firefly520.top"
                            ));
                        }
                    });
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ModPayloadHandler.VERIFIED_PLAYERS.remove(serverPlayer.getUUID());
        }
    }
}
