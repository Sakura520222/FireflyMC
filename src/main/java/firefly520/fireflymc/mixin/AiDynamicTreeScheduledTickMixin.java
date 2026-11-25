package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope;
import net.minecraft.world.ticks.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves manual/global ticking unchanged; only the active AI root callback can hand off a tick. */
@Mixin(value = LevelTicks.class, remap = false)
public abstract class AiDynamicTreeScheduledTickMixin {
    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$causalRootTick(ScheduledTick<?> tick, CallbackInfo callback) {
        if (DynamicTreeNativeScope.captureRootTick(this, tick)) callback.cancel();
    }
}
