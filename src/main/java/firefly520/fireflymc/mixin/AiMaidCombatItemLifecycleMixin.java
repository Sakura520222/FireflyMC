package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.integration.maid.MaidCombatLootLifecycle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ItemEntity.class, remap = false)
public abstract class AiMaidCombatItemLifecycleMixin implements MaidCombatLootLifecycle.ItemHook {
    @WrapMethod(method = "playerTouch")
    private void fireflymc$nativePickup(Player player, Operation<Void> original) {
        var scope = MaidCombatLootLifecycle.beforePickup((ItemEntity) (Object) this, player);
        try { original.call(player); } finally { MaidCombatLootLifecycle.afterPickup(scope); }
    }
    @WrapMethod(method = "tryToMerge")
    private void fireflymc$nativeMerge(ItemEntity other, Operation<Void> original) {
        var scope = MaidCombatLootLifecycle.beforeMerge((ItemEntity) (Object) this, other);
        try { original.call(other); } finally { MaidCombatLootLifecycle.afterMerge(scope); }
    }
}
