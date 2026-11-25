package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact DT 1.7.2: handleMotion gets this list before either target velocity write and hurt. */
@Pseudo
@Mixin(targets = "com.dtteam.dynamictrees.entity.animation.FalloverAnimationHandler", remap = false)
public abstract class AiDynamicTreeCollisionMixin {
    @Inject(method = "testEntityCollision", at = @At("RETURN"), require = 1)
    private void fireflymc$approvedCollisionTargets(@Coerce Object tree, CallbackInfoReturnable<List<LivingEntity>> result) {
        DynamicTreeNativeScope.beforeFallingCollisions((Entity) tree, result.getReturnValue());
    }
}
