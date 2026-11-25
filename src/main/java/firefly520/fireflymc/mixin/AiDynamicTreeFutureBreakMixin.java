package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.dtteam.dynamictrees.systems.FutureBreak", remap = false)
public abstract class AiDynamicTreeFutureBreakMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1)
    private static void fireflymc$handoff(@Coerce Object deferred, CallbackInfo ci) {
        if (DynamicTreeNativeScope.captureDeferred(deferred)) ci.cancel();
    }
}
