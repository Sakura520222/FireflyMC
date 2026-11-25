package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ExperienceOrb.class, remap = false)
public abstract class AiExperienceOrbLifecycleMixin {
    @Unique private NativeExperienceAwardLifecycle.Pickup fireflymc$pickup;
    @Unique private NativeExperienceAwardLifecycle.Merge fireflymc$merge;
    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void fireflymc$beforePickup(Player player, CallbackInfo ci) {
        fireflymc$pickup = NativeExperienceAwardLifecycle.beforePickup((ExperienceOrb) (Object) this, player);
    }
    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void fireflymc$afterPickup(Player player, CallbackInfo ci) {
        var scope = fireflymc$pickup; fireflymc$pickup = null; NativeExperienceAwardLifecycle.afterPickup(scope);
    }
    @Inject(method = "merge", at = @At("HEAD"))
    private void fireflymc$beforeMerge(ExperienceOrb donor, CallbackInfo ci) {
        fireflymc$merge = NativeExperienceAwardLifecycle.beforeMerge((ExperienceOrb) (Object) this, donor);
    }
    @Inject(method = "merge", at = @At("RETURN"))
    private void fireflymc$afterMerge(ExperienceOrb donor, CallbackInfo ci) {
        var scope = fireflymc$merge; fireflymc$merge = null; NativeExperienceAwardLifecycle.afterMerge(scope);
    }
}
