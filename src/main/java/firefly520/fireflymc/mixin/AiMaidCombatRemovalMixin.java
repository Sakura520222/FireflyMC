package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.integration.maid.MaidCombatEntityProof;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Entity.class, remap = false)
public abstract class AiMaidCombatRemovalMixin implements firefly520.fireflymc.ai.integration.maid.MaidCombatScope.RemovalHook {
    @WrapMethod(method = "remove")
    private void fireflymc$targetRemoval(Entity.RemovalReason reason, Operation<Void> original) {
        var entity = (Entity) (Object) this; var transition = MaidCombatEntityProof.before(entity, reason);
        try { original.call(reason); } finally { MaidCombatEntityProof.after(entity, transition); }
    }
}
