package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.NativeLootEscrowScope;
import firefly520.fireflymc.ai.blueprint.NativeKleeSlabScope;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Runs after every BlockDropsEvent listener, before spawning the reviewed native item results. */
@Mixin(value = CommonHooks.class, remap = false)
public abstract class AiNativeLootCustodyMixin {
    @Inject(method = "fireBlockBreak", at = @At("RETURN"))
    private static void fireflymc$partialBreakOutcome(CallbackInfoReturnable<BlockEvent.BreakEvent> ci) {
        NativeKleeSlabScope.outerResult(ci.getReturnValue());
    }
    @Redirect(method = "handleBlockDrops", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/level/BlockDropsEvent;getDrops()Ljava/util/List;"))
    private static List<ItemEntity> fireflymc$nativeLoot(BlockDropsEvent event) {
        return NativeLootEscrowScope.capture(event, event.getDrops());
    }
}
