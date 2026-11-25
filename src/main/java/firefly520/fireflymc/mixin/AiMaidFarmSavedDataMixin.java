package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidFarmSavedDataScope;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SavedData.class, remap = false)
public abstract class AiMaidFarmSavedDataMixin {
    @Inject(method = "setDirty(Z)V", at = @At("HEAD"))
    private void fireflymc$farmSource(boolean dirty, CallbackInfo info) { MaidFarmSavedDataScope.dirty(this, dirty); }
}
