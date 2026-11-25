package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidNativeRevision;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.network.message.MaidTaskPackage", remap = false)
public abstract class AiMaidControlPacketsMixin {
    @Inject(method = "lambda$handle$0(Lnet/neoforged/neoforge/network/handling/IPayloadContext;Lcom/github/tartaricacid/touhoulittlemaid/network/message/MaidTaskPackage;)V", at = @At("HEAD"))
    private static void fireflymc$manual(IPayloadContext context, @Coerce Object message, CallbackInfo info) {
        MaidNativeRevision.manualPacket(context, message);
    }
}
