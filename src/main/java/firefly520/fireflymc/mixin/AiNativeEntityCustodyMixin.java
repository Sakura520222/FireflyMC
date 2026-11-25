package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.NativeKleeSlabScope;
import net.minecraft.world.level.entity.*;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PersistentEntitySectionManager.class, remap = false)
public abstract class AiNativeEntityCustodyMixin {
    @Inject(method = "addEntity", at = @At("HEAD"))
    private void firefly$enter(EntityAccess entity, boolean existing, CallbackInfoReturnable<Boolean> cir) { NativeKleeSlabScope.enteringAdd(entity, existing); }
    @Redirect(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"))
    private Event firefly$post(IEventBus bus, Event event) {
        Event result = bus.post(event); if (result instanceof EntityJoinLevelEvent joined) NativeKleeSlabScope.joined(joined); return result;
    }
    @Inject(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"), cancellable = true)
    private void firefly$custody(EntityAccess entity, boolean existing, CallbackInfoReturnable<Boolean> cir) {
        if (NativeKleeSlabScope.isCandidate(entity) && NativeKleeSlabScope.capture(entity, ((PersistentEntitySectionManager<?>) (Object) this).isLoaded(entity.getUUID()))) {
            NativeKleeSlabScope.leavingAdd(entity); cir.setReturnValue(false);
        }
    }
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void firefly$leave(EntityAccess entity, boolean existing, CallbackInfoReturnable<Boolean> cir) { NativeKleeSlabScope.leavingAdd(entity); }
}
