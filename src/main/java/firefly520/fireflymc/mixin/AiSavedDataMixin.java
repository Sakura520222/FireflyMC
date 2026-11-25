package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SavedData.class, remap = false)
public abstract class AiSavedDataMixin {
    @Inject(method = "setDirty(Z)V", at = @At("HEAD"))
    private void fireflymc$additionalConstructionEffect(boolean dirty, CallbackInfo ci) {
        if (dirty) firefly520.fireflymc.ai.blueprint.NativeConstructionSave.savedDataChanged(this);
    }
    @Redirect(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;copy()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag fireflymc$capture(CompoundTag source) {
        CompoundTag copy = source.copy(); NativeSaveObserver.serialized(this, copy); return copy;
    }
}
