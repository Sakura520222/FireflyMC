package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Entity.class, remap = false)
public abstract class AiEntitySaveMixin {
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void fireflymc$capture(CompoundTag tag, CallbackInfoReturnable<CompoundTag> ci) {
        NativeSaveObserver.serialized(this, ci.getReturnValue());
    }
}
