package firefly520.fireflymc.mixin;

import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import firefly520.fireflymc.ai.AIChatEventHandler;

/**
 * Mixin 类：监听服务端聊天消息
 *
 * 注入到 ServerGamePacketListenerImpl.handleChat 方法
 * 当玩家发送聊天消息时，记录到 AI 历史上下文
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerChatMixin {

    /**
     * 在 handleChat 方法头部注入，拦截玩家聊天消息
     */
    @Inject(
        method = "handleChat",
        at = @At("HEAD"),
        remap = false
    )
    private void onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        // 获取玩家实例
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;

        // 获取聊天内容
        String message = packet.message();

        // 记录到 AI 历史
        AIChatEventHandler.recordPlayerChat(player, message);
    }
}
