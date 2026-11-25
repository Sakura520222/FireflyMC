package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Only unsettled native tree outputs are quarantined; all other native item behavior is untouched. */
@Mixin(value = ItemEntity.class, remap = false)
public abstract class AiDynamicTreeItemMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$ownedTick(CallbackInfo ci) {
        if (!DynamicTreeNativeScope.allowsTick((ItemEntity) (Object) this)) ci.cancel();
    }
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$ownedPickup(Player player, CallbackInfo ci) {
        if (!DynamicTreeNativeScope.allowsPickup((ItemEntity) (Object) this, player)) ci.cancel();
    }
    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$unsettledIdentity(CallbackInfoReturnable<Boolean> cir) {
        if (DynamicTreeNativeScope.pending((ItemEntity) (Object) this)) cir.setReturnValue(false);
    }
    @Inject(method = "tryToMerge", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$unsettledDonor(ItemEntity donor, CallbackInfo ci) {
        if (DynamicTreeNativeScope.pending((ItemEntity) (Object) this) || DynamicTreeNativeScope.pending(donor)) ci.cancel();
    }
}
