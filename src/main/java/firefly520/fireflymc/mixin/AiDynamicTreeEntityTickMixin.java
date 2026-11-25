package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerLevel.class, remap = false)
public abstract class AiDynamicTreeEntityTickMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$ownedTreeTick(Entity entity, CallbackInfo ci) {
        if ((entity.getClass().getName().equals(DynamicTreeAccess.FALLING_TREE) || entity instanceof net.minecraft.world.entity.item.ItemEntity)
                && !DynamicTreeNativeScope.allowsTick(entity)) ci.cancel();
    }
}
