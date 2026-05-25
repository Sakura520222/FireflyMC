package firefly520.fireflymc.mixin;

import firefly520.fireflymc.client.eventws.ClientEventNotificationEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听客户端本地玩家死亡包。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(
        method = "handlePlayerCombatKill",
        at = @At("HEAD"),
        remap = false
    )
    private void fireflymc$onPlayerCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || packet.playerId() != minecraft.player.getId()) {
            return;
        }

        ClientEventNotificationEvents.notifyPlayerDeath(packet.message());
    }
}
