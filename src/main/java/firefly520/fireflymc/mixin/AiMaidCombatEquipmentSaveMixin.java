package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidCombatSaveWitness;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Entity.class, remap = false)
public abstract class AiMaidCombatEquipmentSaveMixin implements MaidCombatSaveWitness.Hook {
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void fireflymc$combatEquipment(CompoundTag original, CallbackInfoReturnable<CompoundTag> result) {
        MaidCombatSaveWitness.serialized((Entity) (Object) this, result.getReturnValue());
    }
}
