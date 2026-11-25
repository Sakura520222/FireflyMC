package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidFarmSaveWitness;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Entity.class, remap = false)
public abstract class AiMaidFarmSaveMixin {
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void fireflymc$ownedInventory(CompoundTag original, CallbackInfoReturnable<CompoundTag> result) {
        MaidFarmSaveWitness.serialized((Entity) (Object) this, result.getReturnValue());
    }
}
