package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidNativeRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.event.maid.SwitchSittingEvent", remap = false)
public abstract class AiMaidControlInteractionMixin {
    @Inject(method = "onInteractMaid", at = @At("HEAD"))
    private static void fireflymc$manual(@Coerce Object event, CallbackInfo info) { MaidNativeRevision.manualInteraction(event); }
}
