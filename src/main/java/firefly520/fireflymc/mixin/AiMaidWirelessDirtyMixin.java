package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.transport.NativeMaidTransportScope;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe actual native container writes only inside the approved wireless-IO callback. */
@Mixin(value = BlockEntity.class, remap = false)
public abstract class AiMaidWirelessDirtyMixin implements NativeMaidTransportScope.ContainerDirtyHook {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void fireflymc$wirelessContainerChanged(CallbackInfo ci) {
        NativeMaidTransportScope.blockEntityChanged((BlockEntity) (Object) this);
    }
}
