package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.advanced.StorageDynamicNativeScope;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage", remap = false)
public abstract class StorageDynamicStoreMixin {
    @Inject(method = "get()Lnet/p3pp3rf1y/sophisticatedstorage/block/ItemContentsStorage;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fireflymc$isolatedPreview(CallbackInfoReturnable<Object> callback) {
        Object preview = StorageDynamicNativeScope.previewStore(); if (preview != null) callback.setReturnValue(preview);
    }
    @Inject(method = "getOrCreateStorageContents", at = @At("HEAD"), remap = false)
    private void fireflymc$readSlice(UUID id, CallbackInfoReturnable<CompoundTag> callback) { StorageDynamicNativeScope.requireEntry(this, id); }
    @Inject(method = "has", at = @At("HEAD"), remap = false)
    private void fireflymc$hasSlice(UUID id, CallbackInfoReturnable<Boolean> callback) { StorageDynamicNativeScope.requireEntry(this, id); }
    @Inject(method = "setStorageContents", at = @At("HEAD"), remap = false)
    private void fireflymc$writeSlice(UUID id, CompoundTag data, CallbackInfo callback) { StorageDynamicNativeScope.requireEntry(this, id); }
    @Inject(method = "removeStorageContents", at = @At("HEAD"), remap = false)
    private void fireflymc$removeSlice(UUID id, CallbackInfo callback) { StorageDynamicNativeScope.requireEntry(this, id); }
}
