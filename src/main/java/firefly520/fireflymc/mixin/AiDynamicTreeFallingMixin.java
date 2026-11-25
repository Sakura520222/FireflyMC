package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.dtteam.dynamictrees.entity.FallingTreeEntity", remap = false)
public abstract class AiDynamicTreeFallingMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$ownedTick(CallbackInfo ci) {
        if (!DynamicTreeNativeScope.allowsTick((Entity) (Object) this)) ci.cancel();
    }
}
