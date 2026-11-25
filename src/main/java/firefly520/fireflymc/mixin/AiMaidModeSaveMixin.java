package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidControlSaveWitness;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The hook stays inert for entities without an explicitly registered dedicated-server mode witness. */
@Mixin(value = Entity.class, remap = false)
public abstract class AiMaidModeSaveMixin {
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void fireflymc$maidMode(CompoundTag original, CallbackInfoReturnable<CompoundTag> result) {
        MaidControlSaveWitness.serialized((Entity) (Object) this, result.getReturnValue());
    }
}
