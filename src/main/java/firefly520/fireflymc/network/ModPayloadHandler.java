package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 数据包处理器
 */
public class ModPayloadHandler {
    public static final Map<UUID, Boolean> VERIFIED_PLAYERS = new HashMap<>();

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
}
