package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.integration.maid.MaidCombatLootLifecycle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid", remap = false)
public abstract class AiMaidCombatPickupMixin implements MaidCombatLootLifecycle.MaidHook {
    @WrapMethod(method = "pickupItem")
    private boolean fireflymc$nativeItemPickup(ItemEntity source, boolean simulate, Operation<Boolean> original) {
        var scope = simulate ? null : MaidCombatLootLifecycle.beforePickup(source, (Entity) (Object) this);
        try { return original.call(source, simulate); } finally { MaidCombatLootLifecycle.afterPickup(scope); }
    }
    @WrapMethod(method = "pickupXPOrb")
    private void fireflymc$nativeMaidXpPickup(ExperienceOrb source, Operation<Void> original) {
        var scope = MaidCombatLootLifecycle.beforePickup(source, (Entity) (Object) this);
        try { original.call(source); } finally { MaidCombatLootLifecycle.afterPickup(scope); }
    }
}
