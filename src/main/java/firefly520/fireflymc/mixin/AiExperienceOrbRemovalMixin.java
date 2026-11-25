package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Entity.class, remap = false)
public abstract class AiExperienceOrbRemovalMixin {
    @Unique private NativeExperienceAwardLifecycle.Removal fireflymc$orbRemoval;
    @Inject(method = "remove", at = @At("HEAD"))
    private void fireflymc$beforeRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof ExperienceOrb orb) fireflymc$orbRemoval = NativeExperienceAwardLifecycle.beforeRemoval(orb, reason);
    }
    @Inject(method = "remove", at = @At("RETURN"))
    private void fireflymc$afterRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        var scope = fireflymc$orbRemoval; fireflymc$orbRemoval = null; NativeExperienceAwardLifecycle.afterRemoval(scope);
    }
}
